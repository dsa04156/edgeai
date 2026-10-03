"""Actual TLS MQTT -> persistent workload -> SQLite -> processing ACK integration.

Assignments are explicit fixtures here. Spring authentication is verified separately.
"""
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
import urllib.request

from test_stream_mqtt import Broker, eventually
from test_stream_journal import A, B, OUT
from test_stream_assignment import GENERATION, document
from edgeai_runner.stream_assignment import Assignment
from edgeai_runner.stream_journal import Emission, Journal, Limits
from edgeai_runner.stream_mqtt import Link, MqttError, topic
from edgeai_runner.stream_processor import Processor
from edgeai_runner.stream_workload import WorkloadError

RUNNER = Path(__file__).resolve().parents[1]
COMMAND = [sys.executable, str(RUNNER / 'examples/stream_sum.py')]
FIXTURE = str(RUNNER / 'tests/fixtures/stream_workload.py')


class StreamProcessorTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='edgeai-processor-')
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.broker = Broker(self.root)
        self.addCleanup(self.broker.stop)
        self.broker.enable_tls()
        self.journals, self.links = {}, {}
        self.processor = None
        self.addCleanup(self.close_peers)
        (self.root / 'work').mkdir(mode=0o700)

    def close_peers(self):
        if self.processor is not None:
            self.processor.close()
        for link in self.links.values():
            link.close(force=True)
        for journal in self.journals.values():
            journal.close()

    def assignments(self, duration=30, outputs=(OUT,)):
        result = []
        for binding in (A, B, *outputs):
            value = document(binding, direction='PRODUCER' if binding in outputs else 'CONSUMER',
                             duration=duration, username='processor', secret=self.broker.credentials['processor'].read_text())
            value['mqtt'].update(host='localhost', port=self.broker.port, tls=True, caPem=self.broker.ca.read_text())
            result.append(Assignment.decode(json.dumps(value).encode(), OUT.producer, GENERATION, time.monotonic()))
        return result

    def start(self, *, create=True, command=None, duration=30, mode='zip', outputs=(OUT,)):
        journal = Journal(self.root / 'processor', [A, B], outputs, Limits(max_frames=2*(2+len(outputs))), create=create)
        self.journals['processor'] = journal
        link = Link.from_assignments(journal, self.assignments(duration, outputs))
        self.links['processor'] = link
        processor = Processor(link, command or COMMAND, self.root / 'work', {'a': A.route_id, 'b': B.route_id},
                              {'sum': [binding.route_id for binding in outputs]}, {'mode': mode}, create=create)
        self.processor = processor
        return processor

    def peer(self, actor, inputs, outputs):
        journal = Journal(self.root / actor, inputs, outputs, Limits(max_frames=6), create=True)
        self.journals[actor] = journal
        self.links[actor] = Link(journal, self.broker.endpoint(actor), 'edgeai-test-' + actor)
        return journal

    def setup_flow(self, **options):
        self.peer('source-a', [], [A])
        self.peer('source-b', [], [B])
        sink = self.peer('sink', options.get('outputs', (OUT,)), [])
        processor = self.start(**options)
        eventually(self.pump, lambda: all(link.ready for link in self.links.values()))
        return processor, sink

    def pump(self):
        for actor, link in self.links.items():
            if actor != 'processor' and not link.closed:
                link.step(.001)
        self.processor.step()

    def emit(self, actor, binding, payload, kind='DATA'):
        journal = self.journals[actor]
        journal.commit(journal.checkpoint().revision, [], b'',
                       [Emission(binding.route_id, payload, None if kind == 'END' else 'application/json', kind)])

    def pair(self, left, right):
        self.emit('source-a', A, str(left).encode())
        self.emit('source-b', B, str(right).encode())

    def consume(self, journal):
        pending = journal.pending()
        self.assertTrue(pending)
        state = pending[0].payload if pending[0].kind == 'DATA' else journal.checkpoint().state
        journal.commit(journal.checkpoint().revision, pending, state)
        return pending[0]

    def test_zip_wait_persistent_model_exact_sum_end_and_lost_terminal_ack_replay(self):
        processor, sink = self.setup_flow()
        self.emit('source-a', A, b'4')
        eventually(self.pump, lambda: processor._waiting is not None)
        count = processor.requests
        pid = processor.workload.process.pid
        for _ in range(20):
            self.pump()
        self.assertEqual(count, processor.requests)
        self.assertEqual(0, processor.journal.checkpoint().revision)
        self.emit('source-b', B, b'5')
        eventually(self.pump, lambda: bool(sink.pending()))
        self.assertEqual(b'9', self.consume(sink).payload)
        self.pair(2, 3)
        eventually(self.pump, lambda: bool(sink.pending()))
        self.assertEqual(b'14', self.consume(sink).payload)
        self.assertEqual(pid, processor.workload.process.pid)
        self.emit('source-a', A, b'', 'END')
        self.emit('source-b', B, b'', 'END')
        eventually(self.pump, lambda: processor._candidate is not None and processor._candidate[3])
        # Disconnect upstream before final commit/ACK; its durable END must replay later.
        self.links['source-a'].close(force=True)
        eventually(self.pump, lambda: processor.complete and bool(sink.pending()))
        self.assertEqual('END', self.consume(sink).kind)
        eventually(self.pump, lambda: processor.settled)
        revision, count = processor.journal.checkpoint().revision, processor.requests
        self.assertIsNone(processor.workload)
        self.assertFalse(processor.link.closed)
        self.assertTrue(self.journals['source-a'].outgoing())
        self.links['source-a'] = Link(self.journals['source-a'], self.broker.endpoint('source-a'), 'edgeai-test-source-a')
        eventually(self.pump, lambda: not self.journals['source-a'].outgoing())
        self.assertEqual(revision, processor.journal.checkpoint().revision)
        self.assertEqual(count, processor.requests)
        self.assertEqual(b'14', processor.journal.checkpoint().state)

    def test_slow_sink_holds_one_candidate_without_recomputing_or_advancing_checkpoint(self):
        processor, sink = self.setup_flow()
        for left, right, revision in ((1, 2, 1), (2, 2, 2)):
            self.pair(left, right)
            eventually(self.pump, lambda: processor.journal.checkpoint().revision == revision)
        self.pair(2, 3)
        eventually(self.pump, lambda: processor._candidate is not None)
        count = processor.requests
        for _ in range(30):
            self.pump()
        self.assertEqual(2, processor.journal.checkpoint().revision)
        self.assertEqual(b'7', processor.journal.checkpoint().state)
        self.assertEqual(count, processor.requests)
        self.assertEqual(2, len(processor.journal.outgoing()))
        self.assertEqual(b'3', self.consume(sink).payload)
        eventually(self.pump, lambda: processor.journal.checkpoint().revision == 3)
        self.assertEqual(count, processor.requests)
        self.assertEqual(b'12', processor.journal.checkpoint().state)
        for expected in (b'7', b'12'):
            eventually(self.pump, lambda: bool(sink.pending()))
            self.assertEqual(expected, self.consume(sink).payload)
        eventually(self.pump, lambda: not processor.journal.outgoing())

    def test_same_volume_reopen_replays_output_and_restores_model_state_but_rejects_different_spec(self):
        processor, sink = self.setup_flow()
        self.pair(4, 5)
        eventually(self.pump, lambda: processor.journal.checkpoint().revision == 1)
        pid = processor.workload.process.pid
        processor.close()
        processor.journal.close()
        with self.assertRaises(WorkloadError):
            self.start(create=False, mode='arrival')
        self.links['processor'].close(force=True)
        self.journals['processor'].close()
        processor = self.start(create=False)
        eventually(self.pump, lambda: bool(sink.pending()))
        self.assertEqual(b'9', self.consume(sink).payload)
        self.assertEqual(0, processor.requests)
        self.pair(1, 2)
        eventually(self.pump, lambda: processor.journal.checkpoint().revision == 2)
        self.assertEqual(b'12', processor.journal.checkpoint().state)
        self.assertNotEqual(pid, processor.workload.process.pid)
        eventually(self.pump, lambda: bool(sink.pending()))
        self.assertEqual(b'12', self.consume(sink).payload)

    def test_invalid_workload_response_cannot_commit_input_state_or_output(self):
        self.assert_rejected('bad-revision')

    def test_one_output_port_fans_out_final_data_and_end_in_one_checkpoint(self):
        second = replace(OUT, route_id='44444444-4444-4444-8444-444444444444')
        acl = self.root / 'acl'
        value = acl.read_text().replace('user processor\n', 'user processor\n'
                + f'topic write {topic(second,"frames")}\ntopic read {topic(second,"acks")}\n')
        value = value.replace('user sink\n', 'user sink\n'
                + f'topic read {topic(second,"frames")}\ntopic write {topic(second,"acks")}\n')
        self.broker.stop(); acl.write_text(value); self.broker.start()
        processor, sink = self.setup_flow(command=[sys.executable, FIXTURE, 'final-data'], outputs=(OUT, second))
        self.emit('source-a', A, b'', 'END')
        self.emit('source-b', B, b'', 'END')
        eventually(self.pump, lambda: processor.complete)
        self.assertEqual(1, processor.journal.checkpoint().revision)
        self.assertEqual(4, len(processor.journal.outgoing()))
        eventually(self.pump, lambda: len(sink.pending()) == 2)
        self.assertEqual({b'9'}, {frame.payload for frame in sink.pending()})
        sink.commit(0, sink.pending(), b'9')
        eventually(self.pump, lambda: len(sink.pending()) == 2)
        self.assertEqual({'END'}, {frame.kind for frame in sink.pending()})
        sink.commit(1, sink.pending(), b'9')
        eventually(self.pump, lambda: processor.settled)

    def test_foreign_output_port_is_rejected(self):
        self.assert_rejected('foreign-port')

    def test_output_above_assigned_payload_limit_is_rejected(self):
        self.assert_rejected('large-output')

    def test_premature_completion_is_rejected(self):
        self.assert_rejected('early-complete')

    def test_wait_cannot_mutate_model_state(self):
        self.assert_rejected('wait-state')

    def test_consuming_an_unoffered_sequence_is_rejected(self):
        self.assert_rejected('wrong-sequence')

    def test_duplicate_json_properties_are_rejected(self):
        self.assert_rejected('duplicate-json')

    def assert_rejected(self, mode):
        processor, _ = self.setup_flow(command=[sys.executable, FIXTURE, mode])
        self.pair(4, 5)
        with self.assertRaisesRegex(WorkloadError, 'INVALID_WORKLOAD_RESPONSE'):
            eventually(self.pump, lambda: processor.closed)
        self.assertTrue(processor.closed)
        self.assertEqual(0, processor.journal.checkpoint().revision)
        self.assertEqual(b'', processor.journal.checkpoint().state)
        self.assertEqual((), processor.journal.outgoing())
        self.assertTrue(self.journals['source-a'].outgoing())
        self.assertIsNotNone(processor.workload.process.poll())

    def test_watchdog_fences_model_while_owner_is_blocked_in_actual_http(self):
        processor, _ = self.setup_flow(command=[sys.executable, FIXTURE, 'hang'])
        self.pair(4, 5)
        eventually(self.pump, lambda: processor.workload is not None)
        deadline = time.monotonic() + .3
        processor.refresh([replace(a, deadline=deadline) for a in processor.link.assignments])
        observed = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_GET(self):
                time.sleep(.7)
                observed.append(processor.workload.process.poll() is not None)
                self.send_response(200)
                self.send_header('Content-Length', '0')
                self.end_headers()
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            # No Processor/Link polling during the real blocking HTTP operation.
            with urllib.request.urlopen(f'http://127.0.0.1:{server.server_port}', timeout=3) as response:
                self.assertEqual(200, response.status)
        finally:
            server.shutdown(); server.server_close(); thread.join(2)
        self.assertEqual([True], observed)
        self.assertEqual(0, processor.journal.checkpoint().revision)
        with self.assertRaises((MqttError, WorkloadError)):
            processor.refresh(self.assignments())
        self.assertTrue(processor.link.closed)
        self.assertTrue(self.journals['source-a'].outgoing())


if __name__ == '__main__':
    unittest.main()
