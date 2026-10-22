"""HTTP front of the GPU object detection runtime.

POST /predict {"imageBase64": "..."} -> detections in original image pixels.
GET  /health -> 200 only after the detector finished a warm-up on its device.
One inference runs at a time; a concurrent request gets 503 instead of queueing.
"""

import base64
import binascii
import io
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from PIL import Image

MAX_IMAGE_BYTES = 4 * 1024 * 1024
# base64 grows data by 4/3; allow the JSON wrapper on top.
MAX_BODY_BYTES = MAX_IMAGE_BYTES * 4 // 3 + 4096
MAX_DIMENSION = 4096
MAX_PIXELS = MAX_DIMENSION * MAX_DIMENSION
ALLOWED_FORMATS = {"JPEG", "PNG"}


class RequestError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code


def decode_image(payload):
    if not isinstance(payload, dict) or not isinstance(payload.get("imageBase64"), str):
        raise RequestError(400, "INVALID_REQUEST", "imageBase64 string is required")
    try:
        raw = base64.b64decode(payload["imageBase64"], validate=True)
    except (binascii.Error, ValueError):
        raise RequestError(400, "INVALID_BASE64", "imageBase64 is not valid base64")
    if not raw:
        raise RequestError(400, "INVALID_IMAGE", "image is empty")
    if len(raw) > MAX_IMAGE_BYTES:
        raise RequestError(413, "IMAGE_TOO_LARGE", f"decoded image exceeds {MAX_IMAGE_BYTES} bytes")
    try:
        image = Image.open(io.BytesIO(raw))
        if image.format not in ALLOWED_FORMATS:
            raise RequestError(415, "UNSUPPORTED_FORMAT", "only JPEG and PNG are accepted")
        width, height = image.size
        if width > MAX_DIMENSION or height > MAX_DIMENSION or width * height > MAX_PIXELS:
            raise RequestError(
                413, "IMAGE_TOO_LARGE_DIMENSIONS", f"image must be at most {MAX_DIMENSION}x{MAX_DIMENSION}"
            )
        # Orientation is the encoded pixel grid; EXIF rotation is deliberately not applied.
        return image.convert("RGB")
    except RequestError:
        raise
    except (OSError, Image.DecompressionBombError, ValueError):
        raise RequestError(400, "INVALID_IMAGE", "image could not be decoded")


class Runtime:
    """Holds the detector, its readiness and the single inference slot."""

    def __init__(self, detector, node_name):
        self.detector = detector
        self.node_name = node_name
        self.ready = False
        self.error = None
        self._slot = threading.Semaphore(1)

    def warm_up(self):
        try:
            self.detector.warm_up()
            self.ready = True
        except Exception as exc:  # health must report why the device is unusable
            self.error = f"{type(exc).__name__}: {exc}"

    def health(self):
        body = {
            "status": "READY" if self.ready else "NOT_READY",
            "modelId": self.detector.model_id,
            "nodeName": self.node_name,
            "deviceName": self.detector.device_name,
            "provider": self.detector.provider,
        }
        if self.error:
            body["error"] = self.error
        return (200 if self.ready else 503), body

    def predict(self, payload):
        if not self.ready:
            raise RequestError(503, "NOT_READY", "detector is not ready")
        image = decode_image(payload)
        # The slot is held until the GPU call returns, even if the client has gone away.
        if not self._slot.acquire(blocking=False):
            raise RequestError(503, "BUSY", "an inference is already running")
        try:
            started = time.perf_counter()
            detections = self.detector.detect(image)
            inference_ms = (time.perf_counter() - started) * 1000
        finally:
            self._slot.release()
        return {
            "modelId": self.detector.model_id,
            "nodeName": self.node_name,
            "deviceName": self.detector.device_name,
            "provider": self.detector.provider,
            "imageWidth": image.width,
            "imageHeight": image.height,
            "inferenceMs": round(inference_ms, 3),
            "detections": detections,
        }


def make_handler(runtime):
    class Handler(BaseHTTPRequestHandler):
        # Bounds a stalled client; inference itself is not interrupted by this.
        timeout = 30

        def _send(self, status, body):
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _error(self, status, code, message):
            self._send(status, {"error": code, "message": message})

        def do_GET(self):
            if self.path != "/health":
                return self._error(404, "NOT_FOUND", "unknown path")
            self._send(*runtime.health())

        def do_POST(self):
            if self.path != "/predict":
                return self._error(404, "NOT_FOUND", "unknown path")
            try:
                length = int(self.headers.get("Content-Length", ""))
            except ValueError:
                return self._error(411, "LENGTH_REQUIRED", "Content-Length is required")
            if length < 0 or length > MAX_BODY_BYTES:
                self.close_connection = True
                return self._error(413, "BODY_TOO_LARGE", f"body exceeds {MAX_BODY_BYTES} bytes")
            try:
                payload = json.loads(self.rfile.read(length))
            except (ValueError, UnicodeDecodeError):
                return self._error(400, "INVALID_JSON", "body is not JSON")
            try:
                self._send(200, runtime.predict(payload))
            except RequestError as exc:
                self._error(exc.status, exc.code, str(exc))

        def log_message(self, fmt, *args):
            pass

    return Handler


def main():
    from .detector import TorchvisionDetector

    runtime = Runtime(TorchvisionDetector(), os.environ.get("NODE_NAME", ""))
    runtime.warm_up()
    print(json.dumps({"event": "INFERENCE_START", **runtime.health()[1]}), flush=True)
    port = int(os.environ.get("PORT", "8080"))
    ThreadingHTTPServer(("0.0.0.0", port), make_handler(runtime)).serve_forever()


if __name__ == "__main__":
    main()
