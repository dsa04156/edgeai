"""Actual HTTPS/TLS MQTT DeviceSource; authority replies are explicit fixtures."""
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import ssl
import tempfile
import threading
import time
import unittest

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
        owner=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*_): pass
            def do_POST(self):
                body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                sequence=body.get('sequence');owner.calls.append(sequence)
                expected=f'/internal/v1/devices/{A.producer.device_id}/sessions/{A.producer.id}/streams'
                valid=(self.path==expected+('/heartbeat' if sequence is not None else '')
                       and self.headers.get('Authorization')=='Bearer fixture-device'
                       and not self.headers.get('X-EdgeAI-Pod-Token') and body.get('epoch')==A.producer.epoch
                       and body.get('generationId')==GENERATION)
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


if __name__=='__main__':unittest.main()
