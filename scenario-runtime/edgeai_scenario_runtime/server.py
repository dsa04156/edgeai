"""HTTP front of one GPU or NPU image classification runtime over the fixed comparison dataset.

GET  /health            -> 200 READY only after the fixture SHA-256s, the device and a warm-up passed; else 503.
GET  /dataset/manifest  -> exact manifest bytes (the API verifies their SHA-256).
POST /classify {"index": n, "manifestSha256": "..."} -> top-5 classes for dataset row n.
The dataset and model are fixed server-side; a request can only choose a row. One call runs at a time;
a concurrent request gets 503 BUSY instead of queueing.
"""

import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np

from .fixture import Fixture, sha256

MAX_BODY_BYTES = 1024
TOP_K = 5
WARM_UP_CALLS = 4


class RequestError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code


def scores_problem(scores):
    if not isinstance(scores, np.ndarray) or scores.shape != (1000,):
        return f"expected 1000 class scores, got {getattr(scores, 'shape', type(scores).__name__)}"
    if not np.isfinite(scores).all():
        return "class scores are not finite"
    return None


class Runtime:
    """Holds the verified fixture, the engine, readiness and the single inference slot."""

    def __init__(self, engine, fixture_dir, manifest_sha256, node_name):
        self.engine = engine
        self.fixture_dir = fixture_dir
        self.expected_manifest_sha256 = manifest_sha256
        self.node_name = node_name
        self.fixture = None
        self.ready = False
        self.error = None
        self._slot = threading.Semaphore(1)

    def start(self):
        try:
            # The fixture is verified before the device is touched.
            self.fixture = Fixture.load(self.fixture_dir, self.expected_manifest_sha256)
            self.engine.load()
            for tensor in self.fixture.inputs[:WARM_UP_CALLS]:
                problem = scores_problem(self.engine.infer(tensor))
                if problem:
                    raise RuntimeError(f"warm-up output invalid: {problem}")
            self.ready = True
        except Exception as exc:  # health must report why the runtime is unusable
            self.error = f"{type(exc).__name__}: {exc}"

    def identity(self):
        return {
            "target": self.engine.target,
            "nodeName": self.node_name,
            "deviceName": self.engine.device_name,
            "provider": self.engine.provider,
            "precision": self.engine.precision,
            "runtime": self.engine.runtime,
            "modelSha256": self.engine.model_sha256,
        }

    def health(self):
        body = {"status": "READY" if self.ready else "NOT_READY", **self.identity()}
        if self.fixture is not None:
            body.update(manifestSha256=self.fixture.manifest_sha256, inputsSha256=self.fixture.inputs_sha256,
                        sampleCount=len(self.fixture.inputs))
        if self.error:
            body["error"] = self.error
        return (200 if self.ready else 503), body

    def manifest(self):
        if not self.ready:
            raise RequestError(503, "NOT_READY", "runtime is not ready")
        return self.fixture.manifest_bytes

    def classify(self, payload):
        if not isinstance(payload, dict) or set(payload) != {"index", "manifestSha256"} \
                or type(payload["index"]) is not int or not isinstance(payload["manifestSha256"], str):
            raise RequestError(400, "INVALID_REQUEST", "exactly {index, manifestSha256} is required")
        if not self.ready:
            raise RequestError(503, "NOT_READY", "runtime is not ready")
        index = payload["index"]
        if not 0 <= index < len(self.fixture.inputs):
            raise RequestError(400, "INVALID_REQUEST", f"index must be 0..{len(self.fixture.inputs) - 1}")
        if payload["manifestSha256"] != self.fixture.manifest_sha256:
            raise RequestError(409, "MANIFEST_MISMATCH", "the request names another dataset manifest")
        tensor = self.fixture.inputs[index]
        # Digest of the exact bytes handed to the engine, so the caller can compare both targets' inputs.
        input_sha256 = sha256(tensor.tobytes())
        # The slot is held until the device call returns, even if the client has gone away.
        if not self._slot.acquire(blocking=False):
            raise RequestError(503, "BUSY", "a classification is already running")
        try:
            started = time.perf_counter()
            scores = self.engine.infer(tensor)
            inference_ms = (time.perf_counter() - started) * 1000
        finally:
            self._slot.release()
        problem = scores_problem(scores)
        if problem:
            raise RequestError(500, "INVALID_OUTPUT", problem)
        # Stable sort keeps the lower class index first on ties.
        order = np.argsort(-scores, kind="stable")[:TOP_K]
        identity = self.identity()
        return {
            "index": index,
            "inputSha256": input_sha256,
            "manifestSha256": self.fixture.manifest_sha256,
            "modelSha256": identity["modelSha256"],
            "nodeName": identity["nodeName"],
            "deviceName": identity["deviceName"],
            "provider": identity["provider"],
            "topK": [{"classIndex": int(i), "score": float(scores[i])} for i in order],
            "inferenceMs": round(inference_ms, 4),
        }


def make_handler(runtime):
    class Handler(BaseHTTPRequestHandler):
        # Bounds a stalled client; inference itself is not interrupted by this.
        timeout = 30

        def _send(self, status, body, content_type="application/json"):
            data = body if isinstance(body, bytes) else json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _error(self, status, code, message):
            self._send(status, {"error": code, "message": message})

        def do_GET(self):
            if self.path == "/health":
                return self._send(*runtime.health())
            if self.path == "/dataset/manifest":
                try:
                    return self._send(200, runtime.manifest())
                except RequestError as exc:
                    return self._error(exc.status, exc.code, str(exc))
            self._error(404, "NOT_FOUND", "unknown path")

        def do_POST(self):
            if self.path != "/classify":
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
                self._send(200, runtime.classify(payload))
            except RequestError as exc:
                self._error(exc.status, exc.code, str(exc))

        def log_message(self, fmt, *args):
            pass

    return Handler


def main():
    from .engines import create

    engine = create(os.environ["SCENARIO_TARGET"], os.environ["SCENARIO_MODEL"], os.environ["SCENARIO_MODEL_SHA256"])
    runtime = Runtime(engine, os.environ.get("SCENARIO_FIXTURE_DIR", "/opt/fixture"),
                      os.environ["SCENARIO_MANIFEST_SHA256"], os.environ.get("NODE_NAME", ""))
    runtime.start()
    print(json.dumps({"event": "SCENARIO_RUNTIME_START", **runtime.health()[1]}), flush=True)
    port = int(os.environ.get("PORT", "8080"))
    ThreadingHTTPServer(("0.0.0.0", port), make_handler(runtime)).serve_forever()


if __name__ == "__main__":
    main()
