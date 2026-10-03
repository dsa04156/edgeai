"""Real private files and SQLite terminal intent; no broker or platform-grant fixture."""
from dataclasses import replace
import json
from pathlib import Path
import tempfile
import unittest

from edgeai_runner.stream_assignment import Assignment
from edgeai_runner.stream_journal import Emission, Journal, Limits
from edgeai_runner import stream_source_completion as intent
from test_stream_assignment import GENERATION, POD, document
from test_stream_journal import A
import time


class SourceCompletionIntentTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.path=self.root/'completion.json';self.limits=Limits()
        self.assignment=Assignment.decode(json.dumps(document()).encode(),A.producer,GENERATION,time.monotonic())
        self.journal=Journal(self.root/'journal',[],[A],self.limits,create=True);self.addCleanup(self.journal.close)

    def save(self):return intent.write(self.root,POD,A.producer,{GENERATION:self.assignment},self.journal)
    def read(self):return intent.read(self.root,POD,A.producer,self.limits)
    def end(self):
        self.journal.commit(0,[],b'private-adapter-state',[Emission(A.route_id,b'',None,'END')])
        self.journal.acknowledge(A,1)

    def test_only_all_ended_and_acknowledged_routes_can_create_intent(self):
        self.assertIsNone(self.read())
        with self.assertRaises(intent.CompletionError):self.save()
        self.journal.commit(0,[],b'private-adapter-state',[Emission(A.route_id,b'',None,'END')])
        with self.assertRaises(intent.CompletionError):self.save()
        self.assertFalse(self.path.exists())
        self.journal.acknowledge(A,1);value=self.save();self.assertEqual(value,self.read())
        intent.verify(value,self.journal)
        self.assertEqual(0o600,self.path.stat().st_mode&0o777)
        self.assertNotIn(b'private-adapter-state',self.path.read_bytes());self.assertNotIn(b'fixture-only',self.path.read_bytes())
        self.assertEqual(['completion.json','journal'],sorted(p.name for p in self.root.iterdir()))

    def test_corrupt_types_scope_sequence_and_unknown_fields_are_rejected(self):
        self.end();value=self.save();original=self.path.read_bytes()
        changes=[lambda v:v.update(version=True),lambda v:v.update(runId=GENERATION),lambda v:v.update(extra=1),
                 lambda v:v['routes'][0].update(sequence=True),lambda v:v['routes'][0].update(sequence=0),
                 lambda v:v['routes'][0].update(sequence=9007199254740992),lambda v:v['actor'].update(epoch=2),
                 lambda v:v['manifest']['limits'].update(max_frames=1),lambda v:v['manifest'].update(extra=1)]
        for change in changes:
            v=json.loads(original);change(v);self.path.write_text(json.dumps(v))
            with self.assertRaises(intent.CompletionError):self.read()
        self.path.write_bytes(original)
        value['routes'][0]['sequence']+=1
        with self.assertRaises(intent.CompletionError):intent.verify(value,self.journal)

    def test_untrusted_file_permissions_links_duplicates_and_size_are_rejected(self):
        self.end();self.save();wire=self.path.read_bytes();self.path.chmod(0o644)
        with self.assertRaises(intent.CompletionError):self.read()
        self.path.chmod(0o600)
        for value in (b'{"version":1,"version":1}',b'['*2000,b'x'*32769):
            self.path.write_bytes(value)
            with self.assertRaises(intent.CompletionError):self.read()
        self.path.unlink();target=self.root/'actual';target.write_bytes(wire);target.chmod(0o600);self.path.symlink_to(target)
        with self.assertRaises(intent.CompletionError):self.read()

    def test_fanout_requires_every_ack_and_preserves_each_terminal_sequence(self):
        self.journal.close();other=replace(A,route_id=POD)
        self.journal=Journal(self.root/'fanout',[],[A,other],create=True);self.addCleanup(self.journal.close)
        self.journal.commit(0,[],b'x',[Emission(A.route_id,b'',None,'END'),Emission(other.route_id,b'4','application/json')])
        self.journal.commit(1,[],b'x',[Emission(other.route_id,b'',None,'END')]);self.journal.acknowledge(A,1)
        second=replace(self.assignment,generation_id=POD,binding=other)
        assignments={GENERATION:self.assignment,POD:second}
        with self.assertRaises(intent.CompletionError):intent.write(self.root,POD,A.producer,assignments,self.journal)
        self.journal.acknowledge(other,2);value=intent.write(self.root,POD,A.producer,assignments,self.journal)
        self.assertEqual({A.route_id:1,other.route_id:2},{r['routeId']:r['sequence'] for r in value['routes']})
        intent.verify(self.read(),self.journal)
