"""Restore a mixed Kubernetes/VD STREAM group using original API Result journals.

Public Device -> NODE -> VD, with both BATCH outputs required by one child.
Explicit routing/Device END and scheduler fixtures; real TLS APIs, Pod-bound
TokenReview, S3 replication/removal, broker fence and retained Pod termination.
"""
import argparse
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
import uuid

from postgres_backup import Blocked,Postgres,ROOT,backup,restore,literal,identifier,private_file
from recovery_kubernetes import Kubernetes,PART,MANAGER
import recovery_kubernetes_results as results
import recovery_kubernetes_retire as retirement
import recovery_runtime_starts as starts
import recovery_stream_retire as broker
from recovery_remote_retire import durable_json
from recovery_stop_kubernetes import Stop,FINALIZER,termination_proof
from storage_backup import Client,backup as storage_backup
from test_recovery_runtime_starts import Fixture,q
from test_runtime_start_api import admit
from test_stream_group_result_fixture import GroupFixture,commit_result
from test_stream_finalizer_fixture import job
from test_vd_supervisor_fixture import supervisor,allocate

Api=runpy.run_path(str(ROOT/'scripts/test-postgres-backup.py'))['Api']
PROGRAM=runpy.run_path(str(ROOT/'scripts/test-recovery-kubernetes-retire.py'))['PROGRAM']


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context',required=True)
    parser.add_argument('--transport',choices=('native','compose'),default='native')
    parser.add_argument('--runner-image');parser.add_argument('--runner-source')
    parser.add_argument('--minio-binary',type=Path,default=ROOT/'.tools/minio')
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/recovery-stream-group-results-test.json')
    args=parser.parse_args();token=uuid.uuid4().hex
    work=ROOT/'.tools'/('recovery-stream-group-results-test-'+token);work.mkdir(mode=0o700)
    namespace='edgeai-group-result-'+token[:12];namespace_uid=None;operation=str(uuid.uuid4())
    kube=Kubernetes(args.context);pg=Postgres(args.transport,diagnostics=work/'postgres')
    fixture=Fixture('test_separate_credentials_identity_tls_pin_and_inspection')
    owned={};apis=[];old_env=os.environ.copy();targets=[];stream=None;members={}
    report={'status':'RUNNING','scope':'mixed-stream-group-result-recovery','sourceMode':'SYNTHETIC',
        'runtimeBoundary':'ACTUAL_POD_TOKENREVIEW_AND_RUNNER_API_WITH_EXPLICIT_DATABASE_BINDING',
        'cases':[],'activated':False,'ownedNamespaceRemoved':False,'ownedDatabasesRemoved':False}
    def passed(name):report['cases'].append(name);print('PASS: '+name,flush=True)
    def call(arguments,document=None):
        response=subprocess.run(kube.command+arguments,input=None if document is None else json.dumps(document).encode(),capture_output=True,timeout=30)
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
    def options(index=0,key='root',**overrides):
        db,receipt=targets[index]
        value=SimpleNamespace(context=args.context,namespace=namespace,namespace_uid=namespace_uid,recovery_id=operation,
            database=db,restore_report=receipt,termination_report=work/'termination-report.json',transport=args.transport,
            pg_bin=None,timeout=120,output=work/('result-'+uuid.uuid4().hex),unclaimed_jobs=True,
            vd_tasks=key=='peer',runtime_id=[members[key]['runtime']],runtime_start_backup=work/'storage-backup',
            runtime_start_bucket=fixture.bucket,runtime_start_certificate_sha256=fixture.pin,**stream.options())
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
    def refuse_cli(a):
        before=fingerprints(a.database);reply=cli(a,2)
        assert reply['activated'] is False and fingerprints(a.database)==before
    def child_state(db,ready):
        assert pg.sql('SELECT state FROM edgeai.task WHERE id='+q(child),db)==('READY' if ready else 'WAITING')
        assert pg.sql('SELECT count(*) FROM edgeai.task_attempt WHERE task_id='+q(child),db)==('1' if ready else '0')
        if ready:assert pg.sql('SELECT state FROM edgeai.task_attempt WHERE task_id='+q(child),db)=='QUEUED'
        assert pg.sql('SELECT count(*) FROM edgeai.runtime_instance',db)=='2'
    def originals(db):
        for key,m in members.items():
            row=json.loads(pg.sql('SELECT to_jsonb(r) FROM edgeai.task_result r WHERE task_id='+q(m['attempt']['task_id']),db))
            assert row['id']==m['result']['resultId'] and row['committed']
            assert starts.instant_ns(row['created_at'])==starts.instant_ns(m['result']['committedAt'])
            assert row['producer_kind']==('VD' if key=='peer' else 'KUBERNETES')
    code=1
    try:
        fixture.setUp();fixture.configure(work,args.minio_binary)
        origin=fixture.storage('origin');fixture.storage('replica');os.environ.update(fixture.environment)
        bundle=work/'storage-backup';bundle.mkdir(mode=0o700);client=Client(bundle,source=True)
        client.call(['mb','origin/'+fixture.bucket]);client.call(['version','enable','origin/'+fixture.bucket])
        pin=json.loads((ROOT/'deploy/kubernetes/overlays/dev/release.json').read_text())
        assert bool(args.runner_image)==bool(args.runner_source)
        image=args.runner_image or 'ghcr.io/dsa04156/edgeai-runner@'+pin['runnerDigest'];revision=args.runner_source or pin['sourceRevision']
        assert re.fullmatch(r'ghcr\.io/dsa04156/edgeai-runner@sha256:[a-f0-9]{64}',image) and re.fullmatch('[a-f0-9]{40}',revision)
        report.update(image=image,imageSourceRevision=revision,apiJarSha256=hashlib.sha256((ROOT/'backend/app/build/libs/edgeai-control-plane.jar').read_bytes()).hexdigest())
        ns=create({'apiVersion':'v1','kind':'Namespace','metadata':{'name':namespace,
            'labels':{PART:'edgeai',MANAGER:'edgeai-bootstrap','edgeai.io/recovery-test':token}}});namespace_uid=ns['metadata']['uid']
        source='edgeai_backup_group_'+token;pg.sql('CREATE DATABASE '+identifier(source),'postgres');remember(source)
        stream=GroupFixture(work,args.minio_binary,namespace,operation);stream.env['EDGEAI_VD_ENABLED']='true'
        api=Api(source,work,extra_env=stream.env);apis.append(api)
        version,fields=stream.define(api)
        vd,vr,vd_pod=supervisor(api,pg,source,namespace,stream.profiles['peer']['id'],create,kube,image,PROGRAM)
        fields['taskExecutions']['peer']={'mode':'VD','vdId':vd['id']}
        run=api.request('POST','workflow-runs',{'workflowVersionId':version['id'],'execution':{'mode':'AUTO'},'parameters':{},**fields},201,str(uuid.uuid4()))
        tasks=json.loads(pg.sql('SELECT jsonb_object_agg(d.task_key,t.id) FROM edgeai.task t JOIN edgeai.task_definition d ON d.id=t.definition_id WHERE t.run_id='+q(run['id']),source));child=tasks['child']
        for key in ('root','peer'):
            a=json.loads(pg.sql('SELECT to_jsonb(a) FROM edgeai.task_attempt a WHERE task_id='+q(tasks[key]),source))
            runtime=pg.sql('SELECT id::text FROM edgeai.runtime_instance WHERE attempt_id='+q(a['id']),source)
            pg.sql("UPDATE edgeai.runtime_instance SET expires_at=now()+interval '1 hour' WHERE id="+q(runtime),source)
            if key=='root':pod=job(pg,source,namespace,run['id'],a,runtime,create,kube,image,PROGRAM)
            else:allocate(pg,source,runtime,vd['id'],vr,vd_pod);pod=vd_pod
            members[key]={'attempt':a,'runtime':runtime,'pod':pod}
        api.close();backup(pg,source,work/'before-claim')
        def context(key):return next(c for c in json.loads(pg.sql(starts.context_query(key=='peer'),source)) if c['runtime']['id']==members[key]['runtime'])
        def root_admitted(root_request,root_start,root_store):
            members['root']['start']=root_start
            pg.sql("UPDATE edgeai.vd_runtime SET lease_until=now()+interval '60 seconds' WHERE id="+q(vr),source)
            def peer_admitted(peer_request,peer_start,store):
                members['peer']['start']=peer_start
                stream.checkpoints(pg,source,members,{'root':root_request,'peer':peer_request},fixture.tls)
                cps=stream.checkpoints_by_task
                assert root_request('streams/complete',{'checkpointId':cps['root']['id']},200)['state']=='WAITING'
                assert pg.sql('SELECT count(*) FROM edgeai.stream_task_completion WHERE granted_at IS NOT NULL',source)=='0'
                assert peer_request('streams/complete',{'checkpointId':cps['peer']['id']},200)['state']=='FINALIZE'
                assert root_request('streams/complete',{'checkpointId':cps['root']['id']},200)['state']=='FINALIZE'
                assert pg.sql('SELECT count(*)::text||\':\'||count(DISTINCT granted_at)::text FROM edgeai.stream_task_completion WHERE granted_at IS NOT NULL',source)=='2:1'
                assert pg.sql('SELECT count(*) FROM edgeai.stream_checkpoint',source)=='5'
                backup(pg,source,work/'sealed')
                commit_result(root_request,store,fixture,members['root'],False);child_state(source,False)
                backup(pg,source,work/'partial')
                commit_result(peer_request,store,fixture,members['peer'],True)
                backup(pg,source,work/'committed')
            admit(fixture,pg,source,context('peer'),vd_pod,kube,create,namespace,on_admitted=peer_admitted,api_env=stream.env)
        admit(fixture,pg,source,context('root'),members['root']['pod'],kube,create,namespace,on_admitted=root_admitted,api_env=stream.env)
        passed('actual NODE and VD claims, task-source checkpoint cursors and one atomic group completion grant precede both Result commits')
        storage_backup(client,[fixture.bucket],120);origin.terminate();origin.wait(15);shutil.rmtree(fixture.work/'origin-data');drop(source)
        report.update(sourceDatabaseRemoved=True,sourceStorageRemoved=True)
        for snapshot in ('sealed','sealed','sealed','partial','committed','before-claim'):
            db='edgeai_restore_group_'+uuid.uuid4().hex
            restoring=Postgres(args.transport,diagnostics=work/('restore-'+uuid.uuid4().hex));restore(restoring,work/snapshot,db);remember(db)
            targets.append((db,restoring.directory/'restore-report.json'))
        stopped={'formatVersion':1,'scope':'observed-kubernetes-producer-termination','status':'RUNNING','namespace':namespace,
            'namespaceUid':namespace_uid,'recoveryId':operation,'activated':False,'globalQuiescenceProven':False,'fenceRetained':False}
        Stop(kube,namespace,namespace_uid,operation,90).execute(stopped);durable_json(work/'termination-report.json',stopped)
        assert len(stopped['terminatedPods'])==2 and all(p['kind']=='ALL_CONTAINERS_TERMINATED' for p in stopped['terminatedPods'])
        assert all(p['status']['containerStatuses'][0]['state']['terminated']['message']=='CHILD_REAPED' for p in kube.items(namespace,'Pod')[0])
        stream.fence_group(members)
        for index in range(len(targets)):
            a=options(index);a.output.mkdir(mode=0o700);retirement.apply(pg,a,retirement.prepare(pg,a))
            a=options(index);a.output.mkdir(mode=0o700);broker.apply(pg,a,broker.prepare(pg,a))
        passed('independent backups survive source removal; both retained Pods and all three broker principals are retired')
        from test_stream_completion_backup import check as check_completion_backup
        check_completion_backup(pg,targets[0][0],targets[0][1],bundle,client,fixture.pin,passed,report)
        for key in members:refuse_cli(options(5,key))
        passed('neither member can invent an absent group completion grant from a before-claim backup')
        from test_stream_group_result_checks import check
        check(pg,options,members,stream,results,refuse_cli,fingerprints,passed,report)
        allowed={'task','task_attempt','task_result','result_artifact','runtime_result_publication'}
        for index,order in [(0,('root','peer')),(1,('peer','root'))]:
            db=targets[index][0];before=fingerprints(db)
            first=cli(options(index,order[0]));assert (first['resultsCreated'],first['childrenReadied'],first['publicationsCompleted'])==(1,0,1)
            child_state(db,False);partial=fingerprints(db)
            assert not cli(options(index,order[0]))['databaseModified'] and fingerprints(db)==partial
            passed(order[0]+'-first recovery restores only its original result; incomplete join and replay create no child Attempt')
            second=cli(options(index,order[1]));assert (second['resultsCreated'],second['childrenReadied'],second['publicationsCompleted'])==(1,1,1)
            child_state(db,True);originals(db);after=fingerprints(db)
            assert all(after[k]==v for k,v in before.items() if k not in allowed)
            for key in order:assert not cli(options(index,key))['databaseModified'] and fingerprints(db)==after
            passed(order[0]+'-first then '+order[1]+' recovery releases the join exactly once, preserves both original Results and all unrelated history')
        db=targets[2][0];assert cli(options(2))['resultsCreated']==1;before=fingerprints(db)
        pg.sql("CREATE FUNCTION edgeai.owned_group_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.state='READY' THEN RAISE EXCEPTION 'owned group join fault'; END IF; RETURN NEW; END $$; CREATE TRIGGER owned_group_fault BEFORE UPDATE ON edgeai.task FOR EACH ROW EXECUTE FUNCTION edgeai.owned_group_fault()",db)
        a=options(2,'peer');a.output.mkdir(mode=0o700);plan=results.prepare(pg,a);offset=pg.log.stat().st_size
        try:
            try:results.apply(pg,a,plan)
            except RuntimeError:
                with pg.log.open('rb') as log:log.seek(offset);assert b'owned group join fault' in log.read()
            else:raise AssertionError('Late join fault was not exercised')
        finally:pg.sql('DROP TRIGGER owned_group_fault ON edgeai.task; DROP FUNCTION edgeai.owned_group_fault()',db)
        assert fingerprints(db)==before and not (a.output/'results.json').exists();child_state(db,False)
        passed('actual late join failure rolls back the second Result, artifacts, publication and readiness while preserving the first')
        a=options(2,'peer');a.output.mkdir(mode=0o700);plan=results.prepare(pg,a);original_call=pg.call
        def lost_reply(tool,arguments,*positional,**kwargs):
            value=original_call(tool,arguments,*positional,**kwargs)
            if tool=='psql' and kwargs.get('source') is not None:raise OSError('Owned COMMIT reply loss')
            return value
        with patch.object(pg,'call',lost_reply):
            try:results.apply(pg,a,plan)
            except OSError:pass
            else:raise AssertionError('COMMIT response loss was not exercised')
        assert not (a.output/'results.json').exists();child_state(db,True);originals(db);after=fingerprints(db)
        assert not cli(options(2,'peer'))['databaseModified'] and fingerprints(db)==after
        passed('lost second-member COMMIT response preserves both results and one queued child; CLI replay makes no duplicate')
        first=cli(options(3));assert first['resultsCreated']==0 and first['childrenReadied']==0;child_state(targets[3][0],False)
        second=cli(options(3,'peer'));assert second['resultsCreated']==1 and second['childrenReadied']==1;originals(targets[3][0])
        passed('source partial-commit backup preserves the existing NODE Result before restoring only the missing VD Result')
        db=targets[4][0];before=fingerprints(db)
        for key in members:
            value=cli(options(4,key));assert value['resultsCreated']==0 and value['childrenReadied']==0
        after=fingerprints(db);assert all(after[k]==v for k,v in before.items() if k!='runtime_result_publication');originals(db)
        passed('fully committed source backup retains all original downstream history and acknowledges only pending publications')
        pg.sql("UPDATE edgeai.task SET state='FAILED' WHERE id="+q(child)+"; UPDATE edgeai.task_attempt SET state='FAILED' WHERE task_id="+q(child)+
            "; UPDATE edgeai.workflow_run SET state='FAILED' WHERE id="+q(run['id']),db)
        after=fingerprints(db)
        for key in members:assert not cli(options(4,key))['databaseModified'] and fingerprints(db)==after
        passed('replaying either group Result preserves a later failed child and terminal Run')
        report.update(mixedGroupFullCliVerified=True,originalResultIdsAndTimesPreserved=True,restoredDatabases=len(targets),
            verifiedMembers=2,actualStartJournals=2,actualResultJournals=2,terminalCheckpoints=2,retainedTerminatedPods=2,
            actualCheckpointReceipts=5,
            retiredBrokerPrincipals=3,restoreOrders=['KUBERNETES_THEN_VD','VD_THEN_KUBERNETES'],preservedOtherTables=len(after)-len(allowed),newRuntimes=0)
        code=0
    except Exception as error:
        report['failureType']=type(error).__name__
        with private_file(work/'failure.log','w') as log:traceback.print_exc(file=log)
    finally:
        try:
            for api in apis:api.close()
            fixture.doCleanups()
            if stream is not None:report['ownedStreamAuthorityStopped']=stream.close()
            report['ownedApisStopped']=all(api.process is None or api.process.poll() is not None for api in apis) and getattr(fixture,'api_claim_source_stopped',True)
            report['ownedStorageStopped']=all(p.poll() is not None for p in getattr(fixture,'processes',[]))
            for db in list(owned):
                try:drop(db)
                except Exception as error:
                    # A slow/failed DROP must not skip other owned databases or the namespace.
                    # Do not retry a timed-out command; observe its eventual state below.
                    report.setdefault('databaseCleanupFailures',[]).append({'database':db,'type':type(error).__name__});code=1
                    with private_file(work/('database-cleanup-'+uuid.uuid4().hex+'.log'),'w') as log:traceback.print_exc(file=log)
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
            for db in list(owned):
                current=pg.sql('SELECT oid::text FROM pg_database WHERE datname='+q(db),'postgres')
                if not current:del owned[db]
                else:assert current==owned[db],'Owned database identity changed during cleanup observation'
            report['ownedDatabasesRemoved']=not owned
        except Exception as error:
            report['cleanupFailureType']=type(error).__name__;code=1
            with private_file(work/'cleanup-failure.log','w') as log:traceback.print_exc(file=log)
        os.environ.clear();os.environ.update(old_env)
        report['status']='PASS' if code==0 else 'FAIL';args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(json.dumps(report,indent=2)+'\n')
        print(report['status']+': '+str(len(report['cases']))+' mixed STREAM Result recovery cases; private diagnostics '+str(work),flush=True)
    return code


if __name__=='__main__':raise SystemExit(main())
