"""Driver regressions against real Device SDK, HTTPS, SQLite and TLS MQTT.

Server authority is the explicit native-test fixture. The Spring suite separately
checks real control-plane transactions and S3 results. No SDK calls are mocked.
"""
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
import sys
import threading
import time
import unittest

root = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(root / p) for p in ('runner', 'runner/integration', 'runner/tests', 'backend/app/src/test/fixtures')]
import test_stream_source as fixture
from test_stream_assignment import GENERATION, POD
from test_stream_journal import A
from test_stream_mqtt import eventually
from edgeai_runner.stream_assignment import AssignmentFenced
from edgeai_runner.stream_device_run import DeviceRunSource
from edgeai_runner.stream_journal import Emission, Limits
from edgeai_runner.stream_source import SourceError
from stream_source_completion_probe import open_source
from vd_stream_probe import discover_routes, emit_samples, wait


class StreamDriverTest(unittest.TestCase):
    def setUp(self):
        fixture.StreamSourceTest.setUp(self)
        self.api.duration = 20
        self.api.deadline = time.monotonic() + 20

    def owner(self, owner=None, **options):
        owner = self if owner is None else owner
        owner.source = DeviceRunSource(owner.client, POD, [A.route_id], owner.directory, create=True, **options)
        eventually(owner.source.step, lambda: owner.source.ready)
        return owner.source

    def peer(self, **options):
        peer = SimpleNamespace(addCleanup=self.addCleanup)
        fixture.StreamSourceTest.setUp(peer)
        peer.api.duration = 20
        peer.api.deadline = time.monotonic() + 20
        self.owner(peer, **options)
        return peer

    def samples(self, value=b'4', state=b'offset1'):
        return ([Emission(A.route_id, value, 'application/json')], state)

    def test_discovery_outage_yields_to_existing_source_and_does_not_extend_lease(self):
        peer = self.peer()
        before = self.api.deadline
        self.api.route_status = 503
        ticks = []
        def tick():
            peer.source.step()
            ticks.append(peer.source.source.heartbeats)
            self.assertFalse((self.directory / 'journal').exists())
            if len(ticks) == 2:
                self.api.route_status = None
        self.assertEqual([A.route_id], discover_routes(self.client, POD, 1, tick))
        self.assertEqual(2, len(ticks))
        self.assertGreater(ticks[-1], 0)
        self.assertEqual(before, self.api.deadline)
        self.assertEqual(3, len(self.api.paths))

    def test_discovery_replaced_session_is_terminal_without_retry(self):
        self.api.route_status = 409
        with self.assertRaises(AssignmentFenced):
            discover_routes(self.client, POD, 1, lambda: self.fail('Identity rejection retried'))
        self.assertEqual(1, len(self.api.paths))
        self.assertFalse((self.directory / 'journal').exists())

    def test_bootstrap_retries_before_journal_and_retains_original_deadline(self):
        peer = self.peer()
        self.api.status = 503
        before, lease = time.monotonic(), self.api.deadline
        ticks = []
        def tick():
            peer.source.step()
            ticks.append(peer.source.source.heartbeats)
            self.assertFalse((self.directory / 'journal').exists())
            if len(ticks) == 2:
                self.api.status = None
        self.source = open_source(self.client, POD, [GENERATION], self.directory, tick, timeout=3)
        self.assertEqual(2, len(ticks))
        self.assertGreater(ticks[-1], 0)
        self.assertLessEqual(self.source.deadline, before + 3.02)
        self.assertEqual(lease, self.api.deadline)
        self.assertEqual([None, None, None], self.api.calls)
        self.assertEqual(0, self.source.checkpoint().output_sequences[A.route_id])

    def test_bootstrap_outage_expires_without_journal_or_lease_renewal(self):
        self.api.status = 503
        before, lease = time.monotonic(), self.api.deadline
        with self.assertRaisesRegex(AssertionError, 'bootstrap deadline'):
            open_source(self.client, POD, [GENERATION], self.directory, lambda: None, timeout=.15)
        self.assertLess(time.monotonic() - before, .65)
        self.assertFalse((self.directory / 'journal').exists())
        self.assertEqual(lease, self.api.deadline)
        self.assertTrue(all(value is None for value in self.api.calls))

    def test_bootstrap_cancellation_and_rejection_are_not_retried(self):
        self.api.status = 503
        cancel = threading.Event()
        with self.assertRaisesRegex(SourceError, 'STREAM_SOURCE_CANCELLED'):
            open_source(self.client, POD, [GENERATION], self.directory, cancel.set, cancel=cancel)
        self.assertEqual(1, len(self.api.calls))
        self.api.status = 401
        with self.assertRaises(AssignmentFenced):
            open_source(self.client, POD, [GENERATION], self.directory, lambda: self.fail('Identity rejection retried'))
        self.assertEqual(2, len(self.api.calls))
        self.assertFalse((self.directory / 'journal').exists())

    def test_peer_reconnect_after_first_commit_does_not_duplicate_data_or_end(self):
        first = self.owner()
        peer = self.peer()
        child = peer.source.source
        child.assignments = {GENERATION: replace(child.assignments[GENERATION], deadline=time.monotonic() - 1)}
        sources = {'a': first, 'b': peer.source}
        def tick():
            for source in sources.values():
                source.step()
            self.link.step(.001)
            peer.link.step(.001)
        emit_samples(sources, {'a': self.samples(), 'b': self.samples(b'5', b'offset2')}, tick)
        self.assertTrue(child.closed)
        self.assertEqual(2, peer.source.connections)
        for source, state in ((first, b'offset1'), (peer.source, b'offset2')):
            self.assertEqual(1, source.checkpoint().output_sequences[A.route_id])
            self.assertEqual(state, source.checkpoint().state)
        eventually(tick, lambda: bool(self.sink.pending()) and bool(peer.sink.pending()))
        self.assertEqual(b'4', self.sink.pending()[0].payload)
        self.assertEqual(b'5', peer.sink.pending()[0].payload)
        child = peer.source.source
        child.assignments = {GENERATION: replace(child.assignments[GENERATION], deadline=time.monotonic() - 1)}
        emit_samples(sources, {name: ([Emission(A.route_id, b'', None, 'END')], None) for name in sources}, tick)
        for source in sources.values():
            self.assertEqual(2, source.checkpoint().output_sequences[A.route_id])
            self.assertEqual(['DATA', 'END'], [f.kind for f in source.source.journal.outgoing()])

    def test_peer_capacity_wait_preserves_successful_source_and_adapter_cursors(self):
        first = self.owner()
        peer = self.peer(limits=Limits(max_frames=1))
        peer.source.emit(*self.samples(b'1', b'old'))
        sources = {'a': first, 'b': peer.source}
        consumed = []
        def tick():
            for source in sources.values():
                source.step()
            peer.link.step(.001)
            if peer.sink.pending():
                frame = peer.sink.pending()[0]
                consumed.append(frame.payload)
                peer.sink.commit(peer.sink.checkpoint().revision, [frame], b'consumed')
        emit_samples(sources, {'a': self.samples(), 'b': self.samples(b'5', b'new')}, tick)
        self.assertEqual([b'1'], consumed)
        self.assertEqual(1, first.checkpoint().output_sequences[A.route_id])
        self.assertEqual(2, peer.source.checkpoint().output_sequences[A.route_id])
        self.assertEqual(b'new', peer.source.checkpoint().state)

    def test_authority_expiring_inside_sqlite_commit_rolls_back_before_retry(self):
        first = self.owner()
        peer = self.peer()
        child = peer.source.source
        expired = []
        def expire_after_write(sql):
            if sql.startswith('UPDATE checkpoint SET revision='):
                expired.append(True)
                child.assignments = {GENERATION: replace(child.assignments[GENERATION], deadline=time.monotonic() - 1)}
        child.journal.db.set_trace_callback(expire_after_write)
        sources = {'a': first, 'b': peer.source}
        def tick():
            for source in sources.values():
                source.step()
        emit_samples(sources, {'a': self.samples(), 'b': self.samples(b'5', b'new')}, tick)
        self.assertEqual([True], expired)
        self.assertTrue(child.closed)
        self.assertEqual(2, peer.source.connections)
        for source in sources.values():
            checkpoint = source.checkpoint()
            self.assertEqual(1, checkpoint.revision)
            self.assertEqual(1, checkpoint.output_sequences[A.route_id])
            self.assertEqual(1, len(source.source.journal.outgoing()))
        self.assertEqual(b'new', peer.source.checkpoint().state)

    def test_cancelled_peer_is_terminal_after_other_source_commits(self):
        first = self.owner()
        peer = self.peer()
        peer.source.cancel.set()
        with self.assertRaisesRegex(SourceError, 'STREAM_SOURCE_CANCELLED'):
            emit_samples({'a': first, 'b': peer.source}, {'a': self.samples(), 'b': self.samples()},
                         lambda: self.fail('Cancellation retried'))
        self.assertTrue(peer.source.closed)
        self.assertEqual(1, first.checkpoint().output_sequences[A.route_id])

    def test_polling_keeps_one_deadline(self):
        before = time.monotonic()
        with self.assertRaisesRegex(AssertionError, 'driver deadline'):
            wait(lambda: False, timeout=.05)
        self.assertLess(time.monotonic() - before, .25)


if __name__ == '__main__':
    unittest.main(verbosity=2)
