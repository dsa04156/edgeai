"""Real HTTP transport with an explicit checkpoint authority/storage fixture."""
import base64
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
import unittest
import uuid

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from edgeai_runner.stream_assignment import BindingClient
from edgeai_runner.stream_checkpoint import Snapshot, MEDIA_TYPE, capture, encode
from edgeai_runner.stream_checkpoint_client import CheckpointClient, CheckpointPublisher, CheckpointError, CheckpointUnavailable
from edgeai_runner.stream_journal import Journal, Limits, Emission
from test_stream_journal import A, B, OUT, data
from test_stream_assignment import POD


class CheckpointFixture:
    def __init__(self, snapshot, run_id, generations):
        self.snapshot,self.run_id,self.generations=snapshot,run_id,generations
        self.status=200;self.cache='no-store';self.latest=None;self.raw=None
        self.drop=False;self.calls=[];self.puts=[];self.version='fixture-fixed-version'
        owner=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*_):pass
            def do_PUT(self):
                owner.puts.append((dict(self.headers),self.rfile.read(int(self.headers['Content-Length']))))
                self.send_response(200);self.send_header('x-amz-version-id',owner.version)
                self.send_header('Content-Length','0');self.end_headers()
            def do_POST(self):
                body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                owner.calls.append((self.path,dict(self.headers),body))
                status=owner.status
                if self.path.endswith('/latest'):
                    value={'checkpoint':owner.latest}
                    if owner.latest is not None:value['download']={'url':owner.url+'/object?versionId='+owner.version,'expiresAt':owner.now()}
                elif self.path.endswith('/uploads'):
                    q=body['checkpoint']
                    replay=owner.latest and owner.latest['serial']==q['serial'] and owner.latest['sha256']==q['sha256']
                    value={'checkpoint':owner.latest} if replay else {'upload':owner.grant()}
                else:
                    owner.latest=owner.receipt(body['checkpoint']);value={'checkpoint':owner.latest}
                    if owner.drop:
                        owner.drop=False;self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
                    if status==200:status=201
                data=owner.raw if owner.raw is not None else encode(value)
                self.send_response(status);self.send_header('Content-Type','application/json')
                self.send_header('Cache-Control',owner.cache);self.send_header('Content-Length',str(len(data)))
                if status==307:self.send_header('Location',owner.url+'/redirect')
                self.end_headers();self.wfile.write(data)
        self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        self.url=f'http://127.0.0.1:{self.server.server_port}'
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()

    def now(self):return datetime.now(timezone.utc).isoformat().replace('+00:00','Z')
    def grant(self):
        return {'url':self.url+'/object?signature=fixture-only','expiresAt':self.now(),
                'headers':{'Content-Type':MEDIA_TYPE,'x-amz-meta-sha256':self.snapshot.sha256,
                           'x-amz-checksum-sha256':base64.b64encode(bytes.fromhex(self.snapshot.sha256)).decode()}}
    def receipt(self,q):
        d=self.snapshot.document();state=base64.b64decode(d['stateBase64'])
        return {**q,'id':str(uuid.uuid5(uuid.NAMESPACE_OID,q['sha256'])),'runId':self.run_id,'taskId':POD,'attemptId':OUT.producer.id,'epoch':OUT.producer.epoch,
                'serviceProfileVersionId':POD,'revision':d['revision'],'versionId':self.version,'createdAt':self.now(),
                'summary':{'manifest':d['manifest'],'revision':d['revision'],
                           'routes':[{k:v for k,v in r.items() if k!='frames'} for r in d['routes']],
                           'stateSha256':hashlib.sha256(state).hexdigest(),'stateBytes':len(state)}}
    def close(self):self.server.shutdown();self.server.server_close();self.thread.join(3)


class StreamCheckpointClientTest(unittest.TestCase):
    def setUp(self):
        self.snapshot=Snapshot((Path(__file__).resolve().parents[2]/'contracts/streams/checkpoint.example.json').read_bytes().strip())
        self.generations=sorted(str(uuid.uuid4()) for _ in self.snapshot.document()['routes'])
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.root=Path(temp.name)
        for name,value in [('claim','fixture-claim'),('pod','fixture-pod')]:
            p=self.root/name;p.write_text(value);p.chmod(0o600)
        self.api=CheckpointFixture(self.snapshot,POD,self.generations);self.addCleanup(self.api.close)
        self.binding=BindingClient(self.api.url,OUT.producer,self.root/'claim',pod_uid=POD,
                                   pod_token_file=self.root/'pod',allow_http_loopback=True)
        self.client=CheckpointClient(self.binding,POD,self.generations,allow_http_loopback=True)

    def test_real_http_candidate_put_and_verified_receipt_scope_and_rotation(self):
        self.assertIsNone(self.client.latest()['checkpoint'])
        grant=self.client.upload(self.snapshot,None)['upload']
        version=self.client.put(self.snapshot,grant)
        self.assertIsNone(self.api.latest)
        self.assertEqual(self.snapshot.wire,self.api.puts[0][1])
        headers={k.lower():v for k,v in self.api.puts[0][0].items()}
        self.assertNotIn('authorization',headers);self.assertNotIn('x-edgeai-pod-token',headers)
        (self.root/'claim').write_text('rotated-claim');(self.root/'pod').write_text('rotated-pod')
        receipt=self.client.commit(self.snapshot,None,version)
        control={k.lower():v for k,v in self.api.calls[-1][1].items()}
        self.assertTrue(control['authorization']=='Bearer rotated-claim' and control['x-edgeai-pod-token']=='rotated-pod')
        self.assertEqual(self.snapshot.sha256,receipt['sha256'])
        self.assertEqual(receipt,self.client.latest()['checkpoint'])
        self.assertEqual(receipt,self.client.upload(self.snapshot,None)['checkpoint'])

    def test_lost_commit_response_can_resume_same_candidate_in_new_client(self):
        version=self.client.put(self.snapshot,self.client.upload(self.snapshot,None)['upload'])
        self.api.drop=True
        with self.assertRaises(CheckpointUnavailable):self.client.commit(self.snapshot,None,version)
        new=CheckpointClient(self.binding,POD,self.generations,allow_http_loopback=True)
        receipt=new.upload(self.snapshot,None)['checkpoint']
        self.assertEqual(self.snapshot.serial,receipt['serial']);self.assertEqual(self.snapshot.sha256,receipt['sha256'])
        self.assertEqual(1,len(self.api.puts))

    def test_mismatched_or_malformed_receipts_never_confirm_a_candidate(self):
        original=self.api.receipt(self.client.candidate(self.snapshot,None))
        for key,value in [('attemptId',POD),('sha256','f'*64),('serial',1),('bytes',False),
                          ('epoch',True),('generationIds',[]),('previousCheckpointId',POD),('extra',1)]:
            changed={**original,key:value};self.api.raw=encode({'checkpoint':changed})
            with self.subTest(field=key),self.assertRaises(CheckpointError):
                self.client.commit(self.snapshot,None,self.api.version)
        for key,value in [('revision',False),('manifest',{}),('routes',[]),('stateSha256','f'*64)]:
            changed={**original,'summary':{**original['summary'],key:value}};self.api.raw=encode({'checkpoint':changed})
            with self.subTest(summary=key),self.assertRaises(CheckpointError):
                self.client.commit(self.snapshot,None,self.api.version)

    def test_control_rejects_redirect_cacheable_duplicate_and_oversized_responses(self):
        self.api.status=307
        with self.assertRaises(CheckpointError):self.client.latest()
        self.assertEqual(1,len(self.api.calls))
        for status in (400,401,403,404,409,413):
            self.api.status=status
            with self.assertRaises(CheckpointError) as failure:self.client.latest()
            self.assertNotIsInstance(failure.exception,CheckpointUnavailable)
        for status in (429,500,502,503,504):
            self.api.status=status
            with self.assertRaises(CheckpointUnavailable):self.client.latest()
        self.api.status=200;self.api.cache='public'
        with self.assertRaises(CheckpointError):self.client.latest()
        self.api.cache='no-store'
        for raw in (b'x'*262145,b'{"checkpoint":null,"checkpoint":null}',b'{"checkpoint":NaN}',b'['*2000):
            self.api.raw=raw
            with self.assertRaises(CheckpointError):self.client.latest()

    def test_storage_cannot_receive_control_headers_or_unapproved_plaintext_bytes(self):
        grant=self.api.grant();grant['headers']['Authorization']='forbidden'
        with self.assertRaises(CheckpointError):self.client.put(self.snapshot,grant)
        for url in ('http://example.test/object','https://user:password@example.test/object',self.api.url+'/object#fragment'):
            grant=self.api.grant();grant['url']=url
            with self.assertRaises(CheckpointError):self.client.put(self.snapshot,grant)
        strict=CheckpointClient(self.binding,POD,self.generations)
        with self.assertRaises(CheckpointError):strict.put(self.snapshot,self.api.grant())
        self.assertEqual([],self.api.puts)
        for timeout in (0,-1,6,True,float('nan')):
            with self.assertRaises(CheckpointError):self.client.latest(timeout=timeout)

    def pending_journal(self):
        journal=Journal(self.root/'journal',[A,B],[OUT],Limits(max_frames=9),create=True,durability='EXTERNAL')
        self.addCleanup(journal.close)
        journal.receive(data());journal.receive(data(B,payload=b'5'))
        journal.commit(0,journal.pending(),b'9',[Emission(OUT.route_id,b'9','application/json')])
        self.api.snapshot=capture(journal,'a'*64)
        return journal

    def test_publisher_advances_only_after_exact_commit_and_links_next_candidate(self):
        journal=self.pending_journal();publisher=CheckpointPublisher(self.client,journal,'a'*64,lambda:None)
        for _ in range(3):
            publisher.step()
            self.assertEqual((),journal.outgoing(confirmed_only=True))
        self.assertEqual(1,len(self.api.puts));self.assertEqual(0,publisher.confirmations)
        publisher.step();self.assertEqual(1,publisher.confirmations)
        self.assertEqual(b'9',journal.outgoing(confirmed_only=True)[0].payload)
        first=publisher.last_receipt['id'];calls=len(self.api.calls);publisher.step()
        self.assertEqual(calls,len(self.api.calls))
        journal.receive(data(A,2,b'5'));journal.commit(1,journal.pending(),b'14',[Emission(OUT.route_id,b'14','application/json')])
        self.api.snapshot=capture(journal,'a'*64);publisher.next_at=0
        for _ in range(3):publisher.step()
        self.assertEqual(2,publisher.confirmations);self.assertEqual(first,publisher.last_receipt['previousCheckpointId'])
        self.assertEqual([b'9',b'14'],[f.payload for f in journal.outgoing(confirmed_only=True)])

    def test_publisher_recovers_lost_commit_in_new_owner_without_reupload(self):
        journal=self.pending_journal();publisher=CheckpointPublisher(self.client,journal,'a'*64,lambda:None)
        for _ in range(3):publisher.step()
        self.api.drop=True;publisher.step();self.assertEqual(0,publisher.confirmations)
        self.assertEqual((),journal.outgoing(confirmed_only=True));journal.close()
        reopened=Journal(self.root/'journal',[A,B],[OUT],Limits(max_frames=9),durability='EXTERNAL');self.addCleanup(reopened.close)
        publisher=CheckpointPublisher(self.client,reopened,'a'*64,lambda:None);publisher.step()
        self.assertEqual(1,publisher.confirmations);self.assertEqual(1,len(self.api.puts))
        self.assertEqual(b'9',reopened.outgoing(confirmed_only=True)[0].payload)

    def test_late_receipt_after_guard_failure_cannot_advance_local_frontier(self):
        journal=self.pending_journal();blocked=[False]
        def guard():
            if blocked[0]:raise RuntimeError('fixture authority expired')
        publisher=CheckpointPublisher(self.client,journal,'a'*64,guard)
        for _ in range(3):publisher.step()
        original=self.client.commit
        def expire(*args,**kwargs):
            value=original(*args,**kwargs);blocked[0]=True;return value
        self.client.commit=expire
        with self.assertRaisesRegex(RuntimeError,'expired'):publisher.step()
        self.assertIsNotNone(self.api.latest);self.assertEqual((),journal.outgoing(confirmed_only=True))
        self.assertEqual(0,publisher.confirmations)


if __name__=='__main__':unittest.main()
