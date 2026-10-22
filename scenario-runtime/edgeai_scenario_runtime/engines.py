"""Device engines, adapted from the accelerator-pool inference probe (inference_probe.py SHA-256 7f55117a…).

Neither engine falls back to the CPU: the CUDA session refuses CPU-assigned nodes and the Hailo engine
requires exactly one Hailo-8 device. model_sha256 stays None until the artifact on disk was hashed.
"""

import contextlib
import ctypes
from pathlib import Path

import numpy as np

from .fixture import sha256

# ResNet-v1-50 TFLite (Hailo Model Zoo) expects caffe-style mean subtraction on RGB 0..255 input.
IMAGENET_MEAN_RGB = np.asarray([123.68, 116.78, 103.94], dtype=np.float32)


def verified(path, expected):
    actual = sha256(Path(path).read_bytes())
    if actual != expected:
        raise ValueError(f"model SHA-256 {actual} is not the configured {expected}")
    return actual


class CudaEngine:
    target = "GPU"
    provider = "CUDAExecutionProvider"
    precision = "FP32 ONNX (TF32 disabled)"

    def __init__(self, model_path, model_sha256):
        self.model_path = model_path
        self.expected_sha256 = model_sha256
        self.model_sha256 = None
        self.device_name = None
        self.runtime = None

    def load(self):
        import onnxruntime as ort

        ort.preload_dlls(directory="")
        cuda = ctypes.CDLL("libcuda.so.1")
        if cuda.cuInit(0) != 0:
            raise RuntimeError("cuInit failed")
        name = ctypes.create_string_buffer(256)
        version = ctypes.c_int()
        if cuda.cuDeviceGetName(name, 256, 0) != 0 or cuda.cuDriverGetVersion(ctypes.byref(version)) != 0:
            raise RuntimeError("CUDA device query failed")
        if "CUDAExecutionProvider" not in ort.get_available_providers():
            raise RuntimeError("onnxruntime has no CUDAExecutionProvider")
        model_sha256 = verified(self.model_path, self.expected_sha256)
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        options.inter_op_num_threads = 1
        options.log_severity_level = 3
        # Session creation fails instead of assigning any node to the CPU provider.
        options.add_session_config_entry("session.disable_cpu_ep_fallback", "1")
        session = ort.InferenceSession(str(self.model_path), options,
                                       providers=[("CUDAExecutionProvider", {"device_id": 0, "use_tf32": 0})])
        session.disable_fallback()
        if session.get_providers()[0] != "CUDAExecutionProvider":
            raise RuntimeError(f"session providers {session.get_providers()} do not start with CUDA")
        self._session = session
        self._input = session.get_inputs()[0].name
        self.device_name = name.value.decode()
        self.runtime = f"onnxruntime {ort.__version__}, CUDA driver {version.value}"
        self.model_sha256 = model_sha256

    def infer(self, tensor):
        return self._session.run(None, {self._input: (tensor - IMAGENET_MEAN_RGB)[None]})[0].reshape(-1)


class HailoEngine:
    target = "NPU"
    provider = "HailoRT"
    precision = "HEF compiled (internal quantization not verified), float32 I/O"
    HAILORT = "4.23.0"

    def __init__(self, model_path, model_sha256):
        self.model_path = model_path
        self.expected_sha256 = model_sha256
        self.model_sha256 = None
        self.device_name = None
        self.runtime = None
        self._stack = contextlib.ExitStack()

    def load(self):
        import hailo_platform as hailo

        if hailo.__version__ != self.HAILORT:
            raise RuntimeError(f"HailoRT {hailo.__version__} is not {self.HAILORT}, the version the HEF was qualified with")
        driver = Path("/sys/module/hailo_pci/version").read_text().strip()
        if driver != self.HAILORT:
            raise RuntimeError(f"hailo_pci driver {driver} is not {self.HAILORT}")
        ids = hailo.Device.scan()
        if len(ids) != 1:
            raise RuntimeError(f"expected exactly one Hailo device, found {len(ids)}")
        with hailo.Device(ids[0]) as device:
            ident = device.control.identify()
            if str(ident.device_architecture) != "HAILO8":
                raise RuntimeError(f"device architecture {ident.device_architecture} is not HAILO8")
            firmware = str(ident.firmware_version)
        model_sha256 = verified(self.model_path, self.expected_sha256)
        params = hailo.VDevice.create_params()
        params.scheduling_algorithm = hailo.HailoSchedulingAlgorithm.NONE
        target = self._stack.enter_context(hailo.VDevice(params, device_ids=ids))
        hef = hailo.HEF(str(self.model_path))
        group = target.configure(hef, hailo.ConfigureParams.create_from_hef(hef, interface=hailo.HailoStreamInterface.PCIe))[0]
        inputs = hailo.InputVStreamParams.make_from_network_group(group, quantized=False, format_type=hailo.FormatType.FLOAT32)
        outputs = hailo.OutputVStreamParams.make_from_network_group(group, quantized=False, format_type=hailo.FormatType.FLOAT32)
        self._pipeline = self._stack.enter_context(hailo.InferVStreams(group, inputs, outputs))
        self._stack.enter_context(group.activate(group.create_params()))
        self._input = hef.get_input_vstream_infos()[0].name
        self._output = hef.get_output_vstream_infos()[0].name
        self.device_name = f"Hailo-8 {ids[0]}"
        self.runtime = f"HailoRT {hailo.__version__}, driver {driver}, firmware {firmware}"
        self.model_sha256 = model_sha256

    def infer(self, tensor):
        # The HEF takes raw float RGB; normalization is compiled into the network.
        return self._pipeline.infer({self._input: tensor.astype(np.float32)[None]})[self._output].reshape(-1)


def create(target, model_path, model_sha256):
    engines = {"GPU": CudaEngine, "NPU": HailoEngine}
    if target not in engines:
        raise ValueError("SCENARIO_TARGET must be GPU or NPU")
    return engines[target](model_path, model_sha256)
