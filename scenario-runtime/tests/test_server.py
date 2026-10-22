"""HTTP contract of the scenario runtime with a TEST-ONLY engine and a tiny generated fixture.

FakeEngine never touches a GPU or NPU; real devices are verified only by deployment.
"""

import hashlib
import http.client
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from edgeai_scenario_runtime import server  # noqa: E402
from edgeai_scenario_runtime.fixture import Fixture  # noqa: E402

COUNT = 3


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_fixture(root, corrupt_tensor=False):
    data = np.arange(COUNT * 224 * 224 * 3, dtype=np.float32).reshape(COUNT, 224, 224, 3) % 255
    np.save(root / "inputs.npy", data, allow_pickle=False)
    rows = [{"path": f"x/{i}.JPEG", "tensor_sha256": sha(data[i].tobytes()), "label": i, "reference_top1": i} for i in range(COUNT)]
    if corrupt_tensor:
        rows[1]["tensor_sha256"] = "0" * 64
    manifest = {"kind": "test-fixture", "preprocessing": "test", "inputs_sha256": sha((root / "inputs.npy").read_bytes()), "samples": rows}
    (root / "manifest.json").write_text(json.dumps(manifest))
    return sha((root / "manifest.json").read_bytes()), data


class FakeEngine:
    """TEST-ONLY: deterministic scores, no accelerator."""

    target = "GPU"
    provider = "fake-provider"
    precision = "fake-precision"
    runtime = "fake-runtime 0"
    model_sha256 = "a" * 64

    def __init__(self, fail=None, scores=None):
        self.device_name = None
        self.fail = fail
        self.scores = scores
        self.calls = 0
        self.release = threading.Event()
        self.release.set()
        self.entered = threading.Event()

    def load(self):
        if self.fail:
            raise RuntimeError(self.fail)
        self.device_name = "Fake Device 0"

    def infer(self, tensor):
        self.calls += 1
        self.entered.set()
        self.release.wait(5)
        if self.scores is not None:
            return self.scores
        scores = np.zeros(1000, dtype=np.float32)
        scores[int(tensor[0, 0, 0]) % 1000] = 9.0
        scores[999] = 1.5
        return scores


class ServerTest(unittest.TestCase):
    def start(self, engine, corrupt_tensor=False, expected=None):
        self.dir = Path(tempfile.mkdtemp())
        manifest_sha, self.data = write_fixture(self.dir, corrupt_tensor)
        self.manifest_sha = manifest_sha
        self.engine = engine
        self.runtime = server.Runtime(engine, self.dir, expected or manifest_sha, "node-a")
        self.runtime.start()
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(self.runtime))
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.addCleanup(self.httpd.server_close)
        self.addCleanup(self.httpd.shutdown)

    def call(self, method, path, body=None, raw=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_address[1], timeout=10)
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        conn.request(method, path, body=data, headers={"Content-Type": "application/json"})
        response = conn.getresponse()
        payload = response.read()
        conn.close()
        return response.status, payload

    def classify(self, body):
        status, payload = self.call("POST", "/classify", body)
        return status, json.loads(payload)

    def test_health_reports_identity_after_fixture_and_engine_are_verified(self):
        self.start(FakeEngine())
        status, payload = self.call("GET", "/health")
        body = json.loads(payload)
        self.assertEqual(status, 200)
        self.assertEqual(body["status"], "READY")
        self.assertEqual(body["target"], "GPU")
        self.assertEqual(body["nodeName"], "node-a")
        self.assertEqual(body["deviceName"], "Fake Device 0")
        self.assertEqual(body["modelSha256"], "a" * 64)
        self.assertEqual(body["manifestSha256"], self.manifest_sha)
        self.assertEqual(body["inputsSha256"], sha((self.dir / "inputs.npy").read_bytes()))
        self.assertEqual(body["sampleCount"], COUNT)
        # Startup warm-up ran on the device before READY.
        self.assertGreater(self.engine.calls, 0)

    def test_manifest_is_served_as_exact_bytes(self):
        self.start(FakeEngine())
        status, payload = self.call("GET", "/dataset/manifest")
        self.assertEqual(status, 200)
        self.assertEqual(sha(payload), self.manifest_sha)

    def test_classify_returns_sorted_top5_for_the_verified_input(self):
        self.start(FakeEngine())
        status, body = self.classify({"index": 1, "manifestSha256": self.manifest_sha})
        self.assertEqual(status, 200)
        self.assertEqual(body["index"], 1)
        self.assertEqual(body["inputSha256"], sha(self.data[1].tobytes()))
        self.assertEqual(body["manifestSha256"], self.manifest_sha)
        self.assertEqual(body["modelSha256"], "a" * 64)
        self.assertEqual((body["nodeName"], body["deviceName"], body["provider"]), ("node-a", "Fake Device 0", "fake-provider"))
        self.assertEqual(len(body["topK"]), 5)
        self.assertEqual(body["topK"][0], {"classIndex": int(self.data[1][0, 0, 0]) % 1000, "score": 9.0})
        self.assertEqual(body["topK"][1], {"classIndex": 999, "score": 1.5})
        scores = [item["score"] for item in body["topK"]]
        self.assertEqual(scores, sorted(scores, reverse=True))
        self.assertGreaterEqual(body["inferenceMs"], 0)

    def test_requests_outside_the_fixed_dataset_are_rejected(self):
        self.start(FakeEngine())
        calls = self.engine.calls
        for body in [{"index": COUNT, "manifestSha256": self.manifest_sha}, {"index": -1, "manifestSha256": self.manifest_sha},
                     {"index": True, "manifestSha256": self.manifest_sha}, {"index": "1", "manifestSha256": self.manifest_sha},
                     {"index": 0}, {"index": 0, "manifestSha256": self.manifest_sha, "path": "/etc/passwd"}, []]:
            status, payload = self.classify(body)
            self.assertEqual(status, 400, body)
            self.assertEqual(payload["error"], "INVALID_REQUEST")
        status, payload = self.classify({"index": 0, "manifestSha256": "f" * 64})
        self.assertEqual((status, payload["error"]), (409, "MANIFEST_MISMATCH"))
        status, payload = self.call("POST", "/classify", raw=b"{")
        self.assertEqual(status, 400)
        status, _ = self.call("POST", "/classify", raw=b"x" * 2048)
        self.assertEqual(status, 413)
        self.assertEqual(self.engine.calls, calls)

    def test_concurrent_classification_is_refused_not_queued(self):
        self.start(FakeEngine())
        self.engine.release.clear()
        self.engine.entered.clear()
        first = {}
        thread = threading.Thread(target=lambda: first.update(result=self.classify({"index": 0, "manifestSha256": self.manifest_sha})))
        thread.start()
        self.assertTrue(self.engine.entered.wait(5))
        status, payload = self.classify({"index": 1, "manifestSha256": self.manifest_sha})
        self.assertEqual((status, payload["error"]), (503, "BUSY"))
        self.engine.release.set()
        thread.join(5)
        self.assertEqual(first["result"][0], 200)

    def test_engine_failure_keeps_runtime_not_ready_with_identity_and_reason(self):
        self.start(FakeEngine(fail="no CUDA device"))
        status, payload = self.call("GET", "/health")
        body = json.loads(payload)
        self.assertEqual(status, 503)
        self.assertEqual(body["status"], "NOT_READY")
        self.assertIn("no CUDA device", body["error"])
        self.assertEqual(body["target"], "GPU")
        status, payload = self.classify({"index": 0, "manifestSha256": self.manifest_sha})
        self.assertEqual((status, payload["error"]), (503, "NOT_READY"))

    def test_fixture_that_does_not_match_its_manifest_is_never_served(self):
        self.start(FakeEngine(), corrupt_tensor=True)
        status, payload = self.call("GET", "/health")
        self.assertEqual(status, 503)
        self.assertIn("tensor", json.loads(payload)["error"])
        self.assertEqual(self.engine.calls, 0)

    def test_unexpected_manifest_digest_is_not_ready(self):
        self.start(FakeEngine(), expected="e" * 64)
        status, payload = self.call("GET", "/health")
        self.assertEqual(status, 503)
        self.assertIn("manifest", json.loads(payload)["error"])
        status, _ = self.call("GET", "/dataset/manifest")
        self.assertEqual(status, 503)

    def test_non_finite_or_wrong_shape_scores_are_a_runtime_error(self):
        self.start(FakeEngine())
        for scores in [np.full(1000, np.nan, dtype=np.float32), np.zeros(1001, dtype=np.float32)]:
            self.engine.scores = scores
            status, payload = self.classify({"index": 0, "manifestSha256": self.manifest_sha})
            self.assertEqual((status, payload["error"]), (500, "INVALID_OUTPUT"))

    def test_warm_up_output_is_validated_before_ready(self):
        self.start(FakeEngine(scores=np.full(1000, np.inf, dtype=np.float32)))
        status, payload = self.call("GET", "/health")
        self.assertEqual((status, json.loads(payload)["status"]), (503, "NOT_READY"))

    def test_fixture_loader_rejects_wrong_tensor_layout(self):
        root = Path(tempfile.mkdtemp())
        manifest_sha, _ = write_fixture(root)
        np.save(root / "inputs.npy", np.zeros((COUNT, 3, 224, 224), dtype=np.float32), allow_pickle=False)
        with self.assertRaises(ValueError):
            Fixture.load(root, manifest_sha)


if __name__ == "__main__":
    unittest.main()
