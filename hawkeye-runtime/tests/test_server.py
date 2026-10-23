"""Adapter HTTP contract for camera frames against a fake Ollama (no model, no GPU)."""

import base64
import http.client
import json
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from edgeai_hawkeye_runtime import server  # noqa: E402

FRAME = b"\xff\xd8" + b"\x10" * 64 + b"\xff\xd9"


class FakeOllama:
    def __init__(self):
        self.images, self.gate = [], None

    def call(self, path, body=None, timeout=None):
        if path == "/api/chat":
            if self.gate:
                self.gate.wait(5)
            self.images.append(base64.b64decode(body["messages"][0]["images"][0]))
            return {"message": {"content": "**obstacle**: pallet in aisle"}, "done_reason": "stop", "total_duration": 7e8,
                    "load_duration": 1e8, "prompt_eval_count": 300, "eval_count": 12}
        if path == "/api/ps":
            return {"models": [{"name": "m", "size": 10, "size_vram": 10, "digest": "d"}]}
        raise AssertionError(path)


class ClassifyFrameTest(unittest.TestCase):
    def setUp(self):
        self.ollama = FakeOllama()
        self.runtime = server.Runtime(self.ollama, "m", "d", "/nonexistent", "s", "etri-ser0001", "hawkeye-pod", "pod-uid")
        self.runtime.ready, self.runtime.version = True, "0.32.5"
        self.http = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(self.runtime))
        threading.Thread(target=self.http.serve_forever, daemon=True).start()

    def tearDown(self):
        self.http.shutdown()
        self.http.server_close()

    def post(self, body, content_type="image/jpeg", path="/classify-frame"):
        connection = http.client.HTTPConnection("127.0.0.1", self.http.server_address[1], timeout=5)
        connection.request("POST", path, body=body, headers={"Content-Type": content_type})
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_frame_is_answered_with_pod_identity_and_placement(self):
        status, body = self.post(FRAME)
        self.assertEqual(status, 200)
        self.assertEqual(self.ollama.images, [FRAME])
        self.assertEqual(body["pod"], {"name": "hawkeye-pod", "uid": "pod-uid"})
        self.assertEqual(body["nodeName"], "etri-ser0001")
        self.assertEqual(body["inputSha256"], server.sha256(FRAME))
        self.assertEqual(body["placement"], {"processor": "GPU", "sizeBytes": 10, "sizeVramBytes": 10})
        self.assertEqual(body["loadedDigest"], "d")
        self.assertEqual(body["content"], "**obstacle**: pallet in aisle")
        self.assertNotIn("sampleId", body)

    def test_invalid_frames_are_rejected_before_the_model(self):
        self.assertEqual(self.post(b"not a jpeg")[1]["error"], "INVALID_FRAME")
        self.assertEqual(self.post(FRAME, content_type="application/json")[1]["error"], "UNSUPPORTED_MEDIA_TYPE")
        self.assertEqual(self.post(b"\xff\xd8" + b"\0" * server.MAX_FRAME_BYTES + b"\xff\xd9")[0], 413)
        self.runtime.ready = False
        self.assertEqual(self.post(FRAME)[1]["error"], "NOT_READY")
        self.assertEqual(self.ollama.images, [])

    def test_a_concurrent_frame_gets_busy(self):
        self.ollama.gate = threading.Event()
        first = threading.Thread(target=self.post, args=(FRAME,))
        first.start()
        while not self.runtime._slot._value == 0:
            pass
        status, body = self.post(FRAME)
        self.ollama.gate.set()
        first.join()
        self.assertEqual((status, body["error"]), (503, "BUSY"))

    def test_sample_requests_keep_their_contract(self):
        self.runtime.samples, self.runtime.expected_samples_sha256 = {"forklift_0004": FRAME}, "s"
        status, body = self.post(json.dumps({"sampleId": "forklift_0004", "samplesSha256": "s"}).encode(), "application/json", "/classify")
        self.assertEqual((status, body["sampleId"], body["inputSha256"]), (200, "forklift_0004", server.sha256(FRAME)))
        self.assertNotIn("pod", body)


if __name__ == "__main__":
    unittest.main()
