"""Real SQLite source handover, ownership and process-crash verification."""
from dataclasses import replace
import os
from pathlib import Path
import signal
import sqlite3
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from edgeai_runner.stream_journal import Backpressure, Emission, Journal, JournalError, Limits
from test_stream_journal import A, OUT, data

NEXT = replace(A, generation=A.generation+1)


class SourceJournalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)/'source'

    def seed(self, *, binding=A, limits=Limits(), durability='LOCAL', large=False):
        with Journal(self.path, [], [binding], limits, create=True, durability=durability) as journal:
            for n in range(3):
                journal.commit(n, [], str(n+1).encode(), [Emission(binding.route_id, b'4'*12000 if large else b'4', 'application/json')])
            journal.acknowledge(binding, 1) if durability == 'LOCAL' else None
            journal.commit(3, [], b'last-adapter-offset', [Emission(binding.route_id, b'', None, 'END')])
            return journal.snapshot_serial, journal.checkpoint(), journal.outgoing()

    def test_only_generation_changes_while_state_sequence_acks_and_end_survive(self):
        serial, checkpoint, frames = self.seed()
        with Journal(self.path, [], [NEXT], source_handover=True, guard=lambda:None) as journal:
            self.assertEqual(checkpoint, journal.checkpoint())
            self.assertEqual(tuple(replace(f, binding=NEXT) for f in frames), journal.outgoing())
            self.assertEqual((4, 1, 1), journal._route(A.route_id))
            self.assertEqual(serial+1, journal.snapshot_serial)
        with self.assertRaises(JournalError): Journal(self.path, [], [A])
        with Journal(self.path, [], [NEXT], source_handover=True, guard=lambda:None) as journal:
            self.assertEqual(serial+1, journal.snapshot_serial)
            journal.acknowledge(NEXT, 4)
            self.assertEqual((), journal.outgoing())
            with self.assertRaises(JournalError): journal.commit(4, [], b'', [Emission(A.route_id, b'5', 'application/json')])

    def test_guard_expiry_at_commit_rolls_back_frames_manifest_and_serial(self):
        serial, checkpoint, frames = self.seed()
        calls = []
        def guard():
            calls.append(1)
            if len(calls) == 2: raise JournalError('fixture authority expired')
        with self.assertRaisesRegex(JournalError, 'expired'):
            Journal(self.path, [], [NEXT], source_handover=True, guard=guard)
        self.assertEqual(2, len(calls))
        with Journal(self.path, [], [A]) as journal:
            self.assertEqual((serial, checkpoint, frames), (journal.snapshot_serial, journal.checkpoint(), journal.outgoing()))

    def test_fanout_preserves_unchanged_route_and_independent_processing_positions(self):
        other=replace(A,route_id=OUT.route_id,generation=7)
        with Journal(self.path,[],[A,other],create=True) as journal:
            journal.commit(0,[],b'cursor1',[Emission(b.route_id,b'4','application/json') for b in [A,other]])
            journal.acknowledge(A,1)
            journal.commit(1,[],b'cursor2',[Emission(b.route_id,b'5','application/json') for b in [A,other]])
            serial=journal.snapshot_serial;checkpoint=journal.checkpoint();frames=journal.outgoing()
        with Journal(self.path,[],[NEXT,other],source_handover=True,guard=lambda:None) as journal:
            self.assertEqual(checkpoint,journal.checkpoint())
            self.assertEqual(serial+1,journal.snapshot_serial)
            self.assertEqual(tuple(replace(f,binding=NEXT) if f.binding==A else f for f in frames),journal.outgoing())
            self.assertEqual((2,1,0),journal._route(A.route_id))
            self.assertEqual((2,0,0),journal._route(other.route_id))

    def test_new_generation_encoding_cannot_exceed_existing_byte_reservation(self):
        old = replace(A, generation=9); new = replace(A, generation=10)
        limits = Limits(max_frames=1, max_buffer_bytes=len(data(old).encode()))
        with Journal(self.path, [], [old], limits, create=True) as journal:
            journal.commit(0, [], b'offset', [Emission(A.route_id,b'4','application/json')])
            serial = journal.snapshot_serial
        with self.assertRaises(Backpressure):
            Journal(self.path, [], [new], limits, source_handover=True, guard=lambda:None)
        with Journal(self.path, [], [old], limits) as journal:
            self.assertEqual(serial, journal.snapshot_serial)
            self.assertEqual((data(old),), journal.outgoing())

    def test_identity_route_limits_regression_and_missing_guard_are_rejected(self):
        serial, checkpoint, frames = self.seed(binding=NEXT)
        cases = [([A], Limits(), lambda:None), ([replace(NEXT,producer=replace(A.producer,epoch=2))], Limits(), lambda:None),
                 ([OUT], Limits(), lambda:None), ([replace(NEXT,route_id=OUT.route_id)], Limits(), lambda:None),
                 ([replace(NEXT,generation=3)], Limits(max_frames=256), lambda:None), ([NEXT], Limits(), None)]
        for outputs, limits, guard in cases:
            with self.subTest(outputs=outputs), self.assertRaises(JournalError):
                Journal(self.path, [], outputs, limits, source_handover=True, guard=guard)
        with Journal(self.path, [], [NEXT]) as journal:
            self.assertEqual((serial, checkpoint, frames), (journal.snapshot_serial,journal.checkpoint(),journal.outgoing()))
            with self.assertRaises(JournalError): Journal(self.path, [], [replace(NEXT,generation=3)], source_handover=True, guard=lambda:None)

    def test_task_input_and_external_checkpoint_cannot_use_local_source_handover(self):
        for name, inputs, outputs, durability, candidate in [
            ('task',[],[OUT],'LOCAL',False), ('input',[A],[OUT],'LOCAL',False),
            ('external',[],[A],'EXTERNAL',False), ('candidate',[],[A],'LOCAL',True)]:
            path = Path(self.temp.name)/name
            with Journal(path,inputs,outputs,create=True,durability=durability) as journal:
                if candidate:
                    # A stale external candidate must fail closed even in a malformed LOCAL volume.
                    journal.db.execute('CREATE TABLE snapshot_candidate (id INTEGER PRIMARY KEY, wire BLOB NOT NULL)')
                    journal.db.execute('INSERT INTO snapshot_candidate VALUES (1,?)',(b'fixture candidate',))
            with self.subTest(name=name), self.assertRaises(JournalError):
                Journal(path,inputs,[replace(b,generation=b.generation+1) for b in outputs], source_handover=True,guard=lambda:None)
            with Journal(path,inputs,outputs,durability=durability) as journal:
                self.assertEqual(b'',journal.checkpoint().state)

    def test_process_killed_during_rewrite_restores_all_original_rows(self):
        self.crash_case('during')

    def test_process_killed_after_commit_reopens_the_new_generation_once(self):
        self.crash_case('after')

    def crash_case(self, point):
        serial, checkpoint, frames = self.seed(large=True)
        child = subprocess.run([sys.executable, str(Path(__file__).resolve()), '--crash', str(self.path), point],
                               capture_output=True, timeout=10)
        self.assertEqual(-signal.SIGKILL, child.returncode, child.stderr.decode())
        binding = A if point == 'during' else NEXT
        with Journal(self.path,[],[binding],source_handover=True,guard=lambda:None) as journal:
            self.assertEqual(checkpoint,journal.checkpoint())
            self.assertEqual(tuple(replace(f,binding=binding) for f in frames),journal.outgoing())
            self.assertEqual(serial+(point=='after'),journal.snapshot_serial)


def crash(directory, point):
    original = sqlite3.connect
    def connect(*args, **kwargs):
        db = original(*args, **kwargs)
        def trace(statement):
            if statement.startswith('UPDATE frame SET wire='):
                db.execute('PRAGMA cache_size=1')
            if point == 'during' and statement.startswith('UPDATE durability SET serial='):
                assert (Path(directory)/'journal.sqlite-journal').stat().st_size > 0
                os.kill(os.getpid(), signal.SIGKILL)
        db.set_trace_callback(trace)
        return db
    sqlite3.connect = connect
    with Journal(directory,[],[NEXT],source_handover=True,guard=lambda:None):
        os.kill(os.getpid(), signal.SIGKILL)


if __name__ == '__main__':
    if len(sys.argv) == 4 and sys.argv[1] == '--crash': crash(sys.argv[2],sys.argv[3])
    else: unittest.main()
