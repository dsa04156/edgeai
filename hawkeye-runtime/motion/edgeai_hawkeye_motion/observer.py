"""One continuous observation of one pinned camera for one Hawkeye video session.

A background thread reads the camera's Edge Mapper preview (only for the pinned configuration digest and source
generation), runs every newer decoded frame through the detector and the session's Motion, and keeps the last
RING_FRAMES observations with their JPEGs in memory. Tracker state therefore continues across result windows.

Windows partition the observation sequence without gaps: window k starts right after window k-1's last frame and
is closed once its frames span at least the requested seconds. Closing is idempotent, so a retried snapshot Task
gets the same range. Frames evicted from the ring before a window was closed are counted, never invented.

The observer stops ingesting when stopped, when its lease is not renewed, or after the source keeps failing for
FAIL_SECONDS; the state and failure stay readable until a new session replaces it.

A replayed recording can cut between camera views (and wraps from its end to its start). A frame whose 32x24 grayscale
thumbnail differs from the previous one by more than SCENE_CUT_MAD (mean absolute difference, 0..255) restarts
tracking (reset SCENE_CUT), so no track or velocity spans two views.
"""

import datetime
import hashlib
import io
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from PIL import Image

from .motion import Motion

SOURCE_FIELDS = {"deviceId", "deviceUid", "nodeName", "mapperUrl", "mapperDeployment", "configurationSha256", "sourceGeneration"}
MAPPER_URL = re.compile(r"http://[0-9]{1,3}(\.[0-9]{1,3}){3}:7783")
FRAME_TIME = re.compile(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z")
MAX_FRAME_BYTES = 1048576
# Measured 2026-10-26 on the 24 fps preview frames of both replay clips: consecutive frames of one view stay at or below
# 18.4 (p99 11.4-41.9 because of the cuts), every camera cut and the loop wrap is 40.6-54.6.
SCENE_CUT_MAD = 30.0


class SourceError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def valid_source(source):
    return isinstance(source, dict) and set(source) == SOURCE_FIELDS and all(isinstance(v, str) and v for v in source.values()) \
        and MAPPER_URL.fullmatch(source["mapperUrl"]) is not None \
        and re.fullmatch(r"sha256:[a-f0-9]{64}", source["configurationSha256"]) is not None


def frame_time(value):
    match = FRAME_TIME.fullmatch(value or "")
    if not match:
        raise SourceError("FRAME_TIME_INVALID")
    fraction = (match.group(2) or "").ljust(6, "0")[:6]
    return datetime.datetime.fromisoformat(match.group(1) + "." + fraction + "+00:00")


def thumbnail(image):
    """32x24 grayscale bytes of a JPEG, decoded at reduced size."""
    picture = Image.open(io.BytesIO(image))
    picture.draft("L", (80, 60))
    return picture.convert("L").resize((32, 24), Image.BILINEAR).tobytes()


def iso(moment):
    return moment.astimezone(datetime.timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


class MapperSource:
    """Latest decoded frame of the pinned camera: (jpeg bytes, decode time text)."""

    def __init__(self, source, timeout=5):
        query = urllib.parse.urlencode({"configurationSha256": source["configurationSha256"], "sourceGeneration": source["sourceGeneration"]})
        self.url = f"{source['mapperUrl']}/devices/{urllib.parse.quote(source['deviceId'], safe='')}/preview?{query}"
        self.timeout = timeout
        self.opener = urllib.request.build_opener(NoRedirect)

    def read(self):
        try:
            with self.opener.open(urllib.request.Request(self.url, method="GET"), timeout=self.timeout) as response:
                if response.status != 200 or response.headers.get("Content-Type") != "image/jpeg":
                    raise SourceError("FRAME_UNAVAILABLE")
                body = response.read(MAX_FRAME_BYTES + 1)
                header = response.headers.get("X-EdgeAI-Frame-Time")
        except urllib.error.HTTPError as exc:
            exc.close()
            raise SourceError("FRAME_UNAVAILABLE") from None
        except (urllib.error.URLError, OSError):
            raise SourceError("MAPPER_UNREACHABLE") from None
        if len(body) > MAX_FRAME_BYTES or len(body) < 4 or body[:2] != b"\xff\xd8" or body[-2:] != b"\xff\xd9":
            raise SourceError("FRAME_INVALID")
        return body, header


class Observer:
    # 120 s of the Mapper's 24 fps preview (JPEGs of about 70 KB: about 200 MB): the retained windows plus their latency.
    RING_FRAMES = 24 * 120
    POLL_SECONDS = 0.1
    # Wait for a newer frame than the one just read: half the preview's frame interval, so no decoded frame is skipped.
    FRAME_WAIT_SECONDS = 1 / 48
    FAIL_SECONDS = 10.0
    MAX_FRAME_AGE_SECONDS = 10.0
    RETAINED_WINDOWS = 3

    def __init__(self, session_id, source, lease_seconds, detector, read_frame=None, clock=None, motion=None):
        self.session_id = session_id
        self.source = source
        self.epoch = str(uuid.uuid4())
        self.detector = detector
        self.read_frame = read_frame or MapperSource(source).read
        self.clock = clock or (lambda: datetime.datetime.now(datetime.timezone.utc))
        self.motion = motion or Motion()
        self.started_at = iso(self.clock())
        self.lease_seconds = lease_seconds
        self.lease_until = time.monotonic() + lease_seconds
        self.state = "RUNNING"
        self.failure = None
        self.last_error = None
        self.processed = 0
        self.resets = 0
        self.scene_cuts = 0
        self.last_detect_ms = None
        self.frames = {}  # seq -> (observation, jpeg), insertion ordered
        self.windows = {}  # k -> closed window
        self._seq = 0
        self._first_time = None
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name=f"observer-{session_id}", daemon=True)

    def start(self):
        self._thread.start()
        return self

    # --- control -------------------------------------------------------------------------------------------------
    def renew(self, lease_seconds):
        with self._lock:
            if self.state == "RUNNING":
                self.lease_seconds = lease_seconds
                self.lease_until = time.monotonic() + lease_seconds

    def stop(self):
        self._end("STOPPED", None)

    def join(self, timeout=None):
        self._thread.join(timeout)

    def _end(self, state, failure):
        with self._lock:
            if self.state == "RUNNING":
                self.state, self.failure = state, failure
        self._stop.set()

    # --- ingestion -----------------------------------------------------------------------------------------------
    def _run(self):
        previous, failing_since, last_thumbnail = None, None, None
        while not self._stop.is_set():
            if time.monotonic() > self.lease_until:
                self._end("EXPIRED", {"code": "OBSERVER_EXPIRED", "message": "lease not renewed"})
                break
            try:
                image, header = self.read_frame()
                decoded = frame_time(header)
                received = self.clock()
                if (received - decoded).total_seconds() > self.MAX_FRAME_AGE_SECONDS:
                    raise SourceError("FRAME_STALE")
            except SourceError as error:
                now = time.monotonic()
                failing_since = failing_since or now
                with self._lock:
                    self.last_error = error.code
                if now - failing_since > self.FAIL_SECONDS:
                    self._end("FAILED", {"code": "SOURCE_" + error.code, "message": f"camera source failing for over {self.FAIL_SECONDS:g} s"})
                    break
                self._stop.wait(self.POLL_SECONDS)
                continue
            failing_since = None
            if decoded == previous:
                # The Mapper has not decoded a newer frame yet. An earlier time is processed: Motion restarts tracking.
                self._stop.wait(self.FRAME_WAIT_SECONDS)
                continue
            previous = decoded
            try:
                detections, width, height, detect_ms = self.detector.detect(image)
            except Exception as error:  # the detector is not trusted to keep running after an error
                self._end("FAILED", {"code": "DETECTOR_FAILED", "message": type(error).__name__})
                break
            try:
                small = thumbnail(image)
            except (OSError, ValueError):
                small = None
            cut = small is not None and last_thumbnail is not None \
                and sum(abs(a - b) for a, b in zip(small, last_thumbnail)) / len(small) > SCENE_CUT_MAD
            last_thumbnail = small
            observation = self.motion.observe(decoded, detections, width, height, scene_cut=cut)
            with self._lock:
                if self.state != "RUNNING":
                    break
                self._seq += 1
                self._first_time = self._first_time or decoded
                observation = {"seq": self._seq, "frameTime": header, "receivedAt": iso(received),
                               "sha256": hashlib.sha256(image).hexdigest(), "detectMs": round(detect_ms, 1), **observation}
                self.frames[self._seq] = (observation, image)
                while len(self.frames) > self.RING_FRAMES:
                    self.frames.pop(next(iter(self.frames)))
                self.processed += 1
                self.resets += observation["reset"] is not None
                self.scene_cuts += observation["reset"] == "SCENE_CUT"
                self.last_detect_ms = observation["detectMs"]
                self.last_error = None

    # --- reads ---------------------------------------------------------------------------------------------------
    def describe(self, identity):
        with self._lock:
            return {"sessionId": self.session_id, "epoch": self.epoch, "state": self.state, "failure": self.failure,
                    "lastError": self.last_error, "startedAt": self.started_at, "leaseSeconds": self.lease_seconds,
                    "source": self.source, "observer": identity,
                    "stats": {"processed": self.processed, "resets": self.resets, "sceneCuts": self.scene_cuts,
                              "lastDetectMs": self.last_detect_ms}}

    def latest(self):
        with self._lock:
            return self.frames[self._seq][0] if self._seq in self.frames else None

    def frame(self, seq):
        with self._lock:
            entry = self.frames.get(seq)
            return entry[1] if entry else None

    def window(self, k, min_seconds):
        """(closed window, None) | (None, pending coverage seconds) | raises WindowError."""
        with self._lock:
            if k in self.windows:
                if self.windows[k]["frames"] is None:
                    raise WindowError(410, "WINDOW_RELEASED")
                return self._window_document(self.windows[k]), None
            if k != len(self.windows) + 1:
                raise WindowError(409, "WINDOW_OUT_OF_ORDER")
            if self.state != "RUNNING":
                raise WindowError(409, "OBSERVER_" + self.state)
            start_seq = self.windows[k - 1]["toSeq"] + 1 if k > 1 else 1
            start = frame_time(self.windows[k - 1]["finishedAt"]) if k > 1 else self._first_time
            if start is None or self._seq < start_seq or self._seq not in self.frames:
                return None, 0.0
            covered = (frame_time(self.frames[self._seq][0]["frameTime"]) - start).total_seconds()
            if covered < min_seconds:
                return None, covered
            closed = {"seq": k, "fromSeq": start_seq, "toSeq": self._seq, "startedAt": iso(start),
                      "finishedAt": self.frames[self._seq][0]["frameTime"]}
            closed["frames"] = [self.frames[s][0] for s in range(start_seq, self._seq + 1) if s in self.frames]
            closed["evicted"] = self._seq - start_seq + 1 - len(closed["frames"])
            self.windows[k] = closed
            # Only recent windows can still be retried by their snapshot Task; older ones keep their range only.
            if k - self.RETAINED_WINDOWS in self.windows:
                self.windows[k - self.RETAINED_WINDOWS]["frames"] = None
            return self._window_document(closed), None

    def _window_document(self, closed):
        return {"window": {key: closed[key] for key in ("seq", "fromSeq", "toSeq", "startedAt", "finishedAt", "evicted")},
                "frames": closed["frames"]}


class WindowError(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status = status
        self.code = code
