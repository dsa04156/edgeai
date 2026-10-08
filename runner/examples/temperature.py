"""Convert a pinned Sense HAT temperature; provenance is copied from the server input."""
import json
import math
import os
from pathlib import Path

parameters = json.loads(Path(os.environ["EDGEAI_PARAMETERS_FILE"]).read_text())
frame = parameters["vdInputs"]
source = frame["sources"]["input"]
reading = source["properties"]["temp_humidity"]
temperature = reading["value"]
if type(temperature) not in (int, float) or not math.isfinite(temperature):
    raise ValueError("Invalid pinned temperature")
output = {
    "sourceMode": "SENSOR",
    "temperatureC": temperature,
    "temperatureF": temperature * 1.8 + 32,
    "deviceId": source["deviceId"],
    "deviceUid": source["deviceUid"],
    "bindingId": source["bindingId"],
    "observedAt": reading["observedAt"],
    "capturedAt": frame["capturedAt"],
}
(Path(os.environ["EDGEAI_OUTPUT_DIR"]) / "output").write_text(json.dumps(output, allow_nan=False))
