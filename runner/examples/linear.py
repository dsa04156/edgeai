"""Reference CPU linear inference on explicitly synthetic data; not hardware acceptance."""
import json
import math
import os
import time
from pathlib import Path

parameters = json.loads(Path(os.environ["EDGEAI_PARAMETERS_FILE"]).read_text())
# Bounded synthetic fault window, shared with the Remote reference provider. Not a latency measurement.
delay = parameters.get("simulationDelayMillis", 0)
if type(delay) is not int or not 0 <= delay <= 60000:
    raise ValueError("Invalid synthetic delay")
time.sleep(delay / 1000)
input_path = Path(os.environ["EDGEAI_INPUT_DIR"]) / "input"
data = json.loads(input_path.read_text()) if input_path.exists() else parameters
features, weights = data["features"], parameters["weights"]
if not features or len(features) != len(weights) or len(features) > 4096:
    raise ValueError("Invalid feature/weight dimensions")
numbers = [float(value) for value in [*features, *weights, parameters.get("bias", 0)]]
if not all(math.isfinite(value) for value in numbers):
    raise ValueError("Non-finite model input")
score = sum(float(x) * float(w) for x, w in zip(features, weights)) + float(parameters.get("bias", 0))
result = {"sourceMode": "SYNTHETIC", "score": score, "prediction": int(score >= 0), "features": features}
(Path(os.environ["EDGEAI_OUTPUT_DIR"]) / "output").write_text(json.dumps(result, allow_nan=False))
