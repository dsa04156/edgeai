"""Public STREAM input and terminal checkpoint fixtures for actual Result API recovery."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import uuid

from postgres_backup import literal,private_file
from test_recovery_runtime_starts import q


class StreamFixture:
    def __init__(self,work,minio,namespace,operation):
        from recovery_device_test_support import Fixtures
        directory=work/'stream-authority';directory.mkdir(mode=0o700)
        self.broker=Fixtures(directory,minio,'sha256:'+'b'*64)
        environment=os.environ.copy()
        try:self.broker.start()
        except Exception:
            self.broker.close();raise
        finally:os.environ.clear();os.environ.update(environment)
        self.broker.cfg.recovery_id=operation;self.work=work;self.namespace=namespace
        key=directory/'api.key'
        with private_file(key,'w') as target:target.write(secrets.token_hex(32))
        self.env={name:'true' for name in ('EDGEAI_RUNTIME_ENABLED','EDGEAI_STREAM_ENABLED','EDGEAI_STREAM_BINDINGS_ENABLED','EDGEAI_STREAM_RUNS_ENABLED')}
        self.env.update(EDGEAI_RUNTIME_WORKER_ENABLED='false',EDGEAI_RUNTIME_NAMESPACE=namespace,
            EDGEAI_STREAM_RECONCILE_MS='3600000',EDGEAI_STREAM_BROKER_URL=self.broker.cfg.endpoint,
            EDGEAI_STREAM_BROKER_DIGEST=self.broker.digest,EDGEAI_STREAM_LEASE_SECONDS='120',
            EDGEAI_STREAM_PRINCIPAL_KEY_FILE=str(key),EDGEAI_STREAM_DEVICE_KEY_FILE=str(key),EDGEAI_RUNNER_KEY_FILE=str(key),
            EDGEAI_STREAM_ADMIN_PASSWORD_FILE=str(self.broker.cfg.admin_password_file),EDGEAI_STREAM_CA_FILE=str(self.broker.cfg.ca_file),
            EDGEAI_KUBE_API_URL='http://127.0.0.1:1',EDGEAI_KUBE_CA_FILE='',EDGEAI_KUBE_TOKEN_FILE='')

    def public_input(self,api):
        profile=api.request('POST','profiles/DEVICE',{'key':'sealed-device','version':'1.0.0','spec':{'protocol':'mqtt'}},201)
        self.device=api.request('POST','devices',{'key':'sealed-device','displayName':'Sealed input','profileVersionId':profile['id'],'sourceMode':'SYNTHETIC'},201)
        self.session=api.request('POST','devices/'+self.device['id']+'/sessions',{'bootId':str(uuid.uuid4())},201)
        return {'streamInputs':[{'deviceId':self.device['id'],'sourcePort':'samples','toTask':'root','toPort':'sample','maxPayloadBytes':4096}],
                'taskExecutions':{'child':{'mode':'AUTO'}}}

    def seal(self,pg,db,runtime,service,bucket,store,request,authority):
        from edgeai_runner.stream_protocol import Binding,Producer,Frame
        from edgeai_runner.stream_checkpoint import capture,MEDIA_TYPE
        from edgeai_runner.stream_journal import Journal,Limits
        from edgeai_runner.stream_processor import execution_digest
        import recovery_runtime_starts as starts
        actor=Producer('DEVICE_SESSION',self.session['id'],self.session['epoch'],self.device['id'])
        route=pg.sql('SELECT id::text FROM edgeai.data_route WHERE consumer_task_id='+q(authority['taskId']),db)
        generation=str(uuid.uuid4());self.binding=Binding(route,1,actor);self.actor=actor
        pg.sql('INSERT INTO edgeai.route_generation(id,route_id,run_id,generation,source_device_id,consumer_task_id,producer_session_id,producer_epoch,'
            'consumer_attempt_id,consumer_epoch,broker_digest,policy_digest,request_digest,created_at,updated_at,lease_until) VALUES ('+
            ','.join(q(v) for v in (generation,route,authority['runId']))+',1,'+
            ','.join(q(v) for v in (self.device['id'],authority['taskId'],self.session['id']))+','+str(self.session['epoch'])+','+
            q(authority['attemptId'])+','+str(authority['epoch'])+','+','.join(literal(v) for v in (self.broker.digest,'sha256:'+'c'*64,'sha256:'+'d'*64))+
            ",now(),now(),now()+interval '120 seconds'); UPDATE edgeai.route_generation SET activated_at=now(),updated_at=now() WHERE id="+q(generation),db)
        context=next(c for c in json.loads(pg.sql(starts.context_query('allocationId' in authority),db)) if c['runtime']['id']==runtime)
        work=json.loads(context['workJson']);spec=work['spec']['stream']
        execution=execution_digest(spec['command']+spec['args'],work['parameters'],{'sample':route},{},spec['stepTimeoutSeconds'])
        limits=Limits(max_frames=spec['limits']['maxFrames'],max_buffer_bytes=spec['limits']['maxBufferBytes'],max_state_bytes=spec['limits']['maxStateBytes'])
        with Journal(self.work/'sealed-checkpoint',[self.binding],[],limits,create=True,durability='EXTERNAL') as journal:
            journal.receive(Frame(self.binding,1,'DATA',b'6','application/json'));journal.commit(0,journal.pending(),b'6')
            journal.receive(Frame(self.binding,2,'END',b'',None));journal.commit(1,journal.pending(),b'6')
            snapshot=capture(journal,execution);document=snapshot.document()
        key='tasks/'+authority['taskId']+'/attempts/'+authority['attemptId']+'/stream-checkpoint/'+snapshot.sha256
        status,headers,_=store.request('PUT','/'+bucket+'/'+key,body=snapshot.wire,extra={'content-type':MEDIA_TYPE});assert status==200
        from recovery_remote_storage import header
        cp={'id':str(uuid.uuid4()),'run_id':authority['runId'],'task_id':authority['taskId'],'attempt_id':authority['attemptId'],
            'runtime_id':runtime,'epoch':authority['epoch'],'producer_pod_uid':authority['podUid'],'service_profile_version_id':service,
            'serial':document['serial'],'state_revision':document['revision'],'sha256':snapshot.sha256,'execution_sha256':execution,
            'bytes':len(snapshot.wire),'generation_ids':'{'+generation+'}',
            'summary_json':{'manifest':document['manifest'],'revision':document['revision'],
                'routes':[{k:v for k,v in row.items() if k!='frames'} for row in document['routes']],
                'stateSha256':hashlib.sha256(b'6').hexdigest(),'stateBytes':1},
            'bucket':bucket,'object_key':key,'object_version':header(headers,'x-amz-version-id'),'created_at':pg.sql('SELECT now()::text',db)}
        pg.sql('INSERT INTO edgeai.stream_checkpoint SELECT (jsonb_populate_record(NULL::edgeai.stream_checkpoint,'+literal(json.dumps(cp))+'::jsonb)).*',db)
        # The Device END report is explicit fixture data. Task completion and the
        # component grant use the real authenticated Runner completion endpoint.
        pg.sql('INSERT INTO edgeai.stream_device_completion(generation_id,sequence,created_at) VALUES ('+q(generation)+',2,now())',db)
        reply=request('streams/complete',{'checkpointId':cp['id']},200)
        assert reply['state']=='FINALIZE' and reply['checkpointId']==cp['id']
        self.checkpoint=cp;self.generation=generation
        self.grant=json.loads(pg.sql('SELECT to_jsonb(c) FROM edgeai.stream_task_completion c WHERE attempt_id='+q(authority['attemptId']),db))
        assert self.grant['granted_at'] is not None

    def fence(self,attempt):self.broker.fence_device(self.actor,attempt,self.binding)

    def options(self):
        return dict(stream_results=True,broker_digest=self.broker.digest,mqtt_state_directory=self.broker.cfg.state_directory,
            mqtt_ca_file=self.broker.cfg.ca_file,mqtt_original_password_file=self.broker.cfg.admin_password_file)

    def close(self):return self.broker.close()
