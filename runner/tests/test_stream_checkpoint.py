from dataclasses import replace
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import unittest

from test_stream_journal import A, B, OUT, data
from edgeai_runner.stream_checkpoint import Snapshot, capture, confirm, encode, restore
from edgeai_runner.stream_journal import Emission, Journal, JournalError, Limits, Backpressure
from edgeai_runner.stream_protocol import Frame

EXECUTION = 'a' * 64


class StreamCheckpointTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.limits = Limits(max_frames=9)
        self.journal = Journal(self.root/'original', [A,B], [OUT], self.limits, create=True, durability='EXTERNAL')
        self.addCleanup(self.journal.close)

    def calculate(self):
        self.journal.receive(data()); self.journal.receive(data(B, payload=b'5'))
        self.journal.commit(0, self.journal.pending(), b'9', [Emission(OUT.route_id,b'9','application/json')])

    def restore(self, snapshot, **overrides):
        options = dict(expected_sha256=snapshot.sha256, execution_sha256=EXECUTION)
        options.update(overrides)
        journal = restore(self.root/'new-volume', snapshot, [A,B], [OUT], self.limits, **options)
        self.addCleanup(journal.close)
        return journal

    def test_external_frontier_blocks_ack_and_output_until_exact_receipt_and_survives_reopen(self):
        self.calculate(); journal=self.journal
        self.assertEqual({A.route_id:0,B.route_id:0},journal.processing_sequences())
        self.assertEqual(1,len(journal.outgoing()))
        self.assertEqual((),journal.outgoing(confirmed_only=True))
        with self.assertRaisesRegex(JournalError,'external checkpoint'):journal.acknowledge(OUT,1)
        snapshot=capture(journal,EXECUTION)
        for serial,sha in ((snapshot.serial+1,snapshot.sha256),(snapshot.serial,'b'*64)):
            with self.assertRaises(JournalError):confirm(journal,serial,sha)
            self.assertEqual((),journal.outgoing(confirmed_only=True))
        serial=journal.snapshot_serial
        confirm(journal,snapshot.serial,snapshot.sha256)
        confirm(journal,snapshot.serial,snapshot.sha256)
        self.assertEqual(serial,journal.snapshot_serial)
        self.assertIsNone(capture(journal,EXECUTION))
        journal.close()
        with Journal(journal.directory,[A,B],[OUT],self.limits,durability='EXTERNAL') as reopened:
            self.assertEqual({A.route_id:1,B.route_id:1},reopened.processing_sequences())
            self.assertEqual((data(OUT,payload=b'9'),),reopened.outgoing(confirmed_only=True))
            reopened.acknowledge(OUT,1)
            self.assertEqual((),reopened.outgoing())

    def test_pending_candidate_is_fixed_across_new_input_and_owner_restart(self):
        self.calculate(); snapshot=capture(self.journal,EXECUTION)
        self.journal.receive(data(sequence=2,payload=b'3'))
        self.assertGreater(self.journal.snapshot_serial,snapshot.serial)
        self.assertEqual(snapshot,capture(self.journal,EXECUTION))
        self.journal.close()
        with Journal(self.root/'original',[A,B],[OUT],self.limits,durability='EXTERNAL') as journal:
            self.assertEqual(snapshot,capture(journal,EXECUTION))
            confirm(journal,snapshot.serial,snapshot.sha256)
            second=capture(journal,EXECUTION)
            self.assertGreater(second.serial,snapshot.serial)
            # An old duplicate receipt cannot discard the newer pending candidate.
            confirm(journal,snapshot.serial,snapshot.sha256)
            self.assertEqual(second,capture(journal,EXECUTION))
            confirm(journal,second.serial,second.sha256)
            with self.assertRaises(JournalError):confirm(journal,snapshot.serial,snapshot.sha256)

    def test_snapshot_serial_tracks_inbox_and_output_ack_not_only_computation(self):
        journal=self.journal
        self.assertEqual(0,journal.snapshot_serial)
        journal.receive(data()); self.assertEqual(1,journal.snapshot_serial)
        journal.receive(data()); self.assertEqual(1,journal.snapshot_serial)
        journal.commit(0,journal.pending(),b'4',[Emission(OUT.route_id,b'4','application/json')])
        self.assertEqual(2,journal.snapshot_serial)
        snapshot=capture(journal,EXECUTION);confirm(journal,snapshot.serial,snapshot.sha256)
        journal.acknowledge(OUT,1)
        self.assertEqual(3,journal.snapshot_serial)
        self.assertEqual(1,journal.checkpoint().revision)
        journal.acknowledge(OUT,0);self.assertEqual(3,journal.snapshot_serial)

    def test_actual_volume_removal_restore_retains_state_pending_input_output_and_end(self):
        self.calculate(); journal=self.journal
        journal.receive(data(sequence=2,payload=b'2'))
        journal.receive(Frame(A,3,'END',b'',None))
        journal.receive(Frame(B,2,'END',b'',None))
        snapshot=capture(journal,EXECUTION);confirm(journal,snapshot.serial,snapshot.sha256)
        journal.close();shutil.rmtree(journal.directory)
        recovered=self.restore(snapshot)
        self.assertEqual(b'9',recovered.checkpoint().state)
        self.assertEqual([data(sequence=2,payload=b'2'),Frame(B,2,'END',b'',None)],list(recovered.pending()))
        self.assertEqual((data(OUT,payload=b'9'),),recovered.outgoing(confirmed_only=True))
        self.assertEqual('COMMITTED',recovered.receive(data()))
        self.assertEqual(EXECUTION,(recovered.directory/'processor.sha256').read_text())
        recovered.acknowledge(OUT,1)
        recovered.commit(1,recovered.pending(),b'11',[Emission(OUT.route_id,b'11','application/json')])
        # The new calculation cannot be observed under the old confirmed snapshot.
        self.assertEqual((),recovered.outgoing(confirmed_only=True))
        recovered.commit(2,recovered.pending(),b'11',[Emission(OUT.route_id,b'',None,'END')])
        final=capture(recovered,EXECUTION);confirm(recovered,final.serial,final.sha256)
        self.assertEqual(['DATA','END'],[f.kind for f in recovered.outgoing(confirmed_only=True)])
        self.assertEqual(frozenset([A.route_id,B.route_id]),recovered.checkpoint().ended_inputs)

    def test_corrupt_digest_execution_or_changed_binding_cannot_create_restore_directory(self):
        self.calculate();snapshot=capture(self.journal,EXECUTION)
        for options in ({'expected_sha256':'b'*64},{'execution_sha256':'b'*64}):
            with self.assertRaises(JournalError):self.restore(snapshot,**options)
            self.assertFalse((self.root/'new-volume').exists())
        with self.assertRaises(JournalError):
            restore(self.root/'new-volume',snapshot,[replace(A,generation=2),B],[OUT],self.limits,
                    expected_sha256=snapshot.sha256,execution_sha256=EXECUTION)
        self.assertFalse((self.root/'new-volume').exists())

    def test_malformed_snapshot_rejects_gaps_end_inconsistency_capacity_and_ambiguous_json(self):
        self.calculate();snapshot=capture(self.journal,EXECUTION)
        mutations = [lambda d:d.update(serial=True), lambda d:d.update(revision=d['serial']+1),
                     lambda d:d.update(stateBase64='===='),lambda d:d.update(extra='unexpected'),
                     lambda d:d['routes'][2].update(received=2),lambda d:d['routes'][2].update(ended=True),
                     lambda d:d['routes'][2]['frames'][0].update(generation=99),
                     lambda d:d['manifest']['limits'].update(max_buffer_bytes=3),
                     lambda d:d['routes'].append(d['routes'][0])]
        for mutate in mutations:
            value=snapshot.document();mutate(value)
            with self.assertRaises(ValueError):Snapshot(encode(value))
        for wire in (b'{"serial":0,"serial":1}',snapshot.wire+b' ',b'NaN',b'['*2000+b']'*2000):
            with self.assertRaises(ValueError):Snapshot(wire)

    def test_mode_cannot_silently_downgrade_or_upgrade_and_rollbacks_do_not_advance_serial(self):
        journal=self.journal
        for n in range(1,4):journal.receive(data(sequence=n))
        serial=journal.snapshot_serial
        with self.assertRaises(Backpressure):journal.receive(data(sequence=4))
        self.assertEqual(serial,journal.snapshot_serial)
        def fenced():raise RuntimeError('fixture-fenced')
        journal.authority_guard=fenced
        with self.assertRaisesRegex(RuntimeError,'fixture-fenced'):capture(journal,EXECUTION)
        journal.authority_guard=None
        journal.close()
        with self.assertRaisesRegex(JournalError,'durability changed'):
            Journal(journal.directory,[A,B],[OUT],self.limits)
        with Journal(self.root/'local',[A],[],create=True) as local:
            local.receive(data());local.commit(0,local.pending(),b'4')
            self.assertEqual({A.route_id:1},local.processing_sequences())
        with self.assertRaisesRegex(JournalError,'durability changed'):
            Journal(self.root/'local',[A],[],durability='EXTERNAL')

    def test_confirmation_expiry_rolls_back_frontier_and_keeps_candidate(self):
        self.calculate();snapshot=capture(self.journal,EXECUTION)
        calls=[]
        def guard():
            calls.append(1)
            if len(calls)==2:raise RuntimeError('fixture-expired')
        self.journal.authority_guard=guard
        with self.assertRaisesRegex(RuntimeError,'fixture-expired'):confirm(self.journal,snapshot.serial,snapshot.sha256)
        self.journal.authority_guard=None
        self.assertEqual((),self.journal.outgoing(confirmed_only=True))
        self.assertEqual(snapshot,capture(self.journal,EXECUTION))
        confirm(self.journal,snapshot.serial,snapshot.sha256)
        self.assertEqual(1,len(self.journal.outgoing(confirmed_only=True)))

    def test_sigkill_during_confirmation_rolls_back_all_frontiers_and_preserves_candidate(self):
        self.calculate();snapshot=capture(self.journal,EXECUTION);self.journal.close()
        code='''
import os,signal,sys
from edgeai_runner.stream_journal import Journal,Limits
from edgeai_runner.stream_checkpoint import confirm
from test_stream_journal import A,B,OUT
with Journal(sys.argv[1],[A,B],[OUT],Limits(max_frames=9),durability='EXTERNAL') as journal:
    def trace(statement):
        if statement.startswith('UPDATE durability SET confirmed_serial='):
            os.kill(os.getpid(),signal.SIGKILL)
    journal.db.set_trace_callback(trace)
    confirm(journal,int(sys.argv[2]),sys.argv[3])
'''
        runner=Path(__file__).resolve().parents[1]
        env=dict(os.environ,PYTHONPATH=os.pathsep.join((str(runner),str(runner/'tests'))))
        result=subprocess.run([sys.executable,'-c',code,str(self.journal.directory),str(snapshot.serial),snapshot.sha256],
                              env=env,capture_output=True,timeout=10)
        self.assertEqual(-signal.SIGKILL,result.returncode)
        with Journal(self.journal.directory,[A,B],[OUT],self.limits,durability='EXTERNAL') as recovered:
            self.assertEqual({A.route_id:0,B.route_id:0},recovered.processing_sequences())
            self.assertEqual((),recovered.outgoing(confirmed_only=True))
            self.assertEqual(snapshot,capture(recovered,EXECUTION))
            confirm(recovered,snapshot.serial,snapshot.sha256)
            self.assertEqual({A.route_id:1,B.route_id:1},recovered.processing_sequences())


if __name__=='__main__':unittest.main()
