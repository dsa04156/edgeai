"""Tracking, constant-velocity prediction and closest-approach signals through the public Motion.observe() seam.

Boxes are image pixels. A person box is 100 px tall, so distances in person heights are pixel distances / 100.
"""

import datetime
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from edgeai_hawkeye_motion.motion import Detection, Motion  # noqa: E402

T0 = datetime.datetime(2026, 10, 26, 3, 0, 0, tzinfo=datetime.timezone.utc)


def at(seconds):
    return T0 + datetime.timedelta(seconds=seconds)


def person(cx, bottom):
    return Detection("person", 0.9, (cx - 25, bottom - 100, cx + 25, bottom))


def forklift(cx, bottom):
    return Detection("forklift", 0.9, (cx - 60, bottom - 120, cx + 60, bottom))


def run(motion, frames):
    """frames: [(seconds, [Detection])] -> observation of the last frame."""
    observation = None
    for seconds, detections in frames:
        observation = motion.observe(at(seconds), detections, 1280, 720)
    return observation


def pair(observation):
    assert len(observation["pairs"]) == 1, observation["pairs"]
    return observation["pairs"][0]


class MotionTest(unittest.TestCase):
    def test_forklift_driving_at_standing_person_is_near_path_with_time_to_closest(self):
        # Forklift moves +100 px/s along y=500 towards a person standing at x=700; at t=2 s it is 300 px away.
        frames = [(t / 2, [forklift(200 + 50 * t, 500), person(700, 500)]) for t in range(5)]
        observation = run(Motion(), frames)
        risk = pair(observation)
        self.assertEqual(observation["status"], "NEAR_PATH")
        self.assertEqual(risk["level"], "NEAR_PATH")
        self.assertAlmostEqual(risk["timeToClosest"], 3.0, delta=0.15)
        self.assertAlmostEqual(risk["distance"], 3.0, delta=0.05)
        self.assertLess(risk["minDistance"], 0.3)
        truck = next(t for t in observation["tracks"] if t["class"] == "forklift")
        self.assertAlmostEqual(truck["velocity"][0], 100, delta=3)
        self.assertAlmostEqual(truck["velocity"][1], 0, delta=3)
        # Predicted points are time-stamped seconds ahead and follow the velocity.
        self.assertEqual([p[0] for p in truck["predicted"]], [0.5, 1.0, 1.5, 2.0, 2.5, 3.0])
        self.assertAlmostEqual(truck["predicted"][-1][1], truck["point"][0] + 300, delta=10)

    def test_crossing_paths_use_closest_approach_not_current_distance(self):
        # Forklift +100 px/s in x, person +100 px/s in y; both reach (800, 500) at t=4 s.
        frames = [(t / 2, [forklift(400 + 50 * t, 500), person(800, 100 + 50 * t)]) for t in range(5)]
        risk = pair(run(Motion(), frames))
        # At t=2 s each is 200 px from the crossing point: 2 s to it, current distance 2.83 person heights.
        self.assertEqual(risk["level"], "NEAR_PATH")
        self.assertAlmostEqual(risk["timeToClosest"], 2.0, delta=0.15)
        self.assertAlmostEqual(risk["distance"], 2.83, delta=0.05)
        self.assertLess(risk["minDistance"], 0.3)
        self.assertEqual(len(risk["closest"]), 2)

    def test_receding_forklift_is_no_conflict(self):
        frames = [(t / 2, [forklift(500 - 50 * t, 500), person(700, 500)]) for t in range(5)]
        risk = pair(run(Motion(), frames))
        self.assertEqual(risk["level"], "NO_CONFLICT")
        self.assertIsNone(risk["timeToClosest"])
        self.assertAlmostEqual(risk["distance"], 4.0, delta=0.05)

    def test_parallel_motion_at_constant_gap_is_no_conflict(self):
        # 50 px/s: at 2 fps ByteTrack's IoU association cannot follow a 50 px wide box moving its full width per frame.
        frames = [(t / 2, [forklift(200 + 25 * t, 500), person(500 + 25 * t, 500)]) for t in range(5)]
        risk = pair(run(Motion(), frames))
        self.assertEqual(risk["level"], "NO_CONFLICT")
        self.assertAlmostEqual(risk["distance"], 3.0, delta=0.05)

    def test_passing_with_lateral_clearance_is_approaching_not_near(self):
        # Forklift passes 150 px (1.5 person heights) beside a standing person.
        frames = [(t / 2, [forklift(200 + 50 * t, 500), person(600, 350)]) for t in range(5)]
        risk = pair(run(Motion(), frames))
        self.assertEqual(risk["level"], "APPROACHING")
        self.assertAlmostEqual(risk["minDistance"], 1.5, delta=0.1)
        self.assertAlmostEqual(risk["timeToClosest"], 2.0, delta=0.15)

    def test_single_frame_has_no_velocity_and_pair_is_unknown(self):
        observation = run(Motion(), [(0, [forklift(200, 500), person(700, 500)])])
        risk = pair(observation)
        self.assertEqual(risk["level"], "UNKNOWN")
        self.assertEqual(risk["reason"], "INSUFFICIENT_HISTORY")
        self.assertEqual(observation["status"], "UNKNOWN")
        self.assertTrue(all(t["velocity"] is None and t["predicted"] is None for t in observation["tracks"]))

    def test_no_forklift_or_no_person_is_no_pair_not_safe(self):
        self.assertEqual(run(Motion(), [(0, [person(700, 500)])])["status"], "NO_PAIR")
        self.assertEqual(run(Motion(), [(0, [])])["status"], "NO_PAIR")

    def test_person_missing_for_a_frame_makes_the_pair_unknown(self):
        frames = [(t / 2, [forklift(200 + 50 * t, 500), person(700, 500)]) for t in range(4)]
        frames.append((2.0, [forklift(400, 500)]))
        observation = run(Motion(), frames)
        risk = pair(observation)
        self.assertEqual(risk["level"], "UNKNOWN")
        self.assertEqual(risk["reason"], "TRACK_LOST")
        lost = next(t for t in observation["tracks"] if t["class"] == "person")
        self.assertFalse(lost["visible"])

    def test_ids_persist_across_frames_and_paths_use_frame_timestamps(self):
        motion = Motion()
        first = motion.observe(at(0), [forklift(200, 500)], 1280, 720)
        # Irregular spacing: 0.4 s then 0.7 s. Velocity must come from timestamps, not frame count.
        motion.observe(at(0.4), [forklift(240, 500)], 1280, 720)
        last = motion.observe(at(1.1), [forklift(310, 500)], 1280, 720)
        self.assertEqual(first["tracks"][0]["id"], last["tracks"][0]["id"])
        self.assertAlmostEqual(last["tracks"][0]["velocity"][0], 100, delta=3)
        self.assertEqual([round(p[0], 3) for p in last["tracks"][0]["path"]], [-1.1, -0.7, 0.0])
        self.assertEqual(last["gapMs"], 700)

    def test_time_gap_resets_tracking_and_ids_are_not_reused(self):
        motion = Motion()
        before = run(motion, [(t / 2, [forklift(200 + 50 * t, 500)]) for t in range(3)])
        after = motion.observe(at(10), [forklift(300, 500)], 1280, 720)
        self.assertEqual(after["reset"], "TIME_GAP")
        self.assertNotEqual(before["tracks"][0]["id"], after["tracks"][0]["id"])
        self.assertIsNone(after["tracks"][0]["velocity"])
        self.assertEqual(len(after["tracks"][0]["path"]), 1)

    def test_scene_cut_restarts_tracking_without_reusing_ids(self):
        motion = Motion()
        before = run(motion, [(t / 2, [forklift(200 + 50 * t, 500)]) for t in range(3)])
        after = motion.observe(at(1.5), [forklift(300, 500)], 1280, 720, scene_cut=True)
        self.assertEqual(after["reset"], "SCENE_CUT")
        self.assertNotEqual(before["tracks"][0]["id"], after["tracks"][0]["id"])
        self.assertEqual(len(after["tracks"][0]["path"]), 1)

    def test_regressed_frame_time_resets(self):
        motion = Motion()
        motion.observe(at(1), [forklift(200, 500)], 1280, 720)
        self.assertEqual(motion.observe(at(0.5), [forklift(200, 500)], 1280, 720)["reset"], "TIME_REGRESSED")

    def test_twenty_four_fps_predicts_after_point_four_seconds_with_a_compact_path(self):
        # Forklift +100 px/s towards a standing person, observed every 1/24 s for 6 s.
        motion, seen = Motion(), {}
        for n in range(24 * 6 + 1):
            observation = motion.observe(at(n / 24), [forklift(200 + 100 * n / 24, 500), person(1200, 500)], 1280, 720)
            seen[n] = next(t for t in observation["tracks"] if t["class"] == "forklift")
        self.assertIsNone(seen[9]["velocity"])  # 0.375 s of history
        early = seen[10]  # 0.417 s
        self.assertAlmostEqual(early["velocity"][0], 100, delta=3)
        self.assertEqual(len(early["predicted"]), 6)
        self.assertEqual(len({t["id"] for t in seen.values()}), 1)
        last = seen[24 * 6]
        self.assertAlmostEqual(last["velocity"][0], 100, delta=3)
        # The shown path stays compact but still reaches back over seconds, ending at the current point.
        self.assertLessEqual(len(last["path"]), 10)
        self.assertEqual(last["path"][-1], [0.0, last["point"][0], last["point"][1]])
        self.assertLessEqual(last["path"][0][0], -4.0)
        self.assertEqual(pair(observation)["level"], "NEAR_PATH")

    def test_first_frame_is_marked_start(self):
        self.assertEqual(Motion().observe(at(0), [], 1280, 720)["reset"], "START")


if __name__ == "__main__":
    unittest.main()
