"""HTTP adapter between edgeai-api and the Ollama process serving the Hawkeye warehouse-safety VLM in the same Pod.

GET  /health                 -> 200 READY only after the pinned samples, the Ollama model digest and one warm-up passed; else 503.
GET  /samples/{id}           -> exact JPEG bytes of one pinned sample.
POST /classify {"sampleId", "samplesSha256"} -> raw model output, Ollama timings and where Ollama placed the model.
The prompt, model and samples are fixed server-side; a request can only choose a sample. The output is returned
verbatim: the API parses the category and never treats unreadable output as safe. One call runs at a time;
a concurrent request gets 503 BUSY instead of queueing.
"""

import base64
import hashlib
import json
import os
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY_BYTES = 1024
MAX_OUTPUT_CHARS = 4000
# Model card quick start text. Its "/no_think " prefix does not disable thinking through the Ollama chat API
# (feasibility 2026-10-23: empty content on 3 of 5 images), so the request sets "think": false instead.
PROMPT = ("Classify this warehouse image into exactly one of: spill, forklift_violation, improper_stacking, obstacle, safe. "
          "Respond with the category and a one-line description.")
OPTIONS = {"temperature": 0, "num_predict": 256}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


class RequestError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code


class Ollama:
    def __init__(self, url, timeout):
        self.url = url.rstrip("/")
        self.timeout = timeout

    def call(self, path, body=None, timeout=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.url + path, data, {"Content-Type": "application/json"} if data else {})
        with urllib.request.urlopen(request, timeout=timeout or self.timeout) as response:
            return json.load(response)


class Runtime:
    """Holds the verified samples, readiness and the single inference slot."""

    def __init__(self, ollama, model, model_digest, samples_dir, samples_sha256, node_name):
        self.ollama = ollama
        self.model = model
        self.model_digest = model_digest
        self.samples_dir = samples_dir
        self.expected_samples_sha256 = samples_sha256
        self.node_name = node_name
        self.samples = {}
        self.version = None
        self.placement = None
        self.ready = False
        self.error = None
        self._slot = threading.Semaphore(1)

    def start(self, wait_seconds=180):
        try:
            # The samples are verified before the model is touched.
            index = open(os.path.join(self.samples_dir, "samples.json"), "rb").read()
            if sha256(index) != self.expected_samples_sha256:
                raise RuntimeError("samples.json SHA-256 differs from the configured value")
            for sample in json.loads(index)["samples"]:
                data = open(os.path.join(self.samples_dir, os.path.basename(sample["file"])), "rb").read()
                if sha256(data) != sample["sha256"]:
                    raise RuntimeError(f"sample {sample['id']} SHA-256 differs from samples.json")
                self.samples[sample["id"]] = data
            # Ollama starts beside this container; wait for it instead of failing the Pod.
            deadline = time.monotonic() + wait_seconds
            while True:
                try:
                    self.version = self.ollama.call("/api/version", timeout=2)["version"]
                    break
                except (OSError, urllib.error.URLError):
                    if time.monotonic() > deadline:
                        raise
                    time.sleep(1)
            tags = self.ollama.call("/api/tags")["models"]
            digest = next((m["digest"] for m in tags if m["name"] == self.model), None)
            if digest != self.model_digest:
                raise RuntimeError(f"Ollama model {self.model} digest {digest} differs from the configured manifest")
            # Warm-up loads the model, so the first user run measures a loaded model; its output only has to exist.
            warm = self.chat(next(iter(self.samples.values())))
            if not warm["message"].get("content", "").strip():
                raise RuntimeError("warm-up produced no output")
            self.placement = self.loaded()
            self.ready = True
        except Exception as exc:  # health must report why the runtime is unusable
            self.error = f"{type(exc).__name__}: {exc}"

    def chat(self, image):
        return self.ollama.call("/api/chat", {
            "model": self.model, "stream": False, "think": False, "keep_alive": -1, "options": OPTIONS,
            "messages": [{"role": "user", "content": PROMPT, "images": [base64.b64encode(image).decode()]}],
        })

    def loaded(self):
        """Where Ollama holds the model now: GPU when all of it is in VRAM, CPU when none is, else a split."""
        entry = next((m for m in self.ollama.call("/api/ps")["models"] if m["name"] == self.model), None)
        if entry is None:
            return None
        size, vram = entry["size"], entry["size_vram"]
        processor = "GPU" if vram == size else "CPU" if vram == 0 else f"CPU/GPU {round(100 * (size - vram) / size)}%/{round(100 * vram / size)}%"
        return {"processor": processor, "sizeBytes": size, "sizeVramBytes": vram, "digest": entry["digest"]}

    def identity(self):
        return {"nodeName": self.node_name, "model": self.model, "modelDigest": self.model_digest,
                "ollamaVersion": self.version, "samplesSha256": self.expected_samples_sha256,
                "promptSha256": sha256(PROMPT.encode())}

    def health(self):
        body = {"status": "READY" if self.ready else "NOT_READY", **self.identity()}
        if self.ready:
            # Live placement: another process on the same GPU can push the model to the CPU.
            try:
                body["placement"] = self.loaded()
            except (OSError, urllib.error.URLError, ValueError, KeyError):
                body["placement"] = None
        if self.error:
            body["error"] = self.error
        return (200 if self.ready else 503), body

    def sample(self, sample_id):
        if sample_id not in self.samples:
            raise RequestError(404, "NOT_FOUND", "unknown sample")
        return self.samples[sample_id]

    def classify(self, payload):
        if not isinstance(payload, dict) or set(payload) != {"sampleId", "samplesSha256"} \
                or not isinstance(payload["sampleId"], str) or not isinstance(payload["samplesSha256"], str):
            raise RequestError(400, "INVALID_REQUEST", "exactly {sampleId, samplesSha256} is required")
        if not self.ready:
            raise RequestError(503, "NOT_READY", "runtime is not ready")
        if payload["samplesSha256"] != self.expected_samples_sha256:
            raise RequestError(409, "SAMPLES_MISMATCH", "the request names another sample set")
        if payload["sampleId"] not in self.samples:
            raise RequestError(400, "INVALID_REQUEST", "unknown sampleId")
        image = self.samples[payload["sampleId"]]
        # The slot is held until Ollama answers, even if the client has gone away.
        if not self._slot.acquire(blocking=False):
            raise RequestError(503, "BUSY", "a classification is already running")
        try:
            started = time.perf_counter()
            try:
                answer = self.chat(image)
                placement = self.loaded()
            except (OSError, urllib.error.URLError, ValueError, KeyError) as exc:
                raise RequestError(502, "OLLAMA_FAILED", type(exc).__name__)
            adapter_ms = (time.perf_counter() - started) * 1000
        finally:
            self._slot.release()
        content = answer.get("message", {}).get("content")
        if not isinstance(content, str) or placement is None:
            raise RequestError(502, "OLLAMA_FAILED", "no message content or model not loaded")
        return {
            "sampleId": payload["sampleId"],
            "inputSha256": sha256(image),
            **self.identity(),
            "loadedDigest": placement.pop("digest"),
            "placement": placement,
            "content": content[:MAX_OUTPUT_CHARS],
            "doneReason": answer.get("done_reason"),
            # Ollama's own nanosecond timings of this request; the adapter adds its HTTP time to Ollama.
            "totalMs": answer["total_duration"] / 1e6,
            "loadMs": answer.get("load_duration", 0) / 1e6,
            "promptEvalCount": answer.get("prompt_eval_count"),
            "evalCount": answer.get("eval_count"),
            "adapterMs": round(adapter_ms, 3),
        }


def make_handler(runtime):
    class Handler(BaseHTTPRequestHandler):
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
            if self.path.startswith("/samples/"):
                try:
                    return self._send(200, runtime.sample(self.path[len("/samples/"):]), "image/jpeg")
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
    runtime = Runtime(Ollama(os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434"), float(os.environ.get("OLLAMA_TIMEOUT", "120"))),
                      os.environ["HAWKEYE_MODEL"], os.environ["HAWKEYE_MODEL_DIGEST"],
                      os.environ.get("HAWKEYE_SAMPLES_DIR", "/opt/hawkeye/samples"), os.environ["HAWKEYE_SAMPLES_SHA256"],
                      os.environ.get("NODE_NAME", ""))
    runtime.start()
    print(json.dumps({"event": "HAWKEYE_RUNTIME_START", **runtime.health()[1]}), flush=True)
    ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))), make_handler(runtime)).serve_forever()


if __name__ == "__main__":
    main()
