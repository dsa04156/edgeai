"""Source-only Task owner through HTTPS leases, TLS MQTT and external checkpoints."""
import json
import shutil
import time
import unittest

import test_stream_session as fixtures
from test_stream_session import GENERATIONS, OUT, POD, A, RUNNER
from test_stream_mqtt import eventually
from edgeai_runner.stream_checkpoint_client import CheckpointClient
from edgeai_runner.stream_journal import Limits
from edgeai_runner.stream_session import Session


SUBSCRIPTION = '0f0e0d0c-0b0a-4908-8706-050403020100'


class Feed:
    """Control-plane ALL_OBSERVATIONS contract: items after the presented committed cursor, paged."""
    def __init__(self, page=2):
        self.items, self.calls, self.page, self.drained = [], [], page, None

    def add(self, *values):
        for value in values:
            n = len(self.items) + 1
            self.items.append({'cursor': f'{SUBSCRIPTION}:{n}', 'sampleId': f'{n:064x}',
                               'sample': {'value': value, 'unit': 'C', 'feedPosition': n}})

    def poll(self, timeout, cursor=None):
        self.calls.append((time.monotonic(), cursor))
        after = 0 if cursor is None else int(cursor.split(':')[1])
        bound = len(self.items) if self.drained is None else self.drained
        page = self.items[after:min(after + self.page, bound)]
        if page:
            return {'state': 'DATA', 'items': [dict(i) for i in page], 'more': after + len(page) < bound}
        return {'state': 'END' if self.drained is not None and after >= self.drained else 'WAITING'}


class WorkflowInputSessionTest(unittest.TestCase):
    setUp = fixtures.StreamSessionTest.setUp
    close_flow = fixtures.StreamSessionTest.close_flow
    pump = fixtures.StreamSessionTest.pump
    consume_sink = fixtures.StreamSessionTest.consume_sink

    def test_external_volume_restore_keeps_source_cursor_and_does_not_duplicate_the_last_sample(self):
        import shutil, time
        self.api.duration = 30
        self.api.leases = {k: time.monotonic() + 30 for k in self.api.leases}
        current = {'state': 'DATA', 'sampleId': 'a' * 64, 'sample': {'value': 21, 'unit': 'C'}}
        def open_source(restore=False):
            self.session = Session(self.client, POD, {}, {'sample': [GENERATIONS[OUT.route_id]]},
                ['edgeai-workflow-input'], self.directory, {}, create=True, durability='EXTERNAL',
                limits=Limits(max_frames=6), timeout=20,
                checkpoint_client=CheckpointClient(self.client, POD, [GENERATIONS[OUT.route_id]], storage_ca_file=self.broker.ca),
                source_poll=lambda timeout: dict(current), restore_latest=restore)
        open_source()
        eventually(self.pump, lambda: bool(self.journals['sink'].pending()))
        self.assertEqual(21, json.loads(self.consume_sink().payload)['value'])
        eventually(self.pump, lambda: self.session.publisher.last_receipt is not None
            and self.session.publisher.last_receipt['serial'] == self.session.journal.snapshot_serial)
        self.session.close(); shutil.rmtree(self.directory); self.directory.mkdir(mode=0o700)
        open_source(True)
        for _ in range(8): self.pump()
        self.assertFalse(self.journals['sink'].pending())
        self.assertEqual(1, json.loads(self.session.journal.checkpoint().state)['count'])
        current.update(sampleId='b' * 64, sample={'value': 24, 'unit': 'C'})
        eventually(self.pump, lambda: bool(self.journals['sink'].pending()))
        self.assertEqual(24, json.loads(self.consume_sink().payload)['value'])
        self.assertEqual(2, json.loads(self.session.journal.checkpoint().state)['count'])

    def test_selected_json_samples_are_processed_continuously_and_sealed_on_end(self):
        import sys, time
        from edgeai_runner.stream_journal import Emission
        self.api.duration = 30
        self.api.leases = {k: time.monotonic() + 30 for k in self.api.leases}
        self.session = Session(self.client, POD, {'input': GENERATIONS[A.route_id]}, {},
            [sys.executable, str(RUNNER / 'examples/workflow_sample.py')], self.directory, {},
            create=True, durability='EXTERNAL', limits=Limits(max_frames=6), timeout=20,
            checkpoint_client=CheckpointClient(self.client, POD, [GENERATIONS[A.route_id]], storage_ca_file=self.broker.ca))
        source = self.journals['source-a']
        for value in (21, 24):
            source.commit(source.checkpoint().revision, [], b'', [Emission(A.route_id,
                json.dumps({'value': value, 'unit': 'C', 'quality': 'VALID'}).encode(), 'application/json')])
            eventually(self.pump, lambda: bool(self.session.journal.checkpoint().state)
                and json.loads(self.session.journal.checkpoint().state)['count'] == (1 if value == 21 else 2))
        source.commit(source.checkpoint().revision, [], b'', [Emission(A.route_id, b'', None, 'END')])
        eventually(self.pump, lambda: self.session.terminal_receipt is not None)
        state = json.loads(self.session.journal.checkpoint().state)
        self.assertEqual(2, state['count'])
        self.assertEqual(24, state['lastSample']['value'])
        self.assertEqual(22.5, state['mean'])
        self.assertTrue(state['ended'])

    def test_continuous_input_deduplicates_samples_and_drains_after_external_ack(self):
        self.api.duration = 30
        import time
        self.api.leases = {k: time.monotonic() + 30 for k in self.api.leases}
        current = {'state': 'DATA', 'sampleId': 'a' * 64, 'sample': {'value': 21, 'unit': 'C'}}
        self.session = Session(self.client, POD, {}, {'sample': [GENERATIONS[OUT.route_id]]},
            ['edgeai-workflow-input'], self.directory, {}, create=True, durability='EXTERNAL',
            limits=Limits(max_frames=6), timeout=20,
            checkpoint_client=CheckpointClient(self.client, POD, [GENERATIONS[OUT.route_id]], storage_ca_file=self.broker.ca),
            source_poll=lambda timeout: dict(current))
        eventually(self.pump, lambda: bool(self.journals['sink'].pending()))
        self.assertEqual({'value': 21, 'unit': 'C'}, json.loads(self.consume_sink().payload))
        for _ in range(8): self.pump()
        self.assertFalse(self.journals['sink'].pending())
        current.update(sampleId='b' * 64, sample={'value': 24, 'unit': 'C'})
        eventually(self.pump, lambda: bool(self.journals['sink'].pending()))
        self.assertEqual(24, json.loads(self.consume_sink().payload)['value'])
        current.clear(); current['state'] = 'END'
        eventually(self.pump, lambda: bool(self.journals['sink'].pending()))
        self.assertIsNone(self.session.terminal_receipt)
        self.assertEqual('END', self.consume_sink().kind)
        eventually(self.pump, lambda: self.session.terminal_receipt is not None)
        self.assertEqual(2, json.loads(self.session.journal.checkpoint().state)['count'])
        self.assertTrue(self.session.settled)

    def open_feed(self, feed, restore=False, frames=6):
        self.session = Session(self.client, POD, {}, {'sample': [GENERATIONS[OUT.route_id]]},
            ['edgeai-workflow-input'], self.directory, {}, create=True, durability='EXTERNAL',
            limits=Limits(max_frames=frames), timeout=20,
            checkpoint_client=CheckpointClient(self.client, POD, [GENERATIONS[OUT.route_id]], storage_ca_file=self.broker.ca),
            source_poll=feed.poll, restore_latest=restore)

    def received(self, count):
        values = []
        def step():
            self.pump()
            while self.journals['sink'].pending() and len(values) < count:
                values.append(json.loads(self.consume_sink().payload)['value'])
        eventually(step, lambda: len(values) >= count)
        return values

    def leases(self):
        self.api.duration = 30
        self.api.leases = {k: time.monotonic() + 30 for k in self.api.leases}

    def test_full_observation_backlog_is_paged_immediately_and_cursor_commits_with_each_emission_across_restore(self):
        self.leases(); feed = Feed(); feed.add(1, 2, 3, 4, 5)
        self.open_feed(feed)
        self.assertEqual([1, 2, 3, 4, 5], self.received(5))
        pages = [at for at, _ in feed.calls[:3]]
        self.assertLess(pages[-1] - pages[0], .9, 'backlog pages must not wait a fixed poll interval each')
        self.assertEqual([None, f'{SUBSCRIPTION}:2', f'{SUBSCRIPTION}:4'], [c for _, c in feed.calls[:3]])
        state = json.loads(self.session.journal.checkpoint().state)
        self.assertEqual((f'{SUBSCRIPTION}:5', 5), (state['cursor'], state['count']))
        eventually(self.pump, lambda: self.session.publisher.last_receipt is not None
            and self.session.publisher.last_receipt['serial'] == self.session.journal.snapshot_serial)
        self.session.close(); shutil.rmtree(self.directory); self.directory.mkdir(mode=0o700)
        feed.calls.clear(); feed.add(6, 7)
        self.open_feed(feed, restore=True)
        self.assertEqual([6, 7], self.received(2))
        self.assertEqual(f'{SUBSCRIPTION}:5', feed.calls[0][1])
        self.assertFalse(self.journals['sink'].pending())

    def test_full_observation_backpressure_retains_the_candidate_and_never_polls_past_the_committed_cursor(self):
        self.leases(); feed = Feed(page=3); feed.add(*range(1, 11))
        self.open_feed(feed, frames=2)
        for _ in range(300):
            for name, link in self.links.items():
                link.step(.001)
            self.session.step()
        committed = json.loads(self.session.journal.checkpoint().state)
        self.assertLess(committed['count'], 10)
        for _, cursor in feed.calls:
            self.assertLessEqual(0 if cursor is None else int(cursor.split(':')[1]), committed['count'])
        self.assertEqual(list(range(1, 11)), self.received(10))
        feed.drained = 10
        eventually(self.pump, lambda: bool(self.journals['sink'].pending()))
        self.assertEqual('END', self.consume_sink().kind)
        eventually(self.pump, lambda: self.session.terminal_receipt is not None)
        self.assertEqual(10, json.loads(self.session.journal.checkpoint().state)['count'])
