"""Real PostgreSQL restores and Kubernetes containers, with explicitly seeded runtime bindings."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import runpy
import subprocess
import sys
import time
import traceback
from types import SimpleNamespace
import urllib.error
import uuid

from postgres_backup import Blocked, Postgres, ROOT, backup, restore, identifier, literal, private_file
from recovery_kubernetes import Kubernetes, PART, MANAGER, RUNTIME, VD
from recovery_kubernetes_retire import prepare, apply
from recovery_remote_retire import durable_json
from recovery_stop_kubernetes import Stop, FINALIZER, OPERATION, FENCE, object_path, termination_proof

Api = runpy.run_path(str(ROOT/'scripts/test-postgres-backup.py'))['Api']
PROGRAM = """
import signal,subprocess,sys,time
from pathlib import Path
child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(900)'])
def stop(signum,frame):
    child.terminate();child.wait(timeout=5)
    Path('/dev/termination-log').write_text('CHILD_REAPED')
    sys.exit(0)
signal.signal(signal.SIGTERM,stop)
Path('/tmp/child-ready').write_text(str(child.pid))
while True:time.sleep(1)
"""


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--context',required=True)
    p.add_argument('--transport',choices=['native','compose'],default='native')
    p.add_argument('--runner-image'); p.add_argument('--runner-source')
    p.add_argument('--report',type=Path,default=ROOT/'.tools/recovery-kubernetes-retire-test.json')
    args=p.parse_args()
    token=uuid.uuid4().hex; operation=str(uuid.uuid4())
    namespace='edgeai-retire-test-'+token[:16]
    work=ROOT/'.tools'/('recovery-kubernetes-retire-test-'+token); work.mkdir(mode=0o700)
    source='edgeai_backup_kret_'+token
    pg=Postgres(args.transport,diagnostics=work/'postgres')
    kube=Kubernetes(args.context); namespace_uid=None
    owned,apis={},[]
    report={'status':'RUNNING','scope':'restored-database-kubernetes-retirement','sourceMode':'SYNTHETIC',
        'runtimeBoundary':'ACTUAL_CONTAINERS_WITH_EXPLICIT_DATABASE_BINDING_FIXTURES','cases':[],
        'ownedNamespaceRemoved':False,'ownedDatabasesRemoved':False,'ownedApisStopped':False}

    def passed(name): report['cases'].append(name); print('PASS: '+name,flush=True)

    def call(arguments,document=None):
        r=subprocess.run(kube.command+arguments,input=None if document is None else json.dumps(document).encode(),capture_output=True,timeout=30)
        assert r.returncode==0,'Owned Kubernetes fixture operation failed; response suppressed'
        return json.loads(r.stdout) if r.stdout.strip() else None

    def create(document): return call(['create','-f','-','-o','json'],document)

    def drop(database):
        assert pg.sql('SELECT oid::text FROM pg_database WHERE datname='+literal(database),'postgres')==str(owned[database])
        pg.sql('DROP DATABASE '+identifier(database),'postgres'); del owned[database]

    def fingerprints(database):
        tables=json.loads(pg.sql("SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='edgeai'",database))
        return {t:hashlib.sha256(pg.sql('SELECT to_jsonb(t)::text FROM edgeai.'+identifier(t)+' t ORDER BY to_jsonb(t)::text',database).encode()).hexdigest() for t in tables}

    def options(database,receipt,**overrides):
        result=SimpleNamespace(context=args.context,namespace=namespace,namespace_uid=namespace_uid,recovery_id=operation,
            database=database,restore_report=receipt,termination_report=work/'termination-report.json',transport=args.transport,
            pg_bin=None,timeout=30,output=work/('retire-'+uuid.uuid4().hex))
        result.__dict__.update(overrides); return result

    def cli(a,expected=0):
        command=[sys.executable,'scripts/recovery_kubernetes_retire.py']
        for k,v in vars(a).items():
            if v is not None: command+=['--'+k.replace('_','-'),str(v)]
        result=subprocess.run(command,capture_output=True,timeout=150)
        with private_file(work/('command-'+uuid.uuid4().hex+'.log')) as log: log.write(result.stdout+result.stderr)
        assert result.returncode==expected,'Kubernetes retirement CLI outcome differs; private diagnostics retained'
        path=a.output/('retirement.json' if expected==0 else 'failure.json')
        return json.loads(path.read_text())

    def refused(a,expected=2):
        before=fingerprints(a.database); cli(a,expected)
        assert fingerprints(a.database)==before

    code=1
    try:
        kube.namespace('edgeai')
        pin=json.loads((ROOT/'deploy/kubernetes/overlays/dev/release.json').read_text())
        assert bool(args.runner_image)==bool(args.runner_source)
        image=args.runner_image or 'ghcr.io/dsa04156/edgeai-runner@'+pin['runnerDigest']
        revision=args.runner_source or pin['sourceRevision']
        assert re.fullmatch(r'ghcr\.io/dsa04156/edgeai-runner@sha256:[0-9a-f]{64}',image)
        assert re.fullmatch('[a-f0-9]{40}',revision)
        report.update(image=image,imageSourceRevision=revision,
            apiJarSha256=hashlib.sha256((ROOT/'backend/app/build/libs/edgeai-control-plane.jar').read_bytes()).hexdigest())
        ns=create({'apiVersion':'v1','kind':'Namespace','metadata':{'name':namespace,
            'labels':{PART:'edgeai',MANAGER:'edgeai-bootstrap','edgeai.io/recovery-test':token}}})
        namespace_uid=ns['metadata']['uid']
        pg.sql('CREATE DATABASE '+identifier(source),'postgres')
        owned[source]=pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(source),'postgres')
        api=Api(source,work); apis.append(api)
        service=api.request('POST','profiles/SERVICE',{'key':'retire-service','version':'1.0.0',
            'spec':json.loads((ROOT/'contracts/profiles/service-execution.example.json').read_text())},201)
        workflow=api.request('POST','workflows',{'key':'retire-workflow','displayName':'Recovery test'},201)
        version=api.request('POST','workflows/'+workflow['id']+'/versions',{'version':'1.0.0',
            'tasks':[{'key':'root','serviceProfileVersionId':service['id'],'parameters':{'features':[2],'weights':[3]}}],
            'dependencies':[]},201)
        run=api.request('POST','workflow-runs',{'workflowVersionId':version['id'],'execution':{'mode':'AUTO'},'parameters':{}},201,str(uuid.uuid4()))
        attempt=json.loads(pg.sql('SELECT to_jsonb(a) FROM edgeai.task_attempt a JOIN edgeai.task t ON t.id=a.task_id WHERE t.run_id='+literal(run['id'])+'::uuid',source))
        runtime=str(uuid.uuid4()); claimed_job='edgeai-'+attempt['id']
        pg.sql('INSERT INTO edgeai.runtime_instance(id,attempt_id,task_id,run_id,epoch,namespace,job_name,claim_nonce,desired_state,observed_state,created_at,updated_at) VALUES ('+
            ','.join(literal(v)+'::uuid' for v in (runtime,attempt['id'],attempt['task_id'],run['id']))+',1,'+literal(namespace)+','+literal(claimed_job)+','+
            literal(str(uuid.uuid4()))+"::uuid,'RUNNING','PENDING',now(),now())",source)
        device_profile=api.request('POST','profiles/DEVICE',{'key':'retire-device','version':'1.0.0','spec':{'protocol':'synthetic'}},201)
        device=api.request('POST','devices',{'key':'retire-source','displayName':'Source','profileVersionId':device_profile['id'],'sourceMode':'SYNTHETIC'},201)
        spec=json.loads((ROOT/'contracts/profiles/vd-profile.example.json').read_text())
        spec['serviceProfileVersionId']=service['id']; spec['sources']['input']['deviceProfileVersionId']=device_profile['id']
        vd_profile=api.request('POST','profiles/VD',{'key':'retire-vd-profile','version':'1.0.0','spec':spec},201)
        vd=api.request('POST','virtual-devices',{'key':'retire-vd','displayName':'VD','profileVersionId':vd_profile['id'],
            'sources':[{'sourceKey':'input','deviceId':device['id']}],'placement':{'mode':'AUTO'}},201)
        api.close()
        vr=str(uuid.uuid4()); vd_pod='edgeai-vd-'+vr
        configuration={'sources':json.loads(pg.sql("SELECT jsonb_object_agg(source_key,id::text) FROM edgeai.vd_source_binding WHERE vd_id="+literal(vd['id'])+'::uuid',source)),
            'serviceProfileVersionId':service['id'],'namespace':namespace,'placementMode':'AUTO','targetNodeId':None,'targetNodeName':None,
            'maxConcurrentTasks':1,'startupSeconds':120,'drainSeconds':120}
        # These rows represent the backup's controller history, not an actual Runner/VD claim protocol.
        pg.sql('BEGIN; INSERT INTO edgeai.vd_runtime(id,vd_id,generation,requested_revision,configuration,configuration_digest,namespace,pod_name,claim_nonce,desired_state,observed_state,startup_deadline,created_at,updated_at) VALUES ('+
            literal(vr)+'::uuid,'+literal(vd['id'])+'::uuid,1,0,'+literal(json.dumps(configuration))+'::jsonb,'+literal('sha256:'+hashlib.sha256(json.dumps(configuration).encode()).hexdigest())+','+
            literal(namespace)+','+literal(vd_pod)+','+literal(str(uuid.uuid4()))+"::uuid,'RUNNING','PENDING',now()+interval '120s',now(),now()); "+
            'INSERT INTO edgeai.vd_runtime_binding(id,vd_id,runtime_id,opened_revision,opened_at) VALUES ('+literal(str(uuid.uuid4()))+'::uuid,'+literal(vd['id'])+'::uuid,'+literal(vr)+'::uuid,0,now()); COMMIT',source)
        for table,rid in [('runtime_command',runtime),('vd_runtime_command',vr)]:
            for kind in ('CREATE','DELETE'):
                pg.sql('INSERT INTO edgeai.'+table+'(id,runtime_id,kind,attempts,available_at,lease_owner,lease_until,created_at,updated_at) VALUES ('+
                    literal(str(uuid.uuid4()))+'::uuid,'+literal(rid)+'::uuid,'+literal(kind)+',3,now(),'+literal(str(uuid.uuid4()))+"::uuid,now()+interval '5 minutes',now(),now())",source)
        labels={PART:'edgeai',MANAGER:RUNTIME,'edgeai.io/run-id':run['id'],'edgeai.io/task-id':attempt['task_id'],
            'edgeai.io/attempt-id':attempt['id'],'edgeai.io/epoch':'1'}
        pod_spec={'restartPolicy':'Never','terminationGracePeriodSeconds':30,'automountServiceAccountToken':False,
            'nodeSelector':{'kubernetes.io/arch':'amd64'},'securityContext':{'runAsNonRoot':True,'runAsUser':10001,'runAsGroup':10001},
            'containers':[{'name':'runner','image':image,'command':['python3','-B','-c',PROGRAM],
                'resources':{'requests':{'cpu':'10m','memory':'32Mi'},'limits':{'cpu':'100m','memory':'96Mi'}},
                'securityContext':{'allowPrivilegeEscalation':False,'capabilities':{'drop':['ALL']}}}]}
        job=create({'apiVersion':'batch/v1','kind':'Job','metadata':{'namespace':namespace,'name':claimed_job,'labels':labels},
            'spec':{'backoffLimit':0,'template':{'metadata':{'labels':labels},'spec':pod_spec}}})
        create({'apiVersion':'v1','kind':'Pod','metadata':{'namespace':namespace,'name':vd_pod,
            'labels':{PART:'edgeai',MANAGER:VD,'edgeai.io/vd-runtime-id':vr,'edgeai.io/vd-id':vd['id'],'edgeai.io/generation':'1'}},'spec':pod_spec})
        deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            pods=kube.items(namespace,'Pod')[0]
            if len(pods)==2 and all(p.get('status',{}).get('phase')=='Running' for p in pods):
                if all(subprocess.run(kube.command+['-n',namespace,'exec',p['metadata']['name'],'--','python3','-c',
                    "from pathlib import Path;import os;os.kill(int(Path('/tmp/child-ready').read_text()),0)"],capture_output=True,timeout=15).returncode==0 for p in pods): break
            time.sleep(.3)
        else: raise AssertionError('Actual parent and child processes did not become ready')
        runner=next(p for p in pods if p['metadata']['labels'][MANAGER]==RUNTIME)
        vd_live=next(p for p in pods if p['metadata']['name']==vd_pod)
        node=kube.read('/api/v1/nodes/'+runner['spec']['nodeName'])
        pg.sql("UPDATE edgeai.runtime_instance SET observed_state='RUNNING',job_uid="+literal(job['metadata']['uid'])+'::uuid,producer_pod_uid='+literal(runner['metadata']['uid'])+
            '::uuid,node_uid='+literal(node['metadata']['uid'])+'::uuid,node_name='+literal(runner['spec']['nodeName'])+' WHERE id='+literal(runtime)+'::uuid',source)
        pg.sql("UPDATE edgeai.vd_runtime SET observed_state='SUBMITTED',pod_uid="+literal(vd_live['metadata']['uid'])+'::uuid,updated_at=now() WHERE id='+literal(vr)+'::uuid',source)
        backup(pg,source,work/'backup')
        targets=[]
        for _ in range(3):
            database='edgeai_restore_kret_'+uuid.uuid4().hex
            restoring=Postgres(args.transport,diagnostics=work/('restore-'+uuid.uuid4().hex))
            restored=restore(restoring,work/'backup',database); owned[database]=restored['databaseOid']
            receipt=restoring.directory/'restore-report.json'
            targets.append((database,receipt))
        drop(source); report['sourceDatabaseRemovedBeforeRecovery']=True
        db,receipt=targets[0]; second,receipt2=targets[1]
        original=fingerprints(db); other=fingerprints(second); assert len(original)==43
        passed('real-running-parent-child-containers-backed-up-restored-three-times-and-source-database-removed')

        extra_vr=str(uuid.uuid4())
        unbound=copy.deepcopy(pod_spec)
        unbound['schedulingGates']=[{'name':'edgeai.io/recovery-test'}]
        create({'apiVersion':'v1','kind':'Pod','metadata':{'namespace':namespace,'name':'edgeai-vd-'+extra_vr,
            'labels':{PART:'edgeai',MANAGER:VD,'edgeai.io/vd-runtime-id':extra_vr,
                'edgeai.io/vd-id':str(uuid.uuid4()),'edgeai.io/generation':'1'}},'spec':unbound})

        stopped={'formatVersion':1,'scope':'observed-kubernetes-producer-termination','status':'RUNNING','namespace':namespace,
            'namespaceUid':namespace_uid,'recoveryId':operation,'activated':False,'globalQuiescenceProven':False,'fenceRetained':False}
        Stop(kube,namespace,namespace_uid,operation,90).execute(stopped)
        durable_json(work/'termination-report.json',stopped)
        actual=kube.items(namespace,'Pod')[0]
        bound=[p for p in actual if p['spec'].get('nodeName')]
        assert len(actual)==3 and len(bound)==2
        assert all(p['status']['containerStatuses'][0]['state']['terminated']['message']=='CHILD_REAPED' for p in bound)
        assert all(termination_proof(p)['kind']=='ALL_CONTAINERS_TERMINATED' for p in bound)
        assert sum(termination_proof(p)['kind']=='NEVER_BOUND_TO_NODE' for p in actual)==1
        report.update(terminatedContainers=2,reapedChildren=2,unboundPostBackupPods=1)
        passed('actual-quota-and-retained-pod-evidence-prove-two-parents-and-children-terminated')

        refused(options(db,receipt,namespace_uid=str(uuid.uuid4())),1)
        invalid=copy.deepcopy(stopped)
        next(p for p in invalid['terminatedPods'] if p['containers'])['containers'][0]['exitCode']=99
        durable_json(work/'tampered-termination.json',invalid)
        refused(options(db,receipt,termination_report=work/'tampered-termination.json'))
        refused(options(db,receipt2),1)
        passed('wrong-scope-restore-identity-and-tampered-termination-report-do-not-write')

        def patch(kind,name,changes):
            value=kube.read(object_path(kind,namespace,name)); meta=value['metadata']
            return call(['-n',namespace,'patch',kind.lower(),name,'--type=json','--patch-file=/dev/stdin','-o','json'],
                [{'op':'test','path':'/metadata/uid','value':meta['uid']},{'op':'test','path':'/metadata/resourceVersion','value':meta['resourceVersion']},*changes])
        patch('Job',claimed_job,[{'op':'replace','path':'/spec/suspend','value':False}])
        refused(options(db,receipt))
        patch('Job',claimed_job,[{'op':'replace','path':'/spec/suspend','value':True}])
        passed('resumed-live-job-refuses-database-mutation-while-admission-fence-remains')

        pg.sql('UPDATE edgeai.runtime_instance SET producer_pod_uid='+literal(str(uuid.uuid4()))+'::uuid WHERE id='+literal(runtime)+'::uuid',db)
        refused(options(db,receipt))
        pg.sql('UPDATE edgeai.runtime_instance SET producer_pod_uid='+literal(runner['metadata']['uid'])+'::uuid WHERE id='+literal(runtime)+'::uuid',db)
        assert fingerprints(db)==original
        passed('restored-claimed-producer-uid-conflict-refused-without-adopting-replacement')

        a=options(db,receipt); a.output.mkdir(mode=0o700); plan=prepare(pg,a)
        pg.sql('UPDATE edgeai.runtime_command SET attempts=attempts+1 WHERE runtime_id='+literal(runtime)+'::uuid',db)
        before=fingerprints(db)
        try: apply(pg,a,plan)
        except Blocked: pass
        else: raise AssertionError('Changed outbox was accepted')
        assert fingerprints(db)==before
        pg.sql('UPDATE edgeai.runtime_command SET attempts=attempts-1 WHERE runtime_id='+literal(runtime)+'::uuid',db)
        passed('fresh-outbox-comparison-rejects-concurrent-database-change')

        # Inject a real writer after the last prepare, immediately before actual locked SQL execution.
        a=options(db,receipt); a.output.mkdir(mode=0o700); plan=prepare(pg,a)
        class RacingWriter:
            def call(self,*positional,**keywords):
                if keywords.get('source') is not None:
                    pg.sql('UPDATE edgeai.runtime_command SET attempts=attempts+1 WHERE runtime_id='+literal(runtime)+'::uuid',db)
                return pg.call(*positional,**keywords)
        try: apply(RacingWriter(),a,plan)
        except RuntimeError: pass
        else: raise AssertionError('Transaction guard missed a real concurrent writer')
        pg.sql('UPDATE edgeai.runtime_command SET attempts=attempts-1 WHERE runtime_id='+literal(runtime)+'::uuid',db)
        assert fingerprints(db)==original
        passed('locked-transaction-guard-detects-writer-after-live-inventory')

        a=options(db,receipt); a.output.mkdir(mode=0o700); plan=prepare(pg,a)
        class MarkerWriter:
            def call(self,*positional,**keywords):
                if keywords.get('source') is not None:
                    pg.sql('COMMENT ON DATABASE '+identifier(db)+" IS 'changed-recovery-marker'",db)
                return pg.call(*positional,**keywords)
        try: apply(MarkerWriter(),a,plan)
        except RuntimeError: pass
        else: raise AssertionError('Changed database marker was accepted')
        pg.sql('COMMENT ON DATABASE '+identifier(db)+' IS '+literal(plan['marker']),db)
        assert fingerprints(db)==original
        passed('database-identity-is-rechecked-inside-locked-transaction')

        locker=None; locker_name='kret-lock-'+uuid.uuid4().hex
        try:
            with private_file(work/'lock-holder.log') as output:
                locker=subprocess.Popen(pg.prefix+[pg.binaries['psql']]+pg.connection+['--dbname',db,
                    '-X','-q','-v','ON_ERROR_STOP=1','-c',
                    'BEGIN; LOCK TABLE edgeai.vd_runtime_binding IN ROW EXCLUSIVE MODE; SELECT pg_sleep(60); COMMIT;'],
                    env={**pg.env,'PGAPPNAME':locker_name},stdout=output,stderr=output)
            deadline=time.monotonic()+5
            while pg.sql("SELECT EXISTS(SELECT FROM pg_locks l JOIN pg_stat_activity a USING(pid) WHERE a.application_name="+
                literal(locker_name)+" AND l.relation='edgeai.vd_runtime_binding'::regclass AND l.mode='RowExclusiveLock' AND l.granted)",db)!='t':
                assert time.monotonic()<deadline and locker.poll() is None
                time.sleep(.05)
            refused(options(db,receipt),1)
        finally:
            if locker is not None:
                pg.sql('SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE application_name='+literal(locker_name),db)
                locker.wait(timeout=10)
        passed('real-competing-vd-binding-lock-times-out-without-partial-write')

        pg.sql("CREATE FUNCTION edgeai.reject_recovery_binding() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected binding failure'; END $$; "
            "CREATE TRIGGER reject_recovery_binding BEFORE UPDATE ON edgeai.vd_runtime_binding FOR EACH ROW EXECUTE FUNCTION edgeai.reject_recovery_binding()",db)
        refused(options(db,receipt),1)
        assert fingerprints(db)==original
        pg.sql('DROP TRIGGER reject_recovery_binding ON edgeai.vd_runtime_binding; DROP FUNCTION edgeai.reject_recovery_binding()',db)
        passed('late-vd-binding-error-rolls-back-runtime-and-command-updates-together')

        completed=cli(options(db,receipt)); after=fingerprints(db)
        assert {k:completed[k] for k in ('runtimesRetired','vdRuntimesRetired','commandsCompleted','bindingsClosed')}=={
            'runtimesRetired':1,'vdRuntimesRetired':1,'commandsCompleted':4,'bindingsClosed':1}
        assert not completed['unresolved'] and not completed['activated'] and not completed['globalQuiescenceProven']
        changed={'runtime_instance','runtime_command','vd_runtime','vd_runtime_command','vd_runtime_binding'}
        assert all(original[t]==after[t] for t in original if t not in changed) and fingerprints(second)==other
        for table in ('runtime_command','vd_runtime_command'):
            assert pg.sql('SELECT count(*) FROM edgeai.'+table+' WHERE NOT completed OR lease_owner IS NOT NULL OR lease_until IS NOT NULL OR attempts<>3',db)=='0'
        for table in ('runtime_instance','vd_runtime'):
            assert pg.sql("SELECT count(*) FROM edgeai."+table+" WHERE desired_state<>'STOPPED' OR observed_state<>'TERMINATED'",db)=='0'
        assert completed['objectsAbsentFromDatabase']==1
        report.update(runtimesRetired=1,vdRuntimesRetired=1,commandsCompleted=4,bindingsClosed=1,preservedTables=38,restoredDatabases=3)
        passed('atomic-runtime-vd-command-and-binding-retirement-preserves-38-tables-and-other-restored-database')
        replay=cli(options(db,receipt)); assert not replay['databaseModified'] and fingerprints(db)==after
        assert len(kube.items(namespace,'Pod')[0])==3
        assert kube.read(object_path('ResourceQuota',namespace,FENCE))['metadata']['uid']==stopped['quotaUid']
        passed('same-recovery-replay-changes-no-timestamps-and-retains-fence-and-pod-evidence')

        a=options(second,receipt2); a.output.mkdir(mode=0o700); plan=prepare(pg,a)
        class LostReply:
            def call(self,*positional,**keywords):
                result=pg.call(*positional,**keywords)
                if keywords.get('source') is not None: raise OSError('Injected actual COMMIT reply loss')
                return result
        try: apply(LostReply(),a,plan)
        except OSError: pass
        else: raise AssertionError('Actual COMMIT reply loss not propagated')
        assert (a.output/'intent.json').exists() and not (a.output/'retirement.json').exists()
        lost_after=fingerprints(second); assert lost_after!=other
        assert not cli(options(second,receipt2))['databaseModified'] and fingerprints(second)==lost_after
        passed('actual-commit-reply-loss-retains-intent-and-fresh-rerun-confirms-completion')

        third,receipt3=targets[2]
        pg.sql("UPDATE edgeai.runtime_instance SET producer_pod_uid=NULL,node_uid=NULL,node_name=NULL,observed_state='SUBMITTED' WHERE id="+literal(runtime)+'::uuid',third)
        pending=fingerprints(third)
        unresolved=cli(options(third,receipt3))
        assert unresolved['runtimesRetired']==0 and unresolved['vdRuntimesRetired']==1 and len(unresolved['unresolved'])==1
        assert unresolved['unresolved'][0]['reason']=='JOB_OR_CLAIMED_PRODUCER_NOT_PROVEN'
        assert unresolved['unresolved'][0]['alreadyTerminal'] is False
        later=fingerprints(third)
        assert later['runtime_instance']==pending['runtime_instance'] and later['runtime_command']==pending['runtime_command']
        assert pg.sql("SELECT count(*) FROM edgeai.runtime_command WHERE NOT completed AND lease_owner IS NOT NULL",third)=='2'
        passed('unrecorded-claimed-producer-remains-unresolved-with-pending-commands-preserved')

        inspection=Api(db,work,inspection=True); apis.append(inspection)
        inspection.request('GET','workflow-runs/'+run['id'])
        try: inspection.request('POST','workflows',{'key':'forbidden','displayName':'Forbidden'},201)
        except urllib.error.HTTPError as error: assert error.code==403
        else: raise AssertionError('Restored inspection API allowed a management write')
        inspection.close()
        passed('retired-restored-database-remains-inspectable-without-starting-workers')

        # Remove only our already-proven terminal fixture. An API 404 must not replace retained evidence.
        assert termination_proof(kube.read(object_path('Pod',namespace,runner['metadata']['name']))) is not None
        patch('Pod',runner['metadata']['name'],[{'op':'replace','path':'/metadata/finalizers','value':[]}])
        deadline=time.monotonic()+30
        while any(p['metadata']['uid']==runner['metadata']['uid'] for p in kube.items(namespace,'Pod')[0]):
            assert time.monotonic()<deadline; time.sleep(.2)
        refused(options(db,receipt))
        passed('deleted-producer-api-record-is-not-accepted-as-fresh-termination-evidence')
        code=0
    except Exception as error:
        report['failureType']=type(error).__name__
        with private_file(work/'failure.log','w') as log: traceback.print_exc(file=log)
        print('FAIL: Kubernetes retirement; private evidence retained',flush=True)
    finally:
        try:
            for api in apis: api.close()
            report['ownedApisStopped']=all(api.process is None or api.process.poll() is not None for api in apis)
            for database in list(owned): drop(database)
            report['ownedDatabasesRemoved']=not owned
            if namespace_uid:
                ns=kube.read('/api/v1/namespaces/'+namespace)
                assert ns['metadata']['uid']==namespace_uid and ns['metadata']['labels']['edgeai.io/recovery-test']==token
                call(['delete','--raw','/api/v1/namespaces/'+namespace,'-f','-'],
                    {'apiVersion':'v1','kind':'DeleteOptions','preconditions':{'uid':namespace_uid}})
                deadline=time.monotonic()+180
                while time.monotonic()<deadline:
                    ns=call(['get','namespace',namespace,'--ignore-not-found','-o','json'])
                    if ns is None: break
                    assert ns['metadata']['uid']==namespace_uid and ns['metadata']['labels']['edgeai.io/recovery-test']==token
                    for pod in kube.items(namespace,'Pod')[0]:
                        meta=pod['metadata']; finalizers=meta.get('finalizers',[])
                        if FINALIZER in finalizers and termination_proof(pod) is not None:
                            call(['-n',namespace,'patch','pod',meta['name'],'--type=json','--patch-file=/dev/stdin','-o','json'],[
                                {'op':'test','path':'/metadata/uid','value':meta['uid']},
                                {'op':'test','path':'/metadata/resourceVersion','value':meta['resourceVersion']},
                                {'op':'replace','path':'/metadata/finalizers','value':[v for v in finalizers if v!=FINALIZER]}])
                    time.sleep(.3)
                else: raise AssertionError('Owned namespace cleanup not confirmed')
            report['ownedNamespaceRemoved']=True
        except Exception as error:
            report['cleanupFailureType']=type(error).__name__; code=1
        report['status']='PASS' if code==0 else 'FAIL'
        args.report.parent.mkdir(parents=True,exist_ok=True); args.report.write_text(json.dumps(report,indent=2)+'\n')
        print(report['status']+': '+str(len(report['cases']))+' Kubernetes retirement cases; private diagnostics '+str(work))
    return code


if __name__=='__main__': raise SystemExit(main())
