"""Common bounded signal processing, shared by every VD and artifact consumer."""
from __future__ import annotations

import hashlib
import json
import math
import cmath
from decimal import Decimal
from datetime import datetime
import os
import re
from pathlib import Path


class ProcessingError(ValueError):
    pass


def _number(value):
    try:
        valid = type(value) in (int, float, Decimal) and math.isfinite(value)
    except (OverflowError, ValueError):
        valid = False
    if not valid:
        raise ProcessingError("A finite numeric signal is required")
    return float(value)


def _object(value, keys):
    if not isinstance(value, dict) or set(value) != set(keys):
        raise ProcessingError("Invalid processing fields")
    return value


def _name(value):
    if not isinstance(value, str) or len(value) > 63 or not re.fullmatch(r"[a-z][a-z0-9_]*", value):
        raise ProcessingError("Invalid processing name")


def _integer(value, maximum):
    if type(value) is not int or not 1 <= value <= maximum:
        raise ProcessingError("Processing integer out of range")
    return value


def _validate(config):
    _object(config, ("apiVersion", "sampleCount", "outputPort", "pipelines"))
    if config["apiVersion"] != "edgeai.processing/v1":
        raise ProcessingError("Unsupported processing version")
    count = _integer(config["sampleCount"], 1024)
    _name(config["outputPort"])
    if not isinstance(config["pipelines"], list) or not 1 <= len(config["pipelines"]) <= 16:
        raise ProcessingError("1–16 processing pipelines required")
    names = set()
    for pipeline in config["pipelines"]:
        _object(pipeline, ("name", "input", "steps"))
        _name(pipeline["name"])
        if pipeline["name"] in names:
            raise ProcessingError("Duplicate pipeline name")
        names.add(pipeline["name"])
        source = pipeline["input"]
        if not isinstance(source, dict):
            raise ProcessingError("Invalid processing input")
        if "sourceKey" in source:
            _object(source, ("sourceKey", "property"))
            if not isinstance(source["sourceKey"], str) or len(source["sourceKey"]) > 100 or not re.fullmatch(r"[a-z][a-z0-9]*([._-][a-z0-9]+)*", source["sourceKey"]):
                raise ProcessingError("Invalid source key")
            _name(source["property"])
        else:
            _object(source, ("port", "output"))
            _name(source["port"])
            _name(source["output"])
        steps = pipeline["steps"]
        if not isinstance(steps, list) or not 1 <= len(steps) <= 16:
            raise ProcessingError("1–16 processing steps required")
        remaining = count
        spectrum = False
        for index, step in enumerate(steps):
            if not isinstance(step, dict):
                raise ProcessingError("Invalid processing step")
            op = step.get("op")
            if op == "filter":
                _object(step, ("op", "mode", "alpha"))
                if step["mode"] not in ("lowPass", "highPass") or not 0 < _number(step["alpha"]) <= 1:
                    raise ProcessingError("Invalid filter")
            elif op in ("movingAverage", "rms"):
                _object(step, ("op", "window"))
                remaining -= _integer(step["window"], remaining) - 1
            elif op == "fft":
                _object(step, ("op", "sampleRateHz"))
                if spectrum or remaining < 2 or remaining & (remaining - 1) or not 0 < _number(step["sampleRateHz"]) <= 1000000:
                    raise ProcessingError("FFT requires a time signal, power-of-two samples and positive sample rate")
                spectrum = True
                remaining = remaining // 2 + 1
            elif op == "threshold":
                _object(step, ("op", "operator", "value"))
                if index != len(steps) - 1 or step["operator"] not in ("gt", "gte", "lt", "lte", "eq"):
                    raise ProcessingError("Threshold must be the final step")
                _number(step["value"])
            else:
                raise ProcessingError("Unknown processing operation")


def _digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False, default=str).encode()).hexdigest()


def _window(values, size, rms=False):
    if type(size) is not int or not 1 <= size <= len(values):
        raise ProcessingError("Insufficient samples for the processing window")
    return [math.sqrt(math.fsum((v / math.sqrt(size)) ** 2 for v in values[i:i + size])) if rms
            else math.fsum(v / size for v in values[i:i + size]) for i in range(len(values) - size + 1)]


def _step(values, step):
    op = step["op"]
    if op == "filter":
        alpha = _number(step["alpha"])
        if not 0 < alpha <= 1 or step["mode"] not in ("lowPass", "highPass"):
            raise ProcessingError("Invalid filter")
        result = [values[0] if step["mode"] == "lowPass" else 0.0]
        for previous, value in zip(values, values[1:]):
            result.append(alpha * value + (1 - alpha) * result[-1] if step["mode"] == "lowPass"
                          else alpha * (result[-1] + value - previous))
        return result
    if op in ("movingAverage", "rms"):
        return _window(values, step["window"], op == "rms")
    if op == "threshold":
        target = _number(step["value"])
        comparisons = {"gt": lambda x: x > target, "gte": lambda x: x >= target,
                       "lt": lambda x: x < target, "lte": lambda x: x <= target, "eq": lambda x: x == target}
        if step["operator"] not in comparisons:
            raise ProcessingError("Invalid threshold operator")
        return [comparisons[step["operator"]](v) for v in values]
    raise ProcessingError("Unknown processing operation")


def _fft(values):
    if len(values) == 1:
        return [complex(values[0])]
    even, odd = _fft(values[::2]), _fft(values[1::2])
    weighted = [cmath.exp(-2j * math.pi * k / len(values)) * value for k, value in enumerate(odd)]
    return [a + b for a, b in zip(even, weighted)] + [a - b for a, b in zip(even, weighted)]


def process(config, frame, artifacts, upstream_hashes=None):
    _validate(config)
    outputs = {}
    upstream = {}
    for pipeline in config["pipelines"]:
        source = pipeline["input"]
        frequencies = None
        if "sourceKey" in source:
            reading = frame["sources"][source["sourceKey"]]["properties"][source["property"]]
            samples = reading["samples"] if "samples" in reading else [{"value": reading["value"], "observedAt": reading["observedAt"]}]
            values = [sample["value"] for sample in samples]
            observed = [sample["observedAt"] for sample in samples]
        else:
            artifact = artifacts[source["port"]]
            if artifact.get("apiVersion") != "edgeai.processing-output/v1":
                raise ProcessingError("Invalid upstream processing artifact")
            values = artifact["outputs"][source["output"]]["values"]
            observed = artifact["outputs"][source["output"]].get("observedAt")
            frequencies = artifact["outputs"][source["output"]].get("frequenciesHz")
            upstream[source["port"]] = upstream_hashes[source["port"]] if upstream_hashes is not None else _digest(artifact)
        count = config["sampleCount"]
        if not isinstance(values, list) or not count <= len(values) <= 1024:
            raise ProcessingError("Insufficient samples")
        for coordinates in (observed, frequencies):
            if coordinates is not None and (not isinstance(coordinates, list) or len(coordinates) != len(values)):
                raise ProcessingError("Signal coordinates must match the values")
        values = [_number(v) for v in values[-count:]]
        if observed is not None:
            observed = observed[-count:]
        if frequencies is not None:
            frequencies = [_number(v) for v in frequencies[-count:]]
        for step in pipeline["steps"]:
            if step["op"] == "fft":
                size = len(values)
                rate = _number(step["sampleRateHz"])
                if frequencies is not None:
                    raise ProcessingError("FFT requires a time signal, not an upstream spectrum")
                if observed is not None:
                    try:
                        times = [datetime.fromisoformat(value.replace("Z", "+00:00")) for value in observed]
                        if any(value.tzinfo is None for value in times):
                            raise ValueError("Timezone required")
                        times = [value.timestamp() for value in times]
                    except (AttributeError, ValueError, OverflowError) as error:
                        raise ProcessingError("Invalid observation timestamp") from error
                    if any(abs((b - a) * rate - 1) > 0.1 for a, b in zip(times, times[1:])):
                        raise ProcessingError("FFT requires observations spaced within 10% of the declared sample rate")
                transformed = _fft(values)
                values = [abs(v) / size * (1 if k in (0, size // 2) else 2) for k, v in enumerate(transformed[:size // 2 + 1])]
                frequencies = [k * rate / size for k in range(size // 2 + 1)]
                observed = None
            else:
                values = _step(values, step)
                if frequencies is not None and step["op"] in ("movingAverage","rms"):
                    frequencies = frequencies[step["window"] - 1:]
                if observed is not None and step["op"] in ("movingAverage","rms"):
                    observed = observed[step["window"] - 1:]
            if any(type(v) is not bool and not math.isfinite(v) for v in values):
                raise ProcessingError("Processing produced a non-finite result")
        outputs[pipeline["name"]] = {"values": values}
        if frequencies is not None:
            outputs[pipeline["name"]]["frequenciesHz"] = frequencies
        if observed is not None:
            outputs[pipeline["name"]]["observedAt"] = observed
    return {"apiVersion": "edgeai.processing-output/v1", "vdId": frame["vdId"], "vdRevision": frame["vdRevision"],
            "capturedAt": frame["capturedAt"], "inputSha256": _digest(frame), "upstreamSha256": upstream,
            "outputs": outputs}


def main():
    """A single reusable SERVICE command; no generated per-sensor code."""
    parameters = json.loads(Path(os.environ["EDGEAI_PARAMETERS_FILE"]).read_text())
    output = parameters["vdInputs"]["processing"]["outputPort"]
    if not output or "/" in output or output in (".", ".."):
        raise ProcessingError("Invalid output port")
    Path(os.environ["EDGEAI_OUTPUT_DIR"], output).write_text(json.dumps(parameters["vdProcessed"], allow_nan=False))


if __name__ == "__main__":
    main()
