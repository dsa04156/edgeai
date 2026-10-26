"""NVIDIA TAO RT-DETR 2D Warehouse (deployable_rn50_v1.0.2) on the TensorRT GPU runtime.

The NGC ONNX contains six nvidia:MultiscaleDeformableAttnPlugin_TRT nodes, which only TensorRT's libnvinfer_plugin
implements, so the graph is parsed unmodified by TensorRT and built into an engine for the local GPU on first use.
Engines are specific to the GPU and the TensorRT version; the cache name carries both and the ONNX SHA-256.

Pre/post-processing follows the NVIDIA VSS warehouse-2d DeepStream configuration of this model
(services/rtvi/rt-cv/configs/warehouse-2d/ds-pgie-config.yml) and the NGC experiment.yaml
(eval_spatial_size 640x640, preserve_aspect_ratio false): RGB stretched to 640x640, scaled by 1/255 without mean
offsets; pred_logits are per-class logits (sigmoid), pred_boxes normalised (cx, cy, w, h); class-wise NMS at IoU 0.5,
top 20 per class. Only Person (0) and Forklift (5) are reported.
"""

import hashlib
import io
import os
import time
from pathlib import Path

import numpy as np
from PIL import Image, UnidentifiedImageError

MODEL = "nvidia/tao/rtdetr_2d_warehouse:deployable_rn50_v1.0.2"
MODEL_SHA256 = "7b57da65ea42d0ed835da7ffee0656a7ba32337402ba8bff3dd1d0dd77b17084"
CLASSES = 7
TRACKED = {0: "person", 5: "forklift"}
INPUT = (640, 640)  # width, height
NMS_IOU = 0.5
TOP_K = 20


class DetectorError(Exception):
    pass


def preprocess(jpeg):
    """JPEG bytes -> (1x3x640x640 float32 RGB in [0, 1], original width, original height)."""
    try:
        with Image.open(io.BytesIO(jpeg)) as frame:
            if frame.format != "JPEG":
                raise DetectorError("FRAME_NOT_JPEG")
            width, height = frame.size
            rgb = frame.convert("RGB").resize(INPUT, Image.BILINEAR)
    except (UnidentifiedImageError, OSError):
        raise DetectorError("FRAME_NOT_JPEG") from None
    return np.ascontiguousarray(np.asarray(rgb, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0), width, height


def _iou(box, boxes):
    x1, y1 = np.maximum(box[0], boxes[:, 0]), np.maximum(box[1], boxes[:, 1])
    x2, y2 = np.minimum(box[2], boxes[:, 2]), np.minimum(box[3], boxes[:, 3])
    inter = np.clip(x2 - x1, 0, None) * np.clip(y2 - y1, 0, None)
    area = lambda b: (b[..., 2] - b[..., 0]) * (b[..., 3] - b[..., 1])  # noqa: E731
    return inter / np.maximum(area(box) + area(boxes) - inter, 1e-9)


def postprocess(logits, boxes, width, height, threshold):
    """pred_logits, pred_boxes -> contract objects in image pixels, highest confidence first per class."""
    logits, boxes = np.asarray(logits, dtype=np.float32), np.asarray(boxes, dtype=np.float32)
    if logits.shape[-1] != CLASSES or boxes.shape[-1] != 4 or logits.size // CLASSES != boxes.size // 4:
        raise DetectorError("MODEL_OUTPUT_INVALID")
    scores = 1.0 / (1.0 + np.exp(-np.clip(logits.reshape(-1, CLASSES), -80, 80)))
    cx, cy, w, h = boxes.reshape(-1, 4).T
    xyxy = np.stack([(cx - w / 2) * width, (cy - h / 2) * height, (cx + w / 2) * width, (cy + h / 2) * height], axis=1)
    xyxy = np.clip(xyxy, 0, [width, height, width, height])
    objects = []
    for class_id, label in TRACKED.items():
        keep = np.flatnonzero(scores[:, class_id] >= threshold)
        keep = keep[np.argsort(-scores[keep, class_id])]
        chosen = []
        while keep.size and len(chosen) < TOP_K:
            best, keep = keep[0], keep[1:]
            chosen.append(best)
            keep = keep[_iou(xyxy[best], xyxy[keep]) < NMS_IOU]
        objects += [{"classId": class_id, "label": label, "confidence": float(scores[i, class_id]),
                     "bbox": [round(float(v), 1) for v in xyxy[i]]} for i in chosen]
    return objects


def file_sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def _check(result):
    error = result[0] if isinstance(result, tuple) else result
    if int(error) != 0:
        raise DetectorError(f"CUDA_ERROR {error}")
    return result[1] if isinstance(result, tuple) and len(result) > 1 else None


class WarehouseDetector:
    """detect(jpeg) -> {width, height, inferenceMs, objects}. engine_path is where the serialized engine is cached; by
    default it is named after TensorRT version, GPU architecture and ONNX digest inside EDGEAI_FORKLIFT_ENGINE_DIR, or
    next to the ONNX file, and that directory must be writable when no engine was built yet. threads is accepted
    for the shared detector contract; TensorRT executes on the GPU and the CPU work is JPEG decode and NMS."""

    def __init__(self, model_path, threads=4, engine_path=None, threshold=0.5):
        import tensorrt_bindings as trt  # the PyPI "tensorrt" name is only an alias package (sdist) for this wheel
        from cuda.bindings import runtime as cudart

        self._trt, self._cudart, self.threshold = trt, cudart, threshold
        digest = file_sha256(model_path)
        if digest != MODEL_SHA256:
            raise DetectorError("MODEL_SHA256_MISMATCH")
        _check(cudart.cudaSetDevice(0))
        props = _check(cudart.cudaGetDeviceProperties(0))
        self.gpu = props.name.decode(errors="replace").rstrip("\x00")
        sm = f"sm{props.major}{props.minor}"
        logger = trt.Logger(trt.Logger.ERROR)
        if not trt.init_libnvinfer_plugins(logger, ""):
            raise DetectorError("TRT_PLUGINS_UNAVAILABLE")
        name = Path(model_path).with_suffix(f".trt{trt.__version__}.{sm}.{digest[:12]}.engine").name
        engine_path = Path(engine_path or Path(os.environ.get("EDGEAI_FORKLIFT_ENGINE_DIR") or Path(model_path).parent) / name)
        if not engine_path.exists():
            self._build(model_path, engine_path, logger)
        self.engine_digest = file_sha256(engine_path)
        self.engine = trt.Runtime(logger).deserialize_cuda_engine(engine_path.read_bytes())
        if self.engine is None:
            raise DetectorError("TRT_ENGINE_INCOMPATIBLE")
        self.context = self.engine.create_execution_context()
        self.stream = _check(cudart.cudaStreamCreate())
        self.host, self.device = {}, {}
        for i in range(self.engine.num_io_tensors):
            name = self.engine.get_tensor_name(i)
            if self.engine.get_tensor_mode(name) == trt.TensorIOMode.INPUT:
                self.context.set_input_shape(name, (1, 3, INPUT[1], INPUT[0]))
                self.input = name
            shape = tuple(self.context.get_tensor_shape(name))
            self.host[name] = np.empty(shape, dtype=trt.nptype(self.engine.get_tensor_dtype(name)))
            self.device[name] = _check(cudart.cudaMalloc(self.host[name].nbytes))
            self.context.set_tensor_address(name, self.device[name])
        if not {"pred_logits", "pred_boxes"} <= set(self.host):
            raise DetectorError("MODEL_OUTPUT_INVALID")
        self.identity = {"provider": f"TensorRT {trt.__version__} GPU ({self.gpu}, {sm})", "model": MODEL,
                         "modelDigest": f"sha256:{digest}", "engineDigest": f"sha256:{self.engine_digest}",
                         "precision": "fp32", "input": [3, INPUT[1], INPUT[0]], "threshold": threshold}

    def _build(self, model_path, engine_path, logger):
        trt = self._trt
        builder = trt.Builder(logger)
        network = builder.create_network(0)
        parser = trt.OnnxParser(network, logger)
        if not parser.parse_from_file(str(model_path)):
            raise DetectorError("TRT_PARSE_FAILED " + "; ".join(str(parser.get_error(i)) for i in range(parser.num_errors)))
        config = builder.create_builder_config()
        config.set_memory_pool_limit(trt.MemoryPoolType.WORKSPACE, 2 << 30)
        profile = builder.create_optimization_profile()
        shape = (1, 3, INPUT[1], INPUT[0])
        profile.set_shape(network.get_input(0).name, shape, shape, shape)
        config.add_optimization_profile(profile)
        serialized = builder.build_serialized_network(network, config)
        if serialized is None:
            raise DetectorError("TRT_BUILD_FAILED")
        engine_path.parent.mkdir(parents=True, exist_ok=True)
        partial = engine_path.with_name(engine_path.name + f".{os.getpid()}.partial")
        partial.write_bytes(serialized)
        partial.replace(engine_path)

    def detect(self, jpeg):
        cudart = self._cudart
        tensor, width, height = preprocess(jpeg)
        h2d, d2h = cudart.cudaMemcpyKind.cudaMemcpyHostToDevice, cudart.cudaMemcpyKind.cudaMemcpyDeviceToHost
        started = time.perf_counter()
        _check(cudart.cudaMemcpyAsync(self.device[self.input], tensor.ctypes.data, tensor.nbytes, h2d, self.stream))
        if not self.context.execute_async_v3(self.stream):
            raise DetectorError("TRT_EXECUTE_FAILED")
        for name in ("pred_logits", "pred_boxes"):
            out = self.host[name]
            _check(cudart.cudaMemcpyAsync(out.ctypes.data, self.device[name], out.nbytes, d2h, self.stream))
        _check(cudart.cudaStreamSynchronize(self.stream))
        elapsed = (time.perf_counter() - started) * 1000
        objects = postprocess(self.host["pred_logits"], self.host["pred_boxes"], width, height, self.threshold)
        return {"width": width, "height": height, "inferenceMs": round(elapsed, 2), "objects": objects}
