"""Per-session forklift/person tracking, short-horizon prediction and closest-approach signals in image coordinates.

No camera calibration exists, so nothing here is metric. Positions are image pixels of the analysed frame; the
reference point of an object is the bottom centre of its box (roughly where it touches the floor). Distances are
reported in person heights: pixels divided by the box height of the person in the pair, a local scale that follows
perspective. Velocities are pixels per second from the frames' decode timestamps, never from frame counts.

Prediction is constant velocity (least squares over the last VELOCITY_SECONDS of a track). A track keeps every point of
its last PATH_SECONDS whatever the frame rate; the reported path is at most PATH_POINTS of them, evenly spread. For a forklift/person
pair, the time and distance of closest approach over the horizon give:
  NEAR_PATH    predicted (or current) separation within NEAR person heights
  APPROACHING  separation shrinking and closest approach within WATCH person heights
  NO_CONFLICT  both tracks have velocity and neither condition holds
  UNKNOWN      a track is too short for a velocity or was not detected in this frame
A frame without both a forklift and a person is NO_PAIR, which is not a safety verdict. Detection confidence is
reported as is and is not a collision probability.

Tracking is supervision's ByteTrack (MIT), one tracker per class so a person can never take a forklift's ID. A
frame-time gap or regression, or a scene cut reported by the caller (another camera view), starts new trackers; IDs
are never reused within one Motion.
"""

import collections
import dataclasses

import numpy as np
import supervision as sv

CLASSES = ("forklift", "person")
HORIZON_SECONDS = 3.0
STEP_SECONDS = 0.5
VELOCITY_SECONDS = 2.0
MIN_VELOCITY_SPAN_SECONDS = 0.4
PATH_SECONDS = 4.5
PATH_POINTS = 10
NEAR = 1.0
WATCH = 2.0
MAX_GAP_SECONDS = 2.5
LOST_SECONDS = 1.5
SEVERITY = ("NEAR_PATH", "APPROACHING", "UNKNOWN", "NO_CONFLICT")


@dataclasses.dataclass(frozen=True)
class Detection:
    cls: str
    confidence: float
    box: tuple  # x1, y1, x2, y2 in pixels


@dataclasses.dataclass
class _Track:
    id: str
    cls: str
    points: collections.deque  # (seconds, x, y) of the last PATH_SECONDS
    box: tuple = None
    confidence: float = 0.0
    last_seen: float = 0.0


def _round(value, digits=1):
    return round(float(value), digits)


class Motion:
    def __init__(self, track_activation_threshold=0.45, lost_track_buffer=45, frame_rate=24):
        self._tracker_args = dict(track_activation_threshold=track_activation_threshold, lost_track_buffer=lost_track_buffer,
                                  minimum_matching_threshold=0.8, frame_rate=frame_rate)
        self._ids = 0
        self._origin = None
        self._previous = None
        self._start()

    def _start(self):
        self._trackers = {cls: sv.ByteTrack(**self._tracker_args) for cls in CLASSES}
        self._external = {}  # (class, tracker id) -> global id within this segment
        self._tracks = {}

    def observe(self, frame_time, detections, width, height, scene_cut=False):
        """frame_time: aware datetime of the decoded frame; scene_cut: the image shows a different view than the
        previous frame. Returns the JSON-ready observation of this frame."""
        if self._origin is None:
            self._origin = frame_time
        now = (frame_time - self._origin).total_seconds()
        gap_ms, reset = None, None
        if self._previous is None:
            reset = "START"
        else:
            gap = now - self._previous
            gap_ms = round(gap * 1000)
            if gap <= 0:
                reset = "TIME_REGRESSED"
            elif gap > MAX_GAP_SECONDS:
                reset = "TIME_GAP"
            elif scene_cut:
                reset = "SCENE_CUT"
            if reset:
                self._start()
        self._previous = now
        for cls in CLASSES:
            self._update(cls, [d for d in detections if d.cls == cls], now)
        tracks = [t for t in self._tracks.values() if now - t.last_seen <= LOST_SECONDS]
        self._tracks = {t.id: t for t in tracks}
        views = {t.id: self._view(t, now) for t in tracks}
        pairs = [self._pair(views[f.id], views[p.id], p.box) for f in tracks if f.cls == "forklift"
                 for p in tracks if p.cls == "person"]
        status = "NO_PAIR" if not pairs else min((p["level"] for p in pairs), key=SEVERITY.index)
        return {"width": width, "height": height, "gapMs": gap_ms, "reset": reset, "status": status,
                "tracks": list(views.values()), "pairs": pairs}

    def _update(self, cls, detections, now):
        tracker = self._trackers[cls]
        if detections:
            found = sv.Detections(xyxy=np.array([d.box for d in detections], dtype=np.float32),
                                  confidence=np.array([d.confidence for d in detections], dtype=np.float32))
        else:
            found = sv.Detections.empty()
        tracked = tracker.update_with_detections(found)
        for box, confidence, tracker_id in zip(tracked.xyxy, tracked.confidence, tracked.tracker_id):
            key = (cls, int(tracker_id))
            if key not in self._external:
                self._ids += 1
                self._external[key] = f"{cls[0].upper()}{self._ids}"
            track_id = self._external[key]
            track = self._tracks.get(track_id) or _Track(track_id, cls, collections.deque())
            x1, y1, x2, y2 = (float(v) for v in box)
            track.box, track.confidence, track.last_seen = (x1, y1, x2, y2), float(confidence), now
            track.points.append((now, (x1 + x2) / 2, y2))
            while now - track.points[0][0] > PATH_SECONDS:
                track.points.popleft()
            self._tracks[track_id] = track

    def _view(self, track, now):
        visible = track.last_seen == now
        velocity = _velocity(track.points)
        _, x, y = track.points[-1]
        predicted = None
        if velocity is not None and visible:
            steps = int(round(HORIZON_SECONDS / STEP_SECONDS))
            predicted = [[STEP_SECONDS * k, _round(x + velocity[0] * STEP_SECONDS * k), _round(y + velocity[1] * STEP_SECONDS * k)]
                         for k in range(1, steps + 1)]
        return {"id": track.id, "class": track.cls, "visible": visible, "confidence": round(track.confidence, 3) if visible else None,
                "box": [_round(v) for v in track.box] if visible else None, "point": [_round(x), _round(y)],
                "path": [[round(t - now, 3), _round(px), _round(py)] for t, px, py in _spread(track.points)],
                "velocity": [_round(velocity[0]), _round(velocity[1])] if velocity is not None and visible else None,
                "predicted": predicted}

    @staticmethod
    def _pair(forklift, person, person_box):
        scale = max(person_box[3] - person_box[1], 1.0)
        f, p = np.array(forklift["point"]), np.array(person["point"])
        distance = float(np.linalg.norm(p - f)) / scale
        result = {"forklift": forklift["id"], "person": person["id"], "level": "UNKNOWN", "reason": None,
                  "distance": round(distance, 2), "minDistance": None, "timeToClosest": None, "closest": None}
        if not forklift["visible"] or not person["visible"]:
            result["reason"] = "TRACK_LOST"
            return result
        if forklift["velocity"] is None or person["velocity"] is None:
            result["reason"] = "INSUFFICIENT_HISTORY"
            return result
        vf, vp = np.array(forklift["velocity"]), np.array(person["velocity"])
        relative, closing = p - f, vp - vf
        speed = float(closing @ closing)
        approaching = speed > 0 and float(relative @ closing) < 0
        t = min(max(-float(relative @ closing) / speed, 0.0), HORIZON_SECONDS) if approaching else 0.0
        separation = float(np.linalg.norm(relative + closing * t)) / scale
        if distance <= NEAR:
            level = "NEAR_PATH"
        elif approaching and separation <= NEAR:
            level = "NEAR_PATH"
        elif approaching and separation <= WATCH:
            level = "APPROACHING"
        else:
            level = "NO_CONFLICT"
        result.update(level=level, minDistance=round(separation, 2))
        if approaching:
            result["timeToClosest"] = round(t, 2)
            result["closest"] = [[_round(v) for v in f + vf * t], [_round(v) for v in p + vp * t]]
        return result


def _spread(points):
    """At most PATH_POINTS of the points, evenly spaced by index, always including the first and the latest."""
    if len(points) <= PATH_POINTS:
        return list(points)
    return [points[round(i * (len(points) - 1) / (PATH_POINTS - 1))] for i in range(PATH_POINTS)]


def _velocity(points):
    """Least-squares pixels/second over the recent part of a track, or None when it is too short to tell."""
    latest = points[-1][0]
    recent = [p for p in points if latest - p[0] <= VELOCITY_SECONDS]
    if len(recent) < 2 or latest - recent[0][0] < MIN_VELOCITY_SPAN_SECONDS:
        return None
    t = np.array([p[0] for p in recent])
    t = t - t.mean()
    denominator = float(t @ t)
    return tuple(float(t @ (np.array([p[i] for p in recent]) - np.mean([p[i] for p in recent]))) / denominator for i in (1, 2))
