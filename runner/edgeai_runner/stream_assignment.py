"""Authenticated, memory-only stream assignments with conservative monotonic leases.

Discovery never renews a generation. The full HTTP round trip consumes the returned
lease; wall-clock changes on the device cannot extend it. No credentials are logged.
"""
from dataclasses import dataclass, field
from datetime import datetime
import json
import math
import os
from pathlib import Path
import re
import ssl
import stat
import time
import urllib.error
import urllib.parse
import urllib.request

from edgeai_runner.stream_protocol import (Binding, Producer, MEDIA_TYPE, StreamProtocolError,
                                          _counter, _uuid, _unique_object, _invalid_constant)

MAX_RESPONSE = 262144


class AssignmentError(RuntimeError):
    """Fixed, non-sensitive reason; response bodies and tokens are never included."""


def require(condition, reason='Invalid stream assignment'):
    if not condition:
        raise AssignmentError(reason)


def text(value, maximum):
    require(type(value) is str and 0 < len(value) <= maximum and all(32 < ord(c) < 127 for c in value))
    return value


def instant(value):
    require(type(value) is str and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z', value))
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


@dataclass(frozen=True)
class Connection:
    host: str
    port: int
    username: str
    client_id: str
    secret: str = field(repr=False)
    ca_pem: str = field(repr=False)
    tls: bool

    def credential(self):
        return self.secret

    def tls_context(self):
        if not self.tls:
            return None
        try:
            return ssl.create_default_context(cadata=self.ca_pem)
        except (ValueError, ssl.SSLError):
            raise AssignmentError('Invalid stream broker CA') from None


@dataclass(frozen=True)
class Assignment:
    generation_id: str
    run_id: str
    binding: Binding
    direction: str
    actor: Producer
    consumer_id: str
    consumer_epoch: int
    media_type: str
    max_payload_bytes: int
    connection: Connection = field(repr=False)
    deadline: float
    clock: object = field(default=time.monotonic, repr=False, compare=False)

    def remaining(self):
        remaining = self.deadline - self.clock()
        require(math.isfinite(remaining) and remaining > 0, 'Stream assignment expired')
        return remaining

    def verify_frame(self, frame):
        self.remaining()
        self.binding.verify(frame)
        require(len(frame.payload) <= self.max_payload_bytes
                and (frame.kind == 'END' or frame.media_type == self.media_type), 'Stream frame violates assignment')

    @classmethod
    def decode(cls, encoded, actor, generation_id, started, *, clock=time.monotonic):
        try:
            require(type(encoded) is bytes and 0 < len(encoded) <= MAX_RESPONSE)
            require(type(actor) is Producer and type(started) in (int, float) and math.isfinite(started) and started <= clock())
            _uuid(generation_id)
            value = json.loads(encoded, object_pairs_hook=_unique_object, parse_constant=_invalid_constant)
            require(type(value) is dict and set(value) == {'apiVersion', 'generationId', 'routeId', 'runId',
                    'generation', 'direction', 'producer', 'consumer', 'mediaType', 'maxPayloadBytes', 'serverTime', 'leaseUntil', 'mqtt'})
            require(value['apiVersion'] == 'edgeai.stream-assignment/v1' and value['generationId'] == generation_id)
            _uuid(value['runId'])
            binding = Binding(value['routeId'], value['generation'], Producer.parse(value['producer']))
            consumer = value['consumer']
            require(type(consumer) is dict and set(consumer) == {'attemptId', 'epoch'})
            _uuid(consumer['attemptId']); _counter(consumer['epoch'])
            require(value['direction'] in ('PRODUCER', 'CONSUMER'))
            expected = binding.producer if value['direction'] == 'PRODUCER' else Producer('TASK_ATTEMPT', consumer['attemptId'], consumer['epoch'])
            require(actor == expected, 'Stream assignment belongs to another actor')
            media = value['mediaType']; size = value['maxPayloadBytes']
            require(type(media) is str and len(media) <= 127 and MEDIA_TYPE.fullmatch(media))
            require(type(size) is int and 1 <= size <= 262144)
            duration = (instant(value['leaseUntil']) - instant(value['serverTime'])).total_seconds()
            require(0 < duration <= 120, 'Invalid stream lease')
            mqtt = value['mqtt']
            require(type(mqtt) is dict and set(mqtt) == {'host', 'port', 'tls', 'caPem', 'clientId', 'username', 'password', 'framesTopic', 'acksTopic'})
            host = text(mqtt['host'], 253)
            require(not any(c in host for c in '/@?#\\'))
            require(type(mqtt['port']) is int and 1 <= mqtt['port'] <= 65535 and type(mqtt['tls']) is bool)
            require(type(mqtt['caPem']) is str and len(mqtt['caPem']) <= 131072)
            require(mqtt['tls'] and bool(mqtt['caPem']) or not mqtt['tls'] and host == '127.0.0.1' and mqtt['caPem'] == '')
            username = text(mqtt['username'], 128); client_id = text(mqtt['clientId'], 128)
            require(username == client_id and re.fullmatch('[A-Za-z0-9_-]+', client_id))
            secret = text(mqtt['password'], 64)
            for channel in ('frames', 'acks'):
                require(mqtt[channel + 'Topic'] == f'edgeai/streams/{binding.route_id}/{binding.generation}/{channel}', 'Invalid stream assignment topic')
            connection = Connection(host, mqtt['port'], username, client_id, secret, mqtt['caPem'], mqtt['tls'])
            connection.tls_context()  # Reject an invalid trust bundle before exposing any assignment.
            result = cls(generation_id, value['runId'], binding, value['direction'], actor, consumer['attemptId'],
                         consumer['epoch'], media, size, connection, started + duration, clock)
            result.remaining()
            return result
        except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
            raise AssignmentError('Invalid stream assignment') from None


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        fp.close()
        raise AssignmentError('Stream discovery redirect rejected')


@dataclass(frozen=True)
class Heartbeat:
    sequence: int
    assignment: Assignment


def _token(path, maximum, *, projected=False):
    try:
        flags = os.O_RDONLY if projected else os.O_RDONLY | os.O_NOFOLLOW
        fd = os.open(path, flags)
        try:
            info = os.fstat(fd)
            require(stat.S_ISREG(info.st_mode) and 0 < info.st_size <= maximum + 1, 'Invalid stream credential file')
            require(projected or info.st_uid == os.getuid() and not stat.S_IMODE(info.st_mode) & 0o077, 'Private stream credential required')
            require(not projected or not stat.S_IMODE(info.st_mode) & 0o137, 'Private projected stream credential required')
            return text(os.read(fd, maximum + 2).decode('ascii').strip(), maximum)
        finally:
            os.close(fd)
    except (OSError, UnicodeError):
        raise AssignmentError('Invalid stream credential file') from None


class BindingClient:
    def __init__(self, origin, actor, token_file, *, pod_uid=None, pod_token_file=None, ca_file=None, allow_http_loopback=False):
        try:
            url = urllib.parse.urlsplit(origin)
            require(url.scheme == 'https' or allow_http_loopback is True and url.scheme == 'http' and url.hostname == '127.0.0.1', 'Verified stream discovery TLS required')
            require(url.hostname and url.path in ('', '/') and not url.query and not url.fragment
                    and url.username is None and url.password is None and (url.port is None or 1 <= url.port <= 65535))
            require(type(actor) is Producer)
            if actor.kind == 'TASK_ATTEMPT':
                _uuid(pod_uid); require(pod_token_file is not None)
                path = f'/internal/v1/attempts/{actor.id}/streams'
            else:
                require(pod_uid is None and pod_token_file is None)
                path = f'/internal/v1/devices/{actor.device_id}/sessions/{actor.id}/streams'
            context = ssl.create_default_context(cafile=ca_file)
        except (ValueError, OSError):
            raise AssignmentError('Invalid stream discovery configuration') from None
        self.url = origin.rstrip('/') + path
        self.actor, self.token_file, self.pod_uid, self.pod_token_file = actor, Path(token_file), pod_uid, pod_token_file
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect(), urllib.request.HTTPSHandler(context=context))

    def fetch(self, generation_id):
        encoded, started = self._request(generation_id)
        return Assignment.decode(encoded, self.actor, generation_id, started)

    def heartbeat(self, generation_id, sequence):
        require(type(sequence) is int and 0 <= sequence <= 9007199254740991, 'Invalid stream heartbeat sequence')
        encoded, started = self._request(generation_id, sequence)
        try:
            require(0 < len(encoded) <= MAX_RESPONSE, 'Invalid stream heartbeat response')
            value = json.loads(encoded, object_pairs_hook=_unique_object, parse_constant=_invalid_constant)
            require(type(value) is dict and set(value) == {'sequence', 'assignment'}, 'Invalid stream heartbeat response')
            acknowledged = value['sequence']
            require(type(acknowledged) is int and 0 <= acknowledged <= 9007199254740991
                    and (sequence == 0 or sequence == acknowledged), 'Invalid stream heartbeat acknowledgement')
            assignment = Assignment.decode(json.dumps(value['assignment']).encode(), self.actor, generation_id, started)
            return Heartbeat(acknowledged, assignment)
        except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
            raise AssignmentError('Invalid stream heartbeat response') from None

    def _request(self, generation_id, sequence=None):
        try:
            _uuid(generation_id)
            body = {'epoch': self.actor.epoch, 'generationId': generation_id}
            if sequence is not None:
                body['sequence'] = sequence
            headers = {'Authorization': 'Bearer ' + _token(self.token_file, 1024, projected=self.pod_uid is not None), 'Content-Type': 'application/json', 'Accept': 'application/json'}
            if self.pod_uid is not None:
                body['podUid'] = self.pod_uid
                headers['X-EdgeAI-Pod-Token'] = _token(self.pod_token_file, 16384, projected=True)
            request = urllib.request.Request(self.url + ('/heartbeat' if sequence is not None else ''), data=json.dumps(body).encode(), headers=headers, method='POST')
            started = time.monotonic()
            with self.http.open(request, timeout=5) as response:
                require(response.status == 200 and response.headers.get_content_type() == 'application/json'
                        and 'no-store' in [v.strip().lower() for v in response.headers.get('Cache-Control', '').split(',')], 'Invalid stream discovery response')
                encoded = response.read(MAX_RESPONSE + 1)
            return encoded, started
        except urllib.error.HTTPError as error:
            status = error.code; error.close()
            if status in (401, 403, 404, 409):
                raise AssignmentError('Stream discovery fenced') from None
            raise AssignmentError('Stream discovery unavailable') from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise AssignmentError('Stream discovery unavailable') from None
        except StreamProtocolError:
            raise AssignmentError('Invalid stream discovery identity') from None
