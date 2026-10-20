"""Authenticated Task input owner; cursor and emissions share one external journal commit."""
import re
import time

from edgeai_runner.main import json_decode, json_encode
from edgeai_runner.stream_journal import Backpressure, Emission
from edgeai_runner.stream_processor import execution_digest, pin_execution
from edgeai_runner.stream_protocol import Frame
from edgeai_runner.stream_workload import WorkloadError


def require(value):
    if not value:
        raise WorkloadError('STREAM_INVALID_SOURCE_RESPONSE')


class TaskSource:
    def __init__(self, link, directory, outputs, parameters, poll, request_timeout,
                 *, step_timeout=60, create=False, cancel):
        self.link, self.journal = link, link.journal
        self.cancel, self.poll, self.request_timeout = cancel, poll, request_timeout
        self.closed = False
        self.next_poll = 0
        self.candidate = None
        require(not self.journal.inputs and set(outputs) == {'sample'} and outputs['sample'])
        self.routes = tuple(outputs['sample'])
        require(set(self.routes) == set(self.journal.outputs) and len(self.routes) == len(set(self.routes)))
        self.by_route = {a.binding.route_id: a for a in link.assignments}
        require(set(self.routes) == set(self.by_route) and self.journal.route_frames >= 2)
        for a in link.assignments:
            require(a.actor.kind == 'TASK_ATTEMPT' and a.clock is time.monotonic
                    and a.media_type == 'application/json' and a.max_payload_bytes <= 4096)
            size = len(Frame(a.binding, 9007199254740990, 'DATA', b'\0' * a.max_payload_bytes, a.media_type).encode())
            size += len(Frame(a.binding, 9007199254740991, 'END', b'', None).encode())
            require(size <= self.journal.route_bytes)
        self.execution_sha256 = execution_digest(['edgeai-workflow-input'], parameters, {}, outputs, step_timeout)
        pin_execution(self.journal, self.execution_sha256, create, require)
        saved = self.journal.checkpoint().state
        self.state = json_decode(saved) if saved else {'lastId': None, 'count': 0, 'lastSample': None, 'ended': False}
        require(type(self.state) is dict and set(self.state) == {'lastId', 'count', 'lastSample', 'ended'}
                and type(self.state['ended']) is bool and type(self.state['count']) is int
                and 0 <= self.state['count'] <= 9007199254740991)
        self._link_guard = self.journal.authority_guard
        require(self._link_guard is not None)
        self.journal.authority_guard = self._authority

    def _authority(self):
        if self.closed or self.cancel.is_set():
            raise WorkloadError('STREAM_CANCELLED')
        self._link_guard()

    def refresh(self, assignments):
        self._authority()
        self.link.refresh(assignments)
        self.by_route = {a.binding.route_id: a for a in assignments}

    @property
    def complete(self):
        return self.state['ended']

    @property
    def settled(self):
        return self.complete and not self.journal.outgoing()

    def step(self):
        try:
            self._authority()
            self.link.step(.001)
            self._authority()
            if self.candidate is not None:
                revision, state, emitted = self.candidate
                try:
                    self.journal.commit(revision, [], json_encode(state), emitted)
                except Backpressure:
                    return
                self.state = state
                self.candidate = None
            if self.complete or time.monotonic() < self.next_poll:
                return
            value = self.poll(self.request_timeout())
            self._authority()
            self.next_poll = time.monotonic() + 1
            require(type(value) is dict and value.get('state') in ('WAITING', 'DATA', 'END'))
            if value['state'] == 'WAITING':
                require(set(value) == {'state'})
                return
            state = dict(self.state)
            if value['state'] == 'END':
                require(set(value) == {'state'})
                state['ended'] = True
                emitted = [Emission(r, b'', None, 'END') for r in self.routes]
            else:
                require(set(value) == {'state', 'sampleId', 'sample'} and type(value['sampleId']) is str
                        and re.fullmatch('[a-f0-9]{64}', value['sampleId']) and type(value['sample']) is dict)
                if value['sampleId'] == state['lastId']:
                    return
                payload = json_encode(value['sample'])
                require(all(len(payload) <= a.max_payload_bytes for a in self.by_route.values())
                        and state['count'] < 9007199254740991)
                state.update(lastId=value['sampleId'], lastSample=value['sample'], count=state['count'] + 1)
                emitted = [Emission(r, payload, 'application/json') for r in self.routes]
            require(len(json_encode(state)) <= self.journal.limits.max_state_bytes)
            self.candidate = self.journal.checkpoint().revision, state, emitted
        except BaseException:
            self.close()
            raise

    def close(self):
        if not self.closed:
            self.closed = True
            self.link.close(force=True)
