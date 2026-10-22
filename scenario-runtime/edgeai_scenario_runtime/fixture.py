"""The fixed comparison dataset: prepared float32 NHWC tensors plus their manifest, verified byte for byte."""

import hashlib
import json
from pathlib import Path

import numpy as np


def sha256(data):
    return hashlib.sha256(data).hexdigest()


class Fixture:
    def __init__(self, manifest_bytes, manifest_sha256, inputs_sha256, inputs):
        self.manifest_bytes = manifest_bytes
        self.manifest_sha256 = manifest_sha256
        self.inputs_sha256 = inputs_sha256
        self.inputs = inputs

    @classmethod
    def load(cls, root, expected_manifest_sha256):
        root = Path(root)
        manifest_bytes = (root / "manifest.json").read_bytes()
        manifest_sha256 = sha256(manifest_bytes)
        if manifest_sha256 != expected_manifest_sha256:
            raise ValueError(f"manifest SHA-256 {manifest_sha256} is not the configured {expected_manifest_sha256}")
        manifest = json.loads(manifest_bytes)
        inputs_bytes = (root / "inputs.npy").read_bytes()
        inputs_sha256 = sha256(inputs_bytes)
        if inputs_sha256 != manifest["inputs_sha256"]:
            raise ValueError("inputs.npy SHA-256 does not match the manifest")
        inputs = np.load(root / "inputs.npy", allow_pickle=False)
        samples = manifest["samples"]
        if inputs.dtype != np.float32 or inputs.ndim != 4 or inputs.shape[1:] != (224, 224, 3) or inputs.shape[0] != len(samples):
            raise ValueError(f"inputs must be float32 NHWC (N,224,224,3) with one row per sample, got {inputs.dtype} {inputs.shape}")
        for index, (row, tensor) in enumerate(zip(samples, inputs)):
            if sha256(tensor.tobytes()) != row["tensor_sha256"]:
                raise ValueError(f"tensor {index} SHA-256 does not match the manifest")
        return cls(manifest_bytes, manifest_sha256, inputs_sha256, inputs)
