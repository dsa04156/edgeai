"""Hawkeye workflow infer Task: send every captured frame to the predeployed Hawkeye adapter (Ollama on a GPU node).

The adapter is an external accelerator service, not part of this Task: its node, Pod and the GPU/CPU placement Ollama
reports are recorded per frame, separately from where this Task's Pod runs. The model's answer is kept verbatim; a
first line that names no single category is stored as category null, never as safe.

Input port `frames` (capture output). Output port `detections` (application/json).
"""

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

CATEGORIES = ("spill", "forklift_violation", "improper_stacking", "obstacle", "safe")
FIRST = re.compile(r"(?:(?:category|classification)\s*:\s*)?([a-z_]+)\s*(?:[:\-–—]\s*(.*))?\.?", re.IGNORECASE)
FRAME = re.compile(r"frame-[0-9]{4}\.jpg")
ADAPTER_URL = re.compile(r"http://[a-z0-9.-]+(:[0-9]{1,5})?")
IDENTITY = ("nodeName", "pod", "model", "modelDigest", "loadedDigest", "ollamaVersion", "promptSha256")
EXPLANATION_CHARS = 500
MAX_RESPONSE_BYTES = 65536


class InferError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def clean(line):
    return re.sub(r"[*#`]", "", line).strip()


def parse(content):
    """Same rule as the API's HawkeyeOutput: (category, explanation) or None."""
    lines = content.strip().splitlines() or [""]
    first = clean(lines[0])
    match = FIRST.fullmatch(first)
    if not first or not match or match.group(1).lower() not in CATEGORIES:
        return None
    explanation = match.group(2).strip() if match.group(2) and match.group(2).strip() else None
    for line in lines[1:]:
        if explanation is not None:
            break
        line = re.sub(r"(?i)^description\s*:\s*", "", clean(line))
        if line:
            explanation = line
    if explanation is not None and len(explanation) > EXPLANATION_CHARS:
        explanation = explanation[:EXPLANATION_CHARS] + "…"
    return match.group(1).lower(), explanation


def frames(archive):
    """Verified (manifest, {file: bytes}) of a capture archive; anything unexpected fails the Task."""
    members = {}
    try:
        with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as tar:
            for info in tar:
                if not info.isreg() or info.name in members or not (info.name == "manifest.json" or FRAME.fullmatch(info.name)):
                    raise InferError("FRAMES_INVALID")
                members[info.name] = tar.extractfile(info).read()
        manifest = json.loads(members.pop("manifest.json"))
    except (tarfile.TarError, KeyError, ValueError):
        raise InferError("FRAMES_INVALID") from None
    if manifest.get("apiVersion") != "edgeai.hawkeye.frames/v1" or not isinstance(manifest.get("frames"), list) or not manifest["frames"]:
        raise InferError("FRAMES_INVALID")
    for frame in manifest["frames"]:
        data = members.get(frame.get("file"))
        if data is None or hashlib.sha256(data).hexdigest() != frame.get("sha256"):
            raise InferError("FRAMES_INVALID")
    if len(members) != len(manifest["frames"]):
        raise InferError("FRAMES_INVALID")
    return manifest, members


def classify(opener, url, image, timeout, busy_seconds):
    """One adapter answer; BUSY (another classification holds the single slot) is waited for, nothing else is retried."""
    deadline = time.monotonic() + busy_seconds
    while True:
        request = urllib.request.Request(url + "/classify-frame", data=image, method="POST", headers={"Content-Type": "image/jpeg"})
        sent = time.perf_counter()
        try:
            with opener.open(request, timeout=timeout) as response:
                body = response.read(MAX_RESPONSE_BYTES + 1)
            if len(body) > MAX_RESPONSE_BYTES:
                raise InferError("ADAPTER_RESPONSE_INVALID")
            return json.loads(body), (time.perf_counter() - sent) * 1000
        except urllib.error.HTTPError as exc:
            try:
                code = json.loads(exc.read(4096)).get("error")
            except ValueError:
                code = None
            exc.close()
            if exc.code == 503 and code == "BUSY" and time.monotonic() < deadline:
                time.sleep(0.5)
                continue
            raise InferError("ADAPTER_" + (code or str(exc.code))) from None
        except (urllib.error.URLError, OSError):
            raise InferError("ADAPTER_UNREACHABLE") from None
        except ValueError:
            raise InferError("ADAPTER_RESPONSE_INVALID") from None


def infer(parameters, archive, opener=None):
    url = parameters.get("adapterUrl")
    timeout, busy = parameters.get("requestTimeoutSeconds", 120), parameters.get("busyWaitSeconds", 120)
    if not isinstance(url, str) or not ADAPTER_URL.fullmatch(url) or type(timeout) is not int or not 1 <= timeout <= 600 \
            or type(busy) is not int or not 0 <= busy <= 600:
        raise InferError("PARAMETER_INVALID")
    manifest, members = frames(archive)
    if manifest.get("source") != parameters.get("cameraSource"):
        # The frames were not captured for this Run's pinned camera.
        raise InferError("SOURCE_MISMATCH")
    opener = opener or urllib.request.build_opener(NoRedirect)
    accelerator, results = None, []
    for frame in manifest["frames"]:
        answer, round_trip = classify(opener, url, members[frame["file"]], timeout, busy)
        try:
            identity = {key: answer[key] for key in IDENTITY}
            placement, content = answer["placement"], answer["content"]
            if answer["inputSha256"] != frame["sha256"] or not isinstance(content, str) or not isinstance(placement, dict):
                raise InferError("ADAPTER_RESPONSE_INVALID")
        except (KeyError, TypeError):
            raise InferError("ADAPTER_RESPONSE_INVALID") from None
        if accelerator is None:
            accelerator = identity
        elif identity != accelerator:
            # Another adapter Pod or model answered mid-window; the window is not one accelerator's result.
            raise InferError("ACCELERATOR_CHANGED")
        if identity["loadedDigest"] != identity["modelDigest"]:
            raise InferError("MODEL_MISMATCH")
        parsed = parse(content)
        results.append({"seq": frame["seq"], "frameTime": frame["frameTime"], "receivedAt": frame["receivedAt"], "sha256": frame["sha256"],
                        "category": parsed[0] if parsed else None, "explanation": parsed[1] if parsed else None,
                        "rawOutput": content, "doneReason": answer.get("doneReason"), "processor": placement.get("processor"),
                        "sizeBytes": placement.get("sizeBytes"), "sizeVramBytes": placement.get("sizeVramBytes"),
                        "inferenceMs": answer.get("totalMs"), "loadMs": answer.get("loadMs"), "adapterMs": answer.get("adapterMs"),
                        "roundTripMs": round(round_trip, 3), "promptEvalCount": answer.get("promptEvalCount"),
                        "evalCount": answer.get("evalCount")})
    counts = {category: sum(1 for r in results if r["category"] == category) for category in CATEGORIES}
    return {"apiVersion": "edgeai.hawkeye.detections/v1", "framesSha256": hashlib.sha256(archive).hexdigest(),
            "source": manifest["source"], "window": {"requestedFrames": manifest["requestedFrames"], "startedAt": manifest["startedAt"],
            "finishedAt": manifest["finishedAt"], "distinctFrames": manifest["distinctFrames"]},
            "accelerator": accelerator, "categories": list(CATEGORIES),
            "summary": {"frames": len(results), "unparseable": sum(1 for r in results if r["category"] is None), "byCategory": counts},
            "frames": results}


def main():
    with open(os.environ["EDGEAI_PARAMETERS_FILE"], "rb") as handle:
        parameters = json.load(handle)
    with open(os.path.join(os.environ["EDGEAI_INPUT_DIR"], "frames"), "rb") as handle:
        archive = handle.read()
    try:
        detections = infer(parameters, archive)
    except InferError:
        return 1
    with open(os.path.join(os.environ["EDGEAI_OUTPUT_DIR"], "detections"), "x", encoding="utf-8") as handle:
        json.dump(detections, handle, ensure_ascii=False, sort_keys=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
