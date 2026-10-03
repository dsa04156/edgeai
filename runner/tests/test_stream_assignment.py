"""Strict assignment/lease checks and actual HTTP against an explicit API fixture."""
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import ssl
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from edgeai_runner.stream_assignment import Assignment, AssignmentError, BindingClient
from test_stream_journal import A, OUT, data

GENERATION = '99999999-9999-4999-8999-999999999999'
POD = '88888888-8888-4888-8888-888888888888'


def document(binding=A, *, direction='PRODUCER', duration=30, username='source-a', secret='fixture-only'):
    now = datetime.now(timezone.utc)
    return {'apiVersion':'edgeai.stream-assignment/v1', 'generationId':GENERATION,
            'routeId':binding.route_id, 'runId':POD, 'generation':binding.generation,
            'direction':direction, 'producer':binding.producer.document(),
            'consumer':{'attemptId':OUT.producer.id, 'epoch':OUT.producer.epoch},
            'mediaType':'application/json', 'maxPayloadBytes':4096,
            'serverTime':now.isoformat().replace('+00:00','Z'),
            'leaseUntil':(now + timedelta(seconds=duration)).isoformat().replace('+00:00','Z'),
            'mqtt':{'host':'127.0.0.1', 'port':1883, 'tls':False, 'caPem':'', 'username':username,
                    'clientId':username, 'password':secret,
                    'framesTopic':f'edgeai/streams/{binding.route_id}/{binding.generation}/frames',
                    'acksTopic':f'edgeai/streams/{binding.route_id}/{binding.generation}/acks'}}


class DiscoveryFixture:
    def __init__(self, value, *, certificate=None, key=None):
        self.value, self.calls, self.status, self.raw, self.cache = value, [], 200, None, 'no-store'
        owner = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                owner.calls.append((self.path, {k.lower():v for k,v in self.headers.items()}, json.loads(self.rfile.read(int(self.headers['Content-Length'])))))
                encoded = owner.raw if owner.raw is not None else json.dumps(owner.value).encode()
                self.send_response(owner.status)
                self.send_header('Content-Type','application/json')
                self.send_header('Cache-Control',owner.cache)
                self.send_header('Content-Length',str(len(encoded)))
                if owner.status == 307: self.send_header('Location','/unexpected-redirect')
                self.end_headers()
                self.wfile.write(encoded)
        self.server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        if certificate:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(certificate,key)
            self.server.socket = context.wrap_socket(self.server.socket,server_side=True)
        self.url = f'{"https://localhost" if certificate else "http://127.0.0.1"}:{self.server.server_port}'
        self.thread = threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(3)


class StreamAssignmentTest(unittest.TestCase):
    def decode(self, value, *, actor=A.producer, started=None, clock=time.monotonic):
        return Assignment.decode(json.dumps(value).encode(),actor,GENERATION,clock() if started is None else started,clock=clock)

    def test_exact_actor_generation_topics_and_private_memory_connection(self):
        value = document()
        assignment = self.decode(value)
        self.assertEqual(A,assignment.binding)
        assignment.verify_frame(data())
        self.assertNotIn(value['mqtt']['password'],repr(assignment))
        self.assertNotIn(value['mqtt']['password'],repr(assignment.connection))
        with self.assertRaises(AssignmentError): self.decode(value,actor=OUT.producer)
        with self.assertRaises(AssignmentError): Assignment.decode(json.dumps(value).encode(),A.producer,POD,time.monotonic())

    def test_network_time_consumes_lease_and_clock_cannot_revive_expired_snapshot(self):
        now = [102.0]
        assignment = self.decode(document(duration=5),started=100.0,clock=lambda:now[0])
        self.assertEqual(3.0,assignment.remaining())
        now[0] = 105.0
        with self.assertRaisesRegex(AssignmentError,'expired'): assignment.remaining()
        with self.assertRaisesRegex(AssignmentError,'expired'): self.decode(document(duration=2),started=100.0,clock=lambda:now[0])

    def test_invalid_types_fields_topics_tls_and_bounds_are_rejected(self):
        mutations = [lambda v:v.update(unexpected=True),lambda v:v.update(generation=True),
            lambda v:v.update(maxPayloadBytes=262145),lambda v:v.update(maxPayloadBytes=True),
            lambda v:v.update(mediaType='invalid'),lambda v:v['consumer'].update(epoch=0),
            lambda v:v['producer'].update(deviceId=POD),lambda v:v['mqtt'].update(framesTopic='#'),
            lambda v:v['mqtt'].update(acksTopic='edgeai/streams/foreign/1/acks'),
            lambda v:v['mqtt'].update(host='broker.example.test'),lambda v:v['mqtt'].update(port=True),
            lambda v:v['mqtt'].update(clientId='different'),lambda v:v['mqtt'].update(password='x'*65),
            lambda v:v['mqtt'].update(tls=True,caPem='not a certificate')]
        for i, mutate in enumerate(mutations):
            with self.subTest(case=i):
                value=document();mutate(value)
                with self.assertRaises(AssignmentError): self.decode(value)
        for duration in (0,-1,121):
            with self.assertRaises(AssignmentError):self.decode(document(duration=duration))
        value=json.dumps(document()).encode()
        for raw in (b'x'*262145,b'{"apiVersion":1,"apiVersion":2}',b'{"n":NaN}',b'['*2000,value[:-1]):
            with self.assertRaises(AssignmentError):Assignment.decode(raw,A.producer,GENERATION,time.monotonic())

    def test_route_payload_media_type_are_enforced_before_processing(self):
        value=document();value['maxPayloadBytes']=1
        assignment=self.decode(value)
        assignment.verify_frame(data())
        with self.assertRaises(AssignmentError):assignment.verify_frame(data(payload=b'55'))
        from edgeai_runner.stream_protocol import Frame
        with self.assertRaises(AssignmentError):assignment.verify_frame(Frame(A,1,'DATA',b'4','text/plain'))

    def fixture(self, value):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup)
        root=Path(temp.name);token=root/'claim';token.write_text('fixture-claim');token.chmod(0o600)
        api=DiscoveryFixture(value);self.addCleanup(api.close)
        return root,token,api

    def test_actual_device_http_scopes_request_and_rereads_private_token(self):
        root,token,api=self.fixture(document())
        client=BindingClient(api.url,A.producer,token,allow_http_loopback=True)
        self.assertEqual(A,client.fetch(GENERATION).binding)
        token.write_text('rotated-fixture-claim');client.fetch(GENERATION)
        self.assertEqual(2,len(api.calls))
        path,headers,body=api.calls[-1]
        self.assertEqual(f'/internal/v1/devices/{A.producer.device_id}/sessions/{A.producer.id}/streams',path)
        self.assertEqual({'epoch':A.producer.epoch,'generationId':GENERATION},body)
        self.assertTrue(headers.get('authorization')=='Bearer rotated-fixture-claim')
        self.assertNotIn('x-edgeai-pod-token',headers)
        token.chmod(0o644)
        with self.assertRaises(AssignmentError):client.fetch(GENERATION)
        self.assertEqual(2,len(api.calls))

    def test_actual_runner_http_uses_rotating_projected_pod_identity(self):
        root,token,api=self.fixture(document(direction='CONSUMER',username='processor'))
        target=root/'pod-value';target.write_text('fixture-pod-one\n');target.chmod(0o640)
        path=root/'projected';path.symlink_to(target)
        claim=root/'projected-claim';claim.symlink_to(token)
        client=BindingClient(api.url,OUT.producer,claim,pod_uid=POD,pod_token_file=path,allow_http_loopback=True)
        client.fetch(GENERATION);target.write_text('fixture-pod-two\n');client.fetch(GENERATION)
        self.assertTrue(api.calls[-1][1]['x-edgeai-pod-token']=='fixture-pod-two')
        self.assertEqual(POD,api.calls[-1][2]['podUid'])
        self.assertEqual(f'/internal/v1/attempts/{OUT.producer.id}/streams',api.calls[-1][0])

    def test_actual_http_rejects_redirect_cacheable_oversized_and_fenced_responses(self):
        root,token,api=self.fixture(document())
        client=BindingClient(api.url,A.producer,token,allow_http_loopback=True)
        api.status=307
        with self.assertRaisesRegex(AssignmentError,'redirect'):client.fetch(GENERATION)
        self.assertEqual(1,len(api.calls))
        for status in (401,403,404,409,503):
            api.status=status
            with self.assertRaises(AssignmentError):client.fetch(GENERATION)
        api.status=200;api.cache='public'
        with self.assertRaises(AssignmentError):client.fetch(GENERATION)
        api.cache='no-store';api.raw=b'x'*262145
        with self.assertRaises(AssignmentError):client.fetch(GENERATION)

    def test_discovery_endpoint_and_private_file_cannot_redirect_identity(self):
        root,token,api=self.fixture(document())
        for origin in ('http://broker.example.test:8080',api.url,api.url+'/path',api.url+'?x=1','https://user:password@example.test'):
            with self.assertRaises(AssignmentError):BindingClient(origin,A.producer,token)
        link=root/'link';link.symlink_to(token)
        client=BindingClient(api.url,A.producer,link,allow_http_loopback=True)
        with self.assertRaises(AssignmentError):client.fetch(GENERATION)
        self.assertEqual([],api.calls)


    def test_actual_heartbeat_http_resume_retry_scope_and_strict_acknowledgement(self):
        root,token,api=self.fixture({'sequence':7,'assignment':document()})
        client=BindingClient(api.url,A.producer,token,allow_http_loopback=True)
        result=client.heartbeat(GENERATION,0)
        self.assertEqual(7,result.sequence);self.assertEqual(A,result.assignment.binding)
        api.value['sequence']=8
        client.heartbeat(GENERATION,8);client.heartbeat(GENERATION,8)
        self.assertEqual(api.calls[-2][2],api.calls[-1][2])
        self.assertTrue(api.calls[-1][0].endswith('/streams/heartbeat'))
        self.assertEqual({'epoch':A.producer.epoch,'generationId':GENERATION,'sequence':8},api.calls[-1][2])
        self.assertTrue(api.calls[-1][1]['authorization']=='Bearer fixture-claim')
        for sequence in (-1,True,9007199254740992,'8'):
            with self.assertRaises(AssignmentError):client.heartbeat(GENERATION,sequence)
        self.assertEqual(3,len(api.calls))
        for value in ({'sequence':True,'assignment':document()},{'sequence':9,'assignment':document()},
                      {'sequence':8,'assignment':document(),'extra':1},{'sequence':8},[],{'sequence':-1,'assignment':document()}):
            api.value=value
            with self.assertRaises(AssignmentError):client.heartbeat(GENERATION,8)
        api.raw=b'{"sequence":8,"sequence":8,"assignment":{}}'
        with self.assertRaises(AssignmentError):client.heartbeat(GENERATION,8)
        api.status=409
        with self.assertRaisesRegex(AssignmentError,'fenced'):client.heartbeat(GENERATION,8)

    def test_actual_runner_heartbeat_requires_both_rotating_credentials(self):
        root,token,api=self.fixture({'sequence':1,'assignment':document(direction='CONSUMER',username='processor')})
        pod=root/'pod';pod.write_text('fixture-pod');pod.chmod(0o600)
        client=BindingClient(api.url,OUT.producer,token,pod_uid=POD,pod_token_file=pod,allow_http_loopback=True)
        self.assertEqual(1,client.heartbeat(GENERATION,1).sequence)
        path,headers,body=api.calls[-1]
        self.assertEqual(f'/internal/v1/attempts/{OUT.producer.id}/streams/heartbeat',path)
        self.assertTrue(headers['x-edgeai-pod-token']=='fixture-pod')
        self.assertEqual(POD,body['podUid']);self.assertEqual(1,body['sequence'])


if __name__ == '__main__':unittest.main()
