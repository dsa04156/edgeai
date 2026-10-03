"""Actual HTTPS/MQTT/session loop; the control-plane responses are explicit fixtures."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import base64
import hashlib
from pathlib import Path
import socket
import shutil
import ssl
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import uuid

from test_stream_mqtt import Broker, eventually
from test_stream_journal import A, B, OUT
from test_stream_assignment import POD, document
from edgeai_runner.stream_assignment import AssignmentError, BindingClient
from edgeai_runner.stream_journal import Emission, Journal, Limits
from edgeai_runner.stream_mqtt import Link
from edgeai_runner.stream_session import Session, SessionError
from edgeai_runner.stream_checkpoint import Snapshot, MEDIA_TYPE, restore
from edgeai_runner.stream_checkpoint_client import CheckpointClient

RUNNER = Path(__file__).resolve().parents[1]
COMMAND = [sys.executable, str(RUNNER/'examples/stream_sum.py')]
GENERATIONS = {binding.route_id: str(uuid.uuid5(uuid.NAMESPACE_OID,binding.route_id)) for binding in (A,B,OUT)}
INPUTS = {'a': GENERATIONS[A.route_id], 'b': GENERATIONS[B.route_id]}
OUTPUTS = {'sum': [GENERATIONS[OUT.route_id]]}


class SessionApi:
    def __init__(self, broker, duration=2):
        self.broker, self.duration = broker, duration
        self.sequence = {identity:7 for identity in GENERATIONS.values()}
        self.leases = {identity:time.monotonic()+duration for identity in GENERATIONS.values()}
        self.calls = []
        self.status = None
        self.drop_after_apply = False
        self.peer_alive = True
        self.foreign_run = False
        self.checkpoint_status=None;self.checkpoint_latest=None;self.checkpoint_objects={}
        self.checkpoint_drop=False;self.checkpoint_requests=[];self.storage_credential_leak=False
        owner = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_PUT(self):
                owner.storage_credential_leak |= bool(self.headers.get('Authorization') or self.headers.get('X-EdgeAI-Pod-Token'))
                snapshot=Snapshot(self.rfile.read(int(self.headers['Content-Length'])))
                owner.checkpoint_objects[snapshot.sha256]=snapshot
                self.send_response(200);self.send_header('x-amz-version-id',snapshot.sha256)
                self.send_header('Content-Length','0');self.end_headers()
            def do_POST(self):
                data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                if '/streams/checkpoints/' in self.path:
                    valid=(self.headers.get('Authorization')=='Bearer fixture-claim' and self.headers.get('X-EdgeAI-Pod-Token')=='fixture-pod'
                           and data.get('podUid')==POD and data.get('epoch')==OUT.producer.epoch
                           and self.path.startswith(f'/internal/v1/attempts/{OUT.producer.id}/streams/checkpoints/'))
                    status=401 if not valid else 409 if any(time.monotonic()>=v for v in owner.leases.values()) else owner.checkpoint_status
                    if status:
                        self.send_response(status);self.send_header('Content-Length','0');self.end_headers();return
                    operation=self.path.rsplit('/',1)[1];owner.checkpoint_requests.append((operation,data))
                    now=document()['serverTime'];status=200
                    if operation=='latest':
                        value={'checkpoint':owner.checkpoint_latest}
                        if owner.checkpoint_latest:value['download']={'url':owner.url+'/checkpoint-object?versionId='+owner.checkpoint_latest['versionId'],'expiresAt':now}
                    elif operation=='uploads':
                        q=data['checkpoint'];latest=owner.checkpoint_latest
                        if latest and latest['serial']==q['serial'] and latest['sha256']==q['sha256']:value={'checkpoint':latest}
                        else:value={'upload':{'url':owner.url+'/checkpoint-object','expiresAt':now,
                            'headers':{'Content-Type':MEDIA_TYPE,'x-amz-meta-sha256':q['sha256'],
                                       'x-amz-checksum-sha256':base64.b64encode(bytes.fromhex(q['sha256'])).decode()}}}
                    else:
                        q=data['checkpoint'];snapshot=owner.checkpoint_objects[data['versionId']];doc=snapshot.document()
                        assert q['sha256']==snapshot.sha256 and q['bytes']==len(snapshot.wire) and q['serial']==snapshot.serial
                        state=base64.b64decode(doc['stateBase64'])
                        owner.checkpoint_latest={**q,'id':str(uuid.uuid5(uuid.NAMESPACE_OID,q['sha256'])),'runId':POD,'taskId':POD,
                            'attemptId':OUT.producer.id,'epoch':OUT.producer.epoch,'serviceProfileVersionId':POD,
                            'revision':doc['revision'],'versionId':data['versionId'],'createdAt':now,
                            'summary':{'manifest':doc['manifest'],'revision':doc['revision'],
                                'routes':[{k:v for k,v in row.items() if k!='frames'} for row in doc['routes']],
                                'stateSha256':hashlib.sha256(state).hexdigest(),'stateBytes':len(state)}}
                        value={'checkpoint':owner.checkpoint_latest};status=201
                        if owner.checkpoint_drop:
                            owner.checkpoint_drop=False;self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
                    encoded=json.dumps(value).encode();self.send_response(status);self.send_header('Content-Type','application/json')
                    self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(encoded)))
                    self.end_headers();self.wfile.write(encoded);return
                identity = data['generationId']
                sequence = data.get('sequence')
                owner.calls.append((identity,sequence))
                valid = (self.headers.get('Authorization')=='Bearer fixture-claim'
                         and self.headers.get('X-EdgeAI-Pod-Token')=='fixture-pod'
                         and data.get('podUid')==POD and data.get('epoch')==OUT.producer.epoch
                         and self.path==f'/internal/v1/attempts/{OUT.producer.id}/streams'+('/heartbeat' if sequence is not None else ''))
                status = 401 if not valid else owner.status
                previous = owner.sequence[identity]
                if time.monotonic() >= owner.leases[identity]:
                    status = 409
                if sequence is not None and sequence not in (0,previous,previous+1):
                    status = 409
                if status:
                    self.send_response(status);self.send_header('Content-Length','0');self.end_headers();return
                if sequence is not None and sequence==previous+1:
                    owner.sequence[identity]=sequence
                    if owner.peer_alive:
                        owner.leases[identity]=time.monotonic()+owner.duration
                    if owner.drop_after_apply:
                        owner.drop_after_apply=False
                        self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
                binding=next(b for b in (A,B,OUT) if GENERATIONS[b.route_id]==identity)
                value=document(binding,direction='PRODUCER' if binding==OUT else 'CONSUMER',
                               duration=max(.001,owner.leases[identity]-time.monotonic()),username='processor',
                               secret=broker.credentials['processor'].read_text())
                value['generationId']=identity
                if owner.foreign_run:value['runId']=OUT.route_id
                value['mqtt'].update(host='localhost',port=broker.port,tls=True,caPem=broker.ca.read_text())
                if sequence is not None:value={'sequence':owner.sequence[identity],'assignment':value}
                encoded=json.dumps(value).encode()
                self.send_response(200);self.send_header('Content-Type','application/json')
                self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(encoded)))
                self.end_headers();self.wfile.write(encoded)
        self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(broker.ca,broker.directory/'server.key')
        self.server.socket=context.wrap_socket(self.server.socket,server_side=True)
        self.url=f'https://localhost:{self.server.server_port}'
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()

    def close(self):
        self.server.shutdown();self.server.server_close();self.thread.join(3)


class StreamSessionTest(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory(prefix='edgeai-session-');self.addCleanup(temp.cleanup)
        self.root=Path(temp.name)
        self.broker=Broker(self.root);self.addCleanup(self.broker.stop);self.broker.enable_tls()
        self.api=SessionApi(self.broker);self.addCleanup(self.api.close)
        for name,value in [('claim','fixture-claim'),('pod','fixture-pod')]:
            path=self.root/name;path.write_text(value);path.chmod(0o600)
        self.client=BindingClient(self.api.url,OUT.producer,self.root/'claim',pod_uid=POD,
                                  pod_token_file=self.root/'pod',ca_file=self.broker.ca)
        self.directory=self.root/'session';self.directory.mkdir(mode=0o700)
        self.journals,self.links={},{}
        self.session=None
        self.addCleanup(self.close_flow)

    def close_flow(self):
        if self.session is not None:self.session.close()
        for link in self.links.values():link.close(force=True)
        for journal in self.journals.values():journal.close()

    def open_session(self, *, create=True, command=COMMAND, timeout=20, durability='LOCAL', automatic_checkpoint=False):
        checkpoint=CheckpointClient(self.client,POD,list(GENERATIONS.values()),storage_ca_file=self.broker.ca) if automatic_checkpoint else None
        self.session=Session(self.client,POD,INPUTS,OUTPUTS,command,self.directory,{'mode':'zip'},
                             limits=Limits(max_frames=6),create=create,timeout=timeout,durability=durability,checkpoint_client=checkpoint)
        return self.session

    def setup_flow(self, **options):
        for actor,inputs,outputs in [('source-a',[],[A]),('source-b',[],[B]),('sink',[OUT],[])]:
            journal=Journal(self.root/actor,inputs,outputs,Limits(max_frames=6),create=True)
            self.journals[actor]=journal
            self.links[actor]=Link(journal,self.broker.endpoint(actor),'session-test-'+actor)
        session=self.open_session(**options)
        eventually(self.pump,lambda:all(link.ready for link in self.links.values()) and session.link.ready)
        return session

    def pump(self):
        for link in self.links.values():link.step(.001)
        self.session.step()

    def emit(self, binding, payload, kind='DATA'):
        journal=self.journals['source-a' if binding==A else 'source-b']
        journal.commit(journal.checkpoint().revision,[],b'',
                       [Emission(binding.route_id,payload,None if kind=='END' else 'application/json',kind)])

    def consume_sink(self):
        sink=self.journals['sink'];pending=sink.pending()
        state=pending[0].payload if pending[0].kind=='DATA' else sink.checkpoint().state
        sink.commit(sink.checkpoint().revision,pending,state)
        return pending[0]

    def test_automatic_resume_renewal_lost_reply_retry_calculation_and_settled_heartbeat(self):
        session=self.setup_flow()
        original=min(a.deadline for a in session.assignments.values())
        self.api.drop_after_apply=True
        self.emit(A,b'4');self.emit(B,b'5')
        eventually(self.pump,lambda:bool(self.journals['sink'].pending()))
        self.assertEqual(b'9',self.consume_sink().payload)
        eventually(self.pump,lambda:time.monotonic()>original+.3,5)
        # The response was lost after a stored observation: replay the same number.
        observed=[call for call in self.api.calls if call[1] not in (None,0)]
        self.assertLess(len(set(observed)),len(observed))
        self.assertTrue(all(value>8 for value in self.api.sequence.values()))
        self.emit(A,b'2');self.emit(B,b'3')
        eventually(self.pump,lambda:bool(self.journals['sink'].pending()))
        self.assertEqual(b'14',self.consume_sink().payload)
        self.emit(A,b'','END');self.emit(B,b'','END')
        eventually(self.pump,lambda:bool(self.journals['sink'].pending()))
        self.assertEqual('END',self.consume_sink().kind)
        eventually(self.pump,lambda:session.settled)
        count=session.heartbeats
        eventually(self.pump,lambda:session.heartbeats>count)
        self.assertFalse(session.closed)
        self.assertFalse(session.link.closed)
        for p in (self.directory/'journal').iterdir():
            self.assertTrue(self.broker.credentials['processor'].read_bytes() not in p.read_bytes())

    def test_transient_service_failure_retries_same_sequence_without_resetting_state(self):
        session=self.setup_flow();self.api.status=503
        count=len(self.api.calls)
        eventually(self.pump,lambda:len(self.api.calls)>count)
        retry=self.api.calls[-1]
        self.api.status=None
        eventually(self.pump,lambda:self.api.calls.count(retry)>=2)
        self.assertEqual(0,session.journal.checkpoint().revision)
        self.assertFalse(session.closed)

    def test_same_volume_restart_resumes_server_counter_and_committed_calculation(self):
        session=self.setup_flow();self.emit(A,b'4');self.emit(B,b'5')
        eventually(self.pump,lambda:session.journal.checkpoint().revision==1)
        session.close();last=dict(self.api.sequence)
        session=self.open_session(create=False)
        eventually(self.pump,lambda:all(self.api.sequence[k]>v for k,v in last.items()))
        self.emit(A,b'1');self.emit(B,b'2')
        eventually(self.pump,lambda:session.journal.checkpoint().revision==2)
        self.assertEqual(b'12',session.journal.checkpoint().state)

    def test_missing_peer_cannot_be_kept_alive_by_our_heartbeats(self):
        session=self.setup_flow(command=[sys.executable,str(RUNNER/'tests/fixtures/stream_workload.py'),'hang'])
        self.emit(A,b'4');self.emit(B,b'5')
        eventually(self.pump,lambda:session.processor.workload is not None)
        self.api.peer_alive=False
        child=session.processor.workload.process
        with self.assertRaises((AssignmentError,SessionError)):
            eventually(self.pump,lambda:session.closed,5)
        self.assertTrue(session.closed)
        self.assertIsNotNone(child.poll())
        with Journal(self.directory/'journal',[A,B],[OUT],Limits(max_frames=6)) as recovered:
            self.assertEqual(0,recovered.checkpoint().revision)
            self.assertEqual((),recovered.outgoing())

    def test_fenced_control_response_stops_model_without_retry(self):
        session=self.setup_flow(command=[sys.executable,str(RUNNER/'tests/fixtures/stream_workload.py'),'hang'])
        self.emit(A,b'4');self.emit(B,b'5')
        eventually(self.pump,lambda:session.processor.workload is not None)
        child=session.processor.workload.process
        count=len(self.api.calls);self.api.status=409
        with self.assertRaises(AssignmentError):
            eventually(self.pump,lambda:session.closed)
        self.assertEqual(count+1,len(self.api.calls))
        self.assertIsNotNone(child.poll())

    def test_session_timeout_cannot_be_extended_by_valid_heartbeats(self):
        session=self.setup_flow(timeout=.8,command=[sys.executable,str(RUNNER/'tests/fixtures/stream_workload.py'),'hang'])
        self.emit(A,b'4');self.emit(B,b'5')
        eventually(self.pump,lambda:session.processor.workload is not None)
        child=session.processor.workload.process
        with self.assertRaises((SessionError,AssignmentError)):
            eventually(self.pump,lambda:session.closed,3)
        self.assertTrue(session.closed);self.assertIsNotNone(child.poll())

    def test_deadline_crossed_inside_processor_reports_session_timeout_and_stops_child(self):
        session=self.setup_flow(timeout=2,command=[sys.executable,str(RUNNER/'tests/fixtures/stream_workload.py'),'hang'])
        self.emit(A,b'4');self.emit(B,b'5')
        eventually(self.pump,lambda:session.processor.workload is not None)
        child=session.processor.workload.process
        advance=session.processor.step
        def cross_deadline():
            time.sleep(max(0,session.deadline-time.monotonic())+.03)
            advance()
        with patch.object(session.processor,'step',side_effect=cross_deadline):
            with self.assertRaisesRegex(SessionError,'^STREAM_SESSION_TIMEOUT$'):
                session.step()
        self.assertTrue(session.closed);self.assertIsNotNone(child.poll())

    def test_foreign_run_and_duplicate_generation_mapping_fail_before_journal_creation(self):
        self.api.foreign_run=True
        with self.assertRaisesRegex(SessionError,'FOREIGN_RUN'):self.open_session()
        self.assertFalse((self.directory/'journal').exists())
        self.api.foreign_run=False
        with self.assertRaises(SessionError):
            Session(self.client,POD,{'a':INPUTS['a'],'b':INPUTS['a']},OUTPUTS,COMMAND,self.directory,create=True)
        self.assertFalse((self.directory/'journal').exists())

    def test_cancellation_stops_live_model_before_any_further_heartbeat(self):
        session=self.setup_flow(command=[sys.executable,str(RUNNER/'tests/fixtures/stream_workload.py'),'hang'])
        self.emit(A,b'4');self.emit(B,b'5')
        eventually(self.pump,lambda:session.processor.workload is not None)
        child=session.processor.workload.process;calls=len(self.api.calls)
        session.cancel.set()
        with self.assertRaisesRegex(SessionError,'CANCELLED'):session.step()
        self.assertTrue(session.closed);self.assertIsNotNone(child.poll())
        self.assertEqual(calls,len(self.api.calls))

    def test_wrong_port_direction_fails_before_journal_creation(self):
        with self.assertRaisesRegex(SessionError,'DIRECTION_MISMATCH'):
            Session(self.client,POD,{'a':GENERATIONS[OUT.route_id]},
                    {'sum':[GENERATIONS[A.route_id]]},COMMAND,self.directory,create=True)
        self.assertFalse((self.directory/'journal').exists())

    def test_external_session_restores_real_model_after_volume_loss_and_resumes_from_nine(self):
        session=self.setup_flow(durability='EXTERNAL');self.emit(A,b'4');self.emit(B,b'5')
        eventually(self.pump,lambda:session.journal.checkpoint().revision==1)
        self.assertFalse(self.journals['sink'].pending())
        snapshot=session.checkpoint();execution=session.processor.execution_sha256
        session.confirm_checkpoint(snapshot.serial,snapshot.sha256)
        eventually(self.pump,lambda:bool(self.journals['sink'].pending()))
        self.assertEqual(b'9',self.consume_sink().payload)
        session.close();shutil.rmtree(self.directory/'journal')
        with restore(self.directory/'journal',snapshot,[A,B],[OUT],Limits(max_frames=6),
                     expected_sha256=snapshot.sha256,execution_sha256=execution):
            pass
        session=self.open_session(create=False,durability='EXTERNAL')
        self.emit(A,b'2');self.emit(B,b'3')
        eventually(self.pump,lambda:session.journal.checkpoint().revision==2)
        self.assertEqual(b'14',session.journal.checkpoint().state)
        newer=session.checkpoint();session.confirm_checkpoint(newer.serial,newer.sha256)
        eventually(self.pump,lambda:bool(self.journals['sink'].pending()))
        self.assertEqual(b'14',self.consume_sink().payload)

    def test_automatic_external_checkpoint_handles_service_failure_lost_reply_and_restart(self):
        self.api.checkpoint_status=503
        session=self.setup_flow(durability='EXTERNAL',automatic_checkpoint=True)
        self.emit(A,b'4');self.emit(B,b'5')
        eventually(self.pump,lambda:session.journal.checkpoint().revision==1)
        count=session.heartbeats
        eventually(self.pump,lambda:session.heartbeats>count+3)
        self.assertFalse(self.journals['sink'].pending())
        self.assertTrue(self.journals['source-a'].outgoing())
        self.assertEqual(0,session.publisher.confirmations)
        self.api.checkpoint_drop=True;self.api.checkpoint_status=None
        eventually(self.pump,lambda:bool(self.journals['sink'].pending()))
        self.assertEqual(b'9',self.consume_sink().payload)
        commits=[data['checkpoint'] for op,data in self.api.checkpoint_requests if op=='commit']
        self.assertGreater(len(commits),len({(q['serial'],q['sha256']) for q in commits}))
        self.assertFalse(self.api.storage_credential_leak)
        session.close();session=self.open_session(create=False,durability='EXTERNAL',automatic_checkpoint=True)
        self.emit(A,b'2');self.emit(B,b'3')
        eventually(self.pump,lambda:bool(self.journals['sink'].pending()))
        self.assertEqual(b'14',self.consume_sink().payload)
        self.assertGreater(session.publisher.confirmations,0)


if __name__=='__main__':unittest.main()
