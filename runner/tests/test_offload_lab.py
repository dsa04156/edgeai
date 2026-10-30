"""Offload test console workloads as real child processes: pinned model inference and the bounded CPU/GPU loads."""
import contextlib
import ctypes.util
import io
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
import offload_lab_gpu_load  # noqa: E402
import offload_lab_model  # noqa: E402

HAS_CUDA = ctypes.util.find_library("cuda") is not None


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


class OffloadLabGpuLoadTest(unittest.TestCase):
    def run_in_process(self, load_driver):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = offload_lab_gpu_load.main(["--seconds", "5"], load_driver=load_driver)
        return code, [json.loads(line) for line in out.getvalue().splitlines()]

    def test_unbounded_requests_are_rejected_before_cuda(self):
        for args in (["--seconds", "0"], ["--seconds", "1801"], ["--workers", "1", "--seconds", "5"], []):
            process = subprocess.run([sys.executable, str(EXAMPLES / "offload_lab_gpu_load.py"), *args], capture_output=True, timeout=30)
            self.assertEqual(process.returncode, 2, args)
            self.assertNotIn(b"gpu-load-", process.stdout)

    def test_missing_cuda_driver_fails_without_cpu_fallback(self):
        def missing():
            raise OSError("libcuda.so.1: cannot open shared object file")
        started = time.monotonic()
        code, events = self.run_in_process(missing)
        self.assertEqual(code, 1)
        self.assertEqual([e["event"] for e in events], ["gpu-load-failed"])
        self.assertEqual(events[0]["failureType"], "OSError")
        self.assertLess(time.monotonic() - started, 2)

    def test_driver_without_device_reports_the_failing_call(self):
        class NoDevice:
            def __getattr__(self, name):
                def call(*_):
                    return 100 if name == "cuInit" else 0
                return call
        code, events = self.run_in_process(lambda: NoDevice())
        self.assertEqual(code, 1)
        self.assertEqual(events, [{"event": "gpu-load-failed", "failureType": "DriverError", "message": "cuInit returned 100",
                                   "function": "cuInit", "driverCode": 100}])

    @unittest.skipUnless(HAS_CUDA, "CUDA driver not present")
    def test_real_gpu_load_runs_bounded_launches_until_its_duration(self):
        started = time.monotonic()
        process = subprocess.run([sys.executable, str(EXAMPLES / "offload_lab_gpu_load.py"), "--seconds", "2"], capture_output=True, timeout=30)
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        events = [json.loads(line) for line in process.stdout.splitlines()]
        self.assertEqual([e["event"] for e in events], ["gpu-load-started", "gpu-load-stopped"])
        self.assertEqual(events[1]["reason"], "deadline")
        self.assertGreater(events[1]["launches"], 5)
        self.assertLess(time.monotonic() - started, 10)

    @unittest.skipUnless(HAS_CUDA, "CUDA driver not present")
    def test_real_gpu_load_stops_promptly_on_sigterm(self):
        process = subprocess.Popen([sys.executable, str(EXAMPLES / "offload_lab_gpu_load.py"), "--seconds", "600"], stdout=subprocess.PIPE)
        self.assertEqual(json.loads(process.stdout.readline())["event"], "gpu-load-started")
        time.sleep(1)
        stopping = time.monotonic()
        process.terminate()
        self.assertEqual(process.wait(timeout=10), 0)
        self.assertLess(time.monotonic() - stopping, 2)
        self.assertEqual(json.loads(process.stdout.readline())["reason"], "signal")
        process.stdout.close()


if __name__ == "__main__":
    unittest.main()
