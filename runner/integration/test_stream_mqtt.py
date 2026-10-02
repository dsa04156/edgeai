"""Real isolated Mosquitto, ACLs and SQLite journals. No control-plane or hardware claim."""
from dataclasses import replace
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tests'))
from edgeai_runner.stream_journal import Backpressure, Emission, Journal, Limits
from edgeai_runner.stream_mqtt import Endpoint, Link, MqttError, topic
from edgeai_runner.stream_protocol import Frame
from test_stream_journal import A, B, OUT, data


def eventually(action, condition, seconds=8):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        action()
        if condition():
            return
        time.sleep(0.005)
    raise AssertionError('Timed out waiting for actual MQTT stream progress')


class Broker:
    def __init__(self, directory):
        self.directory = directory
        self.process = None
        self.ca = None
        self.binary = os.environ.get('EDGEAI_MOSQUITTO_BINARY') or shutil.which('mosquitto')
        self.passwd = os.environ.get('EDGEAI_MOSQUITTO_PASSWD_BINARY') or shutil.which('mosquitto_passwd')
        if not self.binary or not self.passwd:
            raise RuntimeError('Real mosquitto and mosquitto_passwd are required; this test never skips')
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            self.port = sock.getsockname()[1]
        self.credentials = {}
        passwords = []
        for actor in ('source-a', 'source-b', 'processor', 'sink'):
            credential = secrets.token_hex(32)
            path = directory / (actor + '.credential')
            path.write_text(credential)
            path.chmod(0o600)
            self.credentials[actor] = path
            passwords.append(actor + ':' + credential)
        password_file = directory / 'passwords'
        password_file.write_text('\n'.join(passwords) + '\n')
        password_file.chmod(0o600)
        # Hash a private file in-place, never pass a credential in argv or test output.
        subprocess.run([self.passwd, '-U', str(password_file)], check=True, capture_output=True)
        permissions = {
            'source-a': [('write', A, 'frames'), ('read', A, 'acks')],
            'source-b': [('write', B, 'frames'), ('read', B, 'acks')],
            'processor': [('read', A, 'frames'), ('read', B, 'frames'), ('write', A, 'acks'),
                          ('write', B, 'acks'), ('write', OUT, 'frames'), ('read', OUT, 'acks')],
            'sink': [('read', OUT, 'frames'), ('write', OUT, 'acks')],
        }
        acl = directory / 'acl'
        acl.write_text('\n'.join('user ' + actor + '\n' + '\n'.join(
            f'topic {access} {topic(binding, channel)}' for access, binding, channel in entries)
            for actor, entries in permissions.items()) + '\n')
        acl.chmod(0o600)
        self.config = directory / 'mosquitto.conf'
        self.config.write_text(f'listener {self.port} 127.0.0.1\nallow_anonymous false\n'
                               f'password_file {password_file}\nacl_file {acl}\n'
                               'persistence false\nmax_packet_size 525312\nmessage_size_limit 524288\n'
                               'max_queued_messages 32\nmax_queued_bytes 16777216\nmax_inflight_messages 16\n'
                               'log_dest stderr\n')

    def start(self):
        self.process = subprocess.Popen([self.binary, '-c', str(self.config)],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        def ready():
            if self.process.poll() is not None:
                raise AssertionError('Isolated MQTT broker exited during startup')
            try:
                with socket.create_connection(('127.0.0.1', self.port), timeout=0.1):
                    return True
            except OSError:
                return False
        eventually(lambda: None, ready, 5)

    def stop(self, kill=False):
        if self.process is not None:
            if self.process.poll() is None:
                self.process.kill() if kill else self.process.terminate()
            self.process.wait(timeout=5)
            self.process = None

    def endpoint(self, actor):
        return Endpoint('localhost' if self.ca else '127.0.0.1', self.port, actor, self.credentials[actor],
                        ca_file=self.ca, allow_plaintext_loopback=self.ca is None)

    def enable_tls(self):
        self.stop()
        for name in ('server', 'untrusted'):
            result = subprocess.run(['openssl', 'req', '-x509', '-nodes', '-newkey', 'rsa:2048', '-days', '1',
                                     '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost',
                                     '-keyout', str(self.directory / (name + '.key')),
                                     '-out', str(self.directory / (name + '.crt'))], capture_output=True, timeout=15)
            if result.returncode:
                raise AssertionError('Test certificate generation failed; private output suppressed')
            (self.directory / (name + '.key')).chmod(0o600)
        self.ca = self.directory / 'server.crt'
        with self.config.open('a') as file:
            file.write(f'certfile {self.ca}\nkeyfile {self.directory / "server.key"}\n')
        self.start()


class StreamMqttTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='edgeai-stream-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.broker = Broker(self.root)
        self.addCleanup(self.broker.stop)
        self.broker.start()
        self.journals = {}
        self.links = {}
        self.addCleanup(self.close_peers)

    def close_peers(self):
        for link in self.links.values(): link.close()
        for journal in self.journals.values(): journal.close()

    def peer(self, actor, inputs, outputs, *, create=True, credential_actor=None):
        journal = Journal(self.root / actor, inputs, outputs, Limits(max_frames=6), create=create)
        self.journals[actor] = journal
        link = Link(journal, self.broker.endpoint(credential_actor or actor), 'edgeai-test-' + actor)
        self.links[actor] = link
        return journal, link

    def pump(self):
        for link in self.links.values(): link.step(0.001)

    def ready(self):
        eventually(self.pump, lambda: all(link.ready for link in self.links.values()))

    def emit(self, actor, binding, payload, kind='DATA'):
        journal = self.journals[actor]
        return journal.commit(journal.checkpoint().revision, [], b'',
                              [Emission(binding.route_id, payload, None if kind == 'END' else 'application/json', kind)])

    def test_two_devices_actual_payload_join_result_and_end_cross_three_mqtt_routes(self):
        a, _ = self.peer('source-a', [], [A])
        b, _ = self.peer('source-b', [], [B])
        processor, _ = self.peer('processor', [A, B], [OUT])
        sink, _ = self.peer('sink', [OUT], [])
        self.ready()
        self.emit('source-a', A, b'4')
        self.emit('source-b', B, b'5')
        eventually(self.pump, lambda: len(processor.pending()) == 2)
        # Broker PUBACK alone must not discard even successfully delivered source frames.
        self.assertEqual(1, len(a.outgoing()))
        self.assertEqual(1, len(b.outgoing()))
        total = str(sum(int(f.payload) for f in processor.pending())).encode()
        processor.commit(0, processor.pending(), total, [Emission(OUT.route_id, total, 'application/json')])
        eventually(self.pump, lambda: len(sink.pending()) == 1 and not a.outgoing() and not b.outgoing())
        self.assertEqual(b'9', sink.pending()[0].payload)
        sink.commit(0, sink.pending(), b'9')
        self.emit('source-a', A, b'', 'END')
        self.emit('source-b', B, b'', 'END')
        eventually(self.pump, lambda: len(processor.pending()) == 2)
        self.assertTrue(all(f.kind == 'END' for f in processor.pending()))
        processor.commit(1, processor.pending(), b'9', [Emission(OUT.route_id, b'', None, 'END')])
        eventually(self.pump, lambda: len(sink.pending()) == 1)
        self.assertEqual('END', sink.pending()[0].kind)
        sink.commit(1, sink.pending(), b'9')
        eventually(self.pump, lambda: all(not j.outgoing() for j in self.journals.values()))
        self.assertEqual(b'9', sink.checkpoint().state)
        self.assertEqual(frozenset([A.route_id, B.route_id]), processor.checkpoint().ended_inputs)

    def test_consumer_reopen_before_processing_ack_does_not_repeat_checkpoint(self):
        source, _ = self.peer('source-a', [], [A])
        processor, link = self.peer('processor', [A], [])
        self.ready()
        self.emit('source-a', A, b'17')
        eventually(self.pump, lambda: bool(processor.pending()))
        processor.commit(0, processor.pending(), b'17')
        link.close()
        processor.close()
        self.assertEqual(1, len(source.outgoing()))
        processor, _ = self.peer('processor', [A], [], create=False)
        eventually(self.pump, lambda: not source.outgoing())
        self.assertEqual(1, processor.checkpoint().revision)
        self.assertEqual(b'17', processor.checkpoint().state)
        self.assertEqual((), processor.pending())

    def test_slow_consumer_broker_sigkill_and_reconnect_keep_bounded_journals_and_exact_sum(self):
        a, _ = self.peer('source-a', [], [A])
        b, _ = self.peer('source-b', [], [B])
        processor, _ = self.peer('processor', [A, B], [OUT])
        sink, _ = self.peer('sink', [OUT], [])
        self.ready()
        produced = {'source-a': 0, 'source-b': 0}
        consumed = 0
        expected = sum(i + 100 + i for i in range(1, 21))
        full = 0
        restarted = False
        def cycle():
            nonlocal consumed, full, restarted
            # Burst faster than the join can consume; output queues must apply pressure.
            for actor, binding, offset in (('source-a', A, 0), ('source-b', B, 100)):
                for _ in range(8):
                    if produced[actor] >= 20: break
                    try:
                        self.emit(actor, binding, str(produced[actor] + 1 + offset).encode())
                        produced[actor] += 1
                    except Backpressure:
                        full += 1
                        break
            self.pump()
            frames = processor.pending()
            if len(frames) == 2:
                payload = str(sum(int(f.payload) for f in frames)).encode()
                checkpoint = processor.checkpoint()
                try:
                    processor.commit(checkpoint.revision, frames, payload, [Emission(OUT.route_id, payload, 'application/json')])
                except Backpressure:
                    full += 1
            if sink.pending():
                checkpoint = sink.checkpoint()
                total = int(checkpoint.state or b'0') + int(sink.pending()[0].payload)
                sink.commit(checkpoint.revision, sink.pending(), str(total).encode())
                consumed += 1
            if consumed >= 7 and not restarted:
                self.broker.stop(kill=True)
                for _ in range(3): self.pump()
                self.broker.start()
                restarted = True
            for journal in self.journals.values():
                self.assertLessEqual(journal.usage()[0], 6)
        eventually(cycle, lambda: consumed == 20 and all(not j.outgoing() for j in self.journals.values()), 20)
        self.assertTrue(restarted)
        self.assertGreater(full, 0)
        self.assertEqual(20, sink.checkpoint().revision)
        self.assertEqual(expected, int(sink.checkpoint().state))
        self.assertGreater(sum(link.backpressured for link in self.links.values()), 0)

    def test_broker_rejects_source_publishing_another_device_route(self):
        attacker, _ = self.peer('source-a', [], [B])
        processor, _ = self.peer('processor', [B], [])
        self.ready()
        attacker.commit(0, [], b'', [Emission(B.route_id, b'999', 'application/json')])
        with self.assertRaisesRegex(MqttError, 'publication rejected'):
            eventually(self.pump, lambda: bool(processor.pending()), 5)
        self.assertEqual((), processor.pending())
        self.assertEqual(1, len(attacker.outgoing()))

    def test_connection_loss_during_write_keeps_output_and_reconnects(self):
        journal, source = self.peer('source-a', [], [A])
        processor, _ = self.peer('processor', [A], [])
        self.ready()
        frame, = self.emit('source-a', A, b'4')
        # Force the real socket's write to fail between loop() and publish().
        source.client.socket().shutdown(socket.SHUT_WR)
        source._publish('frames', A, frame.encode())
        self.assertEqual((frame,), journal.outgoing())
        eventually(self.pump, lambda: bool(processor.pending()))
        self.assertEqual((frame,), processor.pending())

    def test_wrong_generation_and_retained_frame_are_rejected_before_calculation(self):
        _, source = self.peer('source-a', [], [A])
        processor, consumer = self.peer('processor', [A], [])
        self.ready()
        source.client.publish(topic(A, 'frames'), data(replace(A, generation=2)).encode(), qos=1)
        source.client.publish(topic(A, 'frames'), data().encode(), qos=1, retain=True)
        eventually(self.pump, lambda: consumer.rejected == 2)
        self.assertEqual((), processor.pending())
        self.emit('source-a', A, b'4')
        eventually(self.pump, lambda: bool(processor.pending()))
        self.assertEqual(b'4', processor.pending()[0].payload)

    def test_plaintext_remote_endpoint_and_wrong_credentials_fail_closed(self):
        with self.assertRaises(MqttError):
            Endpoint('example.com', 1883, 'source-a', self.broker.credentials['source-a'], allow_plaintext_loopback=True)
        _, link = self.peer('source-a', [], [A])
        self.broker.credentials['source-a'].write_text('incorrect-private-test-credential')
        with self.assertRaisesRegex(MqttError, 'connection rejected'):
            eventually(self.pump, lambda: link.ready)

    def test_actual_tls_requires_trusted_ca_and_matching_hostname(self):
        self.broker.enable_tls()
        _, source = self.peer('source-a', [], [A])
        processor, _ = self.peer('processor', [A], [])
        self.ready()
        self.emit('source-a', A, b'11')
        eventually(self.pump, lambda: bool(processor.pending()))
        self.assertEqual(b'11', processor.pending()[0].payload)
        endpoint = self.broker.endpoint('source-a')
        for bad in (replace(endpoint, host='127.0.0.1'),
                    replace(endpoint, ca_file=self.root / 'untrusted.crt')):
            sockets_before = self.socket_count()
            candidate = Link(self.journals['source-a'], bad, 'edgeai-test-untrusted')
            try:
                with self.assertRaisesRegex(MqttError, 'TLS verification failed'): candidate.step()
            finally:
                candidate.close()
            self.assertEqual(sockets_before, self.socket_count())

    def socket_count(self):
        count = 0
        for path in Path('/proc/self/fd').iterdir():
            try:
                count += os.readlink(path).startswith('socket:')
            except FileNotFoundError:  # The fd used by directory iteration has closed.
                pass
        self.assertGreater(count, 0, 'The live peer/listener sockets must be counted')
        return count

    def test_tls_handshake_timeout_is_bounded_and_closes_failed_socket(self):
        self.broker.enable_tls()
        journal, _ = self.peer('source-a', [], [A])
        with socket.socket() as silent:
            silent.bind(('127.0.0.1', 0))
            silent.listen(1)
            endpoint = replace(self.broker.endpoint('source-a'), port=silent.getsockname()[1])
            candidate = Link(journal, endpoint, 'edgeai-test-silent')
            candidate.client.connect_timeout = 0.15
            before = self.socket_count()
            started = time.monotonic()
            try:
                candidate.step()
                self.assertFalse(candidate.ready)
                self.assertLess(time.monotonic() - started, 1.5)
            finally:
                candidate.close()
            self.assertEqual(before, self.socket_count())


if __name__ == '__main__':
    unittest.main()
