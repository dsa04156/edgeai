"""RT-DETR Warehouse pre/post-processing contract (no TensorRT, no GPU)."""

import io
import sys
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import forklift_detector as fd  # noqa: E402


def logit(p):
    return np.log(p / (1 - p))


def outputs(rows):
    """rows of (class_id, probability, cx, cy, w, h) -> (1, Q, 7) logits, (1, Q, 4) boxes; other classes near zero."""
    logits = np.full((1, len(rows), fd.CLASSES), -20.0, dtype=np.float32)
    boxes = np.zeros((1, len(rows), 4), dtype=np.float32)
    for i, (class_id, p, *box) in enumerate(rows):
        logits[0, i, class_id] = logit(p)
        boxes[0, i] = box
    return logits, boxes


class Preprocess(unittest.TestCase):
    def test_stretches_rgb_to_640_scaled_by_255(self):
        buffer = io.BytesIO()
        Image.new("RGB", (1920, 1080), (255, 0, 51)).save(buffer, "JPEG", quality=100)
        tensor, width, height = fd.preprocess(buffer.getvalue())
        self.assertEqual((width, height), (1920, 1080))
        self.assertEqual(tensor.shape, (1, 3, 640, 640))
        self.assertEqual(tensor.dtype, np.float32)
        np.testing.assert_allclose(tensor[0, :, 320, 320], [1.0, 0.0, 0.2], atol=0.02)

    def test_rejects_non_jpeg(self):
        buffer = io.BytesIO()
        Image.new("RGB", (8, 8)).save(buffer, "PNG")
        for payload in (buffer.getvalue(), b"not an image"):
            with self.assertRaisesRegex(fd.DetectorError, "FRAME_NOT_JPEG"):
                fd.preprocess(payload)


class Postprocess(unittest.TestCase):
    def test_sigmoid_scores_and_normalised_cxcywh_to_image_pixels(self):
        objects = fd.postprocess(*outputs([(5, 0.9, 0.5, 0.5, 0.2, 0.4), (0, 0.7, 0.1, 0.2, 0.1, 0.1)]), 1920, 1080, 0.5)
        self.assertEqual([o["label"] for o in objects], ["person", "forklift"])
        person, forklift = objects
        self.assertEqual(forklift["classId"], 5)
        self.assertAlmostEqual(forklift["confidence"], 0.9, places=5)
        self.assertEqual(forklift["bbox"], [768.0, 324.0, 1152.0, 756.0])
        self.assertEqual(person["bbox"], [96.0, 162.0, 288.0, 270.0])

    def test_threshold_and_untracked_classes_are_dropped(self):
        rows = [(5, 0.49, 0.5, 0.5, 0.1, 0.1), (6, 0.99, 0.5, 0.5, 0.1, 0.1), (3, 0.99, 0.2, 0.2, 0.1, 0.1)]
        self.assertEqual(fd.postprocess(*outputs(rows), 640, 640, 0.5), [])

    def test_class_wise_nms(self):
        rows = [(0, 0.8, 0.5, 0.5, 0.2, 0.2), (0, 0.9, 0.51, 0.5, 0.2, 0.2), (5, 0.6, 0.5, 0.5, 0.2, 0.2),
                (0, 0.7, 0.1, 0.1, 0.1, 0.1)]
        objects = fd.postprocess(*outputs(rows), 1000, 1000, 0.5)
        self.assertEqual([(o["classId"], round(o["confidence"], 2)) for o in objects], [(0, 0.9), (0, 0.7), (5, 0.6)])

    def test_boxes_clipped_to_image(self):
        (only,) = fd.postprocess(*outputs([(5, 0.9, 0.05, 0.95, 0.2, 0.2)]), 100, 100, 0.5)
        self.assertEqual(only["bbox"], [0.0, 85.0, 15.0, 100.0])

    def test_rejects_unexpected_output_shape(self):
        with self.assertRaisesRegex(fd.DetectorError, "MODEL_OUTPUT_INVALID"):
            fd.postprocess(np.zeros((1, 300, 80)), np.zeros((1, 300, 4)), 640, 640, 0.5)


if __name__ == "__main__":
    unittest.main()
