"""Authenticated Device Session publisher with a persistent, bounded local outbox.

The sensor adapter supplies samples and its opaque resume state. This owner never
claims that a lost source volume or a new Device Session can reuse old sequences.
"""
from dataclasses import replace
import math
import os
from pathlib import Path
import stat
import threading
import time

from edgeai_runner.stream_assignment import AssignmentUnavailable, BindingClient
from edgeai_runner.stream_journal import Backpressure, Emission, Journal, Limits
from edgeai_runner.stream_mqtt import Link
from edgeai_runner.stream_protocol import Frame, _uuid


class SourceError(RuntimeError):
    """Fixed source reason code, without credentials or sample contents."""


def require(condition, code='STREAM_INVALID_DEVICE_SOURCE'):
    if not condition:
        raise SourceError(code)


class DeviceSource:
    def __init__(self, client, run_id, generation_ids, directory, *, limits=Limits(),
                 create=False, handover=False, timeout=3600, cancel=None):
        self.closed = False
        self.link = self.journal = None
        self.cancel = cancel or threading.Event()
        require(isinstance(client, BindingClient) and client.actor.kind == 'DEVICE_SESSION')
        _uuid(run_id)
        require(type(generation_ids) in (list, tuple) and 1 <= len(generation_ids) <= 16)
        for identity in generation_ids:
            _uuid(identity)
        require(len(set(generation_ids)) == len(generation_ids))
        require(type(handover) is bool and (not handover or create is False), 'STREAM_EXPLICIT_SOURCE_HANDOVER_REQUIRED')
        require(type(timeout) in (int, float) and math.isfinite(timeout) and 0 < timeout <= 86400)
        self.deadline = time.monotonic() + timeout
        self.client, self.run_id = client, run_id
        directory = Path(directory)
        info = directory.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700,
                'STREAM_PRIVATE_SOURCE_DIRECTORY_REQUIRED')
        self.assignments, self.sequence, self.next_at, self.retries = {}, {}, {}, {}
        self.heartbeats = 0
        try:
            for identity in generation_ids:
                self._check()
                a = client.fetch(identity, timeout=self._request_timeout())
                require(a.run_id == run_id and a.direction == 'PRODUCER', 'STREAM_SOURCE_SCOPE_MISMATCH')
                self.assignments[identity] = replace(a, deadline=min(self.deadline, a.deadline))
                self.sequence[identity] = self.next_at[identity] = self.retries[identity] = 0
            self._check()
            # Validate all connection and route constraints before changing a journal.
            first = next(iter(self.assignments.values()))
            require(all(a.actor == first.actor and a.connection == first.connection for a in self.assignments.values()),
                    'STREAM_SOURCE_BROKER_MISMATCH')
            outputs = [a.binding for a in self.assignments.values()]
            self.journal = Journal(directory/'journal', [], outputs, limits, create=create,
                                   source_handover=handover, guard=self._check)
            self._check()
            self.journal.authority_guard = None
            self.link = Link.from_assignments(self.journal, list(self.assignments.values()))
            # Also check cancellation at both ends of every local transaction.
            self.journal.authority_guard = self._check
        except BaseException:
            self.close()
            raise

    def _check(self):
        require(not self.closed and not self.cancel.is_set(), 'STREAM_SOURCE_CANCELLED')
        require(time.monotonic() < self.deadline, 'STREAM_SOURCE_TIMEOUT')
        for assignment in self.assignments.values():
            assignment.remaining()

    def _request_timeout(self):
        remaining = min([self.deadline-time.monotonic()] + [a.remaining() for a in self.assignments.values()])
        return max(.01, min(1., remaining/4))

    def checkpoint(self):
        """Local adapter state and generated cursors; not a platform Result receipt."""
        self._check()
        return self.journal.checkpoint()

    @property
    def settled(self):
        self._check()
        return self.journal.usage()[0] == 0 and all(row[0] for row in self.journal.db.execute('SELECT ended FROM route'))

    def emit(self, emissions, state=None):
        """Persist adapter state and at most one new sample per route atomically.

        Backpressure leaves the source open and all supplied changes unapplied.
        The adapter must retain its sample/cursor and retry after downstream progress.
        """
        try:
            self._check()
            require(type(emissions) in (list, tuple) and 1 <= len(emissions) <= len(self.assignments))
            require(all(type(e) is Emission for e in emissions))
            require(len({e.route_id for e in emissions}) == len(emissions))
            current = self.journal.checkpoint()
            assignments = {a.binding.route_id:a for a in self.assignments.values()}
            for item in emissions:
                require(item.route_id in assignments, 'STREAM_SOURCE_FOREIGN_ROUTE')
                a = assignments[item.route_id]
                a.verify_frame(Frame(a.binding, current.output_sequences[item.route_id]+1,
                                     item.kind, item.payload, item.media_type))
            return self.journal.commit(current.revision, [], current.state if state is None else state, emissions)
        except Backpressure:
            raise
        except BaseException:
            self.close()
            raise

    def _heartbeat(self):
        identity = min(self.next_at, key=lambda key:(self.next_at[key], self.assignments[key].deadline, key))
        if time.monotonic() < self.next_at[identity]:
            return
        sequence = self.sequence[identity]
        try:
            reply = self.client.heartbeat(identity, sequence, timeout=self._request_timeout())
        except AssignmentUnavailable:
            self._check()
            self.retries[identity] += 1
            self.next_at[identity] = time.monotonic() + min(1., .05*2**min(self.retries[identity],5), self._request_timeout())
            return
        self._check()
        current = dict(self.assignments)
        current[identity] = replace(reply.assignment, deadline=min(self.deadline, reply.assignment.deadline))
        self.link.refresh(list(current.values()))
        self.assignments = current
        require(reply.sequence < 9007199254740991, 'STREAM_HEARTBEAT_EXHAUSTED')
        self.sequence[identity] = reply.sequence+1
        self.retries[identity] = 0
        self.heartbeats += 1
        self.next_at[identity] = time.monotonic() + (0 if sequence == 0 else min(1., current[identity].remaining()/4))

    def step(self):
        try:
            self._check()
            self._heartbeat()
            self._check()
            self.link.step(.001)
        except BaseException:
            self.close()
            raise

    def close(self):
        if not self.closed:
            self.closed = True
            try:
                if self.link is not None:
                    self.link.close(force=True)
            finally:
                if self.journal is not None:
                    self.journal.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
