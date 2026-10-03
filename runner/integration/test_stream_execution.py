"""Actual Runner/model subprocesses, HTTPS and TLS MQTT; server barrier/storage are fixtures."""
import hashlib
import json
import os
from pathlib import Path
import signal
import ssl
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid

from test_stream_mqtt import Broker
from test_stream_session import SessionApi, A, B, OUT, POD, INPUTS, OUTPUTS
from edgeai_runner.stream_journal import Emission, Journal, Limits
from edgeai_runner.stream_mqtt import Link

ROOT = Path(__file__).resolve().parents[2]


class StreamExecutionTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='edgeai-stream-runner-'); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.broker = Broker(self.root); self.addCleanup(self.broker.stop); self.broker.enable_tls()
        self.api = SessionApi(self.broker,duration=5); self.addCleanup(self.api.close)
        self.finalize = False; self.wrong_receipt = False; self.complete_calls = 0
        self.failure = None; self.artifact = None; self.commits = []; self.execution_calls = 0
        self.recovery = 'NEW'; self.unavailable = 0
        self.hold_grant=False;self.granted=threading.Event();self.release_grant=threading.Event()
        self.finalized_calls=0;self.finalized_status=None;self.reject_during_download=False;self.swap_final_routes=False
        self.checkpoint_actor=None
        profile = json.loads((ROOT/'contracts/profiles/service-stream.example.json').read_text())
        self.spec = profile['stream']; self.spec['command'] = [sys.executable,str(ROOT/'runner/examples/stream_sum.py')]
        self.spec['limits']['maxFrames'] = 12
        self.assignment = {'runId':POD,'taskId':POD,'attemptId':OUT.producer.id,'epoch':OUT.producer.epoch,
            'command':[sys.executable,str(ROOT/'runner/examples/stream_result.py')],'args':[],'parameters':{'mode':'zip'},
            'inputs':{},'outputs':profile['outputs'],'timeoutSeconds':20,'stream':self.spec}
        owner = self
        class Handler(self.api.server.RequestHandlerClass):
            def reply(self,status,value):
                body=json.dumps(value).encode();self.send_response(status)
                self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)))
                self.send_header('Cache-Control','no-store')
                self.end_headers();self.wfile.write(body)
            def do_POST(self):
                if self.path.endswith('/checkpoints/finalized'):
                    data=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    owner.finalized_calls+=1;latest=owner.api.checkpoint_latest
                    valid=(self.headers.get('Authorization')=='Bearer fixture-claim' and self.headers.get('X-EdgeAI-Pod-Token')=='fixture-pod'
                           and data=={'epoch':owner.assignment['epoch'],'podUid':POD,'checkpointId':latest['id']}
                           and self.path.startswith('/internal/v1/attempts/'+owner.assignment['attemptId']+'/'))
                    if not valid or not owner.finalize or owner.finalized_status:return self.reply(owner.finalized_status or 409,{})
                    return self.reply(200,{'checkpoint':latest,'download':{'url':owner.api.url+'/checkpoint-object?versionId='+latest['versionId'],
                        'expiresAt':latest['createdAt']}})
                if self.path.rsplit('/',1)[1] not in ('claim','execution','complete','uploads','commit','fail') or '/checkpoints/' in self.path:
                    return super().do_POST()
                data=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                if self.headers.get('Authorization') != 'Bearer fixture-claim' or self.headers.get('X-EdgeAI-Pod-Token') != 'fixture-pod':
                    return self.reply(401,{})
                if data.get('epoch') != owner.assignment['epoch'] or data.get('podUid') != POD:
                    return self.reply(409,{})
                operation=self.path.rsplit('/',1)[1]
                if operation=='claim':return self.reply(200,owner.assignment)
                if operation=='execution':
                    owner.execution_calls+=1
                    if owner.recovery=='FINALIZE':
                        return self.reply(200,{'state':'FINALIZE',**{k:owner.assignment[k] for k in ('runId','taskId','attemptId','epoch')},
                            'inputRoutes':{'a':B.route_id,'b':A.route_id} if owner.swap_final_routes else {'a':A.route_id,'b':B.route_id},
                            'outputRoutes':{'sum':[OUT.route_id]},'generationIds':owner.api.checkpoint_latest['generationIds'],
                            'checkpointId':owner.api.checkpoint_latest['id'],
                            **({'checkpointActor':owner.checkpoint_actor} if owner.checkpoint_actor is not None else {})})
                    if owner.execution_calls==1:return self.reply(200,{'state':'WAITING'})
                    return self.reply(200,{'state':'READY',**{k:owner.assignment[k] for k in ('runId','taskId','attemptId','epoch')},
                        'inputs':INPUTS,'outputs':OUTPUTS,'recovery':owner.recovery})
                if operation=='complete':
                    owner.complete_calls+=1
                    latest=owner.api.checkpoint_latest
                    if not latest or latest['id']!=data['checkpointId']:return self.reply(409,{})
                    # Concrete proof includes the final output ACK, not just input END.
                    if any(not r['ended'] or r['received']!=r['committed'] for r in latest['summary']['routes']):
                        return self.reply(409,{})
                    if owner.unavailable:
                        owner.unavailable-=1;return self.reply(503,{})
                    if owner.finalize and owner.hold_grant:
                        owner.granted.set();owner.release_grant.wait(8)
                    try:return self.reply(200,{'state':'FINALIZE' if owner.finalize else 'WAITING',
                        'checkpointId':str(uuid.uuid4()) if owner.wrong_receipt else latest['id']})
                    except (BrokenPipeError,ConnectionResetError,ssl.SSLEOFError):return
                if operation=='uploads':
                    return self.reply(200,{'outputs':[{'port':'result','url':owner.api.url+'/artifact',
                        'headers':{'Content-Type':'application/json'}}]})
                if operation=='commit':
                    item=data['outputs'][0]
                    if not owner.finalize or owner.artifact is None or item['sha256']!=hashlib.sha256(owner.artifact).hexdigest():
                        return self.reply(409,{})
                    owner.commits.append(data);return self.reply(201,{'state':'SUCCEEDED'})
                owner.failure=data['reason'];return self.reply(200,{})
            def do_PUT(self):
                if self.path!='/artifact':return super().do_PUT()
                if self.headers.get('Authorization') or self.headers.get('X-EdgeAI-Pod-Token'):
                    return self.reply(403,{})
                owner.artifact=self.rfile.read(int(self.headers['Content-Length']))
                self.send_response(200);self.send_header('x-amz-version-id','fixture-artifact-version')
                self.send_header('Content-Length','0');self.end_headers()
            def do_GET(self):
                if owner.reject_during_download:owner.finalized_status=409
                return super().do_GET()
        self.api.server.RequestHandlerClass=Handler
        for name,value in [('claim','fixture-claim'),('pod','fixture-pod')]:
            path=self.root/name;path.write_text(value);path.chmod(0o600)
        (self.root/'work').mkdir(mode=0o700)
        self.journals={};self.links={};self.process=None;self.consume=True
        self.addCleanup(self.close)
        for actor,inputs,outputs in [('source-a',[],[A]),('source-b',[],[B]),('sink',[OUT],[])]:
            journal=Journal(self.root/actor,inputs,outputs,Limits(max_frames=12),create=True)
            self.journals[actor]=journal
            self.links[actor]=Link(journal,self.broker.endpoint(actor),'runner-test-'+actor)

    def close(self):
        self.release_grant.set()
        if self.process is not None:
            if self.process.poll() is None:self.process.send_signal(signal.SIGTERM)
            try:self.process.communicate(timeout=5)
            except subprocess.TimeoutExpired:self.process.kill();self.process.communicate(timeout=3)
        for link in self.links.values():link.close(force=True)
        for journal in self.journals.values():journal.close()

    def start(self):
        env={**os.environ,'SSL_CERT_FILE':str(self.broker.ca),'EDGEAI_ATTEMPT_ID':self.assignment['attemptId'],
            'EDGEAI_ATTEMPT_EPOCH':str(self.assignment['epoch']),'EDGEAI_POD_UID':POD,'EDGEAI_CONTROL_PLANE_URL':self.api.url,
            'EDGEAI_CLAIM_FILE':str(self.root/'claim'),'EDGEAI_POD_TOKEN_FILE':str(self.root/'pod'),
            'EDGEAI_WORK_DIR':str(self.root/'work')}
        self.process=subprocess.Popen([sys.executable,str(ROOT/'runner/runner.py')],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)

    def emit(self):
        for actor,binding,numbers in [('source-a',A,[4,2]),('source-b',B,[5,3])]:
            journal=self.journals[actor]
            journal.commit(0,[],b'', [Emission(binding.route_id,str(v).encode(),'application/json') for v in numbers]
                           +[Emission(binding.route_id,b'',None,'END')])

    def until(self,predicate,timeout=15):
        deadline=time.monotonic()+timeout
        while not predicate():
            if time.monotonic()>deadline:self.fail('Runner integration deadline exceeded')
            if self.process is not None and self.process.poll() is not None:
                self.fail('Runner exited before expected phase: '+self.process.communicate()[0].decode())
            for link in self.links.values():link.step(.001)
            sink=self.journals['sink'];pending=sink.pending()
            if self.consume and pending:
                state=next((f.payload for f in reversed(pending) if f.kind=='DATA'),sink.checkpoint().state)
                sink.commit(sink.checkpoint().revision,pending,state)
            time.sleep(.005)

    def finish(self,success):
        self.until(lambda:self.process.poll() is not None)
        out,err=self.process.communicate();self.assertEqual(0 if success else 1,self.process.returncode,(out,err))
        self.assertEqual(b'',err)
        for secret in (b'fixture-claim',b'fixture-pod',b'stateBase64'):self.assertNotIn(secret,out)

    def test_real_runner_waits_for_sealed_last_ack_and_server_barrier_then_writes_artifact(self):
        self.consume=False;self.start();self.emit()
        self.until(lambda:self.api.checkpoint_latest is not None and
                   all(r['ended'] for r in self.api.checkpoint_latest['summary']['routes']))
        self.assertEqual(0,self.complete_calls);self.assertIsNone(self.artifact)
        self.consume=True;self.until(lambda:self.complete_calls>=2)
        self.assertIsNone(self.artifact);self.assertFalse(self.commits)
        self.assertFalse((self.root/'work/stream-state').exists())
        self.until(lambda:all(not self.journals[a].outgoing() for a in ('source-a','source-b')))
        self.unavailable=2;self.finalize=True;self.finish(True)
        self.assertEqual({'sum':14},json.loads(self.artifact));self.assertEqual(1,len(self.commits))
        self.assertEqual(b'14',(self.root/'work/stream-state').read_bytes())
        self.assertEqual(0o600,(self.root/'work/stream-state').stat().st_mode & 0o777)
        self.assertFalse(self.api.storage_credential_leak)

    def test_cancel_while_barrier_waits_never_runs_finalizer(self):
        self.start();self.emit();self.until(lambda:self.complete_calls>0)
        self.process.send_signal(signal.SIGTERM);self.finish(False)
        self.assertIsNone(self.artifact);self.assertFalse(self.commits)
        self.assertEqual('CANCELLED',self.failure)

    def test_setgid_work_volume_still_creates_private_stream_session_and_commits(self):
        # Kubernetes fsGroup marks emptyDir setgid; mkdir(0700) inherits that bit.
        import stat
        (self.root/'work').chmod(0o2700)
        self.finalize=True;self.start();self.emit();self.finish(True)
        self.assertEqual({'sum':14},json.loads(self.artifact))
        self.assertEqual(1,len(self.commits))
        for directory in ('stream','stream/journal','stream/workload'):
            self.assertEqual(0o700,stat.S_IMODE((self.root/'work'/directory).stat().st_mode))

    def test_foreign_checkpoint_grant_never_runs_finalizer(self):
        self.wrong_receipt=True;self.finalize=True;self.start();self.emit();self.finish(False)
        self.assertIsNone(self.artifact);self.assertFalse(self.commits)

    def test_assignment_payload_budget_cannot_exceed_service_input(self):
        self.spec['inputs']['a']['maxPayloadBytes']=1
        self.start();self.finish(False)
        self.assertEqual(0,self.complete_calls);self.assertIsNone(self.artifact)

    def test_missing_restore_checkpoint_cannot_silently_start_new_state(self):
        self.recovery='RESTORE';self.start();self.finish(False)
        self.assertEqual(0,self.complete_calls);self.assertIsNone(self.artifact)

    def test_model_process_failure_never_publishes_a_final_result(self):
        self.spec['command']=[sys.executable,'-c','raise SystemExit(19)']
        self.start();self.emit();self.finish(False)
        self.assertEqual(0,self.complete_calls);self.assertIsNone(self.artifact);self.assertFalse(self.commits)

    def test_finalizer_failure_after_permission_does_not_commit_success(self):
        self.assignment['command']=[sys.executable,'-c','raise SystemExit(23)']
        self.finalize=True;self.start();self.emit();self.finish(False)
        self.assertGreater(self.complete_calls,0);self.assertEqual('WORKLOAD_FAILED',self.failure)
        self.assertIsNone(self.artifact);self.assertFalse(self.commits)

    def test_killed_runner_restores_sealed_state_in_new_volume_and_finishes_fourteen(self):
        self.start()
        for actor,binding,number in [('source-a',A,4),('source-b',B,5)]:
            self.journals[actor].commit(0,[],b'',[Emission(binding.route_id,str(number).encode(),'application/json')])
        self.until(lambda:self.journals['sink'].checkpoint().state==b'9' and
                   self.api.checkpoint_latest is not None and
                   self.api.checkpoint_latest['summary']['stateSha256']==hashlib.sha256(b'9').hexdigest() and
                   all(not self.journals[a].outgoing() for a in ('source-a','source-b')))
        self.process.kill();self.process.communicate(timeout=3);self.process=None
        shutil.rmtree(self.root/'work');(self.root/'work').mkdir(mode=0o700)
        for actor,binding,number in [('source-a',A,2),('source-b',B,3)]:
            journal=self.journals[actor]
            journal.commit(journal.checkpoint().revision,[],b'',[
                Emission(binding.route_id,str(number).encode(),'application/json'),Emission(binding.route_id,b'',None,'END')])
        self.recovery='RESTORE';self.finalize=True;self.start();self.finish(True)
        self.assertGreater(self.api.checkpoint_gets,0)
        self.assertEqual({'sum':14},json.loads(self.artifact));self.assertEqual(1,len(self.commits))


    def finalizer_restart(self):
        self.finalize=True;self.hold_grant=True;self.start();self.emit()
        self.until(self.granted.is_set)
        self.assertIsNone(self.artifact);self.assertFalse(self.commits)
        self.process.kill();self.process.communicate(timeout=3);self.process=None
        self.release_grant.set();self.hold_grant=False
        for link in self.links.values():link.close(force=True)
        self.links.clear();self.broker.stop();self.api.status=409
        self.api.leases={identity:0 for identity in self.api.leases}
        shutil.rmtree(self.root/'work');(self.root/'work').mkdir(mode=0o700)
        self.recovery='FINALIZE'

    def test_killed_after_grant_recovers_only_finalizer_without_broker_or_model(self):
        self.finalizer_restart();calls=len(self.api.calls);self.start();self.finish(True)
        self.assertEqual(calls,len(self.api.calls));self.assertEqual(2,self.finalized_calls)
        self.assertFalse((self.root/'work/stream').exists())
        self.assertEqual(b'14',(self.root/'work/stream-state').read_bytes())
        self.assertEqual({'sum':14},json.loads(self.artifact));self.assertEqual(1,len(self.commits))
        self.assertFalse(self.api.storage_credential_leak)

    def test_new_attempt_finalizer_uses_original_sealed_state_and_current_authentication(self):
        self.finalizer_restart();calls=len(self.api.calls)
        self.checkpoint_actor={'attemptId':OUT.producer.id,'epoch':OUT.producer.epoch}
        self.assignment['attemptId']=str(uuid.uuid4());self.assignment['epoch']+=1
        self.start();self.finish(True)
        self.assertEqual(calls,len(self.api.calls));self.assertEqual(2,self.finalized_calls)
        self.assertFalse((self.root/'work/stream').exists())
        self.assertEqual(b'14',(self.root/'work/stream-state').read_bytes())
        self.assertEqual({'sum':14},json.loads(self.artifact));self.assertEqual(1,len(self.commits))
        self.assertEqual(self.assignment['epoch'],self.commits[0]['epoch'])
        self.assertEqual(OUT.producer.id,self.api.checkpoint_latest['attemptId'])

    def test_new_attempt_finalizer_rechecks_cancellation_after_object_read(self):
        self.finalizer_restart();self.reject_during_download=True
        self.checkpoint_actor={'attemptId':OUT.producer.id,'epoch':OUT.producer.epoch}
        self.assignment['attemptId']=str(uuid.uuid4());self.assignment['epoch']+=1
        self.start();self.finish(False)
        self.assertEqual(2,self.finalized_calls);self.assertGreater(self.api.checkpoint_gets,0)
        self.assertIsNone(self.artifact);self.assertFalse((self.root/'work/stream-state').exists())

    def test_finalizer_recovery_rejects_corrupt_fixed_version_without_creating_state(self):
        self.finalizer_restart();self.api.checkpoint_corrupt_download=True;self.start();self.finish(False)
        self.assertIsNone(self.artifact);self.assertFalse(self.commits)
        self.assertFalse((self.root/'work/stream-state').exists())

    def test_finalizer_rechecks_cancellation_during_storage_read_before_writing_state(self):
        self.finalizer_restart();self.reject_during_download=True;self.start();self.finish(False)
        self.assertEqual(2,self.finalized_calls);self.assertGreater(self.api.checkpoint_gets,0)
        self.assertIsNone(self.artifact);self.assertFalse((self.root/'work/stream-state').exists())

    def test_finalizer_port_mapping_must_match_sealed_execution_digest(self):
        self.finalizer_restart();self.swap_final_routes=True;self.start();self.finish(False)
        self.assertEqual(0,self.api.checkpoint_gets);self.assertIsNone(self.artifact)
        self.assertFalse((self.root/'work/stream-state').exists())


if __name__=='__main__':unittest.main()
