"""Bounded persistent computation over nonblocking pipes, with an independent lease watchdog.

Workload stdout is a protocol channel; stderr and credentials never reach logs.
This owns only its child session (or workload group inside a supervised VD Runner).
"""
import ctypes
import math
import os
from pathlib import Path
import signal
import subprocess
import threading
import time

MAX_MESSAGE = 12 * 1024 * 1024


class WorkloadError(RuntimeError):
    """Fixed reason code, without workload data or diagnostics."""


def validate_command(command):
    if (type(command) not in (list, tuple) or not 1 <= len(command) <= 160
            or any(type(v) is not str or not v or len(v) > 4096 or '\0' in v for v in command)):
        raise WorkloadError('STREAM_INVALID_COMMAND')
    return tuple(command)


class WorkloadProcess:
    def __init__(self, command, directory, lease_deadline, *, cancel=None):
        command = validate_command(command)
        if type(lease_deadline) not in (int, float) or not math.isfinite(lease_deadline) or lease_deadline <= time.monotonic():
            raise WorkloadError('STREAM_LEASE_EXPIRED')
        # Reap only this child's adopted descendants, including in host tests.
        if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
            raise WorkloadError('STREAM_PROCESS_SUPERVISION_UNAVAILABLE')
        self.cancel = cancel or threading.Event()
        self._lock = threading.Lock()
        self._cleanup_lock = threading.Lock()
        self._terminated = False
        self._cleanup_error = None
        self._closed = threading.Event()
        self._wake = threading.Event()
        self.reason = None
        self.lease_deadline = lease_deadline
        self.request_deadline = None
        self.active = False
        self._sending = b''
        self._offset = 0
        self._received = bytearray()
        self.supervised = os.environ.get('EDGEAI_VD_SUPERVISED') == 'true'
        if self.supervised and os.getsid(0) != os.getpid():
            raise WorkloadError('STREAM_INVALID_SUPERVISED_SESSION')
        self.scope_id = os.getsid(0) if self.supervised else None
        # Preserve interpreter/accelerator lookup, not control-plane/storage credentials.
        env = {k: os.environ[k] for k in ('PATH', 'LANG', 'LC_ALL', 'LD_LIBRARY_PATH', 'PYTHONPATH') if k in os.environ}
        env.update(PYTHONDONTWRITEBYTECODE='1', PYTHONUNBUFFERED='1')
        try:
            self.process = subprocess.Popen(command, cwd=directory, env=env, stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0,
                start_new_session=not self.supervised, process_group=0 if self.supervised else None)
            if not self.supervised:
                self.scope_id = self.process.pid
            os.set_blocking(self.process.stdin.fileno(), False)
            os.set_blocking(self.process.stdout.fileno(), False)
        except OSError:
            if hasattr(self, 'process'):
                try:
                    self._terminate()
                finally:
                    self.process.stdin.close()
                    self.process.stdout.close()
            raise WorkloadError('STREAM_WORKLOAD_START_FAILED') from None
        self.thread = threading.Thread(target=self._watch, name='edgeai-stream-watchdog', daemon=True)
        self.thread.start()

    def _reason_now(self):
        if self.reason:
            return self.reason
        if self.cancel.is_set() or self._closed.is_set():
            return 'STREAM_CANCELLED'
        now = time.monotonic()
        if now >= self.lease_deadline:
            return 'STREAM_LEASE_EXPIRED'
        if self.request_deadline is not None and now >= self.request_deadline:
            return 'STREAM_WORKLOAD_TIMEOUT'
        return None

    def _trip(self, reason):
        with self._lock:
            self.reason = self.reason or reason
        self._wake.set()

    def check(self):
        with self._lock:
            reason = self._reason_now()
        if reason is None and self.process.poll() is not None:
            reason = 'STREAM_WORKLOAD_EXITED'
        if reason:
            self._trip(reason)
            raise WorkloadError(reason)

    def renew(self, deadline):
        if type(deadline) not in (int, float) or not math.isfinite(deadline):
            raise WorkloadError('STREAM_INVALID_LEASE')
        with self._lock:
            reason = self._reason_now()
            if reason is None:
                self.lease_deadline = deadline
                reason = self._reason_now()
            if reason:
                self.reason = self.reason or reason
        self._wake.set()
        if reason:
            raise WorkloadError(reason)

    def _watch(self):
        while not self._closed.is_set():
            with self._lock:
                reason = self._reason_now()
            if reason is None and self.process.poll() is not None:
                reason = 'STREAM_WORKLOAD_EXITED'
            if reason:
                self._trip(reason)
                try:
                    self._terminate()
                except WorkloadError:
                    # close() reports the fixed cleanup failure to the owner.
                    pass
                return
            self._wake.wait(.02)
            self._wake.clear()

    def request(self, encoded, timeout):
        self.check()
        if (self.active or type(encoded) is not bytes or not 0 < len(encoded) <= MAX_MESSAGE
                or b'\n' in encoded or type(timeout) not in (int, float) or not 0 < timeout <= 3600):
            raise WorkloadError('STREAM_INVALID_WORKLOAD_REQUEST')
        self.active = True
        self._sending, self._offset = encoded + b'\n', 0
        self._received.clear()
        with self._lock:
            self.request_deadline = time.monotonic() + timeout
        self._wake.set()

    def step(self):
        self.check()
        try:
            # Bounded work per poll, so large messages cannot starve MQTT/control checks.
            if self._offset < len(self._sending):
                try:
                    self._offset += os.write(self.process.stdin.fileno(), self._sending[self._offset:self._offset + 65536])
                except BlockingIOError:
                    pass
            for _ in range(16):
                try:
                    chunk = os.read(self.process.stdout.fileno(), 65536)
                except BlockingIOError:
                    break
                if not chunk:
                    raise WorkloadError('STREAM_WORKLOAD_EXITED')
                if not self.active:
                    raise WorkloadError('STREAM_UNSOLICITED_RESPONSE')
                self._received.extend(chunk)
                if len(self._received) > MAX_MESSAGE + 1:
                    raise WorkloadError('STREAM_WORKLOAD_RESPONSE_TOO_LARGE')
                end = self._received.find(b'\n')
                if end >= 0:
                    if end != len(self._received) - 1 or self._offset != len(self._sending):
                        raise WorkloadError('STREAM_INVALID_WORKLOAD_RESPONSE')
                    encoded = bytes(self._received[:end])
                    self.active = False
                    self._received.clear()
                    self._sending = b''
                    with self._lock:
                        self.request_deadline = None
                    self.check()
                    return encoded
            self.check()
            return None
        except (OSError, ValueError):
            self._trip('STREAM_WORKLOAD_IO_FAILED')
            raise WorkloadError('STREAM_WORKLOAD_IO_FAILED') from None
        except WorkloadError as error:
            self._trip(str(error))
            raise

    def _members(self):
        result = []
        for item in Path('/proc').iterdir():
            if not item.name.isdecimal():
                continue
            pid = int(item.name)
            try:
                identity = os.getsid(pid)
                if pid != os.getpid() and identity == self.scope_id:
                    result.append(pid)
            except (ProcessLookupError, PermissionError):
                pass
        return result

    def _terminate(self):
        with self._cleanup_lock:
            if self._terminated:
                if self._cleanup_error:
                    raise WorkloadError(self._cleanup_error)
                return
            # Never signal this PID/PGID again after releasing its ownership.
            self._terminated = True
            try:
                self._terminate_owned()
            except (OSError, subprocess.TimeoutExpired):
                self._cleanup_error = 'STREAM_WORKLOAD_CLEANUP_FAILED'
                raise WorkloadError(self._cleanup_error) from None

    def _terminate_owned(self):
        # SIGKILL immediately fences computation; do not give it a post-lease grace period.
        try:
            os.killpg(self.process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        members = self._members()
        for pid in members:
            descriptor = None
            try:
                descriptor = os.pidfd_open(pid)
                identity = os.getsid(pid)
                if pid != os.getpid() and identity == self.scope_id:
                    signal.pidfd_send_signal(descriptor, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
            finally:
                if descriptor is not None:
                    os.close(descriptor)
        self.process.wait(timeout=3)
        end = time.monotonic() + 1
        pending = set(members) - {self.process.pid}
        while pending and time.monotonic() < end:
            for pid in tuple(pending):
                try:
                    done, _ = os.waitpid(pid, os.WNOHANG)
                    if done:
                        pending.remove(pid)
                except ChildProcessError:
                    pending.remove(pid)
            if pending:
                time.sleep(.005)

    def close(self):
        self._closed.set()
        self._wake.set()
        if hasattr(self, 'thread'):
            self.thread.join(timeout=5)
        try:
            self._terminate()
        finally:
            self.process.stdin.close()
            self.process.stdout.close()
