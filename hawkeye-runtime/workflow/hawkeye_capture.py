"""Hawkeye workflow capture Task: read N fresh decoded frames of the pinned RTSP camera from its edge Mapper.

The RTSP Mapper on the camera's node decodes the stream (ffmpeg, 2 fps) and serves its latest decoded frame (at most
its timeout old) only for exactly the configuration digest and source generation the API pinned when the Run was
accepted. Each frame kept here has a strictly newer decode time than the previous one; a repeated frame is polled
again. This Task never decodes RTSP and never substitutes another image: an unavailable, stale or changed source fails it.

Output port `frames` (application/x-tar, ustar): manifest.json + frame-NNNN.jpg.
"""

import datetime
import hashlib
import io
import json
import os
import re
import sys
import tarfile
import time
import urllib.error
import urllib.parse
import urllib.request

MAX_FRAME_BYTES = 1048576
POLL_SECONDS = 0.2
BINDING = "cameraSource"
BINDING_FIELDS = {"deviceId", "deviceUid", "nodeName", "mapperUrl", "mapperDeployment", "configurationSha256", "sourceGeneration", "pinnedAt"}
MAPPER_URL = re.compile(r"http://[0-9]{1,3}(\.[0-9]{1,3}){3}:7783")
FRAME_TIME = re.compile(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z")


class CaptureError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def frame_time(value):
    """Go RFC3339Nano in UTC as an aware datetime (microsecond precision) and the exact text."""
    match = FRAME_TIME.fullmatch(value or "")
    if not match:
        raise CaptureError("FRAME_TIME_INVALID")
    fraction = (match.group(2) or "").ljust(6, "0")[:6]
    return datetime.datetime.fromisoformat(match.group(1) + "." + fraction + "+00:00")


def iso(moment):
    return moment.astimezone(datetime.timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


def binding(parameters):
    source = parameters.get(BINDING)
    if not isinstance(source, dict) or set(source) != BINDING_FIELDS or not all(isinstance(v, str) for v in source.values()) \
            or not MAPPER_URL.fullmatch(source["mapperUrl"]) or not re.fullmatch(r"sha256:[a-f0-9]{64}", source["configurationSha256"]):
        raise CaptureError("CAMERA_BINDING_INVALID")
    return source


def integer(parameters, name, default, low, high):
    value = parameters.get(name, default)
    if type(value) is not int or not low <= value <= high:
        raise CaptureError("PARAMETER_INVALID")
    return value


def fetch(opener, url, timeout):
    try:
        with opener.open(urllib.request.Request(url, method="GET"), timeout=timeout) as response:
            if response.status != 200 or response.headers.get("Content-Type") != "image/jpeg":
                raise CaptureError("FRAME_UNAVAILABLE")
            body = response.read(MAX_FRAME_BYTES + 1)
            header = response.headers.get("X-EdgeAI-Frame-Time")
    except urllib.error.HTTPError as exc:
        exc.close()
        raise CaptureError("FRAME_UNAVAILABLE") from None
    except (urllib.error.URLError, OSError):
        raise CaptureError("MAPPER_UNREACHABLE") from None
    if len(body) > MAX_FRAME_BYTES or len(body) < 4 or body[:2] != b"\xff\xd8" or body[-2:] != b"\xff\xd9":
        raise CaptureError("FRAME_INVALID")
    return body, header


def capture(parameters, opener=None, now=None, sleep=time.sleep):
    """Returns (manifest, [(file, jpeg bytes)]) for `frames` consecutive fresh frames."""
    source = binding(parameters)
    count = integer(parameters, "frames", 10, 1, 60)
    max_age = integer(parameters, "maxFrameAgeSeconds", 10, 1, 120)
    timeout = integer(parameters, "frameTimeoutSeconds", 8, 1, 60)
    opener = opener or urllib.request.build_opener(NoRedirect)
    now = now or (lambda: datetime.datetime.now(datetime.timezone.utc))
    query = urllib.parse.urlencode({"configurationSha256": source["configurationSha256"], "sourceGeneration": source["sourceGeneration"]})
    url = f"{source['mapperUrl']}/devices/{urllib.parse.quote(source['deviceId'], safe='')}/preview?{query}"
    started, frames, files, previous = now(), [], [], None
    for seq in range(1, count + 1):
        sent = time.perf_counter()
        while True:
            body, header = fetch(opener, url, timeout)
            received, decoded = now(), frame_time(header)
            if previous is None or decoded > previous:
                break
            if decoded < previous:
                raise CaptureError("FRAME_TIME_REGRESSED")
            # The Mapper has not decoded a newer frame yet (2 fps); ask again within this frame's time limit.
            if time.perf_counter() - sent > timeout:
                raise CaptureError("FRAME_NOT_NEWER")
            sleep(POLL_SECONDS)
        age_ms = (received - decoded).total_seconds() * 1000
        if age_ms > max_age * 1000:
            raise CaptureError("FRAME_STALE")
        previous = decoded
        name = f"frame-{seq:04d}.jpg"
        files.append((name, body))
        frames.append({"seq": seq, "file": name, "frameTime": header, "receivedAt": iso(received), "ageMs": round(age_ms, 3),
                       "fetchMs": round((time.perf_counter() - sent) * 1000, 3), "bytes": len(body),
                       "sha256": hashlib.sha256(body).hexdigest()})
    manifest = {"apiVersion": "edgeai.hawkeye.frames/v1", "source": source, "requestedFrames": count,
                "startedAt": iso(started), "finishedAt": iso(now()),
                "distinctFrames": len({f["sha256"] for f in frames}), "frames": frames}
    return manifest, files


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
        manifest, files = capture(parameters)
    except CaptureError:
        return 1
    with open(os.path.join(os.environ["EDGEAI_OUTPUT_DIR"], "frames"), "xb") as handle:
        handle.write(archive(manifest, files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
