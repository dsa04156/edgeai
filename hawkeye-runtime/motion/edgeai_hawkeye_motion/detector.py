"""Adapter from the RT-DETR Warehouse detector (hawkeye-runtime/forklift_detector.py, TensorRT GPU) to Motion input.

The engine's detect(jpeg) returns {width, height, inferenceMs, objects: [{classId, label, confidence, bbox}]} with
boxes in image pixels; its identity names the provider, model and engine digests actually loaded.
"""

from .motion import CLASSES, Detection

# Confidence from which the observation service's engine reports boxes to Motion (WarehouseDetector keeps its
# default 0.5 for other callers). ByteTrack uses boxes below its activation threshold (0.45) only to continue tracks it
# already follows; a new ID still needs 0.55, so this admits weak boxes of established tracks without new IDs.
# At 0.5 the 2026-10-26 HSE replays lost a track for single frames 46 and 30 times; 0.30 is the measured knee
# (7 and 1) where no track is kept visible by sub-0.5 boxes for more than 14 frames
# (output/verification/hawkeye-tracking). A frame without any box still leaves the track lost.
THRESHOLD = 0.3


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
