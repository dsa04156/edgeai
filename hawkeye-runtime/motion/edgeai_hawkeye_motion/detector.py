"""Adapter from the RT-DETR Warehouse detector (hawkeye-runtime/forklift_detector.py, TensorRT GPU) to Motion input.

The engine's detect(jpeg) returns {width, height, inferenceMs, objects: [{classId, label, confidence, bbox}]} with
boxes in image pixels; its identity names the provider, model and engine digests actually loaded.
"""

from .motion import CLASSES, Detection


class Detector:
    def __init__(self, engine):
        self.engine = engine
        self.providers = [engine.identity["provider"]]

    def identity(self):
        return dict(self.engine.identity)

    def detect(self, image):
        """JPEG bytes -> ([Detection], width, height, milliseconds of model execution)."""
        result = self.engine.detect(image)
        detections = [Detection(o["label"], float(o["confidence"]), tuple(float(v) for v in o["bbox"]))
                      for o in result["objects"] if o["label"] in CLASSES]
        return detections, result["width"], result["height"], result["inferenceMs"]
