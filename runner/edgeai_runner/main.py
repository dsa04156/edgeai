"""Run a claimed workload, upload immutable artifacts, then commit the verified manifest.

Only fixed phase/error codes are logged. Tokens, signed URLs, parameters and workload output
are never written to stdout/stderr. Workloads communicate through files in the work volume.
"""
from __future__ import annotations

import decimal
import hashlib
import json
import os
import re
import signal
import stat
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from contextlib import contextmanager
from pathlib import Path
from edgeai_runner.telemetry import Sampler, own_cgroup

MAX_JSON = 262144
MAX_FILE = 268435456
PORT = re.compile(r"[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*\Z")


class Cancellation(threading.Event):
    """Signal callbacks must not acquire a lock interrupted on this same thread.

    Normal thread cancellation retains Event notification. Signal cancellation is
    a flag checked after bounded waits and by the independent workload watchdog.
    """
    def __init__(self):
        super().__init__()
        self._signal_requested = False

    def request_signal(self, *_):
        self._signal_requested = True

    def is_set(self):
        return self._signal_requested or super().is_set()

    def wait(self, timeout):
        return self.is_set() or super().wait(timeout) or self.is_set()

    def clear(self):
        self._signal_requested = False
        super().clear()


cancelled = Cancellation()


class RunnerError(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RunnerError("HTTP_REDIRECT_REJECTED")


def json_decode(data: bytes):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise RunnerError("INVALID_RESPONSE")
            result[key] = value
        return result
    def invalid(_):
        raise RunnerError("INVALID_RESPONSE")
    try:
        return json.loads(data, parse_float=decimal.Decimal, object_pairs_hook=pairs, parse_constant=invalid)
    except (ValueError, RecursionError, UnicodeError):
        raise RunnerError("INVALID_RESPONSE") from None


def json_encode(value, depth=0) -> bytes:
    if depth > 32:
        raise RunnerError("INVALID_RESPONSE")
    if isinstance(value, dict):
        return b"{" + b",".join(json_encode(str(k), depth + 1) + b":" + json_encode(v, depth + 1) for k, v in value.items()) + b"}"
    if isinstance(value, list):
        return b"[" + b",".join(json_encode(v, depth + 1) for v in value) + b"]"
    if isinstance(value, decimal.Decimal):
        if not value.is_finite():
            raise RunnerError("INVALID_RESPONSE")
        return str(value).encode("ascii")
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")


def http_url(value: str) -> str:
    url = urllib.parse.urlsplit(value)
    if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password or url.fragment:
        raise RunnerError("INVALID_RESPONSE")
    return value


def port_name(value):
    if not isinstance(value, str) or len(value) > 100 or not PORT.fullmatch(value):
        raise RunnerError("INVALID_RESPONSE")
    return value


def integer(value, low, high):
    if type(value) is not int or not low <= value <= high:
        raise RunnerError("INVALID_RESPONSE")
    return value


class Runner:
    def __init__(self):
        try:
            self.attempt = str(uuid.UUID(os.environ["EDGEAI_ATTEMPT_ID"]))
            self.pod = str(uuid.UUID(os.environ["EDGEAI_POD_UID"]))
            self.epoch = int(os.environ["EDGEAI_ATTEMPT_EPOCH"])
            if not 1 <= self.epoch <= 9007199254740991:
                raise ValueError()
            origin = http_url(os.environ["EDGEAI_CONTROL_PLANE_URL"])
            split = urllib.parse.urlsplit(origin)
            if split.path not in ("", "/") or split.query:
                raise ValueError()
            self.base = origin.rstrip("/") + "/internal/v1/attempts/" + self.attempt
            self.token_file = Path(os.environ["EDGEAI_CLAIM_FILE"])
            self.token = self.token_file.read_text().strip()
            self.pod_token_file = Path(os.environ["EDGEAI_POD_TOKEN_FILE"])
            if not self.token or len(self.token) > 1024 or any(ord(c) < 33 or ord(c) > 126 for c in self.token):
                raise ValueError()
            self.work = Path(os.environ["EDGEAI_WORK_DIR"]).resolve(strict=True)
        except (KeyError, ValueError, OSError):
            raise RunnerError("INVALID_CONFIGURATION") from None
        self.http = urllib.request.build_opener(NoRedirect())
        self.deadline = time.monotonic() + 30
        self.identity = {"epoch": self.epoch, "podUid": self.pod}
        self.fenced_metrics = threading.Event()

    def timeout(self, maximum=15):
        if self.fenced_metrics.is_set():
            raise RunnerError("FENCED")
        if cancelled.is_set():
            raise RunnerError("CANCELLED")
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise RunnerError("TIMEOUT")
        return max(0.1, min(maximum, remaining))

    def api(self, operation, payload, max_attempts=None, request_timeout=15):
        body = json_encode(payload)
        if len(body) > MAX_JSON:
            raise RunnerError("INVALID_RESPONSE")
        # Initial Pod status/Job observation may lag the already running process.
        # Claim may retry within its existing 30-second deadline; fenced identity never retries.
        retries = max_attempts if max_attempts is not None else 16 if operation == "claim" else 3
        for attempt in range(retries):
            try:
                # Kubelet rotates this Pod-bound credential; reread it for each control-plane request.
                pod_token = self.pod_token_file.read_text().strip()
                if not pod_token or len(pod_token) > 16384 or any(ord(c) < 33 or ord(c) > 126 for c in pod_token):
                    raise RunnerError("INVALID_CONFIGURATION")
                request = urllib.request.Request(self.base + "/" + operation, data=body, method="POST",
                    headers={"Authorization": "Bearer " + self.token, "X-EdgeAI-Pod-Token": pod_token, "Content-Type": "application/json"})
                with self.http.open(request, timeout=self.timeout(request_timeout)) as response:
                    content = response.read(MAX_JSON + 1)
                    if len(content) > MAX_JSON:
                        raise RunnerError("INVALID_RESPONSE")
                    return json_decode(content)
            except urllib.error.HTTPError as error:
                status = error.code
                details = {}
                if operation == "telemetry" and status == 409:
                    try:
                        content = error.read(8193)
                        if len(content) <= 8192:
                            details = json_decode(content)
                    except Exception:
                        pass
                error.close()
                if operation == "telemetry" and status == 409 and isinstance(details, dict) and details.get("code") in {
                        "TELEMETRY_CONFLICT", "TELEMETRY_OUT_OF_ORDER", "TELEMETRY_EXPIRED"}:
                    raise RunnerError("TELEMETRY_REJECTED") from None
                if status in (401, 403, 409):
                    raise RunnerError("FENCED") from None
                if status not in (429, 502, 503, 504):
                    raise RunnerError("CONTROL_PLANE_REJECTED") from None
            except (urllib.error.URLError, TimeoutError, OSError):
                pass
            if attempt < retries - 1:
                cancelled.wait(min(self.timeout(), 2, 0.25 * (2 ** attempt)))
        raise RunnerError("CONTROL_PLANE_UNAVAILABLE")

    def download(self, assignment, directory):
        for name, item in assignment["inputs"].items():
            port_name(name)
            expected = integer(item["bytes"], 0, MAX_FILE)
            digest = item["sha256"]
            if not isinstance(digest, str) or not re.fullmatch("[a-f0-9]{64}", digest):
                raise RunnerError("INVALID_RESPONSE")
            sha = hashlib.sha256()
            length = 0
            try:
                request = urllib.request.Request(http_url(item["url"]), method="GET")
                with self.http.open(request, timeout=self.timeout()) as response, (directory / name).open("xb") as target:
                    while chunk := response.read(65536):
                        self.timeout()
                        length += len(chunk)
                        if length > expected:
                            raise RunnerError("INPUT_INVALID")
                        target.write(chunk)
                        sha.update(chunk)
                if length != expected or sha.hexdigest() != digest:
                    raise RunnerError("INPUT_INVALID")
            except urllib.error.HTTPError as error:
                error.close()
                raise RunnerError("INPUT_INVALID") from None
            except (urllib.error.URLError, TimeoutError, OSError):
                raise RunnerError("STORAGE_FAILED") from None

    def workload(self, assignment, *, state_file=None):
        command = assignment["command"] + assignment["args"]
        if not command or not all(isinstance(v, str) and "\0" not in v and len(v) <= 4096 for v in command):
            raise RunnerError("INVALID_RESPONSE")
        env = {k: v for k, v in os.environ.items() if not k.startswith("EDGEAI_")}
        env.update(EDGEAI_INPUT_DIR=str(self.work / "inputs"), EDGEAI_OUTPUT_DIR=str(self.work / "outputs"),
            EDGEAI_PARAMETERS_FILE=str(self.work / "parameters.json"), EDGEAI_TELEMETRY_FILE=str(self.work / "telemetry.json"), TMPDIR=str(self.work / "tmp"))
        if state_file is not None:
            env['EDGEAI_STATE_FILE'] = str(state_file)
        process = None
        try:
            supervised = os.environ.get("EDGEAI_VD_SUPERVISED") == "true"
            process = subprocess.Popen(command, cwd=self.work, env=env, stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=not supervised,
                process_group=0 if supervised else None)
            while process.poll() is None:
                self.timeout()
                cancelled.wait(0.05)
            if process.returncode != 0:
                raise RunnerError("WORKLOAD_FAILED")
        except OSError:
            raise RunnerError("WORKLOAD_FAILED") from None
        finally:
            if process is not None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    pass
                # Also stop background descendants after the primary workload has exited.
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()

    @contextmanager
    def measurements(self, assignment):
        """One sequence spans stream calculation and finalization for this Attempt."""
        stop_metrics = threading.Event()
        metrics_thread = None
        def measurements(interval):
            sampler = Sampler(self.work, own_cgroup())
            while not stop_metrics.wait(interval):
                try:
                    sample = sampler.sample()
                    if sample is not None:
                        self.api("telemetry", {**self.identity, **sample}, max_attempts=1, request_timeout=2)
                except RunnerError as error:
                    if error.code == "FENCED":
                        self.fenced_metrics.set()
                        return
                except Exception:
                    # Optional measurement must not replace the workload's result/failure path.
                    pass
        try:
            if "telemetry" in assignment:
                interval = integer(assignment["telemetry"]["intervalSeconds"], 1, 60)
                metrics_thread = threading.Thread(target=measurements, args=(interval,), daemon=True)
                metrics_thread.start()
            yield
            self.timeout()
        finally:
            stop_metrics.set()
            if metrics_thread is not None:
                metrics_thread.join(timeout=3)

    def outputs(self, assignment, directory_fd):
        files = {}
        try:
            for name, spec in assignment["outputs"].items():
                port_name(name)
                limit = integer(spec["maxBytes"], 1, MAX_FILE)
                descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory_fd)
                stream = os.fdopen(descriptor, "rb")
                files[name] = stream
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
                    raise RunnerError("OUTPUT_INVALID")
                digest = hashlib.sha256()
                length = 0
                while chunk := stream.read(65536):
                    self.timeout()
                    length += len(chunk)
                    if length > limit:
                        raise RunnerError("OUTPUT_INVALID")
                    digest.update(chunk)
                if length != info.st_size:
                    raise RunnerError("OUTPUT_INVALID")
                stream.seek(0)
                yield {"port": name, "bytes": length, "sha256": digest.hexdigest(), "mediaType": spec["mediaType"]}, stream
        except OSError:
            raise RunnerError("OUTPUT_INVALID") from None
        finally:
            for stream in files.values():
                stream.close()

    def upload(self, grant, manifest, stream):
        headers = grant["headers"]
        if not isinstance(headers, dict) or any(k.lower() not in ("content-type", "x-amz-meta-sha256", "x-amz-checksum-sha256") for k in headers):
            raise RunnerError("INVALID_RESPONSE")
        headers = {**headers, "Content-Length": str(manifest["bytes"])}
        try:
            request = urllib.request.Request(http_url(grant["url"]), data=stream, headers=headers, method="PUT")
            with self.http.open(request, timeout=self.timeout()) as response:
                version = response.headers.get("x-amz-version-id", "")
                if not version or version == "null" or len(version) > 1024:
                    raise RunnerError("STORAGE_FAILED")
                response.read(65536)
            return version
        except urllib.error.HTTPError as error:
            error.close()
            raise RunnerError("STORAGE_FAILED") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise RunnerError("STORAGE_FAILED") from None

    def run(self):
        assignment = self.api("claim", self.identity)
        if assignment["attemptId"] != self.attempt or assignment["epoch"] != self.epoch:
            raise RunnerError("FENCED")
        if not isinstance(assignment["inputs"], dict) or not isinstance(assignment["outputs"], dict) or not isinstance(assignment["parameters"], dict):
            raise RunnerError("INVALID_RESPONSE")
        if len(assignment["inputs"]) > 16 or not 1 <= len(assignment["outputs"]) <= 16:
            raise RunnerError("INVALID_RESPONSE")
        self.deadline = time.monotonic() + integer(assignment["timeoutSeconds"], 1, 86400)
        for folder in ("inputs", "outputs", "tmp"):
            (self.work / folder).mkdir(mode=0o700)
        (self.work / "parameters.json").write_bytes(json_encode(assignment["parameters"]))
        self.download(assignment, self.work / "inputs")
        fd = os.open(self.work / "outputs", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            print("RUNNER_WORKLOAD_START", flush=True)
            with self.measurements(assignment):
                state_file = None
                if 'stream' in assignment:
                    from edgeai_runner.stream_execution import execute
                    try:
                        state_file = execute(self, assignment)
                    except Exception:
                        # Preserve cancellation/deadline disposition across SDK layers.
                        self.timeout()
                        raise
                self.workload(assignment, state_file=state_file)
            committed = []
            for manifest, stream in self.outputs(assignment, fd):
                grants = self.api("uploads", {**self.identity, "outputs": [manifest]})["outputs"]
                if len(grants) != 1 or grants[0]["port"] != manifest["port"]:
                    raise RunnerError("INVALID_RESPONSE")
                version = self.upload(grants[0], manifest, stream)
                committed.append({**manifest, "versionId": version})
            self.api("commit", {**self.identity, "outputs": committed})
            print("RUNNER_RESULT_COMMITTED", flush=True)
        finally:
            os.close(fd)

    def report_failure(self, code):
        # Bounded best effort. Cancellation/expired claims may legitimately reject the report.
        if code == "FENCED":
            return
        allowed = {"WORKLOAD_FAILED", "TIMEOUT", "INPUT_INVALID", "OUTPUT_INVALID", "STORAGE_FAILED", "CANCELLED"}
        reason = code if code in allowed else "RUNNER_FAILED"
        cancelled.clear()
        self.deadline = time.monotonic() + 3
        try:
            self.api("fail", {**self.identity, "reason": reason})
        except Exception:
            pass


def main():
    signal.signal(signal.SIGTERM, cancelled.request_signal)
    signal.signal(signal.SIGINT, cancelled.request_signal)
    runner = None
    try:
        runner = Runner()
        runner.run()
        return 0
    except RunnerError as error:
        if runner is not None:
            runner.report_failure(error.code)
        print("RUNNER_FAILED " + error.code, flush=True)
        return 1
    except Exception:
        if runner is not None:
            runner.report_failure("RUNNER_FAILED")
        print("RUNNER_FAILED INVALID_RESPONSE", flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())
