"""torchvision Faster R-CNN ResNet-50 FPN v2 (COCO_V1) on CUDA only."""

import os

import torch
from torchvision.models.detection import (
    FasterRCNN_ResNet50_FPN_V2_Weights,
    fasterrcnn_resnet50_fpn_v2,
)
from torchvision.transforms.functional import pil_to_tensor

WEIGHTS = FasterRCNN_ResNet50_FPN_V2_Weights.COCO_V1
# Baked into the image at build time by fetch_weights.py; never fetched at runtime.
WEIGHTS_PATH = os.environ.get(
    "EDGEAI_INFERENCE_WEIGHTS", "/opt/models/" + os.path.basename(WEIGHTS.url)
)
SCORE_THRESHOLD = 0.5
MAX_DETECTIONS = 100


class TorchvisionDetector:
    model_id = "fasterrcnn-resnet50-fpn-v2-coco-v1"
    provider = "pytorch-cuda"

    def __init__(self):
        self.device_name = ""
        self._model = None
        self._labels = WEIGHTS.meta["categories"]

    def warm_up(self):
        if not torch.cuda.is_available():
            raise RuntimeError("CUDA is not available")
        device = torch.device("cuda", 0)
        self.device_name = torch.cuda.get_device_name(device)
        model = fasterrcnn_resnet50_fpn_v2(
            weights=None,
            weights_backbone=None,
            num_classes=len(self._labels),
            box_score_thresh=SCORE_THRESHOLD,
            box_detections_per_img=MAX_DETECTIONS,
        )
        model.load_state_dict(torch.load(WEIGHTS_PATH, map_location="cpu", weights_only=True))
        self._model = model.eval().to(device)
        self._device = device
        self._run(torch.zeros(3, 320, 320))

    @torch.inference_mode()
    def _run(self, tensor):
        output = self._model([tensor.to(self._device)])[0]
        torch.cuda.synchronize(self._device)
        return output

    def detect(self, image):
        # WEIGHTS.transforms() is ConvertImageDtype(float): uint8 -> [0, 1].
        # Resize and normalization happen inside the model, and its postprocess
        # maps boxes back to the original image size.
        tensor = WEIGHTS.transforms()(pil_to_tensor(image))
        output = self._run(tensor)
        detections = []
        for box, label, score in zip(
            output["boxes"].tolist(), output["labels"].tolist(), output["scores"].tolist()
        ):
            x1, y1, x2, y2 = box
            # COCO uses 80 classes in 91 index slots; unused slots are named "N/A".
            if self._labels[label] in ("N/A", "__background__"):
                continue
            detections.append(
                {
                    "label": self._labels[label],
                    "score": round(score, 4),
                    "x": round(x1, 2),
                    "y": round(y1, 2),
                    "width": round(x2 - x1, 2),
                    "height": round(y2 - y1, 2),
                }
            )
        return detections
