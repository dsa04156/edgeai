import base64
import http.client
import io
import json
import os
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer

from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from edgeai_inference import server  # noqa: E402


class FakeDetector:
    model_id = "fake/model"
    provider = "fake-cuda"

    def __init__(self, fail=None):
        self.device_name = ""
        self.fail = fail
        self.release = threading.Event()
        self.release.set()
        self.entered = threading.Event()

    def warm_up(self):
        if self.fail:
            raise RuntimeError(self.fail)
        self.device_name = "Fake GPU"

    def detect(self, image):
        self.entered.set()
        self.release.wait(5)
        return [{"label": "person", "score": 0.9, "x": 1.0, "y": 2.0, "width": 3.0, "height": 4.0}]


def encode(fmt="PNG", size=(64, 48)):
    buf = io.BytesIO()
    Image.new("RGB", size, (10, 20, 30)).save(buf, fmt)
    return base64.b64encode(buf.getvalue()).decode()


class ServerTest(unittest.TestCase):
    def start(self, detector, warm=True):
        self.runtime = server.Runtime(detector, "node-a")
        if warm:
            self.runtime.warm_up()
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(self.runtime))
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.addCleanup(self.httpd.server_close)
        self.addCleanup(self.httpd.shutdown)

    def call(self, method, path, body=None, raw=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_address[1], timeout=10)
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        conn.request(method, path, body=data, headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        result = resp.status, json.loads(resp.read())
        conn.close()
        return result

    def test_health_requires_successful_warm_up(self):
        self.start(FakeDetector(), warm=False)
        self.assertEqual(self.call("GET", "/health")[0], 503)
        self.assertEqual(self.call("POST", "/predict", {"imageBase64": encode()})[1]["error"], "NOT_READY")
        self.runtime.warm_up()
        status, body = self.call("GET", "/health")
        self.assertEqual((status, body["status"], body["deviceName"]), (200, "READY", "Fake GPU"))

    def test_failed_device_stays_unready_with_reason(self):
        self.start(FakeDetector(fail="CUDA is not available"))
        status, body = self.call("GET", "/health")
        self.assertEqual(status, 503)
        self.assertIn("CUDA is not available", body["error"])

    def test_predict_returns_contract_fields(self):
        self.start(FakeDetector())
        status, body = self.call("POST", "/predict", {"imageBase64": encode("JPEG", (64, 48))})
        self.assertEqual(status, 200)
        self.assertEqual(
            {k: body[k] for k in ("modelId", "nodeName", "deviceName", "provider", "imageWidth", "imageHeight")},
            {"modelId": "fake/model", "nodeName": "node-a", "deviceName": "Fake GPU",
             "provider": "fake-cuda", "imageWidth": 64, "imageHeight": 48},
        )
        self.assertIsInstance(body["inferenceMs"], float)
        self.assertEqual(body["detections"][0]["label"], "person")

    def test_rejects_invalid_inputs(self):
        self.start(FakeDetector())
        gif = io.BytesIO()
        Image.new("RGB", (4, 4)).save(gif, "GIF")
        cases = [
            ({}, 400, "INVALID_REQUEST"),
            ({"imageBase64": "not base64!"}, 400, "INVALID_BASE64"),
            ({"imageBase64": base64.b64encode(b"hello").decode()}, 400, "INVALID_IMAGE"),
            ({"imageBase64": base64.b64encode(gif.getvalue()).decode()}, 415, "UNSUPPORTED_FORMAT"),
            ({"imageBase64": base64.b64encode(b"\0" * (server.MAX_IMAGE_BYTES + 1)).decode()}, 413, "IMAGE_TOO_LARGE"),
            ({"imageBase64": encode("PNG", (4097, 8))}, 413, "IMAGE_TOO_LARGE_DIMENSIONS"),
        ]
        for payload, status, code in cases:
            with self.subTest(code=code):
                got_status, body = self.call("POST", "/predict", payload)
                self.assertEqual((got_status, body["error"]), (status, code))
        self.assertEqual(self.call("POST", "/predict", raw=b"{")[1]["error"], "INVALID_JSON")

    def test_rejects_oversized_body_before_reading(self):
        self.start(FakeDetector())
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_address[1], timeout=10)
        conn.putrequest("POST", "/predict")
        conn.putheader("Content-Length", str(server.MAX_BODY_BYTES + 1))
        conn.endheaders()
        resp = conn.getresponse()
        self.assertEqual((resp.status, json.loads(resp.read())["error"]), (413, "BODY_TOO_LARGE"))
        conn.close()

    def test_concurrent_request_gets_busy(self):
        detector = FakeDetector()
        self.start(detector)
        detector.release.clear()
        first = {}
        worker = threading.Thread(
            target=lambda: first.update(r=self.call("POST", "/predict", {"imageBase64": encode()}))
        )
        worker.start()
        self.assertTrue(detector.entered.wait(5))
        status, body = self.call("POST", "/predict", {"imageBase64": encode()})
        detector.release.set()
        worker.join(5)
        self.assertEqual((status, body["error"]), (503, "BUSY"))
        self.assertEqual(first["r"][0], 200)


if __name__ == "__main__":
    unittest.main()
