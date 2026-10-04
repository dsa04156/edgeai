"""Public Device fanout, real Kubernetes retirement and TLS broker proof for restored STREAM workflows."""
import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re
import runpy
import secrets
import subprocess
import sys
import time
import traceback
from types import SimpleNamespace
from unittest.mock import patch
import uuid

from postgres_backup import ROOT, Blocked, Postgres, backup, restore, identifier, literal, private_file
from recovery_kubernetes import Kubernetes, PART, MANAGER, RUNTIME
from recovery_stop_kubernetes import Stop, FINALIZER, termination_proof
from recovery_remote_retire import durable_json
from recovery_device_test_support import Fixtures
import recovery_kubernetes_retire as producers
import recovery_stream_retire as routes
import recovery_stream_workflows as workflows
import recovery_kubernetes_workflows as batch_workflows
from edgeai_runner.stream_protocol import Binding, Producer, Frame
from edgeai_runner.stream_checkpoint import capture
from edgeai_runner.stream_journal import Journal

Api=runpy.run_path(str(ROOT/'scripts/test-postgres-backup.py'))['Api']
PROGRAM=runpy.run_path(str(ROOT/'scripts/test-recovery-kubernetes-retire.py'))['PROGRAM']


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context',required=True);parser.add_argument('--transport',choices=['native','compose'],default='native')
    parser.add_argument('--runner-image');parser.add_argument('--runner-source')
    parser.add_argument('--offloads',action='store_true')
    parser.add_argument('--minio-binary',type=Path,default=ROOT/'.tools/minio')
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/recovery-stream-workflows-test.json');args=parser.parse_args()
    token=uuid.uuid4().hex;operation=str(uuid.uuid4());namespace='edgeai-stream-recovery-'+token[:12]
    work=ROOT/'.tools'/('recovery-stream-workflows-'+token);work.mkdir(mode=0o700)
    pg=Postgres(args.transport,diagnostics=work/'postgres');kube=Kubernetes(args.context)
    owned={};apis=[];fixtures=None;namespace_uid=None;source='edgeai_backup_stream_'+token
    report={'status':'RUNNING','scope':'restored-stream-workflow-tests','sourceMode':'SYNTHETIC',
        'runtimeBoundary':'ACTUAL_CONTAINERS_WITH_EXPLICIT_DATABASE_BINDING_FIXTURES','cases':[],'activated':False}
    def passed(name):report['cases'].append(name);print('PASS: '+name,flush=True)
    def call(command,document=None):
        result=subprocess.run(kube.command+command,input=None if document is None else json.dumps(document).encode(),capture_output=True,timeout=30)
        assert result.returncode==0,'Owned Kubernetes operation failed; response suppressed'
        return json.loads(result.stdout) if result.stdout.strip() else None
    def create(document):return call(['create','-f','-','-o','json'],document)
    def remember(db):owned[db]=pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(db),'postgres')
    def drop(db):
        assert owned[db]==pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(db),'postgres')
        pg.sql('DROP DATABASE '+identifier(db),'postgres');del owned[db]
    def fingerprints(db):
        tables=json.loads(pg.sql("SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='edgeai'",db))
        return json.loads(pg.sql('SELECT jsonb_build_object('+','.join(literal(t)+
            ",(SELECT encode(sha256(convert_to(coalesce(string_agg(to_jsonb(t)::text,'' ORDER BY to_jsonb(t)::text),''),'UTF8')),'hex') FROM edgeai."+
            identifier(t)+' t)' for t in tables)+')',db))
    def insert(db,table,values):
        pg.sql('INSERT INTO edgeai.'+identifier(table)+' SELECT * FROM jsonb_populate_record(NULL::edgeai.'+
            identifier(table)+','+literal(json.dumps(values))+'::jsonb)',db)
    def options(target,**extra):
        db,receipt=target
        return SimpleNamespace(context=args.context,namespace=namespace,namespace_uid=namespace_uid,recovery_id=operation,
            database=db,restore_report=receipt,termination_report=work/'termination-report.json',run_id=run_id,
            broker_digest=fixtures.digest,mqtt_state_directory=fixtures.cfg.state_directory,mqtt_ca_file=fixtures.cfg.ca_file,
            mqtt_original_password_file=fixtures.cfg.admin_password_file,timeout=30,output=work/uuid.uuid4().hex,**extra)
    def apply(target,plan=None,**extra):
        values=options(target,**extra);values.output.mkdir(mode=0o700)
        return workflows.apply(pg,values,plan or workflows.prepare(pg,values))
    def refused(operation,kind=(Blocked,RuntimeError)):
        try:operation()
        except kind:return
        raise AssertionError('Unproven STREAM workflow mutation accepted')
    def cli(target,**extra):
        values=options(target,**extra);command=[sys.executable,'scripts/recovery_stream_workflows.py','--transport',args.transport]
        for key,value in vars(values).items():
            if isinstance(value,bool):
                if value:command+=['--'+key.replace('_','-')]
            else:command+=['--'+key.replace('_','-'),str(value)]
        fixtures.run(command);return json.loads((values.output/'workflows.json').read_text())
    code=1
    try:
        pin=json.loads((ROOT/'deploy/kubernetes/overlays/dev/release.json').read_text())
        assert bool(args.runner_image)==bool(args.runner_source)
        image=args.runner_image or 'ghcr.io/dsa04156/edgeai-runner@'+pin['runnerDigest']
        revision=args.runner_source or pin['sourceRevision']
        assert re.fullmatch(r'ghcr\.io/dsa04156/edgeai-runner@sha256:[0-9a-f]{64}',image) and re.fullmatch('[0-9a-f]{40}',revision)
        namespace_uid=create({'apiVersion':'v1','kind':'Namespace','metadata':{'name':namespace,
            'labels':{PART:'edgeai',MANAGER:'edgeai-bootstrap','edgeai.io/recovery-test':token}}})['metadata']['uid']
        authority=work/'authority';authority.mkdir(mode=0o700)
        fixtures=Fixtures(authority,args.minio_binary,'sha256:'+'b'*64);fixtures.start();fixtures.cfg.recovery_id=operation
        signing=work/'signing-key'
        with private_file(signing,'w') as out:out.write(secrets.token_hex(32))
        pg.sql('CREATE DATABASE '+identifier(source),'postgres');remember(source)
        env={name:'true' for name in ('EDGEAI_RUNTIME_ENABLED','EDGEAI_STREAM_ENABLED','EDGEAI_STREAM_BINDINGS_ENABLED','EDGEAI_STREAM_RUNS_ENABLED')}
        env.update(EDGEAI_RUNTIME_WORKER_ENABLED='false',EDGEAI_RUNTIME_NAMESPACE=namespace,EDGEAI_STREAM_RECONCILE_MS='3600000',
            EDGEAI_STREAM_BROKER_URL='tcp://127.0.0.1:1',EDGEAI_STREAM_BROKER_DIGEST=fixtures.digest,EDGEAI_STREAM_LEASE_SECONDS='120',
            EDGEAI_STREAM_PRINCIPAL_KEY_FILE=str(signing),EDGEAI_STREAM_DEVICE_KEY_FILE=str(signing),EDGEAI_RUNNER_KEY_FILE=str(signing),
            EDGEAI_STREAM_ADMIN_PASSWORD_FILE=str(signing),EDGEAI_STREAM_CA_FILE='',EDGEAI_KUBE_API_URL='http://127.0.0.1:1',
            EDGEAI_KUBE_CA_FILE='',EDGEAI_KUBE_TOKEN_FILE='',EDGEAI_STORAGE_URL='http://127.0.0.1:1',EDGEAI_STORAGE_RUNNER_URL='http://127.0.0.1:1')
        api=Api(source,work,extra_env=env);apis.append(api)
        spec=json.loads((ROOT/'contracts/profiles/service-stream.example.json').read_text())
        spec['stream']['inputs']={'sample':{'mediaType':'application/json','maxPayloadBytes':4096}};spec['stream']['outputs']={}
        service=api.request('POST','profiles/SERVICE',{'key':'group-stream','version':'1.0.0','spec':spec},201)
        plain=json.loads((ROOT/'contracts/profiles/service-execution.example.json').read_text())
        plain['inputs']={name:{'mediaType':'application/json','maxBytes':1048576,'required':True} for name in ('a','b')}
        child=api.request('POST','profiles/SERVICE',{'key':'group-child','version':'1.0.0','spec':plain},201)
        dp=api.request('POST','profiles/DEVICE',{'key':'group-device','version':'1.0.0','spec':{'protocol':'mqtt'}},201)
        device=api.request('POST','devices',{'key':'group-device','displayName':'Recovery group','profileVersionId':dp['id'],'sourceMode':'SYNTHETIC'},201)
        session=api.request('POST','devices/'+device['id']+'/sessions',{'bootId':str(uuid.uuid4())},201)
        workflow=api.request('POST','workflows',{'key':'group','displayName':'Group recovery'},201)
        version=api.request('POST','workflows/'+workflow['id']+'/versions',{'version':'1.0.0','tasks':[
            {'key':name,'serviceProfileVersionId':service['id'] if name!='child' else child['id'],'parameters':{}} for name in ('a','b','child')],
            'dependencies':[{'fromTask':name,'toTask':'child','fromPort':'result','toPort':name,'mode':'BATCH'} for name in ('a','b')]},201)
        run=api.request('POST','workflow-runs',{'workflowVersionId':version['id'],'execution':{'mode':'AUTO'},'parameters':{},
            'retry':{'maxAttempts':3,'backoffSeconds':1,'maxElapsedSeconds':3600,'retryOn':['WORKLOAD_FAILED']},
            'streamInputs':[{'deviceId':device['id'],'sourcePort':'samples','toTask':name,'toPort':'sample','maxPayloadBytes':4096} for name in ('a','b')]},201,str(uuid.uuid4()))
        run_id=run['id'];api.close()
        members=json.loads(pg.sql("SELECT jsonb_agg(jsonb_build_object('key',d.task_key,'task',t.id,'attempt',a.id,'runtime',r.id) ORDER BY d.task_key) "
            "FROM edgeai.task t JOIN edgeai.task_definition d ON d.id=t.definition_id JOIN edgeai.task_attempt a ON a.task_id=t.id "
            "JOIN edgeai.runtime_instance r ON r.attempt_id=a.id WHERE t.run_id="+literal(run_id)+'::uuid',source));assert len(members)==2
        child_id=pg.sql("SELECT t.id FROM edgeai.task t JOIN edgeai.task_definition d ON d.id=t.definition_id WHERE t.run_id="+literal(run_id)+"::uuid AND d.task_key='child'",source)
        pod_spec={'restartPolicy':'Never','terminationGracePeriodSeconds':30,'automountServiceAccountToken':False,
            'nodeSelector':{'kubernetes.io/arch':'amd64'},'securityContext':{'runAsNonRoot':True,'runAsUser':10001,'runAsGroup':10001},
            'containers':[{'name':'runner','image':image,'command':['python3','-B','-c',PROGRAM],
                'resources':{'requests':{'cpu':'10m','memory':'32Mi'},'limits':{'cpu':'100m','memory':'96Mi'}},
                'securityContext':{'allowPrivilegeEscalation':False,'capabilities':{'drop':['ALL']}}}]}
        for member in members:
            labels={PART:'edgeai',MANAGER:RUNTIME,'edgeai.io/run-id':run_id,'edgeai.io/task-id':member['task'],
                'edgeai.io/attempt-id':member['attempt'],'edgeai.io/epoch':'1'}
            member['job']=create({'apiVersion':'batch/v1','kind':'Job','metadata':{'namespace':namespace,'name':'edgeai-'+member['attempt'],'labels':labels},
                'spec':{'backoffLimit':0,'template':{'metadata':{'labels':labels},'spec':deepcopy(pod_spec)}}})['metadata']['uid']
        deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            pods=kube.items(namespace,'Pod')[0]
            if len(pods)==2 and all(p.get('status',{}).get('phase')=='Running' for p in pods) and all(
                    subprocess.run(kube.command+['-n',namespace,'exec',p['metadata']['name'],'--','python3','-c',
                    "from pathlib import Path;import os;os.kill(int(Path('/tmp/child-ready').read_text()),0)"],capture_output=True,timeout=15).returncode==0 for p in pods):break
            time.sleep(.3)
        else:raise AssertionError('Owned STREAM fixture children did not start')
        for member in members:
            pod=next(p for p in pods if p['metadata']['labels']['edgeai.io/attempt-id']==member['attempt'])
            node=kube.read('/api/v1/nodes/'+pod['spec']['nodeName']);member['pod']=pod['metadata']['uid']
            pg.sql("UPDATE edgeai.runtime_instance SET observed_state='RUNNING',job_uid="+literal(member['job'])+'::uuid,producer_pod_uid='+literal(member['pod'])+
                '::uuid,node_uid='+literal(node['metadata']['uid'])+'::uuid,node_name='+literal(pod['spec']['nodeName'])+
                ",expires_at=now()+interval '1 hour' WHERE id="+literal(member['runtime'])+'::uuid; '
                "UPDATE edgeai.task_attempt SET state='RUNNING' WHERE id="+literal(member['attempt'])+'::uuid',source)
        pg.sql("UPDATE edgeai.workflow_run SET state='RUNNING'; UPDATE edgeai.task SET state='RUNNING' WHERE id<>"+literal(child_id)+'::uuid',source)
        actor=Producer('DEVICE_SESSION',session['id'],session['epoch'],device['id']);bindings=[]
        for member in members:
            route=pg.sql('SELECT id FROM edgeai.data_route WHERE consumer_task_id='+literal(member['task'])+'::uuid',source)
            binding=Binding(route,1,actor);bindings.append(binding);gid=str(uuid.uuid4())
            pg.sql('INSERT INTO edgeai.route_generation(id,route_id,run_id,generation,source_device_id,consumer_task_id,producer_session_id,producer_epoch,'
                'consumer_attempt_id,consumer_epoch,broker_digest,policy_digest,request_digest,created_at,updated_at,lease_until) VALUES ('+
                ','.join(literal(v)+'::uuid' for v in (gid,route,run_id))+',1,'+','.join(literal(v)+'::uuid' for v in (device['id'],member['task'],actor.id))+',1,'+
                literal(member['attempt'])+'::uuid,1,'+','.join(literal(v) for v in (fixtures.digest,'sha256:'+'c'*64,'sha256:'+'d'*64))+
                ",now(),now(),now()+interval '120 seconds'); UPDATE edgeai.route_generation SET activated_at=now(),updated_at=now() WHERE id="+literal(gid)+'::uuid',source)
            with Journal(work/('checkpoint-'+member['key']),[binding],[],create=True,durability='EXTERNAL') as journal:
                journal.receive(Frame(binding,1,'DATA',b'7','application/json'))
                journal.commit(0,journal.pending(),b'7')
                journal.receive(Frame(binding,2,'END',b'',None))
                journal.commit(1,journal.pending(),b'7')
                snapshot=capture(journal,'a'*64);doc=snapshot.document();member['checkpoint']=str(uuid.uuid4())
                key='tasks/'+member['task']+'/attempts/'+member['attempt']+'/stream-checkpoint/'+snapshot.sha256
                version_id=fixtures.upload(key,snapshot.wire)
                insert(source,'stream_checkpoint',{'id':member['checkpoint'],'run_id':run_id,'task_id':member['task'],
                    'attempt_id':member['attempt'],'runtime_id':member['runtime'],'epoch':1,'producer_pod_uid':member['pod'],
                    'service_profile_version_id':service['id'],'serial':doc['serial'],'state_revision':doc['revision'],
                    'sha256':snapshot.sha256,'execution_sha256':'a'*64,'bytes':len(snapshot.wire),'generation_ids':'{'+gid+'}',
                    'summary_json':{'manifest':doc['manifest'],'revision':doc['revision'],
                        'routes':[{k:v for k,v in row.items() if k!='frames'} for row in doc['routes']],
                        'stateSha256':hashlib.sha256(b'7').hexdigest(),'stateBytes':1},
                    'bucket':fixtures.bucket,'object_key':key,'object_version':version_id,'created_at':pg.sql('SELECT now()::text',source)})
        running_backup=work/'running-stream-backup'
        if args.offloads:backup(pg,source,running_backup)
        task_ids=','.join(literal(m['task'])+'::uuid' for m in members)
        pg.sql("BEGIN; UPDATE edgeai.task_attempt SET created_at=created_at-interval '10 seconds' WHERE id="+literal(members[0]['attempt'])+'::uuid; '
            "UPDATE edgeai.task_attempt SET state='FAILED',updated_at=now(); UPDATE edgeai.task SET state='RETRY_WAIT' WHERE id IN ("+task_ids+
            "); UPDATE edgeai.runtime_instance SET desired_state='STOPPED',failure_reason=CASE WHEN id="+literal(members[0]['runtime'])+
            "::uuid THEN 'WORKLOAD_FAILED' ELSE 'STREAM_GROUP_RESTART' END; INSERT INTO edgeai.task_retry(task_id,failed_attempt_id,namespace,available_at,deadline) "
            'SELECT a.task_id,a.id,'+literal(namespace)+",a.updated_at+interval '1 second',(SELECT min(created_at)+interval '3600 seconds' FROM edgeai.task_attempt) FROM edgeai.task_attempt a; COMMIT",source)
        backup(pg,source,work/'backup');targets=[]
        for name in ('pending','expired','cancel','missing','final'):
            db='edgeai_restore_stream_'+name+'_'+token
            tool=Postgres(args.transport,diagnostics=work/('restore-'+name));restore(tool,work/'backup',db);remember(db)
            targets.append((db,tool.directory/'restore-report.json'))
        offload_fixture=None
        if args.offloads:
            from test_recovery_stream_offloads import seed
            offload_fixture=seed(pg,args.transport,work,running_backup,remember,drop,kube,create,namespace,pod_spec,members,run_id)
        fixtures.seal_storage()
        drop(source);report['sourceDatabaseRemoved']=True
        stopped={'formatVersion':1,'scope':'observed-kubernetes-producer-termination','status':'RUNNING','namespace':namespace,
            'namespaceUid':namespace_uid,'recoveryId':operation,'activated':False,'globalQuiescenceProven':False,'fenceRetained':False}
        Stop(kube,namespace,namespace_uid,operation,90).execute(stopped);durable_json(work/'termination-report.json',stopped)
        assert all(c['state']['terminated']['message']=='CHILD_REAPED' for p in kube.items(namespace,'Pod')[0] for c in p['status']['containerStatuses'])
        fixtures.fence_device(actor,members[0]['attempt'],bindings[0])
        passed('public-device-fanout-two-task-group-and-batch-child-restored-after-real-parent-child-and-broker-retirement')
        missing=targets[3][0]
        pg.sql("UPDATE edgeai.runtime_instance SET producer_pod_uid=NULL,node_uid=NULL,node_name=NULL,observed_state='SUBMITTED' WHERE id="+literal(members[0]['runtime'])+'::uuid',missing)
        for target in targets:
            values=options(target);values.output.mkdir(mode=0o700);producers.apply(pg,values,producers.prepare(pg,values))
        # A public Device-only STREAM Run has BATCH dependencies only. The BATCH recovery
        # command must still refuse it while stream generations/broker work is unresolved.
        cancellation_db=targets[2][0]
        pg.sql("UPDATE edgeai.task SET state='CANCELLING',cancellation_reason='RUN_CANCELLED' WHERE id IN ("+task_ids+
            "); UPDATE edgeai.task SET state='SKIPPED',cancellation_reason='UPSTREAM_CANCELLED' WHERE id="+literal(child_id)+
            "::uuid; UPDATE edgeai.workflow_run SET state='CANCELLING'",cancellation_db)
        before=fingerprints(cancellation_db);values=options(targets[2]);values.output.mkdir(mode=0o700)
        batch_result=batch_workflows.apply(pg,values,batch_workflows.prepare(pg,values))
        report['batchBoundaryObserved']={'databaseModified':batch_result['databaseModified'],
            'tasksCancelled':batch_result['tasksCancelled'],'unresolvedWorkflows':len(batch_result['unresolvedWorkflows'])}
        assert not batch_result['databaseModified'] and fingerprints(cancellation_db)==before
        assert len(batch_result['unresolvedWorkflows'])==2
        passed('batch-workflow-command-refuses-device-only-stream-cancellation-before-route-retirement')
        untouched=fingerprints(targets[0][0]);plan=workflows.prepare(pg,options(targets[0]))
        assert not plan['groups'] and plan['unresolvedGroups'][0]['reason']=='ORIGINAL_STREAM_GENERATIONS_NOT_RETIRED'
        assert not apply(targets[0])['databaseModified'] and fingerprints(targets[0][0])==untouched
        passed('open-restored-stream-generations-block-group-outcomes-despite-proven-physical-stop')
        for target in targets:
            values=options(target);values.output.mkdir(mode=0o700);routes.apply(pg,values,routes.prepare(pg,values))
        pending,expired,cancelling,_,finalizing=targets
        original=fingerprints(pending[0]);result=cli(pending)
        assert not result['databaseModified'] and len(result['groups'])==1 and len(result['groups'][0]['taskIds'])==2
        assert fingerprints(pending[0])==original
        passed('device-fanout-is-one-component-with-original-shared-retry-cutoff-and-peer-restart-reason-preserved')
        before=fingerprints(missing);result=cli(targets[3]);assert not result['databaseModified'] and fingerprints(missing)==before
        assert result['unresolvedGroups'][0]['reason']=='COMPLETE_COMPONENT_PRODUCERS_NOT_RETIRED'
        passed('one-unproven-member-keeps-the-entire-group-unresolved-with-no-partial-retry-change')
        for change in ("deadline=deadline+interval '10 seconds'","available_at=available_at+interval '1 second'"):
            pg.sql('UPDATE edgeai.task_retry SET '+change+' WHERE task_id='+literal(members[1]['task'])+'::uuid',pending[0])
            before=fingerprints(pending[0]);refused(lambda:apply(pending),RuntimeError);assert fingerprints(pending[0])==before
            pg.sql('UPDATE edgeai.task_retry SET '+change.replace('+','-')+' WHERE task_id='+literal(members[1]['task'])+'::uuid',pending[0])
        assert fingerprints(pending[0])==original
        passed('per-member-deadline-or-backoff-drift-rolls-back-group-reconciliation')
        final_db=finalizing[0]
        insert(final_db,'stream_task_completion',{'attempt_id':members[0]['attempt'],'checkpoint_id':members[0]['checkpoint'],
            'created_at':pg.sql('SELECT now()::text',final_db)})
        pg.sql('UPDATE edgeai.stream_task_completion SET granted_at=now()',final_db)
        before=fingerprints(final_db);result=cli(finalizing)
        assert not result['databaseModified'] and fingerprints(final_db)==before
        assert result['unresolvedGroups'][0]['reason']=='COMPONENT_RETRY_OR_FINALIZATION_HISTORY_REQUIRES_RECONCILIATION'
        passed('immutable-granted-finalization-keeps-entire-group-pending-and-preserves-terminal-checkpoint')
        offload=str(uuid.uuid4())
        pg.sql('INSERT INTO edgeai.task_offload(id,task_id,run_id,source_attempt_id,idempotency_key,request_digest,namespace,'
            'state,drain_deadline,start_timeout_seconds,remote_provider_key,remote_configuration_digest,remote_source_mode,created_at,updated_at) '
            'SELECT '+literal(offload)+'::uuid,task_id,run_id,attempt_id,gen_random_uuid(),'+literal('sha256:'+'1'*64)+
            ",namespace,'DRAINING',now()+interval '1 minute',60,'reference',"+literal('sha256:'+'2'*64)+
            ",'SYNTHETIC',now(),now() FROM edgeai.runtime_instance WHERE id="+literal(members[0]['runtime'])+'::uuid',pending[0])
        before=fingerprints(pending[0]);result=cli(pending)
        assert not result['databaseModified'] and fingerprints(pending[0])==before
        assert result['unresolvedGroups'][0]['reason']=='ACTIVE_STREAM_TRANSFER_REQUIRES_RECONCILIATION'
        values=options(pending);values.offloads=True
        batch_plan=batch_workflows.prepare(pg,values)
        assert not batch_plan['entries'] and batch_plan['unresolvedOffloads']==[
            {'operationId':offload,'reason':'STREAM_OFFLOAD_REQUIRES_SEPARATE_RECOVERY'}]
        assert fingerprints(pending[0])==before
        pg.sql('DELETE FROM edgeai.task_offload WHERE id='+literal(offload)+'::uuid',pending[0])
        assert fingerprints(pending[0])==original
        passed('active-transfer-preserves-entire-component-and-original-retry-queues')
        prepared=workflows.prepare(pg,options(pending))
        drift='UPDATE edgeai.task_retry SET available_at=available_at+interval \'1 second\' WHERE task_id='+literal(members[0]['task'])+'::uuid'
        undo=drift.replace('+interval','-interval')
        pg.sql(drift,pending[0]);before=fingerprints(pending[0])
        refused(lambda:apply(pending,prepared),Blocked);assert fingerprints(pending[0])==before
        pg.sql(undo,pending[0]);assert fingerprints(pending[0])==original
        passed('actual-database-change-after-plan-refuses-stale-group-recovery')
        call_pg=pg.call
        def raced(tool,arguments,*a,**kw):
            if '-f' in arguments:pg.sql(drift,pending[0])
            return call_pg(tool,arguments,*a,**kw)
        with patch.object(pg,'call',side_effect=raced):refused(lambda:apply(pending),RuntimeError)
        after=fingerprints(pending[0]);assert {k for k in after if after[k]!=original[k]}=={'task_retry'}
        pg.sql(undo,pending[0]);assert fingerprints(pending[0])==original
        passed('actual-write-after-final-observation-is-rejected-by-locked-sql-guard')
        values=options(pending);plan=workflows.prepare(pg,values)
        def marker_race(tool,arguments,*a,**kw):
            if '-f' in arguments:pg.sql('COMMENT ON DATABASE '+identifier(pending[0])+" IS 'changed-stream-recovery'",pending[0])
            return call_pg(tool,arguments,*a,**kw)
        with patch.object(pg,'call',side_effect=marker_race):refused(lambda:apply(pending,plan),RuntimeError)
        pg.sql('COMMENT ON DATABASE '+identifier(pending[0])+' IS '+literal(plan['marker']),pending[0])
        assert fingerprints(pending[0])==original
        passed('restored-database-identity-is-rechecked-inside-transaction')
        locker=None;locker_name='stream-recovery-lock-'+uuid.uuid4().hex
        try:
            with private_file(work/'lock-holder.log') as output:
                locker=subprocess.Popen(pg.prefix+[pg.binaries['psql']]+pg.connection+['--dbname',pending[0],'-X','-q','-v','ON_ERROR_STOP=1','-c',
                    'BEGIN; LOCK TABLE edgeai.stream_task_completion IN ROW EXCLUSIVE MODE; SELECT pg_sleep(60); COMMIT;'],
                    env={**pg.env,'PGAPPNAME':locker_name},stdout=output,stderr=output)
            deadline=time.monotonic()+5
            while pg.sql("SELECT EXISTS(SELECT FROM pg_locks l JOIN pg_stat_activity a USING(pid) WHERE a.application_name="+
                    literal(locker_name)+" AND l.relation='edgeai.stream_task_completion'::regclass AND l.mode='RowExclusiveLock' AND l.granted)",pending[0])!='t':
                assert time.monotonic()<deadline and locker.poll() is None
                time.sleep(.05)
            refused(lambda:apply(pending),RuntimeError);assert fingerprints(pending[0])==original
        finally:
            if locker is not None:
                pg.sql('SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE application_name='+literal(locker_name),pending[0])
                locker.wait(timeout=10)
        report['ownedLockProcessStopped']=locker.poll() is not None
        passed('actual-finalization-table-lock-times-out-without-partial-group-write')
        pg.sql("UPDATE edgeai.task_attempt SET created_at=created_at-interval '2 hours',updated_at=updated_at-interval '2 hours'; "
            "UPDATE edgeai.task_retry SET available_at=available_at-interval '2 hours',deadline=deadline-interval '2 hours'",expired[0])
        before=fingerprints(expired[0]);transaction=workflows.transaction_sql
        with patch.object(workflows,'transaction_sql',side_effect=lambda p:transaction(p).replace('SET CONSTRAINTS ALL IMMEDIATE;','SELECT 1/0; SET CONSTRAINTS ALL IMMEDIATE;')):
            refused(lambda:apply(expired),RuntimeError)
        assert fingerprints(expired[0])==before
        passed('actual-late-sql-error-rolls-back-both-expired-retries-and-downstream-task-changes')
        result=cli(expired);assert result['retriesExpired']==2 and result['tasksSkipped']==1 and result['runsReconciled']==1
        assert pg.sql('SELECT count(*) FROM edgeai.task_retry',expired[0])=='0'
        after=fingerprints(expired[0]);assert all(before[k]==after[k] for k in before if k not in ('task','task_retry','workflow_run'))
        assert not cli(expired)['databaseModified'] and fingerprints(expired[0])==after
        passed('group-expiry-fails-both-tasks-skips-the-batch-child-and-reconciles-run-once-with-no-new-attempt')
        pg.sql("UPDATE edgeai.task SET state='CANCELLING',cancellation_reason='RUN_CANCELLED' WHERE id IN ("+task_ids+
            "); UPDATE edgeai.task SET state='SKIPPED',cancellation_reason='UPSTREAM_CANCELLED' WHERE id="+literal(child_id)+
            "::uuid; UPDATE edgeai.workflow_run SET state='CANCELLING'",cancelling[0])
        before=fingerprints(cancelling[0]);call_pg=pg.call
        def lost(tool,arguments,*a,**kw):
            value=call_pg(tool,arguments,*a,**kw)
            if '-f' in arguments:raise OSError('Injected actual COMMIT response loss')
            return value
        with patch.object(pg,'call',side_effect=lost):refused(lambda:apply(cancelling),OSError)
        assert pg.sql("SELECT state FROM edgeai.workflow_run WHERE id="+literal(run_id)+'::uuid',cancelling[0])=='CANCELLED'
        after=fingerprints(cancelling[0]);assert all(before[k]==after[k] for k in before if k not in ('task','task_retry','workflow_run'))
        assert not cli(cancelling)['databaseModified'] and fingerprints(cancelling[0])==after
        passed('recorded-group-cancellation-preserves-reasons-and-failed-attempts-after-real-commit-reply-loss')
        # Current broker proof is required even when an old DB retirement report says CLOSED.
        import recovery_mqtt_fence as mqtt
        with mqtt.Connection(fixtures.cfg,fixtures.replacement) as connection:connection.call('enableClient',username=fixtures.device_name)
        refused(lambda:workflows.prepare(pg,options(pending)));assert fingerprints(pending[0])==original
        with mqtt.Connection(fixtures.cfg,fixtures.replacement) as connection:connection.call('disableClient',username=fixtures.device_name)
        passed('broker-reenable-invalidates-recorded-route-retirement-before-group-workflow-writes')
        def changed_authority(tool,arguments,*a,**kw):
            value=call_pg(tool,arguments,*a,**kw)
            if '-f' in arguments:
                with mqtt.Connection(fixtures.cfg,fixtures.replacement) as connection:connection.call('enableClient',username=fixtures.device_name)
            return value
        with patch.object(pg,'call',side_effect=changed_authority):refused(lambda:apply(pending),Blocked)
        assert fingerprints(pending[0])==original
        with mqtt.Connection(fixtures.cfg,fixtures.replacement) as connection:connection.call('disableClient',username=fixtures.device_name)
        assert not cli(pending)['databaseModified']
        passed('post-commit-authority-change-refuses-success-and-same-operation-can-be-reobserved')
        if args.offloads:
            from test_recovery_stream_offloads import check
            check(pg,offload_fixture,options,apply,cli,fingerprints,refused,passed,report)
        manifest=json.loads((fixtures.bundle/'manifest.json').read_text())
        assert len(manifest['versions'])==2
        for item in manifest['versions']:assert fixtures.client.digest('replica',item)==item['sha256']
        for db,_ in targets:assert pg.sql('SELECT count(*) FROM edgeai.stream_checkpoint',db)=='2'
        passed('nonempty-immutable-checkpoints-and-two-fixed-s3-versions-survive-all-group-outcomes')
        report.update(publicStreamRun=True,restoredDatabases=13 if args.offloads else 5,groupMembers=2,
            terminatedContainers=4 if args.offloads else 2,reapedChildren=4 if args.offloads else 2,
            retriesExpired=2,tasksCancelled=2,tasksSkipped=1,runsReconciled=2,pendingRetriesPreserved=2,
            preservedTables=40,checkpointsPreserved=2,storageVersionsPreserved=2,noNewAttempts=True,image=image,imageSourceRevision=revision,
            minioBinarySha256=hashlib.sha256(args.minio_binary.read_bytes()).hexdigest(),
            apiJarSha256=hashlib.sha256((ROOT/'backend/app/build/libs/edgeai-control-plane.jar').read_bytes()).hexdigest())
        code=0
    except Exception as error:
        report.update(failureType=type(error).__name__,failureFrames=[{'file':Path(f.filename).name,'line':f.lineno} for f in traceback.extract_tb(error.__traceback__)])
        with private_file(work/'failure.log','w') as out:traceback.print_exc(file=out)
        print('FAIL: STREAM workflow recovery; private diagnostics retained',flush=True)
    finally:
        try:
            for api in apis:api.close()
            report['ownedApisStopped']=all(a.process.poll() is not None for a in apis)
            if fixtures:
                report['ownedAuthorityProcessesStopped']=fixtures.close()
                report['ownedAuthorityClientsStopped']=all(p.client._thread is None for p in fixtures.peers)
            for db in list(owned):drop(db)
            report['ownedDatabasesRemoved']=not owned
            if namespace_uid:
                current=kube.read('/api/v1/namespaces/'+namespace)
                assert current['metadata']['uid']==namespace_uid and current['metadata']['labels']['edgeai.io/recovery-test']==token
                call(['delete','--raw','/api/v1/namespaces/'+namespace,'-f','-'],{'apiVersion':'v1','kind':'DeleteOptions','preconditions':{'uid':namespace_uid}})
                deadline=time.monotonic()+180
                while time.monotonic()<deadline:
                    current=call(['get','namespace',namespace,'--ignore-not-found','-o','json'])
                    if current is None:break
                    assert current['metadata']['uid']==namespace_uid and current['metadata']['labels']['edgeai.io/recovery-test']==token
                    for pod in kube.items(namespace,'Pod')[0]:
                        meta=pod['metadata'];finalizers=meta.get('finalizers',[])
                        if FINALIZER in finalizers and termination_proof(pod) is not None:
                            call(['-n',namespace,'patch','pod',meta['name'],'--type=json','--patch-file=/dev/stdin','-o','json'],[
                                {'op':'test','path':'/metadata/uid','value':meta['uid']},{'op':'test','path':'/metadata/resourceVersion','value':meta['resourceVersion']},
                                {'op':'replace','path':'/metadata/finalizers','value':[f for f in finalizers if f!=FINALIZER]}])
                    time.sleep(.3)
                else:raise AssertionError('Owned namespace cleanup not confirmed')
            report['ownedNamespaceRemoved']=True
        except Exception as error:report['cleanupFailureType']=type(error).__name__;code=1
        report['status']='PASS' if code==0 else 'FAIL';durable_json(args.report,report)
    return code


if __name__=='__main__':raise SystemExit(main())
