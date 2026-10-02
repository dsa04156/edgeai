"""Persistent VD supervisor. The control-plane poll handler is a separate integration step.

Only fixed event/error codes reach logs. Workload output, credentials and URLs never do.
Runner exit status is a process disposition, not proof of a committed Result.
"""
from __future__ import annotations

import hashlib
import ctypes
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from edgeai_runner.main import MAX_JSON, NoRedirect, RunnerError, http_url, integer, json_decode, json_encode

MAX_ATTEMPTS = 100000
stopping = threading.Event()


def identifier(value):
    if not isinstance(value, str):
        raise RunnerError("VD_INVALID_PROTOCOL")
    try:
        parsed = str(uuid.UUID(value))
    except ValueError:
        raise RunnerError("VD_INVALID_PROTOCOL") from None
    if parsed != value:
        raise RunnerError("VD_INVALID_PROTOCOL")
    return parsed


def fields(value, names):
    if not isinstance(value, dict) or set(value) != set(names):
        raise RunnerError("VD_INVALID_PROTOCOL")
    return value


def credential(path, maximum):
    with path.open("rb") as stream:
        value = stream.read(maximum + 1).strip()
    if not value or len(value) > maximum or any(c < 33 or c > 126 for c in value):
        raise RunnerError("VD_INVALID_CONFIGURATION")
    return value.decode("ascii")


def stop_session(process, grace=3, send_term=True):
    """Signal one owned Runner session, including workload groups in that session."""
    if process.poll() is None:
        if send_term:
            process.terminate()
        try:
            process.wait(timeout=grace)
        except subprocess.TimeoutExpired:
            pass
    # Runner's normal cleanup stops its workload group. Also remove remaining group members
    # if the Runner failed before its finally block. Other concurrent tasks have other SIDs.
    for entry in Path("/proc").iterdir():
        if not entry.name.isdecimal():
            continue
        pid = int(entry.name)
        descriptor = None
        try:
            descriptor = os.pidfd_open(pid)
            if os.getsid(pid) == process.pid:
                signal.pidfd_send_signal(descriptor, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
        finally:
            if descriptor is not None:
                os.close(descriptor)
    if process.poll() is None:
        process.kill()
    process.wait(timeout=3)


class Supervisor:
    def __init__(self):
        try:
            # Reap orphaned workload descendants both as container PID 1 and in host tests.
            if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
                raise ValueError()
            self.vd = identifier(os.environ["EDGEAI_VD_ID"])
            self.runtime = identifier(os.environ["EDGEAI_VD_RUNTIME_ID"])
            self.pod = identifier(os.environ["EDGEAI_POD_UID"])
            self.generation = integer(int(os.environ["EDGEAI_VD_GENERATION"]), 1, 9007199254740991)
            self.capacity = integer(int(os.environ["EDGEAI_VD_MAX_CONCURRENT_TASKS"]), 1, 16)
            self.startup = integer(int(os.environ["EDGEAI_VD_STARTUP_SECONDS"]), 1, 600)
            self.drain_seconds = integer(int(os.environ["EDGEAI_VD_DRAIN_SECONDS"]), 1, 600)
            origin = http_url(os.environ["EDGEAI_CONTROL_PLANE_URL"])
            parsed = urllib.parse.urlsplit(origin)
            if parsed.path not in ("", "/") or parsed.query:
                raise ValueError()
            self.origin = origin.rstrip("/")
            self.url = self.origin + "/internal/v1/vd-runtimes/" + self.runtime + "/poll"
            self.token_file = Path(os.environ["EDGEAI_VD_CLAIM_FILE"])
            self.pod_token_file = Path(os.environ["EDGEAI_POD_TOKEN_FILE"])
            self.work = Path(os.environ["EDGEAI_WORK_DIR"]).resolve(strict=True)
            credential(self.token_file, 1024)
            credential(self.pod_token_file, 16384)
            self.session = str(uuid.uuid4())
            # A restarted process cannot forget previous execution identities in the same volume.
            owner = os.open(self.work / ".vd-owner", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(owner, "wb") as stream:
                stream.write(json_encode({"runtimeId": self.runtime, "generation": self.generation, "podUid": self.pod, "sessionId": self.session}))
            (self.work / "attempts").mkdir(mode=0o700)
        except (KeyError, ValueError, OSError, RunnerError):
            raise RunnerError("VD_INVALID_CONFIGURATION") from None
        self.http = urllib.request.build_opener(NoRedirect())
        self.sequence = 0
        self.active = {}
        self.completed = {}
        self.seen = {}
        self.command = "RUN"
        self.drain_deadline = None
        self.lease_deadline = time.monotonic() + self.startup
        self.marker = self.work / ".vd-ready"

    def unready(self):
        self.marker.unlink(missing_ok=True)

    def ready(self):
        value = {"runtimeId": self.runtime, "podUid": self.pod, "generation": self.generation, "sessionId": self.session,
                 "leaseDeadlineMonotonic": self.lease_deadline}
        temporary = self.marker.with_suffix(".tmp")
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(json_encode(value))
        temporary.replace(self.marker)

    def collect(self):
        for attempt, child in list(self.active.items()):
            code = child["process"].poll()
            if code is not None:
                stop_session(child["process"], 0)
                self.completed[attempt] = {"attemptId": attempt, "epoch": child["epoch"], "exitCode": code}
                shutil.rmtree(child["directory"])
                del self.active[attempt]
        self.reap()

    def reap(self):
        roots = {child["process"].pid for child in self.active.values()}
        children = Path(f"/proc/{os.getpid()}/task/{os.getpid()}/children").read_text().split()
        for value in children:
            pid = int(value)
            if pid not in roots:
                try:
                    os.waitpid(pid, os.WNOHANG)
                except ChildProcessError:
                    pass

    def request(self):
        return json_encode({"vdId": self.vd, "runtimeId": self.runtime, "generation": self.generation, "podUid": self.pod,
            "sessionId": self.session, "sequence": self.sequence, "state": "DRAINING" if self.command == "DRAIN" else "RUNNING",
            "active": [{"attemptId": attempt, "epoch": child["epoch"]} for attempt, child in sorted(self.active.items())],
            "completed": [self.completed[key] for key in sorted(self.completed)[:32]]})

    def poll(self, body):
        if len(body) > MAX_JSON:
            raise RunnerError("VD_INVALID_PROTOCOL")
        request = urllib.request.Request(self.url, data=body, method="POST", headers={
            "Authorization": "Bearer " + credential(self.token_file, 1024),
            "X-EdgeAI-Pod-Token": credential(self.pod_token_file, 16384), "Content-Type": "application/json"})
        try:
            timeout = max(.1, min(5, self.lease_deadline - time.monotonic()))
            with self.http.open(request, timeout=timeout) as response:
                data = response.read(MAX_JSON + 1)
                if len(data) > MAX_JSON:
                    raise RunnerError("VD_INVALID_PROTOCOL")
                return json_decode(data)
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status in (401, 403, 409):
                raise RunnerError("VD_FENCED") from None
            if status not in (429, 502, 503, 504):
                raise RunnerError("VD_CONTROL_PLANE_REJECTED") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            pass
        return None

    def apply(self, reply):
        fields(reply, ("runtimeId", "generation", "podUid", "sessionId", "sequence", "command", "leaseSeconds", "assignments", "cancelAttempts", "acknowledgedAttempts"))
        for key, expected in (("runtimeId", self.runtime), ("generation", self.generation), ("podUid", self.pod), ("sessionId", self.session), ("sequence", self.sequence)):
            if type(reply[key]) is not type(expected) or reply[key] != expected:
                raise RunnerError("VD_FENCED")
        if reply["command"] not in ("RUN", "DRAIN", "STOP"):
            raise RunnerError("VD_INVALID_PROTOCOL")
        lease = integer(reply["leaseSeconds"], 1, 60)
        assignments = reply["assignments"]
        if not isinstance(assignments, list) or len(assignments) > self.capacity:
            raise RunnerError("VD_INVALID_PROTOCOL")
        cancellations, acknowledgements = [], []
        for key, target in (("cancelAttempts", cancellations), ("acknowledgedAttempts", acknowledgements)):
            if not isinstance(reply[key], list) or len(reply[key]) > 32:
                raise RunnerError("VD_INVALID_PROTOCOL")
            target.extend(identifier(value) for value in reply[key])
            if len(set(target)) != len(target):
                raise RunnerError("VD_INVALID_PROTOCOL")
        planned = {}
        for assignment in assignments:
            fields(assignment, ("attemptId", "epoch", "claimToken"))
            attempt = identifier(assignment["attemptId"])
            epoch = integer(assignment["epoch"], 1, 9007199254740991)
            token = assignment["claimToken"]
            if not isinstance(token, str) or not token or len(token) > 1024 or any(ord(c) < 33 or ord(c) > 126 for c in token):
                raise RunnerError("VD_INVALID_PROTOCOL")
            digest = hashlib.sha256(json_encode({"attemptId": attempt, "epoch": epoch, "claimToken": token})).hexdigest()
            if attempt in planned or attempt in cancellations or attempt in acknowledgements or (attempt in self.seen and self.seen[attempt] != digest):
                raise RunnerError("VD_INVALID_PROTOCOL")
            planned[attempt] = (epoch, token, digest)
        if any(attempt not in self.completed for attempt in acknowledgements):
            raise RunnerError("VD_INVALID_PROTOCOL")
        incoming = set(planned) - self.seen.keys()
        if len(self.active) + len(incoming) > self.capacity or len(self.seen) + len(incoming) > MAX_ATTEMPTS:
            raise RunnerError("VD_CAPACITY_EXCEEDED")
        if reply["command"] != "RUN" and assignments:
            raise RunnerError("VD_INVALID_PROTOCOL")
        self.lease_deadline = time.monotonic() + lease
        if self.command == "DRAIN" and reply["command"] == "RUN":
            raise RunnerError("VD_INVALID_PROTOCOL")
        self.command = reply["command"]
        if self.command == "STOP":
            self.unready()
            return
        if self.command == "DRAIN" or len(self.seen) + len(incoming) == MAX_ATTEMPTS:
            self.command = "DRAIN"
            self.unready()
            if self.drain_deadline is None:
                self.drain_deadline = time.monotonic() + self.drain_seconds
        else:
            self.ready()
        for attempt in cancellations:
            if attempt in self.active:
                stop_session(self.active[attempt]["process"])
        self.collect()
        for attempt in acknowledgements:
            del self.completed[attempt]
        for attempt, (epoch, token, digest) in planned.items():
            if attempt in self.seen:
                continue
            if stopping.is_set():
                return
            # Cancelling an unresponsive workload can consume the lease just received.
            if time.monotonic() >= self.lease_deadline:
                raise RunnerError("VD_LEASE_EXPIRED")
            directory = self.work / "attempts" / attempt
            directory.mkdir(mode=0o700)
            token_file = directory / ".claim"
            fd = os.open(token_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, "w") as stream:
                stream.write(token)
            env = {key: value for key, value in os.environ.items() if not key.startswith("EDGEAI_")}
            env.update(EDGEAI_ATTEMPT_ID=attempt, EDGEAI_ATTEMPT_EPOCH=str(epoch), EDGEAI_POD_UID=self.pod,
                EDGEAI_CONTROL_PLANE_URL=self.origin, EDGEAI_CLAIM_FILE=str(token_file), EDGEAI_POD_TOKEN_FILE=str(self.pod_token_file),
                EDGEAI_WORK_DIR=str(directory), EDGEAI_VD_SUPERVISED="true", PYTHONPATH=str(Path(__file__).resolve().parents[1]), PYTHONDONTWRITEBYTECODE="1")
            process = subprocess.Popen([sys.executable, "-m", "edgeai_runner.main"], env=env, cwd=directory,
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            self.active[attempt] = {"process": process, "directory": directory, "epoch": epoch}
            self.seen[attempt] = digest
        self.sequence += 1

    def run(self):
        body = None
        try:
            while not stopping.is_set():
                self.collect()
                if time.monotonic() >= self.lease_deadline:
                    raise RunnerError("VD_LEASE_EXPIRED")
                if self.drain_deadline is not None and time.monotonic() >= self.drain_deadline:
                    raise RunnerError("VD_DRAIN_TIMEOUT")
                if body is None:
                    body = self.request()
                reply = self.poll(body)
                if stopping.is_set():
                    return 0
                if time.monotonic() >= self.lease_deadline:
                    raise RunnerError("VD_LEASE_EXPIRED")
                if reply is None:
                    self.unready()
                else:
                    self.apply(reply)
                    body = None
                    # The server may still hold an assignment whose response was lost. Keep polling
                    # while idle/draining so a later sequence can prove it never started; only STOP
                    # confirms that durable allocations have all been closed.
                    if self.command == "STOP":
                        return 0
                stopping.wait(.25)
            return 0
        finally:
            self.unready()
            for child in self.active.values():
                if child["process"].poll() is None:
                    child["process"].terminate()
            deadline = time.monotonic() + 3
            for child in self.active.values():
                stop_session(child["process"], max(0, deadline - time.monotonic()), send_term=False)
            self.collect()


def probe():
    try:
        marker = Path(os.environ["EDGEAI_WORK_DIR"]) / ".vd-ready"
        with marker.open("rb") as stream:
            raw = stream.read(4097)
        if len(raw) > 4096:
            return 1
        data = json_decode(raw)
        return 0 if data["runtimeId"] == os.environ["EDGEAI_VD_RUNTIME_ID"] and data["podUid"] == os.environ["EDGEAI_POD_UID"] and \
            data["generation"] == int(os.environ["EDGEAI_VD_GENERATION"]) and float(data["leaseDeadlineMonotonic"]) > time.monotonic() else 1
    except Exception:
        return 1


def main():
    if sys.argv[1:] == ["--ready"]:
        return probe()
    signal.signal(signal.SIGTERM, lambda *_: stopping.set())
    signal.signal(signal.SIGINT, lambda *_: stopping.set())
    try:
        return Supervisor().run()
    except RunnerError as error:
        print("VD_STOPPED " + error.code, flush=True)
        return 1
    except Exception:
        print("VD_STOPPED VD_INTERNAL_ERROR", flush=True)
        return 1
