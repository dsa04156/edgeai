"""Drive one authenticated stream actor, persistent model process and atomic journal.

The caller supplies control-plane port mappings and renews through authenticated
heartbeat responses. Local completion is not a sealed Result or permission to exit.
"""
import base64
import hashlib
import os
from pathlib import Path
import stat
import threading
import time

from edgeai_runner.main import RunnerError, json_decode, json_encode, port_name
from edgeai_runner.stream_journal import Backpressure, Emission
from edgeai_runner.stream_protocol import Frame
from edgeai_runner.stream_workload import MAX_MESSAGE, WorkloadError, WorkloadProcess, validate_command

VERSION = 'edgeai.stream-workload/v1'


def require(value):
    if not value:
        raise WorkloadError('STREAM_INVALID_WORKLOAD_RESPONSE')


def blob(value, maximum):
    require(type(value) is str and len(value) <= 4 * ((maximum + 2) // 3))
    try:
        decoded = base64.b64decode(value, validate=True)
        require(len(decoded) <= maximum and base64.b64encode(decoded).decode('ascii') == value)
        return decoded
    except (ValueError, UnicodeError):
        raise WorkloadError('STREAM_INVALID_WORKLOAD_RESPONSE') from None


class Processor:
    def __init__(self, link, command, directory, input_ports, output_ports, parameters=None,
                 *, step_timeout=60, create=False, cancel=None):
        self.link, self.journal = link, link.journal
        self.command, self.directory = validate_command(command), Path(directory)
        self.cancel = cancel or threading.Event()
        self.workload = None
        self.closed = False
        self.requests = 0
        self._request = None
        self._candidate = None
        self._waiting = None
        self.step_timeout = step_timeout
        self.parameters = {} if parameters is None else parameters
        require(type(step_timeout) in (int, float) and 0 < step_timeout <= 3600)
        require(type(input_ports) is dict and 1 <= len(input_ports) <= 16 and type(output_ports) is dict and len(output_ports) <= 16)
        require(type(self.parameters) is dict and len(json_encode(self.parameters)) <= 262144)
        self.inputs = dict(sorted(input_ports.items()))
        require(all(type(routes) in (list, tuple) for routes in output_ports.values()))
        self.outputs = {p: tuple(routes) for p, routes in sorted(output_ports.items())}
        try:
            for name in (*self.inputs, *self.outputs):
                port_name(name)
        except RunnerError:
            raise WorkloadError('STREAM_INVALID_PORT_MAPPING') from None
        require(set(self.inputs.values()) == set(self.journal.inputs) and len(self.inputs) == len(set(self.inputs.values())))
        outgoing = [r for routes in self.outputs.values() for r in routes]
        require(all(routes for routes in self.outputs.values()) and len(outgoing) == len(set(outgoing)) and set(outgoing) == set(self.journal.outputs))
        require(not outgoing or self.journal.route_frames >= 2)
        assignments = link.assignments
        require(assignments and all(a.actor.kind == 'TASK_ATTEMPT' and a.clock is time.monotonic for a in assignments))
        self.by_route = {a.binding.route_id: a for a in assignments}
        self.output_specs = {}
        for name, routes in self.outputs.items():
            media = {self.by_route[r].media_type for r in routes}
            require(len(media) == 1)
            self.output_specs[name] = {'mediaType': next(iter(media)), 'maxBytes': min(self.by_route[r].max_payload_bytes for r in routes)}
            for route in routes:
                a = self.by_route[route]
                # A maximum-sized final DATA plus END must fit each reserved route budget.
                size = len(Frame(a.binding, 9007199254740990, 'DATA', b'\0' * a.max_payload_bytes, a.media_type).encode())
                size += len(Frame(a.binding, 9007199254740991, 'END', b'', None).encode())
                require(size <= self.journal.route_bytes)
        info = self.directory.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700)
        checkpoint = self.journal.checkpoint()
        digest = hashlib.sha256(json_encode({'version': VERSION, 'command': list(self.command), 'parameters': self.parameters,
            'inputs': self.inputs, 'outputs': {p: list(r) for p, r in self.outputs.items()}, 'stepTimeout': step_timeout})).hexdigest().encode('ascii')
        self.execution_sha256 = digest.decode('ascii')
        pin = self.journal.directory / 'processor.sha256'
        if create:
            require(checkpoint.revision == 0)
            fd = os.open(pin, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, 'wb') as file:
                file.write(digest);file.flush();os.fsync(file.fileno())
            parent = os.open(self.journal.directory, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(parent)
            finally:
                os.close(parent)
        fd = os.open(pin, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            info = os.fstat(fd)
            require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and info.st_nlink == 1 and stat.S_IMODE(info.st_mode) == 0o600)
            require(os.read(fd, 65) == digest)
        finally:
            os.close(fd)
        self._complete = checkpoint.ended_inputs == frozenset(self.journal.inputs)
        self._link_guard = self.journal.authority_guard
        require(self._link_guard is not None)
        self.journal.authority_guard = self._authority

    def _authority(self):
        if self.closed or self.cancel.is_set():
            raise WorkloadError('STREAM_CANCELLED')
        self._link_guard()
        if self.workload is not None:
            self.workload.check()

    def refresh(self, assignments):
        self._authority()
        self.link.refresh(assignments)
        if self.workload is not None:
            self.workload.renew(min(a.deadline for a in assignments))
        self.by_route = {a.binding.route_id: a for a in assignments}

    @property
    def complete(self):
        return self._complete

    @property
    def settled(self):
        return self._complete and not self.journal.outgoing()

    def _begin(self):
        checkpoint = self.journal.checkpoint()
        frames = {f.binding.route_id: f for f in self.journal.pending()}
        if not frames:
            return
        pending = {name: frames[route] for name, route in self.inputs.items() if route in frames}
        signature = (checkpoint.revision, tuple((name, f.sequence) for name, f in pending.items()))
        if signature == self._waiting:
            return
        value = {'apiVersion': VERSION, 'revision': checkpoint.revision,
            'stateBase64': base64.b64encode(checkpoint.state).decode('ascii'),
            'inputPorts': list(self.inputs), 'endedInputs': [p for p, r in self.inputs.items() if r in checkpoint.ended_inputs],
            'inputs': {p: json_decode(f.encode()) for p, f in pending.items()},
            'outputs': self.output_specs, 'parameters': self.parameters}
        encoded = json_encode(value)
        require(len(encoded) <= MAX_MESSAGE)
        self._authority()
        if self.workload is None:
            self.workload = WorkloadProcess(self.command, self.directory, min(a.deadline for a in self.link.assignments), cancel=self.cancel)
        self.workload.request(encoded, self.step_timeout)
        self._request = (checkpoint, pending, signature)
        self.requests += 1

    def _decode(self, encoded):
        try:
            require(type(encoded) is bytes and 0 < len(encoded) <= MAX_MESSAGE)
            value = json_decode(encoded)
            require(type(value) is dict and set(value) == {'apiVersion', 'revision', 'status', 'consumed', 'stateBase64', 'outputs'})
            checkpoint, pending, signature = self._request
            require(value['apiVersion'] == VERSION and type(value['revision']) is int and value['revision'] == checkpoint.revision)
            require(value['status'] in ('WAIT', 'COMMIT', 'COMPLETE') and type(value['consumed']) is dict and type(value['outputs']) is dict)
            state = blob(value['stateBase64'], self.journal.limits.max_state_bytes)
            if value['status'] == 'WAIT':
                require(not value['consumed'] and not value['outputs'] and state == checkpoint.state)
                self._waiting = signature
                return None
            consumed = []
            for port, sequence in value['consumed'].items():
                require(port in pending and type(sequence) is int and pending[port].sequence == sequence)
                consumed.append(pending[port])
            require(consumed)
            ended = checkpoint.ended_inputs | {f.binding.route_id for f in consumed if f.kind == 'END'}
            complete = ended == frozenset(self.journal.inputs)
            require((value['status'] == 'COMPLETE') == complete)
            emitted = []
            for port, output in value['outputs'].items():
                require(port in self.output_specs and type(output) is dict and set(output) == {'payloadBase64', 'mediaType'})
                spec = self.output_specs[port]
                require(output['mediaType'] == spec['mediaType'])
                payload = blob(output['payloadBase64'], spec['maxBytes'])
                emitted.extend(Emission(r, payload, output['mediaType']) for r in self.outputs[port])
            if complete:
                emitted.extend(Emission(r, b'', None, 'END') for routes in self.outputs.values() for r in routes)
            return consumed, state, emitted, complete
        except (RunnerError, KeyError, TypeError, ValueError, RecursionError):
            raise WorkloadError('STREAM_INVALID_WORKLOAD_RESPONSE') from None

    def step(self):
        try:
            self._authority()
            self.link.step(.001)
            self._authority()
            if self._candidate is not None:
                consumed, state, emitted, complete = self._candidate
                checkpoint = self._request[0]
                try:
                    self.journal.commit(checkpoint.revision, consumed, state, emitted)
                except Backpressure:
                    return
                self._candidate = self._request = self._waiting = None
                self._complete = complete
                if complete and self.workload is not None:
                    self.workload.close()
                    self.workload = None
            if not self._complete:
                if self.workload is not None:
                    response = self.workload.step()
                    if response is not None:
                        self._candidate = self._decode(response)
                        if self._candidate is None:
                            self._request = None
                if self._request is None:
                    self._begin()
        except BaseException:
            self.close()
            raise

    def close(self):
        if not self.closed:
            self.closed = True
            try:
                if self.workload is not None:
                    self.workload.close()
            finally:
                self.link.close(force=True)
