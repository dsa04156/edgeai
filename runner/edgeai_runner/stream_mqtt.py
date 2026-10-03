"""MQTT5 transport for bounded durable journals, driven by one thread's step loop.

Broker topic ACLs must be provisioned from authenticated DataRoute assignments. This
adapter does not create them or infer bindings from messages. QoS1 is only transport;
application watermarks release durable output after the consumer commits its state.
"""
from dataclasses import dataclass, replace
import ipaddress
import os
from pathlib import Path
import ssl
import stat
import time

import paho.mqtt.client as mqtt
from paho.mqtt.packettypes import PacketTypes
from paho.mqtt.properties import Properties
from paho.mqtt.subscribeoptions import SubscribeOptions

from edgeai_runner.stream_journal import Backpressure, JournalError, SequenceGap
from edgeai_runner.stream_protocol import Acknowledgement, MAX_FRAME_BYTES, StreamProtocolError, decode, decode_ack
from edgeai_runner.stream_assignment import Assignment, AssignmentError


class MqttError(RuntimeError):
    """Fixed reason only; do not print credentials, packets or broker diagnostics."""


class MqttAuthorityExpired(MqttError):
    """The Link has closed before any more queued packets can be sent."""


class _Client(mqtt.Client):
    def _ssl_wrap_socket(self, tcp_sock):
        # Paho 2.1.0 performs a deferred handshake without closing its SSLSocket on
        # failure and uses keepalive as the handshake timeout. Keep this narrow,
        # version-pinned override under actual CA/hostname/timeout tests.
        context = self._ssl_context
        if context is None or not context.check_hostname or context.verify_mode != ssl.CERT_REQUIRED:
            tcp_sock.close()
            raise MqttError('Verified MQTT TLS is required')
        tcp_sock.settimeout(self.connect_timeout)
        try:
            # Python closes the wrapped socket if its immediate handshake fails.
            return context.wrap_socket(tcp_sock, server_hostname=self._host)
        except BaseException:
            tcp_sock.close()
            raise


@dataclass(frozen=True)
class Endpoint:
    host: str
    port: int
    username: str
    password_file: Path
    ca_file: Path | None = None
    allow_plaintext_loopback: bool = False

    def __post_init__(self):
        if (type(self.host) is not str or not self.host or len(self.host) > 253
                or type(self.port) is not int or not 1 <= self.port <= 65535
                or type(self.username) is not str or not 1 <= len(self.username) <= 128
                or any(ord(c) < 33 or ord(c) > 126 for c in self.username)):
            raise MqttError('Invalid MQTT endpoint')
        if self.ca_file is None:
            try:
                local = ipaddress.ip_address(self.host).is_loopback
            except ValueError:
                local = False
            if self.allow_plaintext_loopback is not True or not local:
                raise MqttError('Verified MQTT TLS is required')

    def credential(self):
        return password(self.password_file)

    def tls_context(self):
        return ssl.create_default_context(cafile=self.ca_file) if self.ca_file is not None else None


def topic(binding, channel):
    if channel not in ('frames', 'acks'):
        raise MqttError('Invalid stream channel')
    return f'edgeai/streams/{binding.route_id}/{binding.generation}/{channel}'


def password(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) & 0o077 or not 1 <= info.st_size <= 4096):
            raise MqttError('Private MQTT credential file required')
        try:
            value = os.read(fd, 4097).decode('utf-8')
        except UnicodeError:
            raise MqttError('Invalid MQTT credential file') from None
        if not value or len(value.encode()) > 4096 or any(ord(c) < 32 for c in value):
            raise MqttError('Invalid MQTT credential file')
        return value
    finally:
        os.close(fd)


class Link:
    @property
    def assignments(self):
        return tuple(self._assignments.values())

    @classmethod
    def from_assignments(cls, journal, assignments):
        if type(assignments) not in (list, tuple) or not 1 <= len(assignments) <= 32 or not all(type(a) is Assignment for a in assignments):
            raise MqttError('Invalid stream assignments')
        first = assignments[0]
        if any(a.actor != first.actor or a.connection != first.connection for a in assignments):
            raise MqttError('Stream assignments require one actor and broker connection')
        inputs = {a.binding.route_id: a.binding for a in assignments if a.direction == 'CONSUMER'}
        outputs = {a.binding.route_id: a.binding for a in assignments if a.direction == 'PRODUCER'}
        if len(inputs) + len(outputs) != len(assignments) or inputs != journal.inputs or outputs != journal.outputs:
            raise MqttError('Stream assignments do not match journal routes')
        return cls(journal, first.connection, first.connection.client_id, assignments=assignments)

    def __init__(self, journal, endpoint, client_id, *, assignments=()):
        if type(client_id) is not str or not 1 <= len(client_id) <= 128 or not all(c.isascii() and (c.isalnum() or c in '-_') for c in client_id):
            raise MqttError('Invalid MQTT client identifier')
        self.journal = journal
        self.endpoint = endpoint
        self.ready = False
        self.closed = False
        self.error = None
        self.rejected = 0
        self.backpressured = 0
        self.gaps = 0
        self._retry_at = 0
        self._publish_at = 0
        self._socket_open = False
        self._connection_deadline = 0
        self._assignments = {a.binding.route_id: a for a in assignments}
        if self._assignments:
            if journal.authority_guard is not None:
                raise MqttError('Stream journal already has an authority owner')
            self._check_authority()
        self._incoming = {topic(b, 'frames'): b for b in journal.inputs.values()}
        self._acks = {topic(b, 'acks'): b for b in journal.outputs.values()}
        self.client = _Client(mqtt.CallbackAPIVersion.VERSION2, client_id=client_id, protocol=mqtt.MQTTv5,
                                  reconnect_on_failure=False, manual_ack=True)
        self.client.connect_timeout = 3
        self.client.max_inflight_messages_set(16)
        self.client.max_queued_messages_set(32)
        context = endpoint.tls_context()
        if context is not None:
            self.client.tls_set_context(context)
        self.client.on_connect = self._connected
        self.client.on_disconnect = self._disconnected
        self.client.on_subscribe = self._subscribed
        self.client.on_message = self._message
        self.client.on_publish = self._published
        if self._assignments:
            journal.authority_guard = self._check_authority

    def _check_authority(self):
        if self.closed:
            raise MqttError('MQTT link is closed')
        try:
            return min((a.remaining() for a in self._assignments.values()), default=120)
        except AssignmentError:
            # Close before asking Paho to disconnect, so queued writes cannot flush
            # after expiry. This also fences commits through the attached journal.
            if hasattr(self, 'client'):
                self.close(force=True)
            raise MqttAuthorityExpired('Stream assignment expired') from None

    def refresh(self, assignments):
        """Replace only live, identical authority snapshots, atomically on the step thread.

        A full set is required; identity, routes, broker credentials and wire limits
        cannot change in a live Link. Shorter VD/drain deadlines are respected too.
        """
        self._check_authority()
        if type(assignments) not in (list, tuple) or len(assignments) != len(self._assignments) or not self._assignments:
            raise MqttError('Invalid refreshed stream assignments')
        next_assignments = {}
        for assignment in assignments:
            if type(assignment) is not Assignment:
                raise MqttError('Invalid refreshed stream assignment')
            old = self._assignments.get(assignment.binding.route_id)
            if old is None or assignment.binding.route_id in next_assignments or assignment.clock is not old.clock or replace(assignment, deadline=old.deadline) != old:
                raise MqttError('Stream authority changed during refresh')
            assignment.remaining()
            next_assignments[assignment.binding.route_id] = assignment
        self._check_authority()  # Validation time cannot revive an expired old lease.
        self._assignments = next_assignments

    def _connected(self, client, userdata, flags, reason_code, properties):
        self._check_authority()
        if reason_code.is_failure:
            self.error = MqttError('MQTT connection rejected')
            return
        subscriptions = [(name, SubscribeOptions(qos=1, retainAsPublished=True, retainHandling=2))
                         for name in (*self._incoming, *self._acks)]
        rc, _ = client.subscribe(subscriptions)
        if rc != mqtt.MQTT_ERR_SUCCESS:
            self.error = MqttError('MQTT subscription failed')

    def _subscribed(self, client, userdata, mid, reason_codes, properties):
        self._check_authority()
        if any(code.is_failure for code in reason_codes):
            self.error = MqttError('MQTT subscription rejected')
        else:
            self.ready = True
            self._publish_at = 0

    def _disconnected(self, client, userdata, flags, reason_code, properties):
        self.ready = False
        self._socket_open = False
        self._retry_at = time.monotonic() + 0.5

    def _published(self, client, userdata, mid, reason_code, properties):
        if reason_code.is_failure:
            self.error = MqttError('MQTT publication rejected')
        # Broker acknowledgement deliberately does not touch the durable journal.

    def _message(self, client, userdata, message):
        self._check_authority()
        try:
            if message.qos != 1 or message.retain:
                raise StreamProtocolError('Invalid stream transport flags')
            if message.topic in self._incoming:
                frame = self._incoming[message.topic].verify(decode(message.payload))
                if self._assignments:
                    self._assignments[frame.binding.route_id].verify_frame(frame)
                self.journal.receive(frame)
            elif message.topic in self._acks:
                ack = decode_ack(message.payload)
                if ack.binding != self._acks[message.topic]:
                    raise StreamProtocolError('Stale or foreign stream acknowledgement')
                self.journal.acknowledge(ack.binding, ack.sequence)
            else:
                raise StreamProtocolError('Unknown stream topic')
        except Backpressure:
            self.backpressured += 1
        except SequenceGap:
            self.gaps += 1
        except (StreamProtocolError, JournalError, AssignmentError):
            self.rejected += 1
        # Network ACK releases only broker/Paho memory. On a full queue or gap the
        # application's processing ACK remains unchanged, so the source replays.
        if message.qos == 1:
            self._check_authority()
            client.ack(message.mid, message.qos)

    def _publish(self, channel, binding, payload):
        self._check_authority()
        result = self.client.publish(topic(binding, channel), payload, qos=1, retain=False)
        if result.rc in (mqtt.MQTT_ERR_NO_CONN, mqtt.MQTT_ERR_CONN_LOST):
            self.ready = False
            self._socket_open = False
            self._retry_at = time.monotonic() + 0.5
        elif result.rc not in (mqtt.MQTT_ERR_SUCCESS, mqtt.MQTT_ERR_QUEUE_SIZE, mqtt.MQTT_ERR_AGAIN):
            raise MqttError(f'MQTT publication failed ({int(result.rc)})')

    def step(self, timeout=0.02):
        if self.closed:
            raise MqttError('MQTT link is closed')
        if not 0 <= timeout <= 1:
            raise MqttError('Invalid MQTT polling timeout')
        if self.error is not None:
            raise self.error
        remaining = self._check_authority()
        timeout = min(timeout, remaining)
        if not self._socket_open:
            if time.monotonic() < self._retry_at:
                return
            if self._assignments and self.client.socket() is None:
                self.client.connect_timeout = min(self.client.connect_timeout, remaining)
            self.client.username_pw_set(self.endpoint.username, self.endpoint.credential())
            properties = Properties(PacketTypes.CONNECT)
            properties.ReceiveMaximum = 16
            properties.MaximumPacketSize = MAX_FRAME_BYTES + 1024
            properties.SessionExpiryInterval = 0
            try:
                rc = self.client.connect(self.endpoint.host, self.endpoint.port, keepalive=15,
                                         clean_start=True, properties=properties)
                self._socket_open = rc == mqtt.MQTT_ERR_SUCCESS
                self._connection_deadline = time.monotonic() + 10
            except ssl.SSLError:
                raise MqttError('MQTT TLS verification failed') from None
            except OSError:
                self._retry_at = time.monotonic() + 0.5
                return
        self._check_authority()
        rc = self.client.loop(timeout=timeout)
        self._check_authority()
        if rc != mqtt.MQTT_ERR_SUCCESS:
            self.ready = False
            self._socket_open = False
            self._retry_at = time.monotonic() + 0.5
        if self.error is not None:
            raise self.error
        if self._socket_open and not self.ready and time.monotonic() >= self._connection_deadline:
            self.client.disconnect()
            self._socket_open = False
            self._retry_at = time.monotonic() + 0.5
        if self.ready and time.monotonic() >= self._publish_at:
            for frame in self.journal.outgoing(16, confirmed_only=True):
                if self._assignments:
                    self._assignments[frame.binding.route_id].verify_frame(frame)
                self._publish('frames', frame.binding, frame.encode())
            for identity, sequence in self.journal.processing_sequences().items():
                binding = self.journal.inputs[identity]
                self._publish('acks', binding, Acknowledgement(binding, sequence).encode())
            self._publish_at = time.monotonic() + 0.25

    def close(self, *, force=False):
        if not self.closed:
            self.closed = True
            self.ready = False
            try:
                if force and self.client.socket() is not None:
                    self.client.socket().close()
                self.client.disconnect()
            finally:
                # Pinned Paho 2.1.0 disconnect() leaves loop()'s wake-up socket
                # pair alive until GC. Release all three sockets when this owner
                # closes, even when Session/Journal callbacks retain references.
                self.client._reset_sockets()
