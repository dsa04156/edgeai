"""Hawkeye workflow snapshot Task: persist one window of the session's continuous forklift/person observation.

The motion observer service (edgeai-hawkeye-motion) reads the pinned camera continuously between Runs, so this Task
does not capture or infer anything itself. It asks the observer to close window `observer.window` of session
`observer.sessionId` once that window spans `windowSeconds` of video (the window starts right after the previous
one's last frame), then downloads every retained frame, verifies each JPEG against the SHA-256 the observer recorded,
and writes them as Task Results. An observer watching any other camera source, an ended observer, or a frame that
does not match fails the Task; nothing is substituted.

Output ports `frames` (application/x-tar, edgeai.hawkeye.frames/v1) and `observations` (application/json,
edgeai.hawkeye.motion/v1).
"""

import collections
import hashlib
import io
import json
import os
import re
import sys
import tarfile
import time
import urllib.error
import urllib.request
import uuid

BINDING = "cameraSource"
BINDING_FIELDS = {"deviceId", "deviceUid", "nodeName", "mapperUrl", "mapperDeployment", "configurationSha256", "sourceGeneration", "pinnedAt"}
OBSERVER_URL = re.compile(r"http://[a-z0-9-]+\.[a-z0-9-]+\.svc(\.cluster\.local)?:[0-9]{1,5}")
MAX_FRAME_BYTES = 1048576
# A 10 s window at the 24 fps preview is about 240 JPEGs of about 70 KB (16 MB measured 2026-10-26).
MAX_FRAMES_BYTES = 33554432
MAX_RESPONSE_BYTES = 8388608
POLL_SECONDS = 0.5
NOT_STARTED_SECONDS = 20
STATUSES = ("NEAR_PATH", "APPROACHING", "UNKNOWN", "NO_CONFLICT", "NO_PAIR")


class SnapshotError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def arguments(parameters):
    source = parameters.get(BINDING)
    if not isinstance(source, dict) or set(source) != BINDING_FIELDS or not all(isinstance(v, str) for v in source.values()):
        raise SnapshotError("CAMERA_BINDING_INVALID")
    observer, url = parameters.get("observer"), parameters.get("observerUrl")
    seconds, wait = parameters.get("windowSeconds", 10), parameters.get("waitSeconds", 120)
    try:
        session = str(uuid.UUID(observer["sessionId"]))
        window = observer["window"]
    except (TypeError, KeyError, ValueError, AttributeError):
        raise SnapshotError("PARAMETER_INVALID") from None
    if set(observer) != {"sessionId", "window"} or session != observer["sessionId"] or type(window) is not int or window < 1 \
            or not isinstance(url, str) or not OBSERVER_URL.fullmatch(url) or type(seconds) is not int or not 1 <= seconds <= 120 \
            or type(wait) is not int or not 0 <= wait <= 600:
        raise SnapshotError("PARAMETER_INVALID")
    return source, url, session, window, seconds, wait


def call(opener, request, limit):
    """(status, body bytes, error code) of one observer request."""
    try:
        with opener.open(request, timeout=15) as response:
            body = response.read(limit + 1)
            status = response.status
    except urllib.error.HTTPError as exc:
        try:
            code = json.loads(exc.read(4096)).get("error")
        except (ValueError, AttributeError):
            code = None
        exc.close()
        return exc.code, b"", code or f"HTTP_{exc.code}"
    except (urllib.error.URLError, OSError):
        raise SnapshotError("OBSERVER_UNREACHABLE") from None
    if len(body) > limit:
        raise SnapshotError("OBSERVER_RESPONSE_INVALID")
    return status, body, None


def snapshot(parameters, opener=None, sleep=time.sleep, monotonic=time.monotonic):
    """Returns (frames tar bytes, observations document)."""
    source, url, session, k, seconds, wait = arguments(parameters)
    opener = opener or urllib.request.build_opener(NoRedirect)
    base = f"{url}/observers/{session}"
    started = monotonic()
    while True:
        request = urllib.request.Request(f"{base}/windows/{k}", data=json.dumps({"minSeconds": seconds}).encode(), method="POST",
                                         headers={"Content-Type": "application/json"})
        status, body, code = call(opener, request, MAX_RESPONSE_BYTES)
        elapsed = monotonic() - started
        if status == 200:
            break
        # The API starts the observer right after this window's Run is accepted, so a short absence is expected.
        if status == 202 or (code == "OBSERVER_NOT_FOUND" and elapsed < NOT_STARTED_SECONDS):
            if elapsed > seconds + wait:
                raise SnapshotError("WINDOW_TIMEOUT")
            sleep(POLL_SECONDS)
            continue
        raise SnapshotError(code or f"HTTP_{status}")
    try:
        document = json.loads(body)
        frames, window, identity, epoch = document["frames"], document["window"], document["observer"], document["epoch"]
        if document["sessionId"] != session or window["seq"] != k or not isinstance(frames, list) or not frames \
                or str(uuid.UUID(epoch)) != epoch:
            raise SnapshotError("OBSERVER_RESPONSE_INVALID")
    except (ValueError, KeyError, TypeError, AttributeError):
        raise SnapshotError("OBSERVER_RESPONSE_INVALID") from None
    if document.get("source") != {key: value for key, value in source.items() if key != "pinnedAt"}:
        raise SnapshotError("SOURCE_MISMATCH")
    files, manifest_frames = [], []
    for index, frame in enumerate(frames, start=1):
        status, image, code = call(opener, urllib.request.Request(f"{base}/frames/{frame['seq']}?epoch={epoch}", method="GET"), MAX_FRAME_BYTES)
        if status != 200:
            raise SnapshotError("FRAME_" + (code or str(status)))
        digest = hashlib.sha256(image).hexdigest()
        if digest != frame.get("sha256") or image[:2] != b"\xff\xd8" or image[-2:] != b"\xff\xd9":
            raise SnapshotError("FRAME_DIGEST_MISMATCH")
        name = f"frame-{index:04d}.jpg"
        files.append((name, image))
        manifest_frames.append({"seq": frame["seq"], "file": name, "frameTime": frame["frameTime"], "bytes": len(image), "sha256": digest})
    manifest = {"apiVersion": "edgeai.hawkeye.frames/v1", "source": source, "requestedFrames": len(manifest_frames),
                "startedAt": window["startedAt"], "finishedAt": window["finishedAt"],
                "distinctFrames": len({f["sha256"] for f in manifest_frames}), "frames": manifest_frames}
    tar = archive(manifest, files)
    if len(tar) > MAX_FRAMES_BYTES:
        raise SnapshotError("FRAMES_TOO_LARGE")
    statuses = collections.Counter(f.get("status") for f in frames)
    observations = {"apiVersion": "edgeai.hawkeye.motion/v1", "framesSha256": hashlib.sha256(tar).hexdigest(), "source": source,
                    "observer": {**identity, "epoch": epoch}, "window": window, "horizonSeconds": 3.0,
                    "units": {"position": "image pixels of the analysed frame", "distance": "person box heights", "velocity": "pixels per second"},
                    "summary": {"frames": len(frames), "byStatus": {s: statuses.get(s, 0) for s in STATUSES},
                                "resets": sum(1 for f in frames if f.get("reset")),
                                "tracks": len({t["id"] for f in frames for t in f.get("tracks", [])})},
                    "frames": frames}
    return tar, observations


def archive(manifest, files):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        for name, data in [("manifest.json", json.dumps(manifest, sort_keys=True).encode())] + files:
            info = tarfile.TarInfo(name)
            info.size, info.mode, info.mtime = len(data), 0o444, 0
            tar.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


def main():
    with open(os.environ["EDGEAI_PARAMETERS_FILE"], "rb") as handle:
        parameters = json.load(handle)
    try:
        frames, observations = snapshot(parameters)
    except SnapshotError as error:
        print(f"snapshot failed: {error}", file=sys.stderr)
        return 1
    output = os.environ["EDGEAI_OUTPUT_DIR"]
    with open(os.path.join(output, "frames"), "xb") as handle:
        handle.write(frames)
    with open(os.path.join(output, "observations"), "x", encoding="utf-8") as handle:
        json.dump(observations, handle, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
