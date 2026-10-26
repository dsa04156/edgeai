"""Snapshot workload rules against a fake motion observer (no network, no model)."""

import hashlib
import io
import json
import sys
import tarfile
import unittest
import urllib.error
import urllib.request
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "workflow"))
import hawkeye_snapshot as snapshot  # noqa: E402

SESSION = "0c6f3f8e-1d7b-4c5e-9d55-2f8a4b1c9e01"
SOURCE = {"deviceId": "hawkeye-forklift-video", "deviceUid": "2dffb17e-028d-43ed-a992-b854b9d4c8ef", "nodeName": "edge-a",
          "mapperUrl": "http://10.0.0.5:7783", "mapperDeployment": "edgeai-rtsp-0123456789abcdef", "configurationSha256": "sha256:" + "a" * 64,
          "sourceGeneration": "6b0a1c1e-7f4e-4a52-9a63-6f0f0d1d3a11", "pinnedAt": "2026-10-26T03:00:00Z"}
OBSERVED = {key: value for key, value in SOURCE.items() if key != "pinnedAt"}
URL = "http://edgeai-hawkeye-motion.edgeai.svc:8080"
EPOCH = "5e0a7c2d-8b1f-4c3e-9a6d-1f2e3d4c5b6a"


def jpeg(n):
    return b"\xff\xd8" + bytes([n]) * 30 + b"\xff\xd9"


def observation(seq, status="NO_CONFLICT", reset=None):
    return {"seq": seq, "frameTime": f"2026-10-26T03:00:{seq:02d}.000000Z", "sha256": hashlib.sha256(jpeg(seq)).hexdigest(),
            "status": status, "reset": reset, "tracks": [{"id": "F1", "class": "forklift"}], "pairs": []}


class Response(io.BytesIO):
    def __init__(self, body, status=200):
        super().__init__(body if isinstance(body, bytes) else json.dumps(body).encode())
        self.status = status


class Observer:
    """Answers 202 `pending` times, then the closed window; serves frame bytes by seq."""

    def __init__(self, frames=(3, 4, 5), pending=1, source=OBSERVED, session=SESSION, errors=None, images=None):
        self.pending, self.calls, self.errors = pending, [], list(errors or [])
        self.images = images or {seq: jpeg(seq) for seq in frames}
        self.window = {"sessionId": session, "epoch": EPOCH, "state": "RUNNING", "source": source,
                       "observer": {"nodeName": "etri-ser0004", "pod": "motion", "podUid": "u", "provider": "CPUExecutionProvider",
                                    "model": {"sha256": "7b57"}},
                       "window": {"seq": 2, "fromSeq": frames[0], "toSeq": frames[-1], "startedAt": "a", "finishedAt": "b", "evicted": 0},
                       "frames": [observation(s, "NEAR_PATH" if s == frames[-1] else "NO_CONFLICT") for s in frames]}

    def open(self, request, timeout):
        self.calls.append((request.get_method(), request.full_url, request.data))
        if self.errors:
            status, code = self.errors.pop(0)
            raise urllib.error.HTTPError(request.full_url, status, code, {}, io.BytesIO(json.dumps({"error": code}).encode()))
        if request.get_method() == "POST":
            if self.pending:
                self.pending -= 1
                return Response({"state": "FILLING", "coveredSeconds": 3.0}, 202)
            return Response(self.window)
        path, _, query = request.full_url.partition("?")
        if query != f"epoch={EPOCH}":
            raise urllib.error.HTTPError(request.full_url, 400, "", {}, io.BytesIO(b'{"error":"EPOCH_REQUIRED"}'))
        return Response(self.images[int(path.rsplit("/", 1)[1])])


def parameters(**extra):
    return {"cameraSource": SOURCE, "observer": {"sessionId": SESSION, "window": 2}, "observerUrl": URL, "windowSeconds": 10,
            "waitSeconds": 30, **extra}


def members(archive):
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        return {info.name: tar.extractfile(info).read() for info in tar}


class SnapshotTest(unittest.TestCase):
    def run_snapshot(self, fake, **extra):
        return snapshot.snapshot(parameters(**extra), opener=fake, sleep=lambda _: None)

    def test_closed_window_is_persisted_as_verified_frames_and_observations(self):
        observer = Observer()
        frames, observations = self.run_snapshot(observer)
        posts = [c for c in observer.calls if c[0] == "POST"]
        self.assertEqual(len(posts), 2)
        self.assertEqual(posts[0][1], f"{URL}/observers/{SESSION}/windows/2")
        self.assertEqual(json.loads(posts[0][2]), {"minSeconds": 10})
        content = members(frames)
        manifest = json.loads(content.pop("manifest.json"))
        self.assertEqual(manifest["apiVersion"], "edgeai.hawkeye.frames/v1")
        self.assertEqual(manifest["source"], SOURCE)
        self.assertEqual([f["seq"] for f in manifest["frames"]], [3, 4, 5])
        self.assertEqual(content[manifest["frames"][0]["file"]], jpeg(3))
        self.assertEqual(observations["apiVersion"], "edgeai.hawkeye.motion/v1")
        self.assertEqual(observations["framesSha256"], hashlib.sha256(frames).hexdigest())
        self.assertEqual(observations["source"], SOURCE)
        self.assertEqual(observations["observer"]["epoch"], EPOCH)
        self.assertEqual([c[1] for c in observer.calls if c[0] == "GET"],
                         [f"{URL}/observers/{SESSION}/frames/{seq}?epoch={EPOCH}" for seq in (3, 4, 5)])
        self.assertEqual(observations["window"]["fromSeq"], 3)
        self.assertEqual(observations["summary"]["byStatus"]["NEAR_PATH"], 1)
        self.assertEqual(observations["summary"]["tracks"], 1)
        self.assertEqual(len(observations["frames"]), 3)

    def test_window_without_a_uuid_epoch_is_rejected(self):
        for epoch in (None, "e-1", EPOCH.upper(), f"{EPOCH}&x=1"):
            observer = Observer()
            observer.window["epoch"] = epoch
            with self.assertRaisesRegex(snapshot.SnapshotError, "OBSERVER_RESPONSE_INVALID"):
                self.run_snapshot(observer)
            self.assertFalse([c for c in observer.calls if c[0] == "GET"])

    def test_observer_watching_another_source_fails(self):
        with self.assertRaisesRegex(snapshot.SnapshotError, "SOURCE_MISMATCH"):
            self.run_snapshot(Observer(source={**OBSERVED, "sourceGeneration": "other"}))

    def test_frame_bytes_must_match_the_observed_sha256(self):
        with self.assertRaisesRegex(snapshot.SnapshotError, "FRAME_DIGEST_MISMATCH"):
            self.run_snapshot(Observer(images={3: jpeg(3), 4: jpeg(9), 5: jpeg(5)}))

    def test_ended_observer_fails_the_window(self):
        with self.assertRaisesRegex(snapshot.SnapshotError, "OBSERVER_FAILED"):
            self.run_snapshot(Observer(errors=[(409, "OBSERVER_FAILED")]))

    def test_observer_not_started_yet_is_waited_for(self):
        frames, _ = self.run_snapshot(Observer(errors=[(404, "OBSERVER_NOT_FOUND"), (404, "OBSERVER_NOT_FOUND")]))
        self.assertTrue(frames)

    def test_window_never_filling_times_out(self):
        with self.assertRaisesRegex(snapshot.SnapshotError, "WINDOW_TIMEOUT"):
            snapshot.snapshot(parameters(waitSeconds=0, windowSeconds=1), opener=Observer(pending=10 ** 6), sleep=lambda _: None,
                              monotonic=iter(range(0, 10 ** 6, 1)).__next__)

    def test_observer_url_and_parameters_are_validated(self):
        for extra in ({"observerUrl": "http://evil.example.com:8080/x"}, {"observer": {"sessionId": "x", "window": 1}},
                      {"windowSeconds": 0}, {"cameraSource": None}):
            with self.assertRaisesRegex(snapshot.SnapshotError, "PARAMETER_INVALID|CAMERA_BINDING_INVALID"):
                self.run_snapshot(Observer(), **extra)


try:
    sys.path[:0] = [str(Path(__file__).resolve().parents[1] / "motion"), str(Path(__file__).resolve().parents[1] / "motion" / "tests")]
    from http.server import ThreadingHTTPServer
    from edgeai_hawkeye_motion import server  # noqa: E402
    import test_service  # noqa: E402  (module import, so its ServiceTest is not collected here again)
except ImportError:  # the observer service needs numpy, which the snapshot image does not ship
    server = None


@unittest.skipIf(server is None, "motion observer dependencies are not installed")
class ObserverServiceSeamTest(unittest.TestCase):
    """The snapshot Task against the real observer HTTP service (scripted camera and detector, no model)."""

    def test_snapshot_reads_a_window_from_the_real_observer(self):
        case = test_service.ServiceTest("test_health_reports_identity")
        case.setUp()
        self.addCleanup(case.tearDown)
        session = case.session
        self.assertEqual(case.start()[0], 201)
        port = case.http.server_address[1]
        opener = urllib.request.build_opener(snapshot.NoRedirect)
        seen = []

        class Local:
            def open(self, request, timeout):
                request.full_url = request.full_url.replace(URL, f"http://127.0.0.1:{port}")
                seen.append((request.get_method(), request.full_url))
                return opener.open(request, timeout=timeout)

        frames, observations = snapshot.snapshot(
            {"cameraSource": {**test_service.SOURCE, "pinnedAt": "2026-10-26T03:00:00Z"}, "observer": {"sessionId": session, "window": 1},
             "observerUrl": URL, "windowSeconds": 1, "waitSeconds": 10}, opener=Local())
        epoch = observations["observer"]["epoch"]
        self.assertEqual(str(uuid.UUID(epoch)), epoch)
        gets = [url for method, url in seen if method == "GET"]
        self.assertTrue(gets)
        self.assertTrue(all(url.endswith(f"?epoch={epoch}") for url in gets))
        manifest = json.loads(members(frames)["manifest.json"])
        self.assertEqual(len(manifest["frames"]), observations["summary"]["frames"])


if __name__ == "__main__":
    unittest.main()
