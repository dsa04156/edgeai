"""Bounded stream frame codec. Transport authentication and durable processing are separate.

No MQTT connection, acknowledgement, cursor advancement or Result commit happens here.
Bindings must come from the authenticated control plane, never from an incoming frame.
"""
import base64
import binascii
from dataclasses import dataclass
import hashlib
import hmac
import json
import re
import uuid

VERSION = 'edgeai.stream/v1'
MAX_COUNTER = (1 << 53) - 1
MAX_PAYLOAD_BYTES = 262144
MAX_FRAME_BYTES = 524288
MEDIA_TYPE = re.compile(r'[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*\Z')


class StreamProtocolError(ValueError):
    """Contains only a fixed reason, never payloads or credentials."""


def _require(condition, reason):
    if not condition:
        raise StreamProtocolError(reason)


def _uuid(value):
    _require(type(value) is str and len(value) == 36, 'Invalid stream identifier')
    try:
        valid = str(uuid.UUID(value)) == value
    except ValueError:
        valid = False
    _require(valid, 'Invalid stream identifier')


def _counter(value):
    _require(type(value) is int and 1 <= value <= MAX_COUNTER, 'Invalid stream counter')


@dataclass(frozen=True)
class Producer:
    kind: str
    id: str
    epoch: int
    device_id: str | None = None

    def __post_init__(self):
        _require(self.kind in ('DEVICE_SESSION', 'TASK_ATTEMPT'), 'Invalid stream producer')
        _uuid(self.id)
        _counter(self.epoch)
        if self.kind == 'DEVICE_SESSION':
            _uuid(self.device_id)
        else:
            _require(self.device_id is None, 'Invalid stream producer')

    def document(self):
        value = {'kind': self.kind, 'epoch': self.epoch}
        if self.kind == 'DEVICE_SESSION':
            value.update(deviceId=self.device_id, sessionId=self.id)
        else:
            value['attemptId'] = self.id
        return value

    @classmethod
    def parse(cls, value):
        _require(type(value) is dict, 'Invalid stream producer')
        if value.get('kind') == 'DEVICE_SESSION':
            _require(set(value) == {'kind', 'deviceId', 'sessionId', 'epoch'}, 'Invalid stream producer')
            return cls(value['kind'], value['sessionId'], value['epoch'], value['deviceId'])
        _require(value.get('kind') == 'TASK_ATTEMPT' and set(value) == {'kind', 'attemptId', 'epoch'}, 'Invalid stream producer')
        return cls(value['kind'], value['attemptId'], value['epoch'])


@dataclass(frozen=True)
class Binding:
    route_id: str
    generation: int
    producer: Producer

    def __post_init__(self):
        _uuid(self.route_id)
        _counter(self.generation)
        _require(type(self.producer) is Producer, 'Invalid stream producer')

    def verify(self, frame):
        _require(type(frame) is Frame and frame.binding == self, 'Stale or foreign stream binding')
        return frame


@dataclass(frozen=True)
class Frame:
    binding: Binding
    sequence: int
    kind: str
    payload: bytes
    media_type: str | None

    def __post_init__(self):
        _require(type(self.binding) is Binding, 'Invalid stream binding')
        _counter(self.sequence)
        _require(self.kind in ('DATA', 'END'), 'Invalid stream frame kind')
        _require(type(self.payload) is bytes and len(self.payload) <= MAX_PAYLOAD_BYTES, 'Invalid stream payload size')
        if self.kind == 'END':
            _require(self.payload == b'' and self.media_type is None, 'Invalid stream end marker')
        else:
            _require(type(self.media_type) is str and len(self.media_type) <= 127 and MEDIA_TYPE.fullmatch(self.media_type), 'Invalid stream media type')

    def encode(self):
        value = {'apiVersion': VERSION, 'routeId': self.binding.route_id, 'generation': self.binding.generation,
                 'producer': self.binding.producer.document(), 'sequence': self.sequence, 'kind': self.kind,
                 'mediaType': self.media_type, 'payloadBase64': base64.b64encode(self.payload).decode('ascii'),
                 'sha256': hashlib.sha256(self.payload).hexdigest()}
        encoded = json.dumps(value, separators=(',', ':'), sort_keys=True).encode('ascii')
        _require(len(encoded) <= MAX_FRAME_BYTES, 'Invalid stream frame size')
        return encoded


@dataclass(frozen=True)
class Acknowledgement:
    binding: Binding
    sequence: int

    def __post_init__(self):
        _require(type(self.binding) is Binding, 'Invalid stream acknowledgement binding')
        _require(type(self.sequence) is int and 0 <= self.sequence <= MAX_COUNTER,
                 'Invalid stream acknowledgement sequence')

    def encode(self):
        return json.dumps({'apiVersion': 'edgeai.stream-ack/v1', 'routeId': self.binding.route_id,
                           'generation': self.binding.generation, 'producer': self.binding.producer.document(),
                           'sequence': self.sequence}, sort_keys=True, separators=(',', ':')).encode('ascii')


def decode_ack(encoded):
    _require(type(encoded) is bytes and 0 < len(encoded) <= 2048, 'Invalid stream acknowledgement size')
    try:
        value = json.loads(encoded.decode('utf-8'), object_pairs_hook=_unique_object, parse_constant=_invalid_constant)
    except (UnicodeError, json.JSONDecodeError, RecursionError, ValueError):
        raise StreamProtocolError('Invalid stream acknowledgement JSON') from None
    _require(type(value) is dict and set(value) == {'apiVersion', 'routeId', 'generation', 'producer', 'sequence'}
             and value['apiVersion'] == 'edgeai.stream-ack/v1', 'Invalid stream acknowledgement')
    return Acknowledgement(Binding(value['routeId'], value['generation'], Producer.parse(value['producer'])), value['sequence'])


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        _require(key not in result, 'Duplicate stream field')
        result[key] = value
    return result


def _invalid_constant(_):
    raise StreamProtocolError('Invalid stream number')


def decode(encoded):
    _require(type(encoded) is bytes and 0 < len(encoded) <= MAX_FRAME_BYTES, 'Invalid stream frame size')
    try:
        value = json.loads(encoded.decode('utf-8'), object_pairs_hook=_unique_object, parse_constant=_invalid_constant)
    except (UnicodeError, json.JSONDecodeError, RecursionError, ValueError) as error:
        raise StreamProtocolError('Invalid stream JSON') from None
    fields = {'apiVersion', 'routeId', 'generation', 'producer', 'sequence', 'kind', 'mediaType', 'payloadBase64', 'sha256'}
    _require(type(value) is dict and set(value) == fields and value['apiVersion'] == VERSION, 'Invalid stream envelope')
    digest = value['sha256']
    _require(type(digest) is str and re.fullmatch('[a-f0-9]{64}', digest), 'Invalid stream checksum')
    payload = value['payloadBase64']
    _require(type(payload) is str and len(payload) <= 4 * ((MAX_PAYLOAD_BYTES + 2) // 3), 'Invalid stream payload size')
    try:
        raw = base64.b64decode(payload, validate=True)
    except (ValueError, binascii.Error):
        raise StreamProtocolError('Invalid stream encoding') from None
    _require(base64.b64encode(raw).decode('ascii') == payload, 'Noncanonical stream encoding')
    _require(hmac.compare_digest(hashlib.sha256(raw).hexdigest(), digest), 'Stream checksum mismatch')
    binding = Binding(value['routeId'], value['generation'], Producer.parse(value['producer']))
    return Frame(binding, value['sequence'], value['kind'], raw, value['mediaType'])
