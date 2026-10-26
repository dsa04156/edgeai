"""Detector adapter over the WarehouseDetector contract (the TensorRT engine itself is exercised by the runtime probe)."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from edgeai_hawkeye_motion.detector import Detector  # noqa: E402
from edgeai_hawkeye_motion.motion import Detection  # noqa: E402


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


if __name__ == "__main__":
    unittest.main()
