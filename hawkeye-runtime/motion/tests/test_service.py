"""Observer service HTTP contract with a scripted camera and detector (no model): continuity across windows,
lease expiry, stop, single active observer, source failures and the real Mapper reader against a fake Mapper."""

import datetime
import http.client
import json
import sys
import threading
import time
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from edgeai_hawkeye_motion import server  # noqa: E402
from edgeai_hawkeye_motion.motion import Detection  # noqa: E402
from edgeai_hawkeye_motion.observer import MapperSource, Observer, SourceError  # noqa: E402

T0 = datetime.datetime(2026, 10, 26, 3, 0, 0, tzinfo=datetime.timezone.utc)
SOURCE = {"deviceId": "hawkeye-forklift-video", "deviceUid": str(uuid.uuid4()), "nodeName": "etri-dev0002-raspi5",
          "mapperUrl": "http://10.254.192.123:7783", "mapperDeployment": "edgeai-mapper-rtsp",
          "configurationSha256": "sha256:" + "a" * 64, "sourceGeneration": str(uuid.uuid4())}


def stamp(moment):
    return moment.strftime("%Y-%m-%dT%H:%M:%S.%f") + "Z"


class Camera:
    """A new frame every read, 0.5 s of video apart; frame n shows a forklift driving at a standing person."""

    def __init__(self):
        self.reads, self.failing, self.lock = 0, False, threading.Lock()
        self.seconds = {}  # read number -> decode time override

    def read(self):
        with self.lock:
            if self.failing:
                raise SourceError("MAPPER_UNREACHABLE")
            self.reads += 1
            n = self.reads
        time.sleep(0.005)
        return b"\xff\xd8" + n.to_bytes(4, "big") + b"\xff\xd9", stamp(T0 + datetime.timedelta(seconds=self.seconds.get(n, 0.5 * n)))


class ScriptedDetector:
    providers = ["CPUExecutionProvider"]

    def identity(self):
        return {"name": "scripted", "sha256": "0" * 64}

    def detect(self, image):
        if len(image) == 8:
            n = int.from_bytes(image[2:6], "big")
            return [Detection("forklift", 0.9, (140 + 25 * n, 380, 260 + 25 * n, 500)),
                    Detection("person", 0.9, (675, 400, 725, 500))], 1280, 720, 12.0
        return [], 640, 360, 5.0


class ServiceTest(unittest.TestCase):
    def setUp(self):
        self.camera = Camera()

        def observer(session, source, lease):
            created = Observer(session, source, lease, ScriptedDetector(), read_frame=self.camera.read,
                               clock=lambda: T0 + datetime.timedelta(seconds=0.5 * self.camera.reads + 0.2))
            created.POLL_SECONDS, created.FAIL_SECONDS = 0.01, 0.3
            return created

        self.service = server.Service(ScriptedDetector(), "etri-ser0004", "motion-pod", "pod-uid", make_observer=observer)
        self.service.warm_up()
        self.http = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(self.service))
        threading.Thread(target=self.http.serve_forever, daemon=True).start()
        self.session = str(uuid.uuid4())

    def tearDown(self):
        if self.service.current:
            self.service.current.stop()
            self.service.current.join(2)
        self.http.shutdown()
        self.http.server_close()

    def call(self, method, path, body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.http.server_address[1], timeout=5)
        headers = {"Content-Type": "application/json"} if body is not None else {}
        connection.request(method, path, body=None if body is None else json.dumps(body), headers=headers)
        response = connection.getresponse()
        data = response.read()
        connection.close()
        return response.status, (json.loads(data) if response.getheader("Content-Type") == "application/json" else data)

    def start(self, session=None, source=SOURCE, lease=30):
        return self.call("PUT", f"/observers/{session or self.session}", {"source": source, "leaseSeconds": lease})

    def until(self, predicate, timeout=5):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                return value
            time.sleep(0.02)
        self.fail("condition not reached")

    def window(self, k, seconds=2):
        return self.until(lambda: (lambda r: r if r[0] == 200 else None)(self.call("POST", f"/observers/{self.session}/windows/{k}", {"minSeconds": seconds})))[1]

    def test_health_reports_identity(self):
        status, body = self.call("GET", "/health")
        self.assertEqual(status, 200)
        self.assertEqual((body["nodeName"], body["pod"], body["provider"]), ("etri-ser0004", "motion-pod", "CPUExecutionProvider"))

    def test_windows_are_contiguous_and_tracks_continue_across_them(self):
        self.assertEqual(self.start()[0], 201)
        first, second = self.window(1), self.window(2)
        self.assertEqual(first["window"]["fromSeq"], 1)
        self.assertEqual(second["window"]["fromSeq"], first["window"]["toSeq"] + 1)
        self.assertEqual(second["window"]["startedAt"], first["window"]["finishedAt"])
        self.assertEqual(first["window"]["evicted"], 0)
        self.assertEqual([f["seq"] for f in second["frames"]], list(range(second["window"]["fromSeq"], second["window"]["toSeq"] + 1)))
        # Same forklift ID on both sides of the window boundary; only the very first frame is a tracker start.
        ids = {t["id"] for f in first["frames"] + second["frames"] for t in f["tracks"] if t["class"] == "forklift"}
        self.assertEqual(len(ids), 1)
        self.assertEqual([f["reset"] for f in first["frames"] + second["frames"]].count("START"), 1)
        self.assertEqual(second["frames"][-1]["status"], "NEAR_PATH")
        # A retried snapshot gets the identical range.
        self.assertEqual(self.window(1)["window"], first["window"])
        self.assertEqual(self.call("POST", f"/observers/{self.session}/windows/4", {"minSeconds": 2})[0], 409)

    def test_live_and_frames_are_readable_only_for_the_live_epoch(self):
        self.start()
        live = self.until(lambda: (lambda b: b if b["latest"] else None)(self.call("GET", f"/observers/{self.session}/live")[1]))
        self.assertEqual(live["state"], "RUNNING")
        self.assertEqual(live["source"], SOURCE)
        seq = live["latest"]["seq"]
        status, image = self.call("GET", f"/observers/{self.session}/frames/{seq}?epoch={live['epoch']}")
        self.assertEqual(status, 200)
        self.assertEqual(image[:2], b"\xff\xd8")
        # A restarted observer reuses sequence numbers; a frame is never served for another instance's epoch.
        self.assertEqual(self.call("GET", f"/observers/{self.session}/frames/{seq}?epoch=other"), (409, {"error": "EPOCH_MISMATCH"}))
        self.assertEqual(self.call("GET", f"/observers/{self.session}/frames/{seq}")[0], 400)
        self.assertEqual(self.call("GET", f"/observers/{self.session}/frames/999999?epoch={live['epoch']}")[0], 404)

    def test_regressed_frame_time_restarts_tracking_and_a_repeated_frame_is_skipped(self):
        # Reads 4 and 5 repeat read 3's time (no newer frame yet); read 6 jumps back 1 s, read 7 continues from there.
        self.camera.seconds = {4: 1.5, 5: 1.5, 6: 0.5, 7: 1.0}
        self.start()
        frames = self.until(lambda: (lambda w: w if w and len(w) >= 5 else None)(
            [self.service.current.frames[s][0] for s in sorted(self.service.current.frames)]))
        self.assertEqual([f["frameTime"][17:22] for f in frames[:5]], ["00.50", "01.00", "01.50", "00.50", "01.00"])
        self.assertEqual([f["reset"] for f in frames[:5]], ["START", None, None, "TIME_REGRESSED", None])

    def test_stop_ends_ingestion_and_keeps_reads(self):
        self.start()
        self.until(lambda: self.camera.reads > 3)
        status, body = self.call("DELETE", f"/observers/{self.session}")
        self.assertEqual((status, body["state"]), (200, "STOPPED"))
        self.service.current.join(2)
        reads = self.camera.reads
        time.sleep(0.1)
        self.assertEqual(self.camera.reads, reads)
        self.assertEqual(self.call("GET", f"/observers/{self.session}/live")[1]["state"], "STOPPED")
        # The same session is not silently restarted.
        self.assertEqual(self.start(), (409, {"error": "OBSERVER_STOPPED"}))

    def test_unrenewed_lease_expires_and_stops_ingestion(self):
        self.start(lease=5)
        self.service.current.lease_until = time.monotonic() + 0.1
        live = self.until(lambda: (lambda b: b if b["state"] != "RUNNING" else None)(self.call("GET", f"/observers/{self.session}/live")[1]))
        self.assertEqual((live["state"], live["failure"]["code"]), ("EXPIRED", "OBSERVER_EXPIRED"))

    def test_one_observer_at_a_time_and_source_is_pinned(self):
        self.assertEqual(self.start()[0], 201)
        self.assertEqual(self.start()[0], 200)
        self.assertEqual(self.start(session=str(uuid.uuid4())), (409, {"error": "OBSERVER_BUSY"}))
        changed = {**SOURCE, "sourceGeneration": str(uuid.uuid4())}
        self.assertEqual(self.start(source=changed), (409, {"error": "SOURCE_CHANGED"}))

    def test_arbitrary_urls_and_malformed_sources_are_rejected(self):
        for source in ({**SOURCE, "mapperUrl": "http://example.com:7783"}, {**SOURCE, "mapperUrl": "http://10.0.0.1:80"},
                       {**SOURCE, "extra": "x"}, {**SOURCE, "configurationSha256": "x"}):
            self.assertEqual(self.start(source=source), (400, {"error": "INVALID_OBSERVER_REQUEST"}))

    def test_failing_source_becomes_visible_failure(self):
        self.camera.failing = True
        self.start()
        live = self.until(lambda: (lambda b: b if b["state"] == "FAILED" else None)(self.call("GET", f"/observers/{self.session}/live")[1]))
        self.assertEqual(live["failure"]["code"], "SOURCE_MAPPER_UNREACHABLE")
        self.assertEqual(self.call("POST", f"/observers/{self.session}/windows/1", {"minSeconds": 2}), (409, {"error": "OBSERVER_FAILED"}))
        # A failed observer no longer blocks the next session.
        self.assertEqual(self.start(session=str(uuid.uuid4()))[0], 201)

    def test_not_ready_service_refuses_observers(self):
        self.service.ready = False
        self.assertEqual(self.start(), (503, {"error": "NOT_READY"}))


class MapperSourceTest(unittest.TestCase):
    def test_reads_the_pinned_preview_with_digest_and_generation(self):
        seen = []

        class Mapper(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                seen.append(self.path)
                body = b"\xff\xd8xx\xff\xd9"
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("X-EdgeAI-Frame-Time", "2026-10-26T03:00:00.5Z")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        mapper = ThreadingHTTPServer(("127.0.0.1", 0), Mapper)
        threading.Thread(target=mapper.serve_forever, daemon=True).start()
        try:
            reader = MapperSource({**SOURCE, "mapperUrl": f"http://127.0.0.1:{mapper.server_address[1]}"})
            body, header = reader.read()
        finally:
            mapper.shutdown()
            mapper.server_close()
        self.assertEqual((body, header), (b"\xff\xd8xx\xff\xd9", "2026-10-26T03:00:00.5Z"))
        self.assertEqual(seen, [f"/devices/hawkeye-forklift-video/preview?configurationSha256=sha256%3A{'a' * 64}&sourceGeneration={SOURCE['sourceGeneration']}"])


if __name__ == "__main__":
    unittest.main()
