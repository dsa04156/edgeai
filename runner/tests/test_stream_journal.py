"""Actual SQLite and process-crash tests; broker and control-plane bindings are separate."""
from dataclasses import replace
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from edgeai_runner.stream_journal import Backpressure, Emission, Journal, JournalError, Limits, SequenceGap
from edgeai_runner.stream_protocol import Binding, Frame, Producer, StreamProtocolError

A = Binding('11111111-1111-4111-8111-111111111111', 1,
            Producer('DEVICE_SESSION', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 1, 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'))
B = Binding('22222222-2222-4222-8222-222222222222', 2,
            Producer('DEVICE_SESSION', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 2, 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'))
OUT = Binding('33333333-3333-4333-8333-333333333333', 3,
              Producer('TASK_ATTEMPT', 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', 3))


def data(binding=A, sequence=1, payload=b'4'):
    return Frame(binding, sequence, 'DATA', payload, 'application/json')


class StreamJournalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'journal'

    def journal(self, *, create=True, limits=Limits()):
        journal = Journal(self.path, [A, B], [OUT], limits, create=create)
        self.addCleanup(journal.close)
        return journal

    def test_join_atomically_persists_inputs_calculation_and_outbox_until_processing_ack(self):
        journal = self.journal()
        journal.receive(data())
        journal.receive(data(B, payload=b'5'))
        self.assertEqual({A.route_id: 0, B.route_id: 0}, journal.checkpoint().input_sequences)
        pending = journal.pending()
        state = str(sum(int(f.payload) for f in pending)).encode()
        output = journal.commit(0, pending, state, [Emission(OUT.route_id, state, 'application/json')])
        self.assertEqual((data(OUT, payload=b'9'),), output)
        self.assertEqual(b'9', journal.checkpoint().state)
        self.assertEqual({A.route_id: 1, B.route_id: 1}, journal.checkpoint().input_sequences)
        self.assertEqual((), journal.pending())
        # Reading/publishing repeatedly does not release durable data.
        self.assertEqual(output, journal.outgoing())
        self.assertEqual(output, journal.outgoing())
        journal.close()
        journal = self.journal(create=False)
        self.assertEqual(b'9', journal.checkpoint().state)
        self.assertEqual(output, journal.outgoing())
        journal.acknowledge(OUT, 1)
        self.assertEqual((), journal.outgoing())

    def test_gap_conflicting_replay_and_stale_generation_do_not_advance_input(self):
        journal = self.journal()
        with self.assertRaises(SequenceGap): journal.receive(data(sequence=2))
        self.assertEqual((0, 0), journal.usage())
        self.assertEqual('ACCEPTED', journal.receive(data()))
        self.assertEqual('BUFFERED', journal.receive(data()))
        with self.assertRaises(JournalError): journal.receive(data(payload=b'99'))
        with self.assertRaises(StreamProtocolError): journal.receive(data(replace(A, generation=2)))
        self.assertEqual((data(),), journal.pending())
        journal.commit(0, journal.pending(), b'4')
        self.assertEqual('COMMITTED', journal.receive(data()))
        # Old committed payloads are discarded, not retained or processed a second time.
        self.assertEqual('COMMITTED', journal.receive(data(payload=b'999')))
        self.assertEqual(b'4', journal.checkpoint().state)
        self.assertEqual(1, journal.checkpoint().revision)

    def test_count_and_byte_backpressure_roll_back_without_losing_input_or_state(self):
        journal = self.journal(limits=Limits(max_frames=3))
        journal.receive(data())
        with self.assertRaises(Backpressure): journal.receive(data(sequence=2))
        with self.assertRaises(Backpressure):
            journal.commit(0, [data()], b'changed', [Emission(OUT.route_id, b'1', 'application/json'),
                                                  Emission(OUT.route_id, b'2', 'application/json')])
        self.assertEqual(0, journal.checkpoint().revision)
        self.assertEqual(b'', journal.checkpoint().state)
        self.assertEqual((data(),), journal.pending())
        self.assertEqual((), journal.outgoing())
        journal.commit(0, [data()], b'4', [Emission(OUT.route_id, b'4', 'application/json')])
        journal.receive(data(B))
        with self.assertRaises(Backpressure):
            journal.commit(1, [data(B)], b'8', [Emission(OUT.route_id, b'8', 'application/json')])
        journal.acknowledge(OUT, 1)
        journal.commit(1, [data(B)], b'8', [Emission(OUT.route_id, b'8', 'application/json')])
        self.assertEqual(b'8', journal.checkpoint().state)
        journal.close()
        path = Path(self.temp.name) / 'byte-cap'
        with Journal(path, [A], [], Limits(max_buffer_bytes=len(data().encode()) - 1), create=True) as bounded:
            with self.assertRaises(Backpressure): bounded.receive(data())
            self.assertEqual((0, 0), bounded.usage())

    def test_fast_source_cannot_take_the_capacity_reserved_for_other_join_input_and_output(self):
        journal = self.journal(limits=Limits(max_frames=6))
        journal.receive(data(sequence=1))
        journal.receive(data(sequence=2))
        with self.assertRaises(Backpressure): journal.receive(data(sequence=3))
        journal.receive(data(B))
        output = journal.commit(0, journal.pending(), b'8', [Emission(OUT.route_id, b'8', 'application/json')])
        self.assertEqual((data(OUT, payload=b'8'),), output)

    def test_stale_checkpoint_forged_input_and_oversized_state_never_partially_commit(self):
        journal = self.journal(limits=Limits(max_state_bytes=4))
        journal.receive(data())
        journal.receive(data(B))
        for consumed, state, revision in (([data(), data(B, payload=b'0')], b'4', 0),
                                           ([data()], b'4', 1), ([data()], b'12345', 0),
                                           ([data(), data()], b'4', 0), ([data(sequence=2)], b'4', 0)):
            with self.subTest(revision=revision, state=state), self.assertRaises(JournalError):
                journal.commit(revision, consumed, state)
            self.assertEqual(0, journal.checkpoint().revision)
            self.assertEqual((data(), data(B)), journal.pending())

    def test_end_is_committed_after_all_data_and_prohibits_later_publication(self):
        journal = self.journal()
        end = Frame(A, 2, 'END', b'', None)
        journal.receive(data())
        journal.receive(end)
        self.assertEqual(frozenset(), journal.checkpoint().ended_inputs)
        with self.assertRaises(JournalError): journal.receive(data(sequence=3))
        with self.assertRaises(JournalError): journal.commit(0, [end], b'')
        journal.commit(0, [data()], b'4')
        journal.commit(1, [end], b'4', [Emission(OUT.route_id, b'', None, 'END')])
        self.assertEqual(frozenset([A.route_id]), journal.checkpoint().ended_inputs)
        self.assertEqual('COMMITTED', journal.receive(end))
        with self.assertRaises(JournalError):
            journal.commit(2, [], b'4', [Emission(OUT.route_id, b'5', 'application/json')])
        self.assertEqual(2, journal.checkpoint().revision)

    def test_acknowledgements_require_full_binding_and_cannot_discard_unproduced_sequences(self):
        journal = self.journal()
        output = journal.commit(0, [], b'', [Emission(OUT.route_id, b'1', 'application/json'),
                                          Emission(OUT.route_id, b'2', 'application/json')])
        for binding, sequence in ((replace(OUT, generation=2), 1), (replace(OUT, producer=A.producer), 1),
                                  (OUT, 3), (OUT, True), (OUT, 1.0), (OUT, -1)):
            with self.assertRaises(JournalError): journal.acknowledge(binding, sequence)
            self.assertEqual(output, journal.outgoing())
        journal.acknowledge(OUT, 1)
        journal.acknowledge(OUT, 0)
        journal.acknowledge(OUT, 1)
        self.assertEqual((output[1],), journal.outgoing())

    def test_replay_window_gives_each_output_a_turn_despite_different_sequence_numbers(self):
        with Journal(self.path, [], [A, OUT], create=True) as journal:
            for _ in range(20):
                frame, = journal.commit(journal.checkpoint().revision, [], b'', [Emission(A.route_id, b'1', 'application/json')])
                journal.acknowledge(A, frame.sequence)
            journal.commit(journal.checkpoint().revision, [], b'', [Emission(A.route_id, b'21', 'application/json')])
            journal.commit(journal.checkpoint().revision, [], b'', [Emission(OUT.route_id, b'1', 'application/json'),
                                                                  Emission(OUT.route_id, b'2', 'application/json')])
            self.assertEqual({A.route_id, OUT.route_id}, {f.binding.route_id for f in journal.outgoing(2)})

    def test_reopen_cannot_reset_identity_limits_or_missing_state(self):
        journal = self.journal()
        with self.assertRaises(JournalError): Journal(self.path, [A, B], [OUT])
        journal.close()
        for inputs, limits in (([replace(A, generation=2), B], Limits()), ([A, B], Limits(max_frames=1))):
            with self.assertRaises(JournalError): Journal(self.path, inputs, [OUT], limits)
        with self.assertRaises(FileExistsError): self.journal()
        (self.path / 'journal.sqlite').unlink()
        with self.assertRaises(FileNotFoundError): self.journal(create=False)

    def test_private_directory_and_regular_private_file_are_required(self):
        journal = self.journal()
        self.assertEqual(0o600, (self.path / 'journal.sqlite').stat().st_mode & 0o777)
        journal.close()
        (self.path / 'journal.sqlite').chmod(0o644)
        with self.assertRaises(JournalError): self.journal(create=False)
        linked = Path(self.temp.name) / 'linked'
        linked.symlink_to(self.path)
        with self.assertRaises(JournalError): Journal(linked, [A, B], [OUT])

    def test_actual_process_sigkill_during_transaction_restores_entire_previous_checkpoint(self):
        self._crash_case('during')

    def test_actual_process_sigkill_after_commit_replays_same_output_without_reprocessing(self):
        self._crash_case('after')

    def _crash_case(self, point):
        journal = self.journal()
        journal.receive(data(payload=b'4' * 12000))
        journal.receive(data(B, payload=b'5' * 12000))
        journal.close()
        child = subprocess.run([sys.executable, str(Path(__file__).resolve()), '--crash', str(self.path), point],
                               capture_output=True, timeout=10)
        self.assertEqual(-signal.SIGKILL, child.returncode, child.stderr.decode())
        with Journal(self.path, [A, B], [OUT]) as recovered:
            if point == 'during':
                self.assertEqual(0, recovered.checkpoint().revision)
                self.assertEqual(b'', recovered.checkpoint().state)
                self.assertEqual(2, len(recovered.pending()))
                self.assertEqual((), recovered.outgoing())
            else:
                self.assertEqual(1, recovered.checkpoint().revision)
                self.assertEqual(b'computed-state', recovered.checkpoint().state)
                self.assertEqual((), recovered.pending())
                self.assertEqual((data(OUT, payload=b'9' * 12000),), recovered.outgoing())
                self.assertEqual('COMMITTED', recovered.receive(data(payload=b'4' * 12000)))
                self.assertEqual(b'computed-state', recovered.checkpoint().state)


def crash(directory, point):
    with Journal(directory, [A, B], [OUT]) as journal:
        journal.db.execute('PRAGMA cache_size=1')  # Force dirty-page spill and an actual rollback journal.
        if point == 'during':
            def trace(statement):
                if statement.startswith('UPDATE checkpoint SET revision='):
                    assert (Path(directory) / 'journal.sqlite-journal').stat().st_size > 0
                    os.kill(os.getpid(), signal.SIGKILL)
            journal.db.set_trace_callback(trace)
        journal.commit(0, journal.pending(), b'computed-state', [Emission(OUT.route_id, b'9' * 12000, 'application/json')])
        os.kill(os.getpid(), signal.SIGKILL)


if __name__ == '__main__':
    if len(sys.argv) == 4 and sys.argv[1] == '--crash':
        crash(sys.argv[2], sys.argv[3])
    else:
        unittest.main()
