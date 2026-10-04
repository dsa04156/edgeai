"""Actual TLS Runner API journals, retained Pod termination, independent S3 and PostgreSQL restores.

The public API creates a BATCH DAG. An explicit DB/Job fixture binds a real sleeping
Pod; the test client submits synthetic output through authenticated Runner APIs.
This verifies recovery protocol, not model computation or global activation.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import sys
import time
import traceback
from types import SimpleNamespace
from unittest.mock import patch
import urllib.error
import urllib.request
import uuid

from postgres_backup import Blocked, Postgres, ROOT, backup, restore, literal, identifier, private_file
from recovery_kubernetes import Kubernetes, PART, MANAGER, RUNTIME
import recovery_kubernetes_results as results
import recovery_kubernetes_retire as retirement
import recovery_runtime_starts as starts
from recovery_remote_retire import durable_json
from recovery_remote_storage import Storage, header
from recovery_stop_kubernetes import Stop, FINALIZER, termination_proof
from storage_backup import Client, backup as storage_backup
from test_recovery_runtime_starts import Fixture, q
from test_runtime_start_api import admit

Api=runpy.run_path(str(ROOT/'scripts/test-postgres-backup.py'))['Api']
PROGRAM=runpy.run_path(str(ROOT/'scripts/test-recovery-kubernetes-retire.py'))['PROGRAM']


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context',required=True)
    parser.add_argument('--transport',choices=('native','compose'),default='native')
    parser.add_argument('--runner-image');parser.add_argument('--runner-source')
    parser.add_argument('--minio-binary',type=Path,default=ROOT/'.tools/minio')
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/recovery-kubernetes-results-test.json')
    args=parser.parse_args();token=uuid.uuid4().hex
    work=ROOT/'.tools'/('recovery-kubernetes-results-test-'+token);work.mkdir(mode=0o700)
    namespace='edgeai-result-test-'+token[:16];namespace_uid=None;operation=str(uuid.uuid4())
    kube=Kubernetes(args.context);pg=Postgres(args.transport,diagnostics=work/'postgres')
    fixture=Fixture('test_separate_credentials_identity_tls_pin_and_inspection')
    owned={};apis=[];old_env=os.environ.copy();targets=[]
    report={'status':'RUNNING','scope':'restored-kubernetes-result-commit-tests','sourceMode':'SYNTHETIC',
        'runtimeBoundary':'ACTUAL_POD_TOKENREVIEW_AND_RUNNER_API_WITH_EXPLICIT_DATABASE_BINDING',
        'cases':[],'activated':False,'ownedNamespaceRemoved':False,'ownedDatabasesRemoved':False}
    def passed(name):report['cases'].append(name);print('PASS: '+name,flush=True)
    def call(arguments,document=None):
        response=subprocess.run(kube.command+arguments,input=None if document is None else json.dumps(document).encode(),
            capture_output=True,timeout=30)
        assert response.returncode==0,'Owned Kubernetes operation failed; credentials suppressed'
        return json.loads(response.stdout) if response.stdout.strip() else None
    def create(document):return call(['create','-f','-','-o','json'],document)
    def remember(db):owned[db]=pg.sql('SELECT oid::text FROM pg_database WHERE datname='+literal(db),'postgres')
    def drop(db):
        assert pg.sql('SELECT oid::text FROM pg_database WHERE datname='+literal(db),'postgres')==owned[db]
        pg.sql('DROP DATABASE '+identifier(db),'postgres');del owned[db]
    def fingerprints(db):
        names=json.loads(pg.sql("SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='edgeai'",db))
        return json.loads(pg.sql('SELECT jsonb_build_object('+','.join(literal(n)+",(SELECT encode(sha256(convert_to(coalesce("
            "string_agg(to_jsonb(t)::text,E'\\n' ORDER BY to_jsonb(t)::text),''),'UTF8')),'hex') FROM edgeai."+identifier(n)+' t)' for n in names)+')',db))
    def options(index=0,**overrides):
        db,receipt=targets[index]
        value=SimpleNamespace(context=args.context,namespace=namespace,namespace_uid=namespace_uid,recovery_id=operation,
            database=db,restore_report=receipt,termination_report=work/'termination-report.json',transport=args.transport,
            pg_bin=None,timeout=120,output=work/('result-'+uuid.uuid4().hex),unclaimed_jobs=True,
            runtime_id=[runtime],runtime_start_backup=work/'storage-backup',runtime_start_bucket=fixture.bucket,
            runtime_start_certificate_sha256=fixture.pin)
        vars(value).update(overrides);return value
    def cli(a,expected=0):
        command=[sys.executable,'scripts/recovery_kubernetes_results.py']
        for k,v in vars(a).items():
            flag='--'+k.replace('_','-')
            if v is True:command.append(flag)
            elif isinstance(v,list):
                for item in v:command.extend([flag,str(item)])
            elif v is not None and v is not False:command.extend([flag,str(v)])
        response=subprocess.run(command,capture_output=True,timeout=240)
        with private_file(work/('command-'+uuid.uuid4().hex+'.log')) as log:log.write(response.stdout+response.stderr)
        assert response.returncode==expected,'Result CLI returned '+str(response.returncode)+'; private diagnostics retained'
        for secret in (fixture.token,fixture.operator,fixture.environment['EDGEAI_BACKUP_STORAGE_PASSWORD']):
            assert secret.encode() not in response.stdout+response.stderr
        return json.loads((a.output/('results.json' if expected==0 else 'failure.json')).read_text())
    def direct(a):
        a.output.mkdir(mode=0o700);return results.apply(pg,a,results.prepare(pg,a))
    def refuse(a,action):
        before=fingerprints(a.database)
        try:action()
        except (Blocked,RuntimeError,OSError,ValueError):pass
        else:raise AssertionError('Conflicting recovery was accepted')
        assert fingerprints(a.database)==before and not (a.output/'results.json').exists()
    def refuse_cli(a):
        before=fingerprints(a.database);rejected=cli(a,2)
        assert rejected['activated'] is False and fingerprints(a.database)==before
    code=1
    try:
        fixture.setUp();fixture.configure(work,args.minio_binary)
        origin=fixture.storage('origin');fixture.storage('replica');os.environ.update(fixture.environment)
        bundle=work/'storage-backup';bundle.mkdir(mode=0o700);client=Client(bundle,source=True)
        client.call(['mb','origin/'+fixture.bucket]);client.call(['version','enable','origin/'+fixture.bucket])
        pin=json.loads((ROOT/'deploy/kubernetes/overlays/dev/release.json').read_text())
        assert bool(args.runner_image)==bool(args.runner_source)
        image=args.runner_image or 'ghcr.io/dsa04156/edgeai-runner@'+pin['runnerDigest']
        revision=args.runner_source or pin['sourceRevision']
        assert re.fullmatch(r'ghcr\.io/dsa04156/edgeai-runner@sha256:[a-f0-9]{64}',image) and re.fullmatch('[a-f0-9]{40}',revision)
        report.update(image=image,imageSourceRevision=revision,apiJarSha256=hashlib.sha256((ROOT/'backend/app/build/libs/edgeai-control-plane.jar').read_bytes()).hexdigest())
        ns=create({'apiVersion':'v1','kind':'Namespace','metadata':{'name':namespace,
            'labels':{PART:'edgeai',MANAGER:'edgeai-bootstrap','edgeai.io/recovery-test':token}}})
        namespace_uid=ns['metadata']['uid']
        source='edgeai_backup_kresult_'+token;pg.sql('CREATE DATABASE '+identifier(source),'postgres');remember(source)
        api=Api(source,work);apis.append(api)
        spec=json.loads((ROOT/'contracts/profiles/service-execution.example.json').read_text())
        parent=api.request('POST','profiles/SERVICE',{'key':'result-root','version':'1.0.0','spec':spec},201)
        child_spec={**spec,'inputs':{'input':{'mediaType':'application/json','maxBytes':1048576,'required':True}}}
        child_profile=api.request('POST','profiles/SERVICE',{'key':'result-child','version':'1.0.0','spec':child_spec},201)
        workflow=api.request('POST','workflows',{'key':'result-recovery','displayName':'Result recovery'},201)
        v=api.request('POST','workflows/'+workflow['id']+'/versions',{'version':'1.0.0','tasks':[
            {'key':'root','serviceProfileVersionId':parent['id'],'parameters':{'features':[2],'weights':[3]}},
            {'key':'child','serviceProfileVersionId':child_profile['id'],'parameters':{}}],
            'dependencies':[{'fromTask':'root','toTask':'child','fromPort':'output','toPort':'input','mode':'BATCH'}]},201)
        run=api.request('POST','workflow-runs',{'workflowVersionId':v['id'],'execution':{'mode':'AUTO'},'parameters':{}},201,str(uuid.uuid4()))
        attempt=json.loads(pg.sql('SELECT to_jsonb(a) FROM edgeai.task_attempt a JOIN edgeai.task t ON t.id=a.task_id WHERE t.run_id='+q(run['id']),source))
        child=pg.sql('SELECT id::text FROM edgeai.task WHERE run_id='+q(run['id'])+' AND id<>'+q(attempt['task_id']),source)
        runtime=str(uuid.uuid4());job_name='edgeai-'+attempt['id']
        pg.sql('INSERT INTO edgeai.runtime_instance(id,attempt_id,task_id,run_id,epoch,namespace,job_name,claim_nonce,desired_state,observed_state,expires_at,created_at,updated_at) VALUES ('+
            ','.join(q(x) for x in (runtime,attempt['id'],attempt['task_id'],run['id']))+',1,'+literal(namespace)+','+literal(job_name)+','+q(str(uuid.uuid4()))+
            ",'RUNNING','PENDING',now()+interval '1 hour',now(),now()); UPDATE edgeai.task SET state='RUNNING' WHERE id="+q(attempt['task_id'])+
            "; UPDATE edgeai.task_attempt SET state='DISPATCHING' WHERE id="+q(attempt['id'])+
            "; UPDATE edgeai.workflow_run SET state='RUNNING' WHERE id="+q(run['id']),source)
        labels={PART:'edgeai',MANAGER:RUNTIME,'edgeai.io/run-id':run['id'],'edgeai.io/task-id':attempt['task_id'],
            'edgeai.io/attempt-id':attempt['id'],'edgeai.io/epoch':'1'}
        job=create({'apiVersion':'batch/v1','kind':'Job','metadata':{'namespace':namespace,'name':job_name,'labels':labels},
            'spec':{'backoffLimit':0,'parallelism':1,'completions':1,'template':{'metadata':{'labels':labels},'spec':{
                'restartPolicy':'Never','terminationGracePeriodSeconds':30,'automountServiceAccountToken':False,
                'nodeSelector':{'kubernetes.io/arch':'amd64'},'securityContext':{'runAsNonRoot':True,'runAsUser':10001,'runAsGroup':10001},
                'containers':[{'name':'runner','image':image,'command':['python3','-B','-c',PROGRAM],
                    'resources':{'requests':{'cpu':'10m','memory':'32Mi'},'limits':{'cpu':'100m','memory':'96Mi'}},
                    'securityContext':{'allowPrivilegeEscalation':False,'capabilities':{'drop':['ALL']}}}]}}}})
        deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            pods=kube.items(namespace,'Pod')[0]
            if len(pods)==1 and pods[0].get('status',{}).get('phase')=='Running':
                response=subprocess.run(kube.command+['-n',namespace,'exec',pods[0]['metadata']['name'],'-c','runner','--','python3','-c',
                    "from pathlib import Path;import os;os.kill(int(Path('/tmp/child-ready').read_text()),0)"],capture_output=True,timeout=15)
                if response.returncode==0:break
            time.sleep(.3)
        else:raise AssertionError('Owned real producer did not become ready')
        pod=pods[0]
        pg.sql("UPDATE edgeai.runtime_instance SET observed_state='SUBMITTED',job_uid="+q(job['metadata']['uid'])+' WHERE id='+q(runtime),source)
        api.close();backup(pg,source,work/'before-claim')
        context=json.loads(pg.sql(starts.QUERY,source))[0]
        observed={}
        def commit(request,authority,store):
            backup(pg,source,work/'after-claim')
            raw=b'{"value":6,"sourceMode":"SYNTHETIC"}'
            content={'port':'output','bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),'mediaType':'application/json'}
            grant=request('uploads',{'outputs':[content]},200)['outputs'][0]
            with urllib.request.urlopen(urllib.request.Request(grant['url'],data=raw,headers=grant['headers'],method='PUT'),context=fixture.tls,timeout=15) as response:
                assert response.status==200;version=response.headers['x-amz-version-id']
            body={'outputs':[{**content,'versionId':version}]}
            first=request('commit',body,201);assert request('commit',body,200)==first
            path='/'+fixture.bucket+'/authority/runtime-result/'+runtime+'.json'
            status,headers,value=store.request('GET',path);assert status==200
            journal=json.loads(value);assert journal['resultId']==first['resultId']
            assert request('commit',body,200)==first
            status,again,repeated=store.request('GET',path)
            assert status==200 and repeated==value and header(again,'x-amz-version-id')==header(headers,'x-amz-version-id')
            observed.update(journal=journal,start=authority,raw=value,objectVersion=header(headers,'x-amz-version-id'))
            backup(pg,source,work/'after-commit')
        admit(fixture,pg,source,context,pod,kube,create,namespace,on_admitted=commit)
        storage_backup(client,[fixture.bucket],120)
        origin.terminate();origin.wait(15);shutil.rmtree(fixture.work/'origin-data');drop(source)
        report['sourceDatabaseRemoved']=True;report['sourceStorageRemoved']=True
        for snapshot in ('before-claim','before-claim','after-claim','after-commit','before-claim'):
            db='edgeai_restore_kresult_'+uuid.uuid4().hex
            restoring=Postgres(args.transport,diagnostics=work/('restore-'+uuid.uuid4().hex));restore(restoring,work/snapshot,db);remember(db)
            targets.append((db,restoring.directory/'restore-report.json'))
        stopped={'formatVersion':1,'scope':'observed-kubernetes-producer-termination','status':'RUNNING','namespace':namespace,
            'namespaceUid':namespace_uid,'recoveryId':operation,'activated':False,'globalQuiescenceProven':False,'fenceRetained':False}
        Stop(kube,namespace,namespace_uid,operation,90).execute(stopped);durable_json(work/'termination-report.json',stopped)
        proof=stopped['terminatedPods'][0]
        assert proof['kind']=='ALL_CONTAINERS_TERMINATED'
        assert kube.items(namespace,'Pod')[0][0]['status']['containerStatuses'][0]['state']['terminated']['message']=='CHILD_REAPED'
        for index in range(len(targets)):
            a=options(index);a.output.mkdir(mode=0o700);retirement.apply(pg,a,retirement.prepare(pg,a))
        passed('actual TLS claim/TokenReview, signed upload and commit journals; distinct backup, sources removed, five DB restores and retained process termination')
        store=Storage(SimpleNamespace(certificate_sha256=fixture.pin,timeout=120))
        authority=observed['journal'];start=observed['start'];context=json.loads(pg.sql(results.CONTEXT_QUERY,targets[0][0]))[0]
        assert results.validate(authority,start,context,proof,fixture.bucket)
        variants=[]
        for field in ('resultId','runtimeId','runId','taskId','attemptId','jobUid','podUid','nodeUid'):
            value=copy.deepcopy(authority);value[field]='not-a-uuid' if field=='resultId' else str(uuid.uuid4());variants.append(value)
        for field,value in [('apiVersion','wrong'),('epoch',True),('namespace','other'),('jobName','other'),('nodeName','other'),
            ('startKey','wrong'),('manifestDigest','sha256:'+'0'*64),('committedAt','2000-01-01T00:00:00Z'),('outputs',[])]:
            item=copy.deepcopy(authority);item[field]=value;variants.append(item)
        for field,value in [('versionId','null'),('bytes',True),('sha256','0'*64),('bucket','other'),('objectKey','outside'),('mediaType','text/plain')]:
            item=copy.deepcopy(authority);item['outputs'][0][field]=value;variants.append(item)
        for item in variants:
            try:results.validate(item,start,context,proof,fixture.bucket)
            except (Blocked,ValueError):pass
            else:raise AssertionError('Altered Result authority accepted')
        report['rejectedAuthorityVariants']=len(variants)
        passed('original source digest matches; 23 identity, time, manifest and output authority mutations rejected')
        a=options();db=a.database;pristine=fingerprints(db)
        # Restore each explicit state mutation and verify every original row hash.
        for table,column,value,row_id,old in [('task','state','CANCELLING',attempt['task_id'],'RUNNING'),
            ('task_attempt','state','FAILED',attempt['id'],'DISPATCHING'),('workflow_run','state','CANCELLING',run['id'],'RUNNING')]:
            pg.sql('UPDATE edgeai.'+table+' SET '+column+'='+literal(value)+' WHERE id='+q(row_id),db)
            refuse(options(),lambda:results.prepare(pg,options()))
            pg.sql('UPDATE edgeai.'+table+' SET '+column+'='+literal(old)+' WHERE id='+q(row_id),db)
        assert fingerprints(db)==pristine
        passed('late cancellation, failed Attempt and cancelling Run cannot be overwritten')
        start_path='/'+fixture.bucket+'/authority/runtime-start/'+runtime+'.json'
        status,headers,_=store.request('DELETE',start_path);assert status==204
        marker=header(headers,'x-amz-version-id')
        try:refuse_cli(options())
        finally:assert store.request('DELETE',start_path,{'versionId':marker})[0]==204
        passed('committed Result without its readable original start authority is insufficient')
        refuse_cli(options(runtime_start_certificate_sha256='0'*64))
        passed('backup TLS certificate mismatch blocks the full CLI before writes')
        # A new version with identical bytes must never replace original authority.
        path='/'+fixture.bucket+'/authority/runtime-result/'+runtime+'.json'
        status,headers,_=store.request('PUT',path,body=observed['raw'],extra={'content-type':results.MEDIA_TYPE});assert status==200
        replacement=header(headers,'x-amz-version-id')
        try:refuse_cli(options())
        finally:assert store.request('DELETE',path,{'versionId':replacement})[0]==204
        passed('same-byte latest-version replacement blocks full CLI without DB writes')
        # Captured duplicate JSON is a different invalid authority, even with a matching byte hash.
        manifest_path=bundle/'manifest.json';original_manifest=manifest_path.read_bytes();manifest=json.loads(original_manifest)
        bad=observed['raw'][:-1]+b',"apiVersion":"edgeai.runtime.result/v1"}'
        status,headers,_=store.request('PUT',path,body=bad,extra={'content-type':results.MEDIA_TYPE});assert status==200
        bad_version=header(headers,'x-amz-version-id')
        for item in manifest['versions']:
            if item['key']=='authority/runtime-result/'+runtime+'.json':item.update(versionId=bad_version,bytes=len(bad),sha256=hashlib.sha256(bad).hexdigest())
        manifest_path.write_text(json.dumps(manifest))
        try:refuse_cli(options())
        finally:
            manifest_path.write_bytes(original_manifest);assert store.request('DELETE',path,{'versionId':bad_version})[0]==204
        passed('duplicate-key journal rejected through pinned TLS storage and CLI')
        a=options();a.output.mkdir(mode=0o700);plan=results.prepare(pg,a)
        pg.sql("UPDATE edgeai.task SET updated_at=updated_at+interval '1 second' WHERE id="+q(child),db)
        refuse(a,lambda:results.apply(pg,a,plan))
        pg.sql("UPDATE edgeai.task SET updated_at=updated_at-interval '1 second' WHERE id="+q(child),db)
        assert fingerprints(db)==pristine
        passed('whole-row snapshot guard catches a child changed after verification')
        a=options();a.output.mkdir(mode=0o700);plan=results.prepare(pg,a);original_prepare=results.prepare
        raced=[]
        def race_after_snapshot(pg_arg,args_arg):
            value=original_prepare(pg_arg,args_arg)
            if not raced:
                pg.sql("UPDATE edgeai.task SET updated_at=updated_at+interval '1 second' WHERE id="+q(child),db)
                raced.append(fingerprints(db))
            return value
        try:
            with patch.object(results,'prepare',race_after_snapshot):
                try:results.apply(pg,a,plan)
                except RuntimeError:pass
                else:raise AssertionError('Database race after final prepare was accepted')
            assert raced and fingerprints(db)==raced[0] and not (a.output/'results.json').exists()
        finally:pg.sql("UPDATE edgeai.task SET updated_at=updated_at-interval '1 second' WHERE id="+q(child),db)
        assert fingerprints(db)==pristine
        passed('actual concurrent row change after final observation is rejected by the locked SQL guard')
        a=options();a.output.mkdir(mode=0o700);plan=results.prepare(pg,a)
        locker=None;locker_name='kresult-lock-'+uuid.uuid4().hex
        try:
            with private_file(work/'lock-holder.log') as log:
                locker=subprocess.Popen(pg.prefix+[pg.binaries['psql']]+pg.connection+['--dbname',db,'-X','-q','-v','ON_ERROR_STOP=1','-c',
                    'BEGIN; LOCK TABLE edgeai.task_result IN ROW EXCLUSIVE MODE; SELECT pg_sleep(60); COMMIT;'],
                    env={**pg.env,'PGAPPNAME':locker_name},stdout=log,stderr=log)
            deadline=time.monotonic()+5
            while pg.sql("SELECT count(*) FROM pg_stat_activity a JOIN pg_locks l ON l.pid=a.pid WHERE a.application_name="+
                    literal(locker_name)+" AND l.relation='edgeai.task_result'::regclass AND l.granted",db)!='1':
                assert time.monotonic()<deadline;time.sleep(.05)
            refuse(a,lambda:results.apply(pg,a,plan))
        finally:
            pg.sql('SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='+literal(locker_name),db)
            if locker is not None:locker.wait(10)
        assert fingerprints(db)==pristine
        passed('real competing table writer reaches lock timeout without partial recovery changes')
        pg.sql("CREATE FUNCTION edgeai.owned_result_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.state='READY' THEN RAISE EXCEPTION 'owned late child fault'; END IF; RETURN NEW; END $$; CREATE TRIGGER owned_result_fault BEFORE UPDATE ON edgeai.task FOR EACH ROW EXECUTE FUNCTION edgeai.owned_result_fault()",db)
        a=options();a.output.mkdir(mode=0o700);plan=results.prepare(pg,a);refuse(a,lambda:results.apply(pg,a,plan))
        pg.sql('DROP TRIGGER owned_result_fault ON edgeai.task; DROP FUNCTION edgeai.owned_result_fault()',db)
        assert fingerprints(db)==pristine
        passed('actual late child failure rolls back binding, Result, artifacts, outbox and Task changes together')
        before_runtime=json.loads(pg.sql('SELECT to_jsonb(r) FROM edgeai.runtime_instance r WHERE id='+q(runtime),db))
        restored=cli(options())
        assert (restored['resultsCreated'],restored['bindingsRestored'],restored['childrenReadied'],restored['publicationsCompleted'])==(1,1,1,1)
        final_runtime=json.loads(pg.sql('SELECT to_jsonb(r) FROM edgeai.runtime_instance r WHERE id='+q(runtime),db))
        for column,field in [('producer_pod_uid','podUid'),('node_uid','nodeUid'),('node_name','nodeName')]:before_runtime[column]=authority[field]
        assert final_runtime==before_runtime
        result=json.loads(pg.sql('SELECT to_jsonb(r) FROM edgeai.task_result r',db))
        assert result['id']==authority['resultId'] and starts.instant_ns(result['created_at'])==starts.instant_ns(authority['committedAt'])
        assert result['manifest_digest']==authority['manifestDigest'] and result['committed']
        assert pg.sql('SELECT state FROM edgeai.task WHERE id='+q(child),db)=='READY'
        assert pg.sql('SELECT state FROM edgeai.task_attempt WHERE task_id='+q(child),db)=='QUEUED'
        assert pg.sql('SELECT count(*) FROM edgeai.runtime_instance',db)=='1'
        allowed={'runtime_instance','task_result','result_artifact','task','task_attempt','runtime_result_publication'}
        after=fingerprints(db);assert all(after[k]==v for k,v in pristine.items() if k not in allowed)
        report['preservedOtherTables']=len(after)-len(allowed)
        report['restoredResults']=restored['resultsCreated'];report['childrenReadied']=restored['childrenReadied']
        passed('original Result ID/time/fixed output restored once; missing claim history restored while termination/nonce stay intact; child queued without runtime')
        replay=cli(options());assert not replay['databaseModified'] and fingerprints(db)==after
        passed('identical CLI replay writes nothing, preserves timestamps and creates no duplicate Attempt or Result')
        claimed=cli(options(2));assert claimed['resultsCreated']==1 and claimed['bindingsRestored']==0 and claimed['childrenReadied']==1
        passed('claimed-before-commit snapshot recovers original result without rewriting producer binding')
        existing_before=fingerprints(targets[3][0]);existing=cli(options(3));existing_after=fingerprints(targets[3][0])
        assert existing['resultsCreated']==0 and existing['bindingsRestored']==0 and existing['childrenReadied']==0 and existing['publicationsCompleted']==1
        assert all(existing_after[k]==v for k,v in existing_before.items() if k!='runtime_result_publication')
        assert not cli(options(3))['databaseModified']
        passed('already committed snapshot retains source child/history and acknowledges only verified pending publication once')
        terminal_db=targets[3][0]
        pg.sql("UPDATE edgeai.task SET state='FAILED' WHERE id="+q(child)+"; UPDATE edgeai.task_attempt SET state='FAILED' WHERE task_id="+
            q(child)+"; UPDATE edgeai.workflow_run SET state='FAILED' WHERE id="+q(run['id']),terminal_db)
        terminal_before=fingerprints(terminal_db)
        assert not cli(options(3))['databaseModified'] and fingerprints(terminal_db)==terminal_before
        passed('replay of original successful parent preserves later child failure and terminal Run history')
        a=options(1);a.output.mkdir(mode=0o700);plan=results.prepare(pg,a);original_call=pg.call
        def lost_reply(tool,arguments,*positional,**kwargs):
            value=original_call(tool,arguments,*positional,**kwargs)
            if tool=='psql' and kwargs.get('source') is not None:raise OSError('Owned COMMIT reply loss')
            return value
        with patch.object(pg,'call',lost_reply):
            try:results.apply(pg,a,plan)
            except OSError:pass
            else:raise AssertionError('COMMIT reply loss was not exercised')
        assert pg.sql('SELECT id::text FROM edgeai.task_result',a.database)==authority['resultId'] and not (a.output/'results.json').exists()
        before=fingerprints(a.database);assert not cli(options(1))['databaseModified'] and fingerprints(a.database)==before
        passed('lost reply after actual COMMIT retains original history; fresh replay verifies without duplicate writes')
        inspection=Api(db,work,inspection=True);apis.append(inspection)
        assert inspection.request('GET','workflow-runs/'+run['id'])['run']['id']==run['id']
        visible=inspection.request('GET','tasks/'+attempt['task_id']+'/results')
        assert visible['items'][0]['id']==authority['resultId'] and len(visible['items'][0]['artifacts'])==1
        try:inspection.request('POST','workflows',{'key':'forbidden','displayName':'forbidden'},201)
        except urllib.error.HTTPError as error:assert error.code==403
        else:raise AssertionError('Restored DB admitted management writes')
        inspection.close();passed('restored inspection API stays readable and refuses new workflow writes')
        # Storage and DB cannot share a transaction: failure after COMMIT must not claim rollback.
        a=options(4);a.output.mkdir(mode=0o700);plan=results.prepare(pg,a);new_version=[]
        def change_after_commit(tool,arguments,*positional,**kwargs):
            value=original_call(tool,arguments,*positional,**kwargs)
            if tool=='psql' and kwargs.get('source') is not None:
                status,headers,_=store.request('PUT',path,body=observed['raw'],extra={'content-type':results.MEDIA_TYPE});assert status==200
                new_version.append(header(headers,'x-amz-version-id'))
            return value
        try:
            with patch.object(pg,'call',change_after_commit):
                try:results.apply(pg,a,plan)
                except Blocked:pass
                else:raise AssertionError('Post-commit authority replacement accepted')
            assert pg.sql('SELECT id::text FROM edgeai.task_result',a.database)==authority['resultId'] and not (a.output/'results.json').exists()
        finally:
            for version in new_version:assert store.request('DELETE',path,{'versionId':version})[0]==204
        assert not cli(options(4))['databaseModified']
        passed('post-commit storage change blocks success report while keeping quarantine and committed original history')
        output=authority['outputs'][0];artifact_path='/'+fixture.bucket+'/'+output['objectKey']
        assert store.request('DELETE',artifact_path,{'versionId':output['versionId']})[0]==204
        refuse_cli(options())
        passed('missing fixed output version blocks even immutable-result replay; no latest-object fallback')
        report.update(originalResultIdPreserved=True,originalCommitTimePreserved=True,restoredDatabases=len(targets),
            actualStartApiVerified=fixture.api_claim_verified,actualResultApiVerified=True,newRuntimeCreated=False)
        code=0
    except Exception as error:
        report['failureType']=type(error).__name__
        with private_file(work/'failure.log','w') as log:traceback.print_exc(file=log)
    finally:
        try:
            for api in apis:api.close()
            fixture.doCleanups()
            report['ownedApisStopped']=all(api.process is None or api.process.poll() is not None for api in apis) and getattr(fixture,'api_claim_source_stopped',True)
            report['ownedStorageStopped']=all(p.poll() is not None for p in getattr(fixture,'processes',[]))
            for db in list(owned):drop(db)
            report['ownedDatabasesRemoved']=not owned
            if namespace_uid:
                ns=kube.read('/api/v1/namespaces/'+namespace)
                assert ns['metadata']['uid']==namespace_uid and ns['metadata']['labels']['edgeai.io/recovery-test']==token
                call(['delete','--raw','/api/v1/namespaces/'+namespace,'-f','-'],{'apiVersion':'v1','kind':'DeleteOptions','preconditions':{'uid':namespace_uid}})
                deadline=time.monotonic()+180
                while time.monotonic()<deadline:
                    ns=call(['get','namespace',namespace,'--ignore-not-found','-o','json'])
                    if ns is None:break
                    assert ns['metadata']['uid']==namespace_uid
                    for pod in kube.items(namespace,'Pod')[0]:
                        meta=pod['metadata'];finalizers=meta.get('finalizers',[])
                        if FINALIZER in finalizers and termination_proof(pod) is not None:
                            call(['-n',namespace,'patch','pod',meta['name'],'--type=json','--patch-file=/dev/stdin','-o','json'],[
                                {'op':'test','path':'/metadata/uid','value':meta['uid']},
                                {'op':'test','path':'/metadata/resourceVersion','value':meta['resourceVersion']},
                                {'op':'replace','path':'/metadata/finalizers','value':[f for f in finalizers if f!=FINALIZER]}])
                    time.sleep(.3)
                else:raise AssertionError('Owned namespace cleanup not confirmed')
            report['ownedNamespaceRemoved']=True
        except Exception as error:report['cleanupFailureType']=type(error).__name__;code=1
        os.environ.clear();os.environ.update(old_env)
        report['status']='PASS' if code==0 else 'FAIL';args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(json.dumps(report,indent=2)+'\n')
        print(report['status']+': '+str(len(report['cases']))+' Kubernetes Result recovery cases; private diagnostics '+str(work))
    return code


if __name__=='__main__':raise SystemExit(main())
