"""Authenticated discovery, lease renewal and persistent stream calculation owner.

Port/generation mappings and run identity must come from the control plane. This
does not seal a Result or infer that a locally settled stream may exit.
"""
from dataclasses import replace
import math
import os
from pathlib import Path
import stat
import threading
import time

from edgeai_runner.main import port_name
from edgeai_runner.stream_assignment import AssignmentUnavailable, BindingClient
from edgeai_runner.stream_checkpoint import capture, confirm
from edgeai_runner.stream_journal import Journal, Limits
from edgeai_runner.stream_mqtt import Link
from edgeai_runner.stream_processor import Processor
from edgeai_runner.stream_protocol import _uuid


class SessionError(RuntimeError):
    """Fixed session reason code, without data or credentials."""


def require(condition, code='STREAM_INVALID_SESSION'):
    if not condition:
        raise SessionError(code)


class Session:
    def __init__(self, client, run_id, input_generations, output_generations, command,
                 directory, parameters=None, *, limits=Limits(), step_timeout=60,
                 timeout=3600, create=False, cancel=None, durability='LOCAL'):
        self.closed = False
        self.processor = self.link = self.journal = None
        self.cancel = cancel or threading.Event()
        require(isinstance(client, BindingClient) and client.actor.kind == 'TASK_ATTEMPT')
        _uuid(run_id)
        require(type(timeout) in (int, float) and math.isfinite(timeout) and 0 < timeout <= 86400)
        self.deadline = time.monotonic() + timeout
        self.client, self.run_id = client, run_id
        require(type(input_generations) is dict and 1 <= len(input_generations) <= 16)
        require(type(output_generations) is dict and len(output_generations) <= 16)
        for name in (*input_generations, *output_generations):
            port_name(name)
        require(all(type(values) in (list, tuple) and values for values in output_generations.values()))
        outputs = {port: tuple(ids) for port, ids in output_generations.items()}
        identities = list(input_generations.values()) + [identity for ids in outputs.values() for identity in ids]
        for identity in identities:
            _uuid(identity)
        require(len(identities) <= 32 and len(set(identities)) == len(identities))
        require(sum(map(len, outputs.values())) <= 16)
        directory = Path(directory)
        info = directory.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700,
                'STREAM_PRIVATE_SESSION_DIRECTORY_REQUIRED')
        self.assignments = {}
        self.sequence = {}
        self.next_at = {}
        self.retries = {}
        self.heartbeats = 0
        try:
            for identity in identities:
                self._check()
                a = client.fetch(identity, timeout=self._request_timeout())
                require(a.run_id == run_id, 'STREAM_FOREIGN_RUN')
                direction = 'CONSUMER' if identity in input_generations.values() else 'PRODUCER'
                require(a.direction == direction, 'STREAM_DIRECTION_MISMATCH')
                self.assignments[identity] = self._bounded(a)
                self.sequence[identity] = 0
                self.next_at[identity] = 0
                self.retries[identity] = 0
            self._check()
            self.journal = Journal(directory / 'journal',
                [a.binding for a in self.assignments.values() if a.direction == 'CONSUMER'],
                [a.binding for a in self.assignments.values() if a.direction == 'PRODUCER'], limits, create=create, durability=durability)
            self.link = Link.from_assignments(self.journal, list(self.assignments.values()))
            work = directory / 'workload'
            if create:
                work.mkdir(mode=0o700)
            self.processor = Processor(self.link, command, work,
                {port: self.assignments[identity].binding.route_id for port, identity in input_generations.items()},
                {port: [self.assignments[identity].binding.route_id for identity in ids] for port, ids in outputs.items()},
                parameters, step_timeout=step_timeout, create=create, cancel=self.cancel)
        except BaseException:
            self.close()
            raise

    def _bounded(self, assignment):
        return replace(assignment, deadline=min(self.deadline, assignment.deadline))

    def _check(self):
        require(not self.closed and not self.cancel.is_set(), 'STREAM_CANCELLED')
        require(time.monotonic() < self.deadline, 'STREAM_SESSION_TIMEOUT')
        for assignment in getattr(self, 'assignments', {}).values():
            assignment.remaining()

    def _request_timeout(self):
        remaining = min([self.deadline - time.monotonic()]
                        + [a.remaining() for a in self.assignments.values()])
        return max(.01, min(1., remaining / 4))

    @property
    def complete(self):
        return self.processor.complete

    @property
    def settled(self):
        return self.processor.settled

    def checkpoint(self):
        self._check()
        return capture(self.journal, self.processor.execution_sha256)

    def confirm_checkpoint(self, serial, sha256):
        """Caller must supply an authenticated, externally verified control-plane receipt."""
        self._check()
        confirm(self.journal, serial, sha256)

    def step(self):
        try:
            self._check()
            # MQTT, computation and control all share the owner thread. An independent
            # WorkloadProcess watchdog still fences computation if HTTP itself stalls.
            self.processor.step()
            identity = min(self.next_at, key=lambda key: (self.next_at[key], self.assignments[key].deadline, key))
            if time.monotonic() < self.next_at[identity]:
                return
            sequence = self.sequence[identity]
            try:
                reply = self.client.heartbeat(identity, sequence, timeout=self._request_timeout())
            except AssignmentUnavailable:
                self._check()
                self.retries[identity] += 1
                backoff = min(1., .05 * 2 ** min(self.retries[identity], 5))
                self.next_at[identity] = time.monotonic() + min(backoff, self._request_timeout())
                # Same sequence is retried: a response may have been lost after commit.
                return
            self._check()
            current = dict(self.assignments)
            current[identity] = self._bounded(reply.assignment)
            self.processor.refresh(list(current.values()))
            self.assignments = current
            require(reply.sequence < 9007199254740991, 'STREAM_HEARTBEAT_EXHAUSTED')
            self.sequence[identity] = reply.sequence + 1
            self.retries[identity] = 0
            self.heartbeats += 1
            # Sequence0 only resumes the counter. Record our first real observation
            # immediately, then schedule against the current conservative lease.
            self.next_at[identity] = time.monotonic() + (0 if sequence == 0 else min(1., current[identity].remaining() / 4))
            self.processor.step()
        except BaseException as failure:
            # The deadline can pass inside MQTT, HTTP or a model step after the
            # initial check. Report the owning session's terminal reason consistently.
            reason = ('STREAM_CANCELLED' if self.cancel.is_set() else
                      'STREAM_SESSION_TIMEOUT' if time.monotonic() >= self.deadline else None)
            self.close()
            if reason is not None and isinstance(failure, Exception):
                raise SessionError(reason) from None
            raise

    def close(self):
        if not self.closed:
            self.closed = True
            try:
                if self.processor is not None:
                    self.processor.close()
                elif self.link is not None:
                    self.link.close(force=True)
            finally:
                if self.journal is not None:
                    self.journal.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
