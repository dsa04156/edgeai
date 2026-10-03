"""Actual HTTPS/TLS MQTT DeviceSource; authority replies are explicit fixtures."""
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from test_stream_mqtt import Broker, eventually
from test_stream_assignment import GENERATION, POD, document
from test_stream_journal import A, OUT
from edgeai_runner.stream_assignment import AssignmentError, BindingClient
from edgeai_runner.stream_journal import Backpressure, Emission, Journal, Limits
from edgeai_runner.stream_mqtt import Link, MqttError
from edgeai_runner.stream_source import DeviceSource, SourceError


class DeviceApi:
    def __init__(self, broker):
        self.binding=A;self.duration=2;self.deadline=time.monotonic()+self.duration
        self.sequence=7;self.calls=[];self.status=None;self.peer_alive=True;self.drop=False
        self.foreign_run=False;self.max_bytes=4096
        self.completion_state='WAITING';self.completion_calls=[];self.completion_status=None
        self.drop_completion=False;self.on_completion=None;self.paths=[]
        self.generation_id=GENERATION;self.generation_state='ACTIVE';self.run_state='RUNNING'
        self.route_status=None;self.on_routes=None;self.route_items=None
        owner=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*_): pass
            def do_POST(self):
                body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                sequence=body.get('sequence');owner.calls.append(sequence)
                expected=f'/internal/v1/devices/{A.producer.device_id}/sessions/{A.producer.id}/streams'
                owner.paths.append(self.path)
                if self.path==expected+'/routes':
                    valid=(self.headers.get('Authorization')=='Bearer fixture-device' and not self.headers.get('X-EdgeAI-Pod-Token')
                           and set(body)=={'epoch','runId','limit','offset'} and body['epoch']==A.producer.epoch and body['runId']==POD)
                    status=401 if not valid else owner.route_status
                    if status:
                        self.send_response(status);self.send_header('Content-Length','0');self.end_headers();return
                    items=owner.route_items if owner.route_items is not None else [
                        {'routeId':owner.binding.route_id,'sourcePort':'samples','consumerTaskId':OUT.producer.id,'consumerPort':'a',
                         'sourceMode':'SYNTHETIC','mediaType':'application/json','maxPayloadBytes':owner.max_bytes,
                         'generation':None if owner.generation_state is None else {'id':owner.generation_id,'number':owner.binding.generation,
                         'state':owner.generation_state,'leaseUntil':'2026-10-03T00:00:00Z'}}]
                    start=body['offset'];end=start+body['limit']
                    value={'apiVersion':'edgeai.device-routes/v1','runId':POD,'runState':owner.run_state,
                           'deviceId':A.producer.device_id,'sessionId':A.producer.id,'epoch':A.producer.epoch,
                           'items':items[start:end],'limit':body['limit'],'offset':start,'nextOffset':end if end<len(items) else None}
                    if owner.on_routes:owner.on_routes(value)
                    wire=json.dumps(value).encode();self.send_response(200);self.send_header('Content-Type','application/json')
                    self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(wire)))
                    self.end_headers();self.wfile.write(wire);return
                if self.path==expected+'/complete':
                    valid=(self.headers.get('Authorization')=='Bearer fixture-device' and not self.headers.get('X-EdgeAI-Pod-Token')
                           and set(body)=={'epoch','generationId','sequence'} and body['epoch']==A.producer.epoch
                           and body['generationId']==owner.generation_id and type(sequence) is int and sequence>0)
                    status=401 if not valid else owner.completion_status
                    if owner.completion_state!='FINALIZE' and time.monotonic()>=owner.deadline:status=409
                    if status:
                        self.send_response(status);self.send_header('Content-Length','0');self.end_headers();return
                    owner.completion_calls.append(body)
                    if owner.on_completion:owner.on_completion()
                    if owner.drop_completion:
                        owner.drop_completion=False;self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
                    value={'state':owner.completion_state,'generationId':owner.generation_id,'sequence':sequence}
                    wire=json.dumps(value).encode();self.send_response(200);self.send_header('Content-Type','application/json')
                    self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(wire)))
                    self.end_headers();self.wfile.write(wire);return
                valid=(self.path==expected+('/heartbeat' if sequence is not None else '')
                       and self.headers.get('Authorization')=='Bearer fixture-device'
                       and not self.headers.get('X-EdgeAI-Pod-Token') and body.get('epoch')==A.producer.epoch
                       and body.get('generationId')==owner.generation_id)
                status=401 if not valid else owner.status
                if time.monotonic()>=owner.deadline or sequence is not None and sequence not in (0,owner.sequence,owner.sequence+1):status=409
                if status:
                    self.send_response(status);self.send_header('Content-Length','0');self.end_headers();return
                if sequence is not None and sequence==owner.sequence+1:
                    owner.sequence=sequence
                    if owner.peer_alive:owner.deadline=time.monotonic()+owner.duration
                    if owner.drop:
                        owner.drop=False;self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
                value=document(owner.binding,duration=max(.001,owner.deadline-time.monotonic()),
                               username='source-a',secret=broker.credentials['source-a'].read_text())
                value['generationId']=owner.generation_id
                value['mqtt'].update(host='localhost',port=broker.port,tls=True,caPem=broker.ca.read_text())
                value['maxPayloadBytes']=owner.max_bytes
                if owner.foreign_run:value['runId']=OUT.route_id
                if sequence is not None:value={'sequence':owner.sequence,'assignment':value}
                wire=json.dumps(value).encode();self.send_response(200);self.send_header('Content-Type','application/json')
                self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(wire)))
                self.end_headers();self.wfile.write(wire)
        self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(broker.ca,broker.directory/'server.key')
        self.server.socket=context.wrap_socket(self.server.socket,server_side=True)
        self.url=f'https://localhost:{self.server.server_port}'
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()

    def close(self):
        self.server.shutdown();self.server.server_close();self.thread.join(3)


class StreamSourceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='source-sdk-');self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.broker=Broker(self.root);self.addCleanup(self.broker.stop);self.broker.enable_tls()
        self.api=DeviceApi(self.broker);self.addCleanup(self.api.close)
        token=self.root/'device.token';token.write_text('fixture-device');token.chmod(0o600)
        self.client=BindingClient(self.api.url,A.producer,token,ca_file=self.broker.ca)
        self.directory=self.root/'source';self.directory.mkdir(mode=0o700)
        self.source=None
        self.sink=Journal(self.root/'sink',[A],[],create=True);self.addCleanup(self.sink.close)
        self.link=Link(self.sink,self.broker.endpoint('processor'),'source-test-consumer');self.addCleanup(self.link.close)
        self.addCleanup(lambda:self.source.close() if self.source else None)

    def open(self,**options):
        options.setdefault('completion',False)  # Historical transport-only fixture; completion cases opt in below.
        self.source=DeviceSource(self.client,POD,[GENERATION],self.directory,**options)
        return self.source

    def pump(self):
        self.source.step();self.link.step(.001)

    def emit(self,payload=b'4',state=b'offset1'):
        return self.source.emit([Emission(A.route_id,payload,'application/json')],state)

    def consume(self):
        pending=self.sink.pending();self.assertTrue(pending)
        self.sink.commit(self.sink.checkpoint().revision,pending,b'processed')

    def test_live_heartbeat_lost_reply_replay_restart_and_end(self):
        source=self.open(create=True);original=min(a.deadline for a in source.assignments.values())
        self.api.drop=True;self.emit()
        eventually(self.pump,lambda:bool(self.sink.pending()))
        # Receipt by broker or inbox is not a processing acknowledgement.
        self.assertEqual(1,len(source.journal.outgoing()))
        self.consume();eventually(self.pump,lambda:not source.journal.outgoing())
        eventually(self.pump,lambda:time.monotonic()>original+.1,5)
        seen=self.api.sequence;serial=source.journal.snapshot_serial;source.close()
        source=self.open(create=False)
        self.assertEqual(b'offset1',source.checkpoint().state)
        self.assertEqual(serial,source.journal.snapshot_serial)
        eventually(self.pump,lambda:self.api.sequence>seen)
        self.emit(b'5',b'offset2');eventually(self.pump,lambda:bool(self.sink.pending()))
        self.assertEqual(2,self.sink.pending()[0].sequence);self.consume()
        source.emit([Emission(A.route_id,b'',None,'END')],b'offset3')
        eventually(self.pump,lambda:bool(self.sink.pending()))
        self.assertEqual('END',self.sink.pending()[0].kind);self.consume()
        eventually(self.pump,lambda:source.settled)
        self.assertFalse(source.closed);self.assertGreater(source.heartbeats,0)
        calls=[n for n in self.api.calls if n not in (None,0)]
        self.assertLess(len(set(calls)),len(calls))
        secret=self.broker.credentials['source-a'].read_bytes()
        self.assertTrue(all(secret not in p.read_bytes() for p in (self.directory/'journal').iterdir()))

    def test_backpressure_preserves_adapter_state_and_keeps_owner_open_for_retry(self):
        source=self.open(create=True,limits=Limits(max_frames=1))
        self.emit()
        with self.assertRaises(Backpressure):self.emit(b'5',b'offset2')
        self.assertFalse(source.closed);self.assertEqual(b'offset1',source.checkpoint().state)
        self.assertEqual(1,source.checkpoint().output_sequences[A.route_id])
        eventually(self.pump,lambda:bool(self.sink.pending()));self.consume()
        eventually(self.pump,lambda:not source.journal.outgoing())
        self.assertEqual(2,self.emit(b'5',b'offset2')[0].sequence)
        eventually(self.pump,lambda:bool(self.sink.pending()))
        self.assertEqual(b'5',self.sink.pending()[0].payload)

    def test_503_retries_same_heartbeat_but_missing_peer_still_expires(self):
        source=self.open(create=True);self.api.status=503
        count=len(self.api.calls);eventually(source.step,lambda:len(self.api.calls)>count)
        self.assertEqual(0,self.api.calls[-1]);self.assertFalse(source.closed)
        self.api.status=None;eventually(source.step,lambda:source.heartbeats>=2)
        self.assertGreaterEqual(self.api.calls.count(0),2)
        self.api.peer_alive=False
        with self.assertRaises((AssignmentError,SourceError,MqttError)):
            eventually(source.step,lambda:source.closed,5)
        self.assertTrue(source.closed);self.assertTrue(source.link.closed)
        with Journal(self.directory/'journal',[],[A]) as journal:self.assertEqual(0,journal.checkpoint().revision)

    def test_cancellation_during_transaction_cannot_commit_another_sample(self):
        source=self.open(create=True);self.emit()
        source.journal.db.set_trace_callback(lambda statement:source.cancel.set()
            if statement.startswith('UPDATE checkpoint SET revision=') else None)
        with self.assertRaisesRegex(SourceError,'STREAM_SOURCE_CANCELLED'):self.emit(b'5',b'offset2')
        self.assertTrue(source.closed)
        with Journal(self.directory/'journal',[],[A]) as journal:
            self.assertEqual(b'offset1',journal.checkpoint().state);self.assertEqual(1,len(journal.outgoing()))

    def test_wire_budget_is_checked_before_persistence(self):
        self.api.max_bytes=1;source=self.open(create=True)
        with self.assertRaises(AssignmentError):self.emit(b'55')
        self.assertTrue(source.closed)
        with Journal(self.directory/'journal',[],[A]) as journal:
            self.assertEqual(0,journal.checkpoint().revision);self.assertEqual((),journal.outgoing())

    def test_heartbeat_cannot_implicitly_change_generation(self):
        source=self.open(create=True);self.emit();self.api.binding=replace(A,generation=2)
        with self.assertRaises(MqttError):source.step()
        self.assertTrue(source.closed)
        with Journal(self.directory/'journal',[],[A]) as journal:
            self.assertEqual(A,journal.outgoing()[0].binding);self.assertEqual(b'offset1',journal.checkpoint().state)

    def test_foreign_run_is_rejected_before_journal_creation(self):
        self.api.foreign_run=True
        with self.assertRaisesRegex(SourceError,'STREAM_SOURCE_SCOPE_MISMATCH'):self.open(create=True)
        self.assertFalse((self.directory/'journal').exists())

    def end(self):
        self.source.emit([Emission(A.route_id,b'',None,'END')],b'last-offset')
        eventually(self.pump,lambda:bool(self.sink.pending()));self.consume()
        eventually(self.pump,lambda:self.source.intent is not None)

    def test_completion_waits_with_heartbeats_and_lost_grant_survives_route_fence(self):
        source=self.open(create=True,completion=True);self.end()
        self.assertFalse(source.completed);self.assertTrue(source.settled)
        start=source.heartbeats;deadline=self.api.deadline
        eventually(self.pump,lambda:time.monotonic()>deadline+.1,5)
        self.assertGreater(source.heartbeats,start);self.assertFalse(source.completed)
        self.api.completion_state='FINALIZE';self.api.drop_completion=True
        self.api.on_completion=lambda:setattr(self.api,'status',409)
        eventually(source.step,lambda:source.completed)
        self.assertTrue(source.link.closed);self.assertTrue(source.transport_stopped)
        self.assertEqual({1},{v['sequence'] for v in self.api.completion_calls})
        self.assertEqual(0o600,(self.directory/'completion.json').stat().st_mode&0o777)
        for secret in (b'fixture-device',self.broker.credentials['source-a'].read_bytes(),b'last-offset'):
            self.assertNotIn(secret,(self.directory/'completion.json').read_bytes())

    def test_waiting_restart_resumes_current_heartbeat_before_grant(self):
        source=self.open(create=True,completion=True);self.end();source.close()
        source=self.open(completion=True);self.assertIsNone(source.link);self.assertFalse(source.completed)
        deadline=self.api.deadline
        eventually(self.pump,lambda:time.monotonic()>deadline+.1,5)
        self.assertGreater(source.heartbeats,0);self.assertFalse(source.completed)
        self.api.completion_state='FINALIZE';eventually(source.step,lambda:source.completed)

    def test_granted_restart_never_fetches_or_reopens_closed_transport(self):
        source=self.open(create=True,completion=True);self.end();source.close()
        self.api.completion_state='FINALIZE';self.api.status=409;count=len(self.api.paths)
        source=self.open(completion=True);self.assertFalse(source.completed)
        eventually(source.step,lambda:source.completed)
        self.assertIsNone(source.link);self.assertEqual(b'last-offset',source.checkpoint().state)
        self.assertTrue(all(path.endswith('/complete') for path in self.api.paths[count:]))

    def test_restart_lease_expires_between_fresh_fetch_and_link_creation(self):
        source=self.open(create=True,completion=True);self.end();source.close()
        self.api.deadline=time.monotonic()+.5
        source=self.open(completion=True);create=Link.from_assignments
        def delayed_creation(journal,assignments):
            time.sleep(max(0,max(a.deadline for a in assignments)-time.monotonic())+.02)
            self.api.completion_state='FINALIZE'
            return create(journal,assignments)
        with patch('edgeai_runner.stream_source.Link.from_assignments',side_effect=delayed_creation):
            source.step()
        self.assertFalse(source.closed);self.assertTrue(source.transport_stopped)
        self.assertIsNone(source.link);self.assertFalse(source.completed)
        eventually(source.step,lambda:source.completed)
        self.assertIsNone(source.link)

    def test_terminal_intent_does_not_override_server_rejection_or_cancellation(self):
        source=self.open(create=True,completion=True);self.end();source.close()
        self.api.completion_status=409;source=self.open(completion=True)
        with self.assertRaises(AssignmentError):source.step()
        self.assertTrue(source.closed);self.assertFalse(source.completed)
        self.api.completion_status=None;self.api.completion_state='FINALIZE';source=self.open(completion=True)
        self.api.on_completion=source.cancel.set
        with self.assertRaisesRegex(SourceError,'STREAM_SOURCE_CANCELLED'):source.step()
        self.assertTrue(source.closed);self.assertFalse(source.completed)

    def test_corrupt_intent_is_rejected_before_network_or_journal_changes(self):
        source=self.open(create=True,completion=True);self.end();source.close();count=len(self.api.paths)
        path=self.directory/'completion.json';value=json.loads(path.read_bytes());value['routes'][0]['sequence']+=1
        path.write_text(json.dumps(value))
        from edgeai_runner.stream_source_completion import CompletionError
        with self.assertRaises(CompletionError):self.open(completion=True)
        self.assertEqual(count,len(self.api.paths))

    def test_waiting_completion_does_not_promote_an_expired_peer(self):
        source=self.open(create=True,completion=True);self.end();self.api.peer_alive=False
        with self.assertRaises((AssignmentError,SourceError,MqttError)):
            eventually(source.step,lambda:source.completed,5)
        self.assertTrue(source.closed);self.assertFalse(source.completed);self.assertTrue(source.link.closed)

    def test_sigkill_after_terminal_report_recovers_grant_without_mqtt(self):
        code='''import sys,time
from pathlib import Path
from test_stream_journal import A
from test_stream_assignment import POD,GENERATION
from edgeai_runner.stream_assignment import BindingClient
from edgeai_runner.stream_source import DeviceSource
from edgeai_runner.stream_journal import Emission
url,token,ca,directory=sys.argv[1:]
client=BindingClient(url,A.producer,token,ca_file=ca)
with DeviceSource(client,POD,[GENERATION],Path(directory),create=True,timeout=20) as source:
 source.emit([Emission(A.route_id,b'4','application/json')],b'offset1')
 source.emit([Emission(A.route_id,b'',None,'END')],b'offset2')
 while True:source.step();time.sleep(.005)
'''
        self.api.duration=8;self.api.deadline=time.monotonic()+8
        sdk=Path(__file__).resolve().parents[1]
        env={**os.environ,'PYTHONPATH':os.pathsep.join(str(p) for p in (sdk,sdk/'tests',sdk/'integration'))}
        child=subprocess.Popen([sys.executable,'-c',code,self.api.url,str(self.root/'device.token'),str(self.broker.ca),str(self.directory)],
                               env=env,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        try:
            def consume():
                self.assertIsNone(child.poll(),'Source child exited before terminal report')
                self.link.step(.001)
                if self.sink.pending():self.consume()
            eventually(consume,lambda:bool(self.api.completion_calls),8)
            child.kill();self.assertEqual(-9,child.wait(5));self.assertEqual(b'',child.stderr.read())
        finally:
            if child.poll() is None:child.kill();child.wait(5)
            child.stderr.close()
        self.api.completion_state='FINALIZE';self.api.status=409;count=len(self.api.paths)
        source=self.open(completion=True);eventually(source.step,lambda:source.completed)
        self.assertIsNone(source.link);self.assertEqual(b'offset2',source.checkpoint().state)
        self.assertEqual(2,source.checkpoint().output_sequences[A.route_id])
        self.assertTrue(all(path.endswith('/complete') for path in self.api.paths[count:]))


if __name__=='__main__':unittest.main()
