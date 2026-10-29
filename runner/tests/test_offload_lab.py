"""Offload test console workloads as real child processes: pinned model inference and the bounded CPU load."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

EXAMPLES = Path(__file__).resolve().parents[1] / "examples"
sys.path.insert(0, str(EXAMPLES))
import offload_lab_model  # noqa: E402


class OffloadLabModelTest(unittest.TestCase):
    def test_pinned_artifact_reaches_its_recorded_held_out_accuracy(self):
        model, digest = offload_lab_model.load()
        self.assertEqual(digest, offload_lab_model.MODEL_SHA256)
        pixels, labels = model["heldOut"]["pixels"], model["heldOut"]["labels"]
        correct = sum(offload_lab_model.predict(model, p) == l for p, l in zip(pixels, labels))
        self.assertEqual(len(labels), 360)
        self.assertGreaterEqual(correct / len(labels), model["heldOut"]["sklearnAccuracy"] - 0.01)

    def test_altered_artifact_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            copy = Path(temp) / "model.json"
            copy.write_bytes(offload_lab_model.MODEL.read_bytes().replace(b'"relu"', b'"tanh"', 1))
            with self.assertRaises(ValueError):
                offload_lab_model.load(copy)

    def run_model(self, parameters):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        work = Path(temp.name)
        (work / "out").mkdir()
        (work / "parameters.json").write_text(json.dumps(parameters))
        env = {**os.environ, "EDGEAI_PARAMETERS_FILE": str(work / "parameters.json"), "EDGEAI_OUTPUT_DIR": str(work / "out"),
               "EDGEAI_TELEMETRY_FILE": str(work / "telemetry.json")}
        process = subprocess.run([sys.executable, str(EXAMPLES / "offload_lab_model.py")], env=env, capture_output=True, timeout=60)
        return process, work

    def test_bounded_run_reports_latency_and_verified_totals(self):
        process, work = self.run_model({"durationSeconds": 10, "inferencesPerSecond": 50})
        self.assertEqual(process.returncode, 0, process.stderr)
        result = json.loads((work / "out" / "output").read_text())
        self.assertEqual(result["modelSha256"], offload_lab_model.MODEL_SHA256)
        self.assertGreater(result["inferences"], 100)
        self.assertGreater(result["heldOutAccuracy"], 0.9)
        sample = json.loads((work / "telemetry.json").read_text())
        self.assertGreaterEqual(sample["sequence"], 5)
        self.assertGreater(sample["latencyMicros"], 0)

    def test_out_of_range_parameters_fail_before_inference(self):
        for parameters in ({"durationSeconds": 9}, {"durationSeconds": 3601}, {"inferencesPerSecond": 0}, {"durationSeconds": "10"}):
            process, work = self.run_model(parameters)
            self.assertNotEqual(process.returncode, 0)
            self.assertFalse((work / "out" / "output").exists())


class OffloadLabLoadTest(unittest.TestCase):
    def test_load_exits_by_itself_after_its_duration(self):
        started = time.monotonic()
        process = subprocess.run([sys.executable, str(EXAMPLES / "offload_lab_load.py"), "--workers", "2", "--seconds", "2"], timeout=30)
        self.assertEqual(process.returncode, 0)
        self.assertLess(time.monotonic() - started, 10)

    def test_sigterm_stops_every_worker(self):
        process = subprocess.Popen([sys.executable, str(EXAMPLES / "offload_lab_load.py"), "--workers", "2", "--seconds", "600"])
        time.sleep(1)
        process.terminate()
        self.assertEqual(process.wait(timeout=10), 0)

    def test_unbounded_requests_are_rejected(self):
        for args in (["--workers", "0", "--seconds", "5"], ["--workers", "17", "--seconds", "5"], ["--workers", "1", "--seconds", "1801"]):
            process = subprocess.run([sys.executable, str(EXAMPLES / "offload_lab_load.py"), *args], capture_output=True, timeout=30)
            self.assertNotEqual(process.returncode, 0)


if __name__ == "__main__":
    unittest.main()
