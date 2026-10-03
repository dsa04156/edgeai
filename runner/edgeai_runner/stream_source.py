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

from edgeai_runner.stream_assignment import AssignmentError, AssignmentUnavailable, BindingClient
from edgeai_runner.stream_journal import Backpressure, Emission, Journal, Limits
from edgeai_runner.stream_mqtt import Link, MqttError
from edgeai_runner.stream_protocol import Binding, Frame, Producer, _uuid
from edgeai_runner import stream_source_completion as completion_intent


class SourceError(RuntimeError):
    """Fixed source reason code, without credentials or sample contents."""


def require(condition, code='STREAM_INVALID_DEVICE_SOURCE'):
    if not condition:
        raise SourceError(code)


class DeviceSource:
    def __init__(self, client, run_id, generation_ids, directory, *, limits=Limits(),
                 create=False, handover=False, timeout=3600, cancel=None, completion=True, expected_bindings=None):
        self.closed = False
        self.link = self.journal = None
        self.cancel = cancel or threading.Event()
        require(isinstance(client, BindingClient) and client.actor.kind == 'DEVICE_SESSION')
        _uuid(run_id)
        require(type(generation_ids) in (list, tuple) and 1 <= len(generation_ids) <= 16)
        for identity in generation_ids:
            _uuid(identity)
        require(len(set(generation_ids)) == len(generation_ids))
        if expected_bindings is not None:
            require(type(expected_bindings) is dict and set(expected_bindings) == set(generation_ids)
                    and all(type(b) is Binding and b.producer == client.actor for b in expected_bindings.values()),
                    'STREAM_SOURCE_SCOPE_MISMATCH')
        require(type(handover) is bool and (not handover or create is False), 'STREAM_EXPLICIT_SOURCE_HANDOVER_REQUIRED')
        require(type(timeout) in (int, float) and math.isfinite(timeout) and 0 < timeout <= 86400)
        require(type(completion) is bool)
        self.deadline = time.monotonic() + timeout
        self.client, self.run_id = client, run_id
        directory = Path(directory)
        info = directory.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700,
                'STREAM_PRIVATE_SOURCE_DIRECTORY_REQUIRED')
        self.assignments, self.sequence, self.next_at, self.retries = {}, {}, {}, {}
        self.heartbeats = 0
        self.completion_enabled, self.completed = completion, False
        self.intent = None
        self.transport_stopped = False
        self.recovery_probe = False
        self.completion_next, self.completion_retries, self.completion_done = {}, {}, set()
        self.directory = directory
        try:
            previous = completion_intent.read(directory, run_id, client.actor, limits)
            if previous is not None:
                require(completion and not create, 'STREAM_EXPLICIT_COMPLETION_RECOVERY_REQUIRED')
                if not handover:
                    require(set(generation_ids) == {r['generationId'] for r in previous['routes']}, 'STREAM_SOURCE_SCOPE_MISMATCH')
                    outputs = [Binding(b['routeId'],b['generation'],Producer.parse(b['producer'])) for b in previous['manifest']['outputs']]
                    require(expected_bindings is None or set(outputs) == set(expected_bindings.values()), 'STREAM_SOURCE_SCOPE_MISMATCH')
                    self.journal = Journal(directory/'journal', [], outputs, limits, guard=self._check_owner)
                    completion_intent.verify(previous, self.journal)
                    self._set_intent(previous)
                    self.transport_stopped = True
                    self.recovery_probe = True
                    return  # Only authenticated completion polling; never recreate an expired MQTT lease.
            for identity in generation_ids:
                self._check()
                a = client.fetch(identity, timeout=self._request_timeout())
                require(a.run_id == run_id and a.direction == 'PRODUCER', 'STREAM_SOURCE_SCOPE_MISMATCH')
                require(expected_bindings is None or a.binding == expected_bindings[identity], 'STREAM_SOURCE_SCOPE_MISMATCH')
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
            if previous is not None:
                completion_intent.verify_cursors(previous, self.journal)
                self._capture_completion()  # Explicit, authenticated generation handover preserves terminal cursors.
        except BaseException:
            self.close()
            raise

    def _check_owner(self):
        require(not self.closed and not self.cancel.is_set(), 'STREAM_SOURCE_CANCELLED')
        require(time.monotonic() < self.deadline, 'STREAM_SOURCE_TIMEOUT')

    def _check(self):
        self._check_owner()
        if self.transport_stopped:
            return
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

    def _set_intent(self, value):
        self.intent = value
        for row in value['routes']:
            self.completion_next[row['generationId']] = self.completion_retries[row['generationId']] = 0

    def _capture_completion(self):
        if self.completion_enabled and self.intent is None and self.settled:
            self._set_intent(completion_intent.write(self.directory,self.run_id,self.client.actor,self.assignments,self.journal))

    def _stop_transport(self):
        if not self.transport_stopped:
            if self.link is not None:
                self.link.close(force=True)
            self.transport_stopped = True
            self.journal.authority_guard = self._check_owner

    def _resume_transport(self):
        # A persisted intent may still be WAITING after an explicit process restart.
        # Fetch fresh authority only then; ordinary expiry never revives this Link.
        current = {}
        try:
            for row in self.intent['routes']:
                self._check_owner()
                a = self.client.fetch(row['generationId'],timeout=max(.01,min(1.,(self.deadline-time.monotonic())/4)))
                require(a.run_id == self.run_id and a.direction == 'PRODUCER'
                        and a.binding == self.journal.outputs[row['routeId']], 'STREAM_SOURCE_SCOPE_MISMATCH')
                current[a.generation_id] = replace(a,deadline=min(self.deadline,a.deadline))
        except AssignmentError:
            # A grant and peer route closure may race this fresh lookup. Poll the
            # same durable intent again; it cannot grant data-plane authority.
            return
        first = next(iter(current.values()))
        require(all(a.connection == first.connection for a in current.values()), 'STREAM_SOURCE_BROKER_MISMATCH')
        self.assignments = current
        self.transport_stopped = False
        try:
            self._check()
            self.journal.authority_guard = None
            self.link = Link.from_assignments(self.journal,list(current.values()))
        except (AssignmentError,MqttError):
            # Fresh authority may expire before Link construction. Preserve only
            # the terminal intent, so an already-issued grant can still be read.
            self._stop_transport()
            self._check_owner()
            return
        self.journal.authority_guard = self._check
        for identity in current:
            self.sequence[identity] = self.next_at[identity] = self.retries[identity] = 0
        self.recovery_probe = False

    def _complete(self, *, force=False):
        self._check_owner()
        pending = [r for r in self.intent['routes'] if r['generationId'] not in self.completion_done]
        if not pending:
            self.completed = True
            self._stop_transport()
            return
        row = min(pending,key=lambda r:(self.completion_next[r['generationId']],r['generationId']))
        identity = row['generationId']
        if not force and time.monotonic() < self.completion_next[identity]:
            return
        timeout = max(.01,min(1.,(self.deadline-time.monotonic())/4))
        if not self.transport_stopped:
            # Keep network polling short enough to service live bilateral heartbeats.
            timeout = max(.01,min(timeout,min(a.deadline-time.monotonic() for a in self.assignments.values())/4))
        try:
            reply = self.client.complete(identity,row['sequence'],timeout=timeout)
        except AssignmentUnavailable:
            self._check_owner()
            self.completion_retries[identity] += 1
            delay = min(1.,.05*2**min(self.completion_retries[identity],5))
        else:
            self._check_owner()
            self.completion_retries[identity] = 0
            if reply.state == 'FINALIZE':
                self.completion_done.add(identity)
            elif self.recovery_probe:
                self._resume_transport()
            delay = .1
        self.completion_next[identity] = time.monotonic()+delay
        if len(self.completion_done) == len(self.intent['routes']):
            self.completed = True
            self._stop_transport()

    def emit(self, emissions, state=None):
        """Persist adapter state and at most one new sample per route atomically.

        Backpressure leaves the source open and all supplied changes unapplied.
        The adapter must retain its sample/cursor and retry after downstream progress.
        """
        try:
            self._check()
            require(self.intent is None, 'STREAM_SOURCE_TERMINAL')
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
            self._check_owner()
            if self.completed:
                return
            if self.intent is not None:
                self._complete()
                if self.completed or self.transport_stopped:
                    return
            try:
                self._check()
                self._heartbeat()
                self._check()
                self.link.step(.001)
            except (AssignmentError,MqttError):
                if self.intent is None:
                    raise
                self._stop_transport()
                self._complete(force=True)
                return
            self._capture_completion()
            if self.intent is not None:
                self._complete()
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
