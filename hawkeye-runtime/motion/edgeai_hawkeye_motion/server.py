"""HTTP service for continuous forklift/person observation of one pinned camera (Hawkeye video sessions).

GET    /health                                -> 200 when the pinned model loaded and one warm-up inference ran; else 503
PUT    /observers/{sessionId}                 {"source": <pinned cameraSource without pinnedAt>, "leaseSeconds"} -> 201 started, 200 renewed
DELETE /observers/{sessionId}                 -> stops ingestion; the observer stays readable
GET    /observers/{sessionId}/live            -> state, identity, counters and the latest observation
GET    /observers/{sessionId}/frames/{seq}?epoch=E -> image/jpeg of a retained frame of observer instance E (409 otherwise)
POST   /observers/{sessionId}/windows/{k}     {"minSeconds"} -> 202 while the window is still filling, 200 its closed range

Only edgeai-api (start/renew/stop/live) and the snapshot Task Pods may call it (NetworkPolicy). The camera source is
the one the API pinned: a Mapper address of the form http://<IPv4>:7783 plus its configuration digest and source
generation. At most one observer runs; another session gets 409 OBSERVER_BUSY. A session's observer is never
restarted: once it ended, PUT answers 409 with its state so the caller sees the discontinuity.
"""

import io
import json
import os
import re
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from PIL import Image

from .observer import Observer, WindowError, valid_source

MAX_BODY_BYTES = 4096
PATH = re.compile(r"/observers/([0-9a-f-]{36})(?:/(live|frames|windows)(?:/([0-9]{1,9}))?)?(?:\?epoch=([0-9a-zA-Z-]{1,64}))?")


class RequestError(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status = status
        self.code = code


class Service:
    def __init__(self, detector, node_name="", pod_name="", pod_uid="", make_observer=None):
        self.detector = detector
        self.identity = {"nodeName": node_name, "pod": pod_name, "podUid": pod_uid,
                         "provider": detector.providers[0] if detector else None, "model": detector.identity() if detector else None}
        self.make_observer = make_observer or (lambda session, source, lease: Observer(session, source, lease, detector))
        self.ready, self.error = False, None
        self.current = None
        self._lock = threading.Lock()

    def warm_up(self):
        try:
            buffer = io.BytesIO()
            Image.new("RGB", (640, 360)).save(buffer, "JPEG")
            self.detector.detect(buffer.getvalue())
            self.ready = True
        except Exception as error:
            self.error = type(error).__name__

    def health(self):
        current = self.current
        return {"ready": self.ready, "error": self.error, **self.identity,
                "observer": {"sessionId": current.session_id, "state": current.state} if current else None}

    def observer(self, session_id):
        current = self.current
        if current is None or current.session_id != session_id:
            raise RequestError(404, "OBSERVER_NOT_FOUND")
        return current

    def put(self, session_id, body):
        if not isinstance(body, dict) or set(body) != {"source", "leaseSeconds"} or not valid_source(body["source"]) \
                or type(body["leaseSeconds"]) is not int or not 5 <= body["leaseSeconds"] <= 120:
            raise RequestError(400, "INVALID_OBSERVER_REQUEST")
        if not self.ready:
            raise RequestError(503, "NOT_READY")
        with self._lock:
            current = self.current
            if current is not None and current.session_id == session_id:
                if current.source != body["source"]:
                    raise RequestError(409, "SOURCE_CHANGED")
                if current.state != "RUNNING":
                    raise RequestError(409, "OBSERVER_" + current.state)
                current.renew(body["leaseSeconds"])
                return 200, current
            if current is not None and current.state == "RUNNING":
                raise RequestError(409, "OBSERVER_BUSY")
            self.current = self.make_observer(session_id, body["source"], body["leaseSeconds"]).start()
            return 201, self.current

    def describe(self, observer):
        return observer.describe(self.identity)


def make_handler(service):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def send(self, status, body, content_type="application/json"):
            data = body if isinstance(body, bytes) else json.dumps(body, separators=(",", ":")).encode()
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def body(self):
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY_BYTES or self.headers.get("Content-Type") != "application/json":
                raise RequestError(400, "INVALID_OBSERVER_REQUEST")
            try:
                return json.loads(self.rfile.read(length))
            except ValueError:
                raise RequestError(400, "INVALID_OBSERVER_REQUEST") from None

        def route(self, method):
            if self.path == "/health" and method == "GET":
                health = service.health()
                return self.send(200 if health["ready"] else 503, health)
            match = PATH.fullmatch(self.path)
            if not match:
                raise RequestError(404, "NOT_FOUND")
            try:
                session_id = str(uuid.UUID(match.group(1)))
            except ValueError:
                raise RequestError(404, "NOT_FOUND") from None
            kind, number, epoch = match.group(2), match.group(3), match.group(4)
            if epoch is not None and kind != "frames":
                raise RequestError(404, "NOT_FOUND")
            if kind is None and method == "PUT":
                status, observer = service.put(session_id, self.body())
                return self.send(status, service.describe(observer))
            observer = service.observer(session_id)
            if kind is None and method == "DELETE":
                observer.stop()
                return self.send(200, service.describe(observer))
            if kind == "live" and number is None and method == "GET":
                return self.send(200, {**service.describe(observer), "latest": observer.latest()})
            if kind == "frames" and number is not None and method == "GET":
                # Sequence numbers restart with each observer instance, so the caller names the instance it expects.
                if epoch is None:
                    raise RequestError(400, "EPOCH_REQUIRED")
                if epoch != observer.epoch:
                    raise RequestError(409, "EPOCH_MISMATCH")
                image = observer.frame(int(number))
                if image is None:
                    raise RequestError(404, "FRAME_NOT_FOUND")
                return self.send(200, image, "image/jpeg")
            if kind == "windows" and number is not None and method == "POST":
                body = self.body()
                if not isinstance(body, dict) or set(body) != {"minSeconds"} or type(body["minSeconds"]) is not int \
                        or not 1 <= body["minSeconds"] <= 120 or int(number) < 1:
                    raise RequestError(400, "INVALID_WINDOW_REQUEST")
                try:
                    document, covered = observer.window(int(number), body["minSeconds"])
                except WindowError as error:
                    raise RequestError(error.status, error.code) from None
                if document is None:
                    return self.send(202, {"state": "FILLING", "coveredSeconds": round(covered, 3)})
                return self.send(200, {**service.describe(observer), **document})
            raise RequestError(405 if kind is not None or method != "GET" else 404, "NOT_FOUND")

        def handle_method(self, method):
            try:
                self.route(method)
            except RequestError as error:
                self.send(error.status, {"error": error.code})

        def do_GET(self):
            self.handle_method("GET")

        def do_PUT(self):
            self.handle_method("PUT")

        def do_POST(self):
            self.handle_method("POST")

        def do_DELETE(self):
            self.handle_method("DELETE")

    return Handler


def make_detector():
    from forklift_detector import WarehouseDetector  # TensorRT engine of the base image (hawkeye-runtime/forklift_detector.py)

    from .detector import THRESHOLD, Detector

    engine = WarehouseDetector(os.environ["EDGEAI_FORKLIFT_MODEL"], threads=int(os.environ.get("HAWKEYE_MOTION_THREADS", "4")),
                               threshold=THRESHOLD)
    return Detector(engine)


def main():
    service = Service(make_detector(), os.environ.get("NODE_NAME", ""), os.environ.get("POD_NAME", ""), os.environ.get("POD_UID", ""))
    threading.Thread(target=service.warm_up, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))), make_handler(service)).serve_forever()


if __name__ == "__main__":
    main()
