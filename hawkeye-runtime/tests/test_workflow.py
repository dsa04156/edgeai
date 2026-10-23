"""Capture/infer workload rules against fake Mapper and adapter HTTP responses (no network, no model)."""

import datetime
import hashlib
import io
import json
import sys
import tarfile
import time
import unittest
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "workflow"))
import hawkeye_capture as capture  # noqa: E402
import hawkeye_infer as infer  # noqa: E402

SOURCE = {"deviceId": "hawkeye-forklift-video", "deviceUid": "2dffb17e-028d-43ed-a992-b854b9d4c8ef", "nodeName": "edge-a",
          "mapperUrl": "http://10.0.0.5:7783", "mapperDeployment": "edgeai-rtsp-0123456789abcdef", "configurationSha256": "sha256:" + "a" * 64,
          "sourceGeneration": "6b0a1c1e-7f4e-4a52-9a63-6f0f0d1d3a11", "pinnedAt": "2026-10-23T08:00:00Z"}
T0 = datetime.datetime(2026, 10, 23, 8, 0, 0, tzinfo=datetime.timezone.utc)


def jpeg(n):
    return b"\xff\xd8" + bytes([n % 256]) * (20 + n) + b"\xff\xd9"


class Response(io.BytesIO):
    def __init__(self, body, status=200, headers=None):
        super().__init__(body)
        self.status, self.headers = status, headers or {}


class Mapper:
    """Serves frames whose decode time advances 500 ms per request, like the Mapper's 2 fps decoder."""
    def __init__(self, frames, times=None, content_type="image/jpeg", error=None):
        self.frames, self.times, self.content_type, self.error, self.urls = list(frames), times, content_type, error, []

    def open(self, request, timeout):
        self.urls.append(request.full_url)
        if self.error:
            raise self.error
        i = len(self.urls) - 1
        at = self.times[i] if self.times else (T0 + datetime.timedelta(milliseconds=500 * i)).strftime("%Y-%m-%dT%H:%M:%S.%f000Z")
        return Response(self.frames[i], headers={"Content-Type": self.content_type, "X-EdgeAI-Frame-Time": at})


def clock(start=T0, step_ms=500, lag_ms=120):
    moments = iter(start + datetime.timedelta(milliseconds=step_ms * i + lag_ms) for i in range(1000))
    return lambda: next(moments)


class CaptureTest(unittest.TestCase):
    def run_capture(self, mapper, **extra):
        parameters = {"cameraSource": SOURCE, "frames": 3, **extra}
        return capture.capture(parameters, opener=mapper, now=clock(T0 - datetime.timedelta(milliseconds=500)), sleep=lambda _: None)

    def test_reads_consecutive_fresh_frames_for_the_pinned_identity(self):
        mapper = Mapper([jpeg(1), jpeg(2), jpeg(3)])
        manifest, files = self.run_capture(mapper)
        self.assertEqual([name for name, _ in files], ["frame-0001.jpg", "frame-0002.jpg", "frame-0003.jpg"])
        self.assertEqual(manifest["source"], SOURCE)
        self.assertEqual(manifest["distinctFrames"], 3)
        self.assertEqual([f["sha256"] for f in manifest["frames"]], [hashlib.sha256(jpeg(i)).hexdigest() for i in (1, 2, 3)])
        self.assertIn("configurationSha256=sha256%3A" + "a" * 64, mapper.urls[0])
        self.assertIn("sourceGeneration=" + SOURCE["sourceGeneration"], mapper.urls[0])
        self.assertTrue(mapper.urls[0].startswith("http://10.0.0.5:7783/devices/hawkeye-forklift-video/preview?"))

    def test_archive_is_plain_ustar_with_manifest_first(self):
        manifest, files = self.run_capture(Mapper([jpeg(1), jpeg(2), jpeg(3)]))
        data = capture.archive(manifest, files)
        with tarfile.open(fileobj=io.BytesIO(data)) as tar:
            names = tar.getnames()
            self.assertEqual(names[0], "manifest.json")
            self.assertTrue(all(m.isreg() and not m.pax_headers for m in tar.getmembers()))
        self.assertEqual(infer.frames(data)[0], json.loads(json.dumps(manifest)))

    def test_source_failures_never_produce_frames(self):
        cases = {
            "unavailable": Mapper([], error=urllib.error.HTTPError("u", 503, "x", {}, io.BytesIO(b""))),
            "unreachable": Mapper([], error=urllib.error.URLError("refused")),
            "not jpeg": Mapper([b"<html>"] * 3),
            "wrong type": Mapper([jpeg(1)] * 3, content_type="text/plain"),
            "decode time going back": Mapper([jpeg(1), jpeg(2)], times=["2026-10-23T08:00:00.5Z", "2026-10-23T08:00:00.4Z"]),
            "missing time": Mapper([jpeg(1), jpeg(2), jpeg(3)], times=[""] * 3),
        }
        for name, mapper in cases.items():
            with self.subTest(name), self.assertRaises(capture.CaptureError):
                self.run_capture(mapper)

    def test_a_frame_the_mapper_serves_again_is_polled_until_a_newer_decode(self):
        same = "2026-10-23T08:00:00.000000000Z"
        mapper = Mapper([jpeg(1), jpeg(1), jpeg(1), jpeg(2)], times=[same, same, same, "2026-10-23T08:00:00.5Z"])
        manifest, files = self.run_capture(mapper, frames=2)
        self.assertEqual(len(mapper.urls), 4)
        self.assertEqual([body for _, body in files], [jpeg(1), jpeg(2)])
        self.assertEqual([f["frameTime"] for f in manifest["frames"]], [same, "2026-10-23T08:00:00.5Z"])

    def test_a_source_that_stops_decoding_fails_within_the_frame_limit(self):
        same = "2026-10-23T08:00:00Z"
        mapper = Mapper([jpeg(1)] * 1000, times=[same] * 1000)
        with self.assertRaises(capture.CaptureError) as error:
            capture.capture({"cameraSource": SOURCE, "frames": 2, "frameTimeoutSeconds": 1}, opener=mapper,
                            now=lambda: T0, sleep=lambda _: time.sleep(0.05))
        self.assertEqual(str(error.exception), "FRAME_NOT_NEWER")

    def test_old_frame_is_stale(self):
        old = (T0 - datetime.timedelta(seconds=30)).strftime("%Y-%m-%dT%H:%M:%SZ")
        with self.assertRaises(capture.CaptureError) as error:
            self.run_capture(Mapper([jpeg(1)], times=[old]), frames=1)
        self.assertEqual(str(error.exception), "FRAME_STALE")

    def test_binding_must_be_the_server_pinned_shape(self):
        for broken in ({}, {**SOURCE, "mapperUrl": "http://evil.example:7783"}, {**SOURCE, "extra": "x"},
                       {**SOURCE, "configurationSha256": "abc"}):
            with self.subTest(broken), self.assertRaises(capture.CaptureError):
                capture.capture({"cameraSource": broken}, opener=Mapper([jpeg(1)]))


def archive(count=2, source=SOURCE):
    frames = [jpeg(i) for i in range(1, count + 1)]
    manifest = {"apiVersion": "edgeai.hawkeye.frames/v1", "source": source, "requestedFrames": count, "startedAt": "s",
                "finishedAt": "f", "distinctFrames": count,
                "frames": [{"seq": i, "file": f"frame-{i:04d}.jpg", "frameTime": f"t{i}", "receivedAt": f"r{i}",
                            "sha256": hashlib.sha256(frames[i - 1]).hexdigest()} for i in range(1, count + 1)]}
    return capture.archive(manifest, [(f"frame-{i:04d}.jpg", frames[i - 1]) for i in range(1, count + 1)]), frames


IDENTITY = {"nodeName": "etri-ser0001", "pod": {"name": "edgeai-hawkeye-safety-x", "uid": "u-1"}, "model": "m", "modelDigest": "d",
            "loadedDigest": "d", "ollamaVersion": "0.32.5", "promptSha256": "p"}


class Adapter:
    def __init__(self, contents, identities=None, busy=0, error=None):
        self.contents, self.identities, self.busy, self.error, self.bodies = list(contents), identities, busy, error, []

    def open(self, request, timeout):
        assert request.full_url == "http://edgeai-hawkeye-safety.edgeai.svc:8080/classify-frame"
        assert request.headers["Content-type"] == "image/jpeg"
        if self.busy:
            self.busy -= 1
            raise urllib.error.HTTPError(request.full_url, 503, "busy", {}, io.BytesIO(b'{"error":"BUSY"}'))
        if self.error:
            raise urllib.error.HTTPError(request.full_url, self.error, "x", {}, io.BytesIO(b'{"error":"NOT_READY"}'))
        i = len(self.bodies)
        self.bodies.append(request.data)
        identity = self.identities[i] if self.identities else IDENTITY
        return Response(json.dumps({**identity, "inputSha256": hashlib.sha256(request.data).hexdigest(),
                                    "placement": {"processor": "GPU", "sizeBytes": 10, "sizeVramBytes": 10},
                                    "content": self.contents[i], "doneReason": "stop", "totalMs": 700.5, "loadMs": 180.0,
                                    "adapterMs": 702.0, "promptEvalCount": 300, "evalCount": 20}).encode())


PARAMETERS = {"cameraSource": SOURCE, "adapterUrl": "http://edgeai-hawkeye-safety.edgeai.svc:8080", "busyWaitSeconds": 5}


class InferTest(unittest.TestCase):
    def test_every_frame_is_classified_with_separate_accelerator_identity(self):
        data, frames = archive()
        adapter = Adapter(["**forklift_violation**\nWorker walks in the forklift lane.", "I cannot tell."])
        result = infer.infer(PARAMETERS, data, opener=adapter)
        self.assertEqual(adapter.bodies, frames)
        self.assertEqual(result["accelerator"], IDENTITY)
        self.assertEqual(result["framesSha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual([f["category"] for f in result["frames"]], ["forklift_violation", None])
        self.assertEqual(result["frames"][0]["explanation"], "Worker walks in the forklift lane.")
        self.assertEqual(result["frames"][1]["rawOutput"], "I cannot tell.")
        self.assertEqual(result["summary"], {"frames": 2, "unparseable": 1, "byCategory": {
            "spill": 0, "forklift_violation": 1, "improper_stacking": 0, "obstacle": 0, "safe": 0}})
        self.assertEqual(result["frames"][0]["processor"], "GPU")

    def test_busy_adapter_is_waited_for(self):
        data, _ = archive(1)
        self.assertEqual(infer.infer(PARAMETERS, data, opener=Adapter(["safe"], busy=2))["frames"][0]["category"], "safe")

    def test_failures_fail_the_task(self):
        data, frames = archive()
        other = {**IDENTITY, "pod": {"name": "other", "uid": "u-2"}}
        cases = {
            "not ready": (PARAMETERS, data, Adapter(["safe"], error=503)),
            "accelerator changed": (PARAMETERS, data, Adapter(["safe", "safe"], identities=[IDENTITY, other])),
            "model not loaded": (PARAMETERS, data, Adapter(["safe", "safe"], identities=[{**IDENTITY, "loadedDigest": "x"}] * 2)),
            "other camera": ({**PARAMETERS, "cameraSource": {**SOURCE, "deviceUid": "other"}}, data, Adapter(["safe", "safe"])),
            "tampered frame": (PARAMETERS, data.replace(frames[0][2:10], b"\x00" * 8), Adapter(["safe", "safe"])),
            "bad adapter url": ({**PARAMETERS, "adapterUrl": "file:///etc/passwd"}, data, Adapter(["safe"])),
        }
        for name, (parameters, archive_bytes, adapter) in cases.items():
            with self.subTest(name), self.assertRaises(infer.InferError):
                infer.infer(parameters, archive_bytes, opener=adapter)

    def test_category_rule_matches_the_api(self):
        cases = {"**forklift_violation**": ("forklift_violation", None), "**Category: spill**\nOil on floor": ("spill", "Oil on floor"),
                 "Classification: improper_stacking": ("improper_stacking", None), "**obstacle**: box in aisle": ("obstacle", "box in aisle"),
                 "SAFE - clear aisle.": ("safe", "clear aisle."), "Description: none": None, "unknown_category": None, "": None,
                 "safe\nDescription: Clear aisle": ("safe", "Clear aisle")}
        for content, expected in cases.items():
            with self.subTest(content):
                self.assertEqual(infer.parse(content), expected)


if __name__ == "__main__":
    unittest.main()
