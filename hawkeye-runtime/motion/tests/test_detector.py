"""Detector adapter over the WarehouseDetector contract (the TensorRT engine itself is exercised by the runtime probe)."""

import datetime
import math
import os
import sys
import unittest
from pathlib import Path
from unittest import mock

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import forklift_detector  # noqa: E402
from edgeai_hawkeye_motion import server  # noqa: E402
from edgeai_hawkeye_motion.detector import Detector  # noqa: E402
from edgeai_hawkeye_motion.motion import Detection, Motion  # noqa: E402


class Engine:
    identity = {"provider": "TensorRT 11.4.0 GPU (RTX 5060 Ti, sm120)", "model": "nvidia/tao/rtdetr_2d_warehouse:deployable_rn50_v1.0.2",
                "modelDigest": "sha256:7b57", "engineDigest": "sha256:e1"}

    def detect(self, jpeg):
        return {"width": 1280, "height": 720, "inferenceMs": 9.1, "objects": [
            {"classId": 0, "label": "person", "confidence": 0.91, "bbox": [10, 20, 60, 220]},
            {"classId": 5, "label": "forklift", "confidence": 0.62, "bbox": [300, 100, 700, 500]}]}


class DetectorTest(unittest.TestCase):
    def test_engine_objects_become_motion_detections_with_the_engine_identity(self):
        detector = Detector(Engine())
        detections, width, height, ms = detector.detect(b"jpeg")
        self.assertEqual(detections, [Detection("person", 0.91, (10.0, 20.0, 60.0, 220.0)),
                                      Detection("forklift", 0.62, (300.0, 100.0, 700.0, 500.0))])
        self.assertEqual((width, height, ms), (1280, 720, 9.1))
        self.assertEqual(detector.providers, ["TensorRT 11.4.0 GPU (RTX 5060 Ti, sm120)"])
        self.assertEqual(detector.identity()["engineDigest"], "sha256:e1")


class ScriptedWarehouseDetector(forklift_detector.WarehouseDetector):
    """The engine as the service constructs it, with the TensorRT execution replaced by scripted model outputs; the
    threshold and the real post-processing (sigmoid, class-wise NMS) are those of forklift_detector."""

    frames = []

    def __init__(self, model_path, threads=4, engine_path=None, threshold=0.5):
        self.threshold, self.identity = threshold, {"provider": "scripted", "threshold": threshold}
        self.outputs = iter(self.frames)

    def detect(self, jpeg):
        people = next(self.outputs)  # [(confidence, (cx, cy, w, h) normalised)] of class 0 (person)
        logits = np.full((1, 300, forklift_detector.CLASSES), -10.0, dtype=np.float32)
        boxes = np.full((1, 300, 4), 0.01, dtype=np.float32)
        for i, (confidence, box) in enumerate(people):
            logits[0, i, 0] = math.log(confidence / (1 - confidence))
            boxes[0, i] = box
        objects = forklift_detector.postprocess(logits, boxes, 640, 360, self.threshold)
        return {"width": 640, "height": 360, "inferenceMs": 9.0, "objects": objects}


class ServiceDetectorTrackingTest(unittest.TestCase):
    """The service's engine construction -> adapter -> Motion: weak detections of an established track reach the tracker."""

    WORKER = (0.3, (0.5, 0.6, 0.1, 0.5))
    ELSEWHERE = (0.3, (0.2, 0.6, 0.1, 0.5))

    def track(self, frames, cuts=()):
        ScriptedWarehouseDetector.frames = frames
        with mock.patch.object(forklift_detector, "WarehouseDetector", ScriptedWarehouseDetector), \
                mock.patch.dict(os.environ, {"EDGEAI_FORKLIFT_MODEL": "/model.onnx"}):
            detector = server.make_detector()
        motion, t0 = Motion(frame_rate=24), datetime.datetime(2026, 10, 26, 5, 0, tzinfo=datetime.timezone.utc)
        observations = []
        for i in range(len(frames)):
            detections, width, height, _ = detector.detect(b"jpeg")
            observations.append(motion.observe(t0 + datetime.timedelta(seconds=i / 24), detections, width, height, scene_cut=i in cuts))
        return observations

    def test_weak_detection_keeps_an_established_track_visible_but_cannot_start_one(self):
        strong = (0.9, self.WORKER[1])
        frames = [[strong]] * 10 + [[self.WORKER, self.ELSEWHERE]] * 3 + [[]] + [[strong]]
        observations = self.track(frames)
        weak = observations[10:13]
        self.assertEqual([[(t["id"], t["visible"], t["confidence"]) for t in o["tracks"]] for o in weak], [[("P1", True, 0.3)]] * 3)
        # A frame without any detection stays honestly lost; the next strong detection resumes the same ID.
        self.assertEqual([(t["id"], t["visible"]) for t in observations[13]["tracks"]], [("P1", False)])
        self.assertEqual([(t["id"], t["visible"]) for t in observations[14]["tracks"]], [("P1", True)])

    def test_after_a_scene_cut_weak_detections_do_not_continue_or_start_tracks(self):
        frames = [[(0.9, self.WORKER[1])]] * 10 + [[self.WORKER]] * 3
        observations = self.track(frames, cuts={10})
        self.assertEqual(observations[10]["reset"], "SCENE_CUT")
        self.assertEqual([o["tracks"] for o in observations[10:]], [[]] * 3)


if __name__ == "__main__":
    unittest.main()
