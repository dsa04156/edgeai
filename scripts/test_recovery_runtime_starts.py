"""Real retained Pods, restored PostgreSQL and TLS S3; explicit or actual API start authority."""
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import time
from types import SimpleNamespace
from unittest.mock import patch
import urllib.request
import uuid

from postgres_backup import Blocked, Postgres, ROOT, backup, restore, literal, identifier, private_file
from recovery_kubernetes import Kubernetes
import recovery_kubernetes_workflows as workflows
import recovery_runtime_starts as starts
from recovery_remote_retire import durable_json
from recovery_remote_storage import Storage, header
from recovery_work_digest import digest
from storage_backup import Client, backup as storage_backup

sys.path.insert(0,str(ROOT/'simulator/tests'))
from test_remote_recovery import RemoteRecoveryTest


def q(value):return literal(value)+'::uuid'


class Fixture(RemoteRecoveryTest):
    def configure(self,work,minio):
        self.work=work/'runtime-starts';self.work.mkdir(mode=0o700)
        self.minio=minio;self.processes=[];self.environment={}
        self.pin=hashlib.sha256(ssl.PEM_cert_to_DER_cert((self.root/'cert.pem').read_text())).hexdigest()
        self.bucket='edgeai-start-recovery-test'
        self.addCleanup(self.close_storage)

    def close_storage(self):
        for process in self.processes:
            if process.poll() is None:process.terminate()
        for process in self.processes:process.wait(timeout=15)

    def storage(self,name):
        certs=self.work/(name+'-certs');certs.mkdir(mode=0o700)
        shutil.copyfile(self.root/'cert.pem',certs/'public.crt')
        shutil.copyfile(self.root/'key.pem',certs/'private.key');(certs/'private.key').chmod(0o600)
        (certs/'CAs').mkdir();shutil.copyfile(self.root/'cert.pem',certs/'CAs/root.crt')
        with socket.socket() as one,socket.socket() as two:
            one.bind(('127.0.0.1',0));two.bind(('127.0.0.1',0));port,console=one.getsockname()[1],two.getsockname()[1]
        user,password='start-recovery-test',secrets.token_hex(24)
        url='https://localhost:'+str(port)
        prefix='EDGEAI_BACKUP_SOURCE_' if name=='origin' else 'EDGEAI_BACKUP_STORAGE_'
        self.environment.update({prefix+'URL':url,prefix+'USER':user,prefix+'PASSWORD':password,
            'EDGEAI_BACKUP_CA_FILE':str(self.root/'cert.pem')})
        with private_file(self.work/(name+'.log')) as log:
            process=subprocess.Popen([str(self.minio.resolve()),'server',str(self.work/(name+'-data')),
                '--address','127.0.0.1:'+str(port),'--console-address','127.0.0.1:'+str(console),
                '--certs-dir',str(certs),'--quiet'],stdout=log,stderr=log,
                env={**os.environ,'MINIO_ROOT_USER':user,'MINIO_ROOT_PASSWORD':password})
        self.processes.append(process);deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            assert process.poll() is None
            try:
                with urllib.request.urlopen(url+'/minio/health/live',context=self.tls,timeout=1) as response:
                    if response.status==200:return process
            except OSError:time.sleep(.1)
        raise AssertionError('Owned start journal storage failed readiness')

    def seed(self,pg,source_db,owned,transport,kube,namespace,fixture,api_claim=False,create=None):
        self.fixture=fixture;target=fixture['target']
        self.api_claim=api_claim
        def restored(bundle):
            db='edgeai_restore_start_'+uuid.uuid4().hex
            restoring=Postgres(transport,diagnostics=self.work/('restore-'+uuid.uuid4().hex))
            result=restore(restoring,bundle,db);owned[db]=result['databaseOid']
            return db,restoring.directory/'restore-report.json'
        if api_claim:
            # This is a fresh owned test source cloned from explicitly seeded
            # fixture rows, not activation of a quarantined recovery database.
            source='edgeai_backup_start_'+uuid.uuid4().hex
            pg.sql('CREATE DATABASE '+identifier(source)+' TEMPLATE '+identifier(source_db),'postgres')
            owned[source]=pg.sql('SELECT oid::text FROM pg_database WHERE datname='+literal(source),'postgres')
            assert pg.sql("SELECT shobj_description(oid,'pg_database') IS NULL FROM pg_database WHERE datname=current_database()",source)=='t'
        else:
            backup(pg,source_db,self.work/'base')
            source,_=restored(self.work/'base')
        # Record an original deadline while the actual fixture process is alive.
        # Workload/placement history is explicitly seeded. Admission is either a
        # fixture or produced by the real TLS API and Kubernetes TokenReview.
        pg.sql("UPDATE edgeai.runtime_instance SET expires_at=now()+interval '1 hour' WHERE id="+q(target['runtime'])+
            "; UPDATE edgeai.task_offload SET start_deadline=now()+interval '30 seconds',updated_at=now() WHERE id="+q(fixture['operation']),source)
        backup(pg,source,self.work/'before-admission')
        context=next(c for c in json.loads(pg.sql(starts.QUERY,source)) if c['runtime']['id']==target['runtime'])
        runtime=context['runtime'];op=context['offloads'][0]
        pod=next(p for p in kube.items(namespace,'Pod')[0] if p['metadata']['uid'] in target['podUids'] and p['spec'].get('nodeName'))
        node=kube.read('/api/v1/nodes/'+pod['spec']['nodeName'])
        self.authority={'apiVersion':'edgeai.runtime.start/v1',
            **{key:runtime[column] for key,column in {'runtimeId':'id','runId':'run_id','taskId':'task_id',
                'attemptId':'attempt_id','epoch':'epoch','namespace':'namespace','jobName':'job_name','jobUid':'job_uid'}.items()},
            'podUid':pod['metadata']['uid'],'nodeUid':node['metadata']['uid'],'nodeName':node['metadata']['name'],
            'workDigest':digest(context['workJson']),'expiresAt':runtime['expires_at'],
            'offloadId':op['id'],'startDeadline':op['start_deadline'],'admittedAt':datetime.now(timezone.utc).isoformat()}
        assert starts.instant_ns(self.authority['admittedAt'])<starts.instant_ns(op['start_deadline'])
        # Demonstrate the post-snapshot DB history that the restored DB loses.
        if not api_claim:
            pg.sql('UPDATE edgeai.runtime_instance SET producer_pod_uid='+q(pod['metadata']['uid'])+',node_uid='+q(node['metadata']['uid'])+
                ',node_name='+literal(node['metadata']['name'])+",observed_state='RUNNING' WHERE id="+q(target['runtime'])+
                "; UPDATE edgeai.task_attempt SET state='RUNNING' WHERE id="+q(target['attempt'])+
                "; UPDATE edgeai.task_offload SET state='SUCCEEDED',updated_at=now() WHERE id="+q(op['id']),source)
        origin=self.storage('origin');self.storage('replica')
        self.bundle=self.work/'storage-backup';self.bundle.mkdir(mode=0o700)
        with patch.dict(os.environ,self.environment):
            client=Client(self.bundle,source=True)
            client.call(['mb','origin/'+self.bucket]);client.call(['version','enable','origin/'+self.bucket])
            origin_env={key.replace('EDGEAI_BACKUP_SOURCE_','EDGEAI_BACKUP_STORAGE_'):value
                        for key,value in self.environment.items() if key.startswith('EDGEAI_BACKUP_SOURCE_')}
            if api_claim:
                from test_runtime_start_api import admit
                self.authority=admit(self,pg,source,context,pod,kube,create,namespace)
            else:
                with patch.dict(os.environ,origin_env):
                    code,_,_=Storage(SimpleNamespace(certificate_sha256=self.pin,timeout=30)).request('PUT',
                        '/'+self.bucket+'/authority/runtime-start/'+target['runtime']+'.json',
                        body=json.dumps(self.authority).encode(),extra={'content-type':starts.MEDIA_TYPE,'if-none-match':'*'})
                    assert code==200
            storage_backup(client,[self.bucket],120)
        origin.terminate();origin.wait(timeout=15);shutil.rmtree(self.work/'origin-data')
        self.targets=[restored(self.work/'before-admission') for _ in range(2)]
        assert pg.sql('SELECT oid::text FROM pg_database WHERE datname='+literal(source),'postgres')==str(owned[source])
        pg.sql('DROP DATABASE '+identifier(source),'postgres');del owned[source]


def check(pg,fixture,options,retire_cli,fingerprints,passed,report):
    with patch.dict(os.environ,fixture.environment):
        _check(pg,fixture,options,retire_cli,fingerprints,passed,report)
    fixture.close_storage()
    report['ownedStartStorageStopped']=all(p.poll() is not None for p in fixture.processes)


def _check(pg,fixture,options,retire_cli,fingerprints,passed,report):
    target=fixture.fixture['target'];operation=fixture.fixture['operation'];db,receipt=fixture.targets[0]
    def opts(index=0,**kwargs):
        return options(*fixture.targets[index],unclaimed_jobs=True,offloads=True,
            runtime_start_backup=fixture.bundle,runtime_start_bucket=fixture.bucket,
            runtime_start_certificate_sha256=fixture.pin,**kwargs)
    def row(table,rid):return json.loads(pg.sql('SELECT to_jsonb(t) FROM edgeai.'+table+' t WHERE id='+q(rid),db))
    for database,restore_report in fixture.targets:
        retire_cli(options(database,restore_report,unclaimed_jobs=True))
        a=options(database,restore_report,unclaimed_jobs=True);a.output.mkdir(mode=0o700)
        workflows.apply(pg,a,workflows.prepare(pg,a))
    pristine=fingerprints(db);other=fingerprints(fixture.targets[1][0])
    a=opts();plan=workflows.prepare(pg,a)
    assert [e['action'] for e in plan['entries'] if e.get('operationId')==operation]==['COMPLETE_KUBERNETES_OFFLOAD']
    record=plan['runtimeStartEvidence']['records'][target['runtime']]
    assert record['authority']==fixture.authority
    assert starts.instant_ns(fixture.authority['startDeadline'])<starts.instant_ns(datetime.now(timezone.utc).isoformat())
    assert all(row('runtime_instance',target['runtime'])[k] is None for k in ('producer_pod_uid','node_uid','node_name'))
    assert fingerprints(db)==pristine and not (fixture.work/'origin-data').exists()
    passed('replicated-fixed-start-version-and-real-retained-pod-prove-original-admission-after-source-db-and-storage-are-removed')

    original_validate=starts.validate
    variants=[('workDigest','sha256:'+'0'*64),('podUid',str(uuid.uuid4())),('nodeUid',str(uuid.uuid4())),
        ('jobUid',str(uuid.uuid4())),('attemptId',str(uuid.uuid4())),('epoch',True),
        ('offloadId',str(uuid.uuid4())),('startDeadline',fixture.authority['expiresAt']),
        ('admittedAt',fixture.authority['startDeadline']),('admittedAt','2000-01-01T00:00:00Z'),
        ('admittedAt','2020-01-01T00:00:00+01:00'),('expiresAt',fixture.authority['startDeadline']),
        ('apiVersion','unsupported'),('unknown',True)]
    for field,value in variants:
        def altered(authority,*args):
            changed=copy.deepcopy(authority);changed[field]=value
            return original_validate(changed,*args)
        with patch.object(starts,'validate',altered):
            try:workflows.prepare(pg,opts())
            except Blocked:pass
            else:raise AssertionError('Altered start identity/authority accepted: '+field)
        assert fingerprints(db)==pristine
    passed('fourteen-altered-authority-observations-block-identity-work-lease-deadline-schema-and-admission-conflicts')

    # A real missing backup entry/object is uncertainty, not evidence of timeout.
    store=Storage(SimpleNamespace(certificate_sha256=fixture.pin,timeout=30))
    item=record['object'];path='/'+fixture.bucket+'/'+item['key']
    manifest=json.loads((fixture.bundle/'manifest.json').read_text())
    empty=fixture.work/'missing-backup';empty.mkdir(mode=0o700)
    durable_json(empty/'manifest.json',{**manifest,'versions':[]})
    code,headers,_=store.request('DELETE',path);assert code==204
    marker=header(headers,'x-amz-version-id')
    missing=opts();missing.runtime_start_backup=empty
    missing_plan=workflows.prepare(pg,missing)
    assert {'operationId':operation,'reason':'KUBERNETES_START_AUTHORITY_NOT_PROVEN'} in missing_plan['unresolvedOffloads']
    assert not any(e.get('operationId')==operation for e in missing_plan['entries'])
    try:workflows.prepare(pg,opts())
    except Blocked:pass
    else:raise AssertionError('Delete marker hid captured start authority')
    assert store.request('DELETE',path,{'versionId':marker})[0]==204
    assert fingerprints(db)==pristine
    passed('real-delete-marker-and-missing-journal-never-invent-a-target-start-timeout')

    for table,rid,field,value in [('task_offload',operation,'start_deadline',
            (datetime.fromisoformat(fixture.authority['startDeadline'])+timedelta(seconds=1)).isoformat()),
            ('task',target['task'],'cancellation_reason','RUN_CANCELLED')]:
        old=row(table,rid)[field]
        pg.sql('UPDATE edgeai.'+table+' SET '+field+'='+literal(value)+' WHERE id='+q(rid),db)
        changed=fingerprints(db)
        try:workflows.prepare(pg,opts())
        except Blocked:pass
        else:raise AssertionError('Actual original deadline or cancellation conflict accepted')
        assert fingerprints(db)==changed
        pg.sql('UPDATE edgeai.'+table+' SET '+field+'='+('NULL' if old is None else literal(old))+' WHERE id='+q(rid),db)
    newer=str(uuid.uuid4())
    pg.sql('INSERT INTO edgeai.task_attempt SELECT (jsonb_populate_record(NULL::edgeai.task_attempt,to_jsonb(a)||'+
        literal(json.dumps({'id':newer,'number':3,'epoch':3,'cause':'RETRY','state':'FAILED'}))+'::jsonb)).* '
        'FROM edgeai.task_attempt a WHERE id='+q(target['attempt']),db)
    newer_plan=workflows.prepare(pg,opts())
    assert {'operationId':operation,'reason':'NEWER_ATTEMPT_RECORDED'} in newer_plan['unresolvedOffloads']
    assert not any(e.get('operationId')==operation for e in newer_plan['entries'])
    pg.sql('DELETE FROM edgeai.task_attempt WHERE id='+q(newer),db)
    assert fingerprints(db)==pristine
    passed('actual-deadline-cancellation-and-newer-attempt-rows-prevent-an-older-admission-from-overriding-history')

    # Change actual latest S3 version after preparation, retaining original bytes.
    code,_,raw=store.request('GET',path,{'versionId':item['versionId']},max_bytes=8192);assert code==200
    code,headers,_=store.request('PUT',path,body=raw,extra={'content-type':starts.MEDIA_TYPE});assert code==200
    replacement=header(headers,'x-amz-version-id');a.output.mkdir(mode=0o700)
    try:workflows.apply(pg,a,plan)
    except Blocked:pass
    else:raise AssertionError('Replacement start version was adopted')
    assert not (a.output/'intent.json').exists() and fingerprints(db)==pristine
    assert store.request('DELETE',path,{'versionId':replacement})[0]==204
    passed('another-real-version-with-identical-authority-cannot-replace-the-captured-start-version')

    original_call=pg.call
    def racing(tool,arguments,*args,**kwargs):
        if arguments[-2:]==['-f','-']:
            pg.sql("UPDATE edgeai.task_offload SET start_deadline=start_deadline+interval '1 second' WHERE id="+q(operation),db)
        return original_call(tool,arguments,*args,**kwargs)
    a=opts();a.output.mkdir(mode=0o700);plan=workflows.prepare(pg,a)
    with patch.object(pg,'call',racing):
        try:workflows.apply(pg,a,plan)
        except RuntimeError:pass
        else:raise AssertionError('Concurrent original deadline change escaped guard')
    pg.sql('UPDATE edgeai.task_offload SET start_deadline='+literal(fixture.authority['startDeadline'])+' WHERE id='+q(operation),db)
    assert fingerprints(db)==pristine
    passed('actual-concurrent-db-change-after-observation-prevents-the-whole-reconciliation-transaction')

    pg.sql("CREATE FUNCTION edgeai.reject_start_recovery() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned final write fault'; END $$; "
        'CREATE TRIGGER reject_start_recovery BEFORE UPDATE ON edgeai.task_offload FOR EACH ROW EXECUTE FUNCTION edgeai.reject_start_recovery()',db)
    a=opts();a.output.mkdir(mode=0o700)
    try:workflows.apply(pg,a,workflows.prepare(pg,a))
    except RuntimeError:pass
    else:raise AssertionError('Start recovery write fault was not observed')
    pg.sql('DROP TRIGGER reject_start_recovery ON edgeai.task_offload; DROP FUNCTION edgeai.reject_start_recovery()',db)
    assert fingerprints(db)==pristine
    passed('actual-write-failure-rolls-back-the-admission-reconciliation-without-changing-any-table')

    before=row('task_offload',operation);a=opts()
    command=[sys.executable,'scripts/recovery_kubernetes_workflows.py']
    for key,value in vars(a).items():
        if value is True:command+=['--'+key.replace('_','-')]
        elif value is not None and value is not False:command+=['--'+key.replace('_','-'),str(value)]
    response=subprocess.run(command,capture_output=True,timeout=180)
    with private_file(fixture.work/'workflow-cli.log') as log:log.write(response.stdout+response.stderr)
    assert response.returncode==0,'Start journal recovery CLI failed; private evidence retained'
    completed=json.loads((a.output/'workflows.json').read_text());after=fingerprints(db)
    assert completed['offloadsCompleted']==1 and not completed['activated'] and not completed['globalQuiescenceProven']
    assert {t for t in pristine if pristine[t]!=after[t]}=={'task_offload'}
    assert row('task_offload',operation)['state']=='SUCCEEDED'
    assert all(row('task_offload',operation)[k]==v for k,v in before.items() if k not in ('state','updated_at'))
    assert fingerprints(fixture.targets[1][0])==other
    a=opts();a.output.mkdir(mode=0o700)
    assert not workflows.apply(pg,a,workflows.prepare(pg,a))['databaseModified'] and fingerprints(db)==after
    passed('only-original-transfer-admission-completes-with-42-tables-claims-attempts-results-and-replay-unchanged')

    def lost_reply(tool,arguments,*args,**kwargs):
        result=original_call(tool,arguments,*args,**kwargs)
        if arguments[-2:]==['-f','-']:raise OSError('Injected actual COMMIT reply loss')
        return result
    a=opts(1);a.output.mkdir(mode=0o700);plan=workflows.prepare(pg,a)
    with patch.object(pg,'call',lost_reply):
        try:workflows.apply(pg,a,plan)
        except OSError:pass
        else:raise AssertionError('Actual start recovery COMMIT reply loss not injected')
    assert (a.output/'intent.json').exists() and not (a.output/'workflows.json').exists()
    a=opts(1);a.output.mkdir(mode=0o700)
    assert not workflows.apply(pg,a,workflows.prepare(pg,a))['databaseModified']
    passed('actual-commit-reply-loss-recovers-with-original-start-version-and-zero-duplicate-writes')
    report.update(runtimeStartJournalFixtures=1,
        runtimeStartAuthoritySource='ACTUAL_API_WITH_KUBERNETES_TOKENREVIEW' if fixture.api_claim else 'EXPLICIT_ADMISSION_FIXTURE',
        runtimeStartBackupSourcesRemoved=True,runtimeStartRestoredDatabases=2,runtimeStartOffloadsCompleted=1,
        runtimeStartResultsCreated=0,runtimeStartClaimsCreated=0,runtimeStartPreservedTables=42,
        runtimeStartAlteredObservations=len(variants))
    if fixture.api_claim:
        assert fixture.api_claim_verified and fixture.api_claim_source_stopped
        report.update(runtimeStartApiClaimsVerified=True,runtimeStartSourceApiStopped=True)
        passed('actual-tls-api-and-kubernetes-tokenreview-generate-the-original-start-journal-consumed-by-restored-transfer-recovery')
