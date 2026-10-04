"""Actual Remote failure budgets and fixed S3 outputs under an unfinished transfer."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
from types import SimpleNamespace
import urllib.request
import uuid

from postgres_backup import Blocked, ROOT, literal, private_file
import recovery_kubernetes_workflows as workflows
import recovery_remote_outputs as outputs
import recovery_remote_results as results
import recovery_remote_storage as storage
from storage_backup import Client, backup as storage_backup


def q(value):return literal(value)+'::uuid'


@contextmanager
def owned_storage(work,fixture,report):
    directory=work/'mixed-outcome-storage';directory.mkdir(mode=0o700)
    provider=fixture['provider'];previous=os.environ.copy();processes=[]
    def command(args,body=None):
        value=subprocess.run(list(map(str,args)),input=body,capture_output=True,timeout=180)
        with private_file(directory/(uuid.uuid4().hex+'.log')) as log:log.write(value.stdout+value.stderr)
        assert value.returncode==0,'Owned outcome storage command failed; private diagnostics retained'
        return value
    def mc(*args,body=None):return command([ROOT/'.tools/mc','--json',*args],body)
    def start(name):
        certs=directory/(name+'-certs');certs.mkdir(mode=0o700)
        shutil.copyfile(provider.root/'cert.pem',certs/'public.crt')
        shutil.copyfile(provider.root/'key.pem',certs/'private.key');(certs/'private.key').chmod(0o600)
        (certs/'CAs').mkdir();shutil.copyfile(provider.root/'cert.pem',certs/'CAs/root.crt')
        with socket.socket() as one,socket.socket() as two:
            one.bind(('127.0.0.1',0));two.bind(('127.0.0.1',0));port,console=one.getsockname()[1],two.getsockname()[1]
        user,password='mixed-recovery',secrets.token_hex(24);origin='https://localhost:'+str(port)
        os.environ['MC_HOST_'+name]='https://'+user+':'+password+'@localhost:'+str(port)
        prefix='EDGEAI_BACKUP_SOURCE_' if name=='origin' else 'EDGEAI_BACKUP_STORAGE_'
        os.environ.update({prefix+'URL':origin,prefix+'USER':user,prefix+'PASSWORD':password})
        with private_file(directory/(name+'.log')) as log:
            process=subprocess.Popen([str(fixture['minioBinary'].resolve()),'server',str(directory/(name+'-data')),
                '--address','127.0.0.1:'+str(port),'--console-address','127.0.0.1:'+str(console),'--certs-dir',str(certs),'--quiet'],
                stdout=log,stderr=log,env={**os.environ,'MINIO_ROOT_USER':user,'MINIO_ROOT_PASSWORD':password})
        processes.append(process);deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            assert process.poll() is None,'Owned outcome MinIO exited'
            try:
                with urllib.request.urlopen(origin+'/minio/health/live',context=provider.tls,timeout=1) as response:
                    if response.status==200:return process
            except OSError:time.sleep(.1)
        raise AssertionError('Owned outcome MinIO readiness timed out')
    try:
        os.environ.update(MC_CONFIG_DIR=str(directory/'mc'),MC_NO_COLOR='1',MC_DISABLE_PAGER='1',EDGEAI_BACKUP_CA_FILE=str(provider.root/'cert.pem'))
        ca=directory/'mc/certs/CAs';ca.mkdir(parents=True);shutil.copyfile(provider.root/'cert.pem',ca/'root.crt')
        original=start('origin');start('replica')
        bucket='edgeai-mixed-outcome-test';mc('mb','origin/'+bucket);mc('version','enable','origin/'+bucket)
        mc('pipe','origin/'+bucket+'/retained.bin',body=b'original mixed recovery bytes')
        backup=directory/'backup';backup.mkdir(mode=0o700);storage_backup(Client(backup,source=True),[bucket],120)
        original.terminate();original.wait(timeout=15)
        report['mixedOutcomeMinioSha256']=hashlib.sha256(fixture['minioBinary'].read_bytes()).hexdigest()
        yield bucket,backup
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=15)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
        report['ownedOutcomeStorageStopped']=all(p.poll() is not None for p in processes)
        os.environ.clear();os.environ.update(previous)


def check(pg,db,receipt,options,cli,fingerprints,passed,work,fixture,row,report):
    failures=fixture['failureFixtures'];namespace=options().namespace
    for value in failures.values():
        source,target=value['source'],value['target']
        pg.sql('INSERT INTO edgeai.task_offload(id,task_id,run_id,source_attempt_id,target_attempt_id,idempotency_key,request_digest,namespace,state,drain_deadline,'
            'start_timeout_seconds,start_deadline,remote_provider_key,remote_configuration_digest,remote_source_mode,created_at,updated_at) VALUES ('+
            ','.join(q(v) for v in (value['operation'],source['task'],source['run'],source['attempt'],target['attempt'],str(uuid.uuid4())))+','+
            literal('sha256:'+'1'*64)+','+literal(namespace)+",'STARTING',now()-interval '2 minutes',60,now()-interval '1 second','reference',"+
            literal(fixture['bindingDigest'])+",'SYNTHETIC',now()-interval '3 minutes',now())",db)
    pristine=fingerprints(db);a=options();a.output.mkdir(mode=0o700);plan=workflows.prepare(pg,a)
    entries=[e for e in plan['entries'] if e.get('operationId') in {f['operation'] for f in failures.values()}]
    assert len(entries)==2 and all(e['action']=='FAIL_REMOTE_OFFLOAD' and e['reason']=='WORKLOAD_FAILED' for e in entries)
    passed('actual-terminal-remote-failure-takes-precedence-over-an-expired-transfer-start-deadline')
    old_sources={name:row('runtime_instance',v['source']['runtime']) for name,v in failures.items()}
    pg.sql("CREATE FUNCTION edgeai.reject_mixed_retry() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned retry write fault'; END $$; "
        'CREATE TRIGGER reject_mixed_retry BEFORE INSERT ON edgeai.task_retry FOR EACH ROW EXECUTE FUNCTION edgeai.reject_mixed_retry()',db)
    try:workflows.apply(pg,a,plan)
    except RuntimeError:pass
    else:raise AssertionError('Remote target retry error was not observed')
    pg.sql('DROP TRIGGER reject_mixed_retry ON edgeai.task_retry; DROP FUNCTION edgeai.reject_mixed_retry()',db)
    assert fingerprints(db)==pristine and not (a.output/'workflows.json').exists()
    passed('real-retry-insert-error-rolls-back-target-failure-operation-and-task-in-one-transaction')
    a=options();a.output.mkdir(mode=0o700);plan=workflows.prepare(pg,a);original=pg.call;committed={}
    def lost_reply(tool,arguments,*positional,**keywords):
        result=original(tool,arguments,*positional,**keywords)
        if arguments[-2:]==['-f','-']:committed.update(json.loads(result));raise OSError('Injected target failure COMMIT reply loss')
        return result
    pg.call=lost_reply
    try:
        try:workflows.apply(pg,a,plan)
        except OSError:pass
        else:raise AssertionError('Actual target failure reply loss not injected')
    finally:pg.call=original
    assert committed['offloadsFailed']==2 and committed['attemptsFailed']==2 and committed['retriesScheduled']==1
    after=fingerprints(db);assert {t for t in pristine if pristine[t]!=after[t]}=={'runtime_instance','task_attempt','task','workflow_run','task_retry','task_offload'}
    replay=cli(options());assert not replay['databaseModified'] and replay['pendingRetries']==2 and fingerprints(db)==after
    for name,value in failures.items():
        assert row('task_offload',value['operation'])['failure_reason']=='TARGET_FAILED'
        assert row('runtime_instance',value['target']['runtime'])['failure_reason']=='WORKLOAD_FAILED'
        assert row('task_attempt',value['target']['attempt'])['state']=='FAILED'
        assert row('runtime_instance',value['source']['runtime'])==old_sources[name]
        assert row('task_attempt',value['source']['attempt'])['state']=='OFFLOADED'
        assert row('task',value['target']['task'])['state']==('FAILED' if name=='final' else 'RETRY_WAIT')
    retry=failures['retry']['target']
    assert pg.sql('SELECT q.deadline=first.created_at+make_interval(secs=>w.retry_max_elapsed_seconds) AND '
        'q.available_at=a.updated_at+make_interval(secs=>w.retry_backoff_seconds) AND '
        "(SELECT count(*) FROM edgeai.task_attempt WHERE task_id=t.id AND cause<>'OFFLOAD')=1 "
        'FROM edgeai.task_retry q JOIN edgeai.task t ON t.id=q.task_id JOIN edgeai.workflow_run w ON w.id=t.run_id '
        'JOIN edgeai.task_attempt a ON a.id=q.failed_attempt_id JOIN edgeai.task_attempt first ON first.task_id=t.id AND first.number=1 '
        'WHERE t.id='+q(retry['task']),db)=='t'
    passed('target-failure-reply-loss-retains-one-original-budget-retry-one-final-failure-and-38-other-tables-with-zero-change-replay')
    pg.sql("UPDATE edgeai.task_retry SET deadline=deadline+interval '1 second' WHERE task_id="+q(retry['task']),db)
    invalid=fingerprints(db);cli(options(),1);assert fingerprints(db)==invalid
    pg.sql("UPDATE edgeai.task_retry SET deadline=deadline-interval '1 second' WHERE task_id="+q(retry['task']),db)
    assert fingerprints(db)==after
    passed('a-recovered-offload-target-cannot-extend-the-original-task-retry-budget')

    a=SimpleNamespace(**vars(fixture['remote']),database=db,restore_report=receipt,output=work/('mixed-output-'+uuid.uuid4().hex))
    a.output.mkdir(mode=0o700);outputs.recover(pg,a);bundle=a.output
    assert outputs.verify(bundle)['allocations']==1
    with owned_storage(work,fixture,report) as (bucket,backup):
        publication=SimpleNamespace(bundle=bundle,storage_backup=backup,bucket=bucket,
            certificate_sha256=fixture['remote'].certificate_sha256,timeout=120,output=work/('mixed-publish-'+uuid.uuid4().hex))
        publication.output.mkdir(mode=0o700);storage.publish(storage.Storage(publication),publication)
        a=SimpleNamespace(**vars(publication));a.database=db;a.restore_report=receipt
        a.receipt=publication.output/'publication.json';a.output=work/('mixed-result-'+uuid.uuid4().hex);a.output.mkdir(mode=0o700)
        try:results.prepare(pg,storage.Storage(a),a)
        except Blocked as error:
            assert str(error)=='Active offload requires recorded start authority before result recovery'
        else:raise AssertionError('Active offload success was accepted without recorded start authority')
        assert fingerprints(db)==after
        pending=fixture['fixtures']['success-pending']
        assert row('task_offload',pending['operation'])['state']=='STARTING'
        assert row('task_attempt',pending['target']['attempt'])['state']=='DISPATCHING'
        assert row('task',pending['target']['task'])['state']=='RUNNING'
        assert not cli(options())['databaseModified'] and fingerprints(db)==after
        passed('actual-fixed-s3-success-cannot-bypass-an-active-offload-or-invent-missing-start-authority')
        if fixture.get('startReceipts'):
            from test_recovery_remote_start_receipts import check as check_starts
            check_starts(pg,db,options,cli,fingerprints,passed,fixture,row,report,a)
    report.update(mixedRemoteFailureOutcomesVerified=True,mixedRemoteTargetFailures=2,mixedRemoteTargetRetries=1,
        mixedRemoteActiveSuccessBlocked=True,mixedRemoteFailurePreservedTables=38)
