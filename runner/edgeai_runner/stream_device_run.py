"""One Device Session follows its pinned Run routes across group retries.

This explicit owner retains the same LOCAL source volume. Discovery is metadata;
every replacement transport needs fresh authenticated assignments. No sensor data
is accepted while reconnecting and a different session never inherits this owner.
"""
import fcntl
import math
import os
from pathlib import Path
import stat
import threading
import time

from edgeai_runner.stream_assignment import AssignmentExpired, AssignmentFenced, AssignmentUnavailable, BindingClient
from edgeai_runner.stream_journal import Backpressure, Limits
from edgeai_runner.stream_mqtt import MqttAuthorityExpired
from edgeai_runner.stream_protocol import Binding, _uuid
from edgeai_runner.stream_source import DeviceSource, SourceError, require
from edgeai_runner import stream_source_completion as completion_intent


class SourceReconnecting(Backpressure):
    """No sample was committed. Retain it and retry after step() becomes ready."""


class DeviceRunSource:
    def __init__(self, client, run_id, route_ids, directory, *, create=False, limits=Limits(),
                 timeout=3600, cancel=None):
        self.closed = False
        self.source = None
        self.lock = None
        self.completed = False
        self.connections = 0
        require(isinstance(client, BindingClient) and client.actor.kind == 'DEVICE_SESSION')
        _uuid(run_id)
        require(type(route_ids) in (list, tuple) and 1 <= len(route_ids) <= 16)
        for identity in route_ids:
            _uuid(identity)
        require(len(set(route_ids)) == len(route_ids) and type(create) is bool and type(limits) is Limits)
        require(type(timeout) in (int, float) and math.isfinite(timeout) and 0 < timeout <= 86400)
        self.client, self.run_id, self.route_ids = client, run_id, frozenset(route_ids)
        self.directory, self.create, self.limits = Path(directory), create, limits
        self.cancel = cancel or threading.Event()
        self.deadline = time.monotonic() + timeout
        self.next_at, self.retries = 0, 0
        self.offset, self.rows, self.last_route = 0, {}, None
        try:
            info = self.directory.lstat()
            require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700,
                    'STREAM_PRIVATE_SOURCE_DIRECTORY_REQUIRED')
            self.lock = os.open(self.directory/'run-owner.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            info = os.fstat(self.lock)
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == os.getuid()
                    and stat.S_IMODE(info.st_mode) == 0o600, 'STREAM_PRIVATE_SOURCE_DIRECTORY_REQUIRED')
            try:
                fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise SourceError('STREAM_SOURCE_ALREADY_OWNED') from None
            exists = (self.directory/'journal').exists()
            require(exists != create, 'STREAM_EXPLICIT_SOURCE_RECOVERY_REQUIRED')
            self.intent = completion_intent.read(self.directory, run_id, client.actor, limits)
            require(not create or self.intent is None, 'STREAM_EXPLICIT_COMPLETION_RECOVERY_REQUIRED')
        except BaseException:
            self.close()
            raise

    @property
    def ready(self):
        return not self.closed and self.source is not None and not self.source.closed

    def _check(self):
        require(not self.closed and not self.cancel.is_set(), 'STREAM_SOURCE_CANCELLED')
        require(time.monotonic() < self.deadline, 'STREAM_SOURCE_TIMEOUT')

    def _waiting(self):
        if self.source is not None:
            self.source.close()
            self.source = None
        # The child may have committed its initial journal or a handover just
        # before authority expired. Reopen that journal; never recreate it.
        if (self.directory/'journal').exists():
            self.create = False
        self.offset, self.rows, self.last_route = 0, {}, None
        self.retries += 1
        self.next_at = time.monotonic() + min(1., .05*2**min(self.retries, 5))

    def _discover(self):
        timeout = max(.01, min(1., (self.deadline-time.monotonic())/4))
        page = self.client.device_routes(self.run_id, offset=self.offset, timeout=timeout)
        self._check()
        require(page['runState'] not in ('CANCELLING', 'CANCELLED', 'FAILED'), 'STREAM_SOURCE_RUN_TERMINATED')
        if page['items']:
            require(self.last_route is None or page['items'][0]['routeId'] > self.last_route, 'STREAM_SOURCE_SCOPE_MISMATCH')
            self.last_route = page['items'][-1]['routeId']
        self.rows.update((row['routeId'], row) for row in page['items'] if row['routeId'] in self.route_ids)
        if self.rows.keys() != self.route_ids and page['nextOffset'] is not None:
            self.offset = page['nextOffset']
            return  # At most one page per step; cancellation is checked between pages.
        require(self.rows.keys() == self.route_ids, 'STREAM_SOURCE_SCOPE_MISMATCH')
        rows = list(self.rows.values())
        self.intent = completion_intent.read(self.directory, self.run_id, self.client.actor, self.limits)
        same_intent = self.intent is not None and {r['routeId']:r['generationId'] for r in self.intent['routes']} == {
            r['routeId']:r['generation']['id'] if r['generation'] else None for r in rows}
        # A closed generation can still have a durable FINALIZE grant. Recover
        # that intent without granting MQTT authority or inferring success.
        if not same_intent:
            require(page['runState'] != 'SUCCEEDED', 'STREAM_SOURCE_COMPLETION_MISSING')
            if page['runState'] != 'RUNNING' or any(r['generation'] is None or r['generation']['state'] != 'ACTIVE' for r in rows):
                self._waiting()
                return
        bindings = {r['generation']['id']: Binding(r['routeId'], r['generation']['number'], self.client.actor) for r in rows}
        require(len(bindings) == len(rows), 'STREAM_SOURCE_SCOPE_MISMATCH')
        remaining = self.deadline-time.monotonic()
        self._check()
        try:
            self.source = DeviceSource(self.client, self.run_id, list(bindings), self.directory, limits=self.limits,
                                       create=self.create, handover=not self.create and not same_intent,
                                       timeout=remaining, cancel=self.cancel, expected_bindings=bindings)
        except AssignmentFenced as error:
            if error.status != 409:
                raise
            # The page can race route revocation. Re-discover; no stale page can
            # authorize a journal handover or a new transport.
            self._waiting()
            return
        self.create = False
        self.connections += 1
        self.retries = 0
        self.offset, self.rows, self.last_route = 0, {}, None

    def step(self):
        try:
            self._check()
            if self.completed:
                return
            if self.source is None:
                if time.monotonic() >= self.next_at:
                    self._discover()
                return
            self.source.step()
            self.completed = self.source.completed
        except AssignmentFenced as error:
            # Discovery 409 is a replaced pinned Device Session and is terminal.
            # Assignment/heartbeat 409 can be an old route during group retry.
            if error.status != 409 or self.source is None:
                self.close()
                raise
            self._waiting()
        except (AssignmentExpired, MqttAuthorityExpired, AssignmentUnavailable):
            self._waiting()
        except BaseException:
            self.close()
            raise

    def _active(self):
        self._check()
        if not self.ready:
            raise SourceReconnecting('STREAM_SOURCE_RECONNECTING')
        return self.source

    def _call(self, operation, *args):
        try:
            return getattr(self._active(), operation)(*args)
        except Backpressure:
            raise
        except (AssignmentExpired, MqttAuthorityExpired):
            self._waiting()
            raise SourceReconnecting('STREAM_SOURCE_RECONNECTING') from None
        except BaseException:
            self.close()
            raise

    def emit(self, emissions, state=None):
        return self._call('emit', emissions, state)

    def checkpoint(self):
        return self._call('checkpoint')

    def close(self):
        if not self.closed:
            self.closed = True
            try:
                if self.source is not None:
                    self.source.close()
            finally:
                if self.lock is not None:
                    os.close(self.lock)
                    self.lock = None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
