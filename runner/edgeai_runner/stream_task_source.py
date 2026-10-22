"""Authenticated Task input owner; cursor and emissions share one external journal commit.

Latest-value sources dedupe by sample identity. ALL_OBSERVATIONS sources page a
server-ordered feed: each item is committed with its own cursor, and the next
page is requested only from the committed cursor, so restart never skips or
repeats an emitted position.
"""
from collections import deque
import re
import time

from edgeai_runner.main import json_decode, json_encode
from edgeai_runner.stream_journal import Backpressure, Emission
from edgeai_runner.stream_processor import execution_digest, pin_execution
from edgeai_runner.stream_protocol import Frame
from edgeai_runner.stream_workload import WorkloadError


CURSOR = re.compile('[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|[1-9][0-9]{0,15})')
STATE = {'lastId', 'count', 'lastSample', 'ended'}


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
        self.pending = deque()
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
        require(type(self.state) is dict and set(self.state) in (STATE, STATE | {'cursor'})
                and type(self.state['ended']) is bool and type(self.state['count']) is int
                and 0 <= self.state['count'] <= 9007199254740991)
        require('cursor' not in self.state or type(self.state['cursor']) is str and CURSOR.fullmatch(self.state['cursor']))
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
            if self.complete:
                return
            if self.pending:
                return self._stage(self.pending.popleft())
            if time.monotonic() < self.next_poll:
                return
            # Only the committed cursor is ever presented; uncommitted items are refetched after restart.
            value = self.poll(self.request_timeout()) if 'cursor' not in self.state \
                else self.poll(self.request_timeout(), cursor=self.state['cursor'])
            self._authority()
            self.next_poll = time.monotonic() + 1
            require(type(value) is dict and value.get('state') in ('WAITING', 'DATA', 'END'))
            if value['state'] == 'WAITING':
                require(set(value) == {'state'})
                return
            if value['state'] == 'DATA' and 'items' in value:
                require(set(value) == {'state', 'items', 'more'} and type(value['more']) is bool
                        and type(value['items']) is list and 1 <= len(value['items']) <= 256)
                # A source pinned to latest polling never switches to a feed mid-run.
                require('cursor' in self.state or self.state['count'] == 0)
                self.pending.extend(value['items'])
                if value['more']:
                    self.next_poll = 0
                return self._stage(self.pending.popleft())
            state = dict(self.state)
            if value['state'] == 'END':
                require(set(value) == {'state'})
                state['ended'] = True
                emitted = [Emission(r, b'', None, 'END') for r in self.routes]
            else:
                require(set(value) == {'state', 'sampleId', 'sample'} and type(value['sampleId']) is str
                        and re.fullmatch('[a-f0-9]{64}', value['sampleId']) and type(value['sample']) is dict
                        and 'cursor' not in state)
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

    def _stage(self, item):
        require(type(item) is dict and set(item) == {'cursor', 'sampleId', 'sample'} and type(item['cursor']) is str
                and CURSOR.fullmatch(item['cursor']) and type(item['sampleId']) is str
                and re.fullmatch('[a-f0-9]{64}', item['sampleId']) and type(item['sample']) is dict)
        state = dict(self.state)
        if 'cursor' in state:
            subscription, position = state['cursor'].split(':')
            following, at = item['cursor'].split(':')
            require(following == subscription and int(at) > int(position))
        payload = json_encode(item['sample'])
        require(all(len(payload) <= a.max_payload_bytes for a in self.by_route.values())
                and state['count'] < 9007199254740991)
        state.update(cursor=item['cursor'], lastId=item['sampleId'], lastSample=item['sample'], count=state['count'] + 1)
        require(len(json_encode(state)) <= self.journal.limits.max_state_bytes)
        self.candidate = self.journal.checkpoint().revision, state, [Emission(r, payload, 'application/json') for r in self.routes]

    def close(self):
        if not self.closed:
            self.closed = True
            self.link.close(force=True)
