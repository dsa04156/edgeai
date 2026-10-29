"""Offload test console model: real CPU inference of a pinned, pre-trained digits MLP over its held-out samples.

Small CPU model for an edge-to-worker placement test, not a GPU/NPU or accuracy benchmark. Runs a bounded loop, reports the
measured per-inference latency through EDGEAI_TELEMETRY_FILE and writes the verified totals when the duration ends.
"""
import hashlib
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path

MODEL = Path(__file__).with_name("models") / "digits-mlp-v1.json"
MODEL_SHA256 = "7a88a17359f7d6ee301eb5d5a9f84325782ccee838ef488a6cf7216f10ff5469"


def load(path=MODEL):
    body = path.read_bytes()
    digest = hashlib.sha256(body).hexdigest()
    if digest != MODEL_SHA256:
        raise ValueError("Model artifact digest mismatch")
    model = json.loads(body)
    if model.get("format") != "edgeai.offload-lab.mlp/v1" or [(l["inputs"], l["outputs"]) for l in model["layers"]] != [(64, 32), (32, 10)]:
        raise ValueError("Unsupported model artifact")
    return model, digest


def predict(model, pixels):
    values = [p / 16.0 for p in pixels]
    for index, layer in enumerate(model["layers"]):
        weights, outputs = layer["weights"], layer["outputs"]
        values = [sum(v * weights[i * outputs + j] for i, v in enumerate(values)) + layer["bias"][j] for j in range(outputs)]
        if index == 0:
            values = [max(0.0, v) for v in values]
    return values.index(max(values))


def bounded(parameters, name, default, low, high):
    value = parameters.get(name, default)
    if type(value) is not int or not low <= value <= high:
        raise ValueError(f"Invalid {name}")
    return value


def report(path, sequence, latency):
    if not path:
        return
    target = Path(path)
    temporary = target.with_name(target.name + ".tmp")
    observed = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    temporary.write_text(json.dumps({"sequence": sequence, "observedAt": observed, "latencyMicros": latency}))
    os.replace(temporary, target)


def main():
    parameters = json.loads(Path(os.environ["EDGEAI_PARAMETERS_FILE"]).read_text())
    duration = bounded(parameters, "durationSeconds", 1200, 10, 3600)
    rate = bounded(parameters, "inferencesPerSecond", 20, 1, 200)
    model, digest = load()
    samples, labels = model["heldOut"]["pixels"], model["heldOut"]["labels"]
    telemetry = os.environ.get("EDGEAI_TELEMETRY_FILE")
    started = time.monotonic()
    inferences = correct = sequence = 0
    last_report = 0.0
    while time.monotonic() - started < duration:
        tick = time.monotonic()
        index = inferences % len(samples)
        begin = time.perf_counter_ns()
        prediction = predict(model, samples[index])
        latency = max(1, (time.perf_counter_ns() - begin) // 1000)
        inferences += 1
        correct += prediction == labels[index]
        if tick - last_report >= 1.0:
            sequence += 1
            report(telemetry, sequence, latency)
            last_report = tick
        time.sleep(max(0.0, 1.0 / rate - (time.monotonic() - tick)))
    result = {"sourceMode": "MODEL", "model": model["name"], "modelSha256": digest, "dataset": model["dataset"],
              "inferences": inferences, "heldOutAccuracy": round(correct / inferences, 6) if inferences else None,
              "durationSeconds": duration}
    (Path(os.environ["EDGEAI_OUTPUT_DIR"]) / "output").write_text(json.dumps(result, allow_nan=False))


if __name__ == "__main__":
    main()
