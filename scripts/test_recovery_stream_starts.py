"""Original group admissions from the actual TLS API, retained Pods and independent S3 backup."""
from copy import deepcopy
import json
import os
import shutil
import subprocess
import sys
from types import SimpleNamespace
from unittest.mock import patch
import uuid

from postgres_backup import Blocked, Postgres, backup, restore, identifier, literal, private_file
from recovery_remote_retire import durable_json
from recovery_remote_storage import Storage, header
import recovery_runtime_starts as starts
import recovery_stream_workflows as workflows
import recovery_kubernetes_retire as producers
import recovery_stream_retire as routes
from storage_backup import Client, backup as storage_backup
from test_recovery_runtime_starts import q
from test_runtime_start_api import admit


def seed(fixture,pg,source_db,targets,operation,transport,kube,create,namespace,remember,drop):
    # A fresh owned source is allowed to serve the API; restored quarantined
    # databases never do. The underlying placement/work history is a fixture.
    source='edgeai_backup_group_start_'+uuid.uuid4().hex
    pg.sql('CREATE DATABASE '+identifier(source)+' TEMPLATE '+identifier(source_db),'postgres');remember(source)
    assert pg.sql("SELECT shobj_description(oid,'pg_database') IS NULL FROM pg_database WHERE datname=current_database()",source)=='t'
    ids=','.join(q(t['attempt']) for t in targets)
    pg.sql("BEGIN; UPDATE edgeai.task_attempt SET created_at=transaction_timestamp(),updated_at=transaction_timestamp() WHERE id IN ("+ids+
        "); UPDATE edgeai.task_offload SET start_timeout_seconds=90,start_deadline=transaction_timestamp()+interval '90 seconds',updated_at=transaction_timestamp() WHERE id="+q(operation)+
        "; UPDATE edgeai.runtime_instance SET expires_at=transaction_timestamp()+interval '1 hour' WHERE attempt_id IN ("+ids+
        "); UPDATE edgeai.task SET state='RUNNING' WHERE id IN ("+','.join(q(t['task']) for t in targets)+'); COMMIT',source)
    snapshot=fixture.work/'before-admission';backup(pg,source,snapshot)
    origin=fixture.storage('origin');fixture.storage('replica')
    fixture.bundle=fixture.work/'storage-backup';fixture.bundle.mkdir(mode=0o700)
    fixture.authorities={};fixture.group_targets=targets;fixture.operation=operation
    with patch.dict(os.environ,fixture.environment):
        client=Client(fixture.bundle,source=True)
        client.call(['mb','origin/'+fixture.bucket]);client.call(['version','enable','origin/'+fixture.bucket])
        contexts=json.loads(pg.sql(starts.QUERY,source))+json.loads(pg.sql(starts.context_query(vd=True),source))
        for i,target in enumerate(targets):
            context=next(c for c in contexts if c['runtime']['id']==target['runtime'])
            pod=next(p for p in kube.items(namespace,'Pod')[0] if p['metadata']['uid']==target['pod'])
            if target.get('vd'):
                pg.sql("UPDATE edgeai.vd_runtime SET lease_until=now()+interval '60 seconds' WHERE id="+q(target['vd_runtime']),source)
            fixture.authorities[target['runtime']]=admit(fixture,pg,source,context,pod,kube,create,namespace,
                offload_state='STARTING' if i<len(targets)-1 else 'SUCCEEDED')
        storage_backup(client,[fixture.bucket],120)
    origin.terminate();origin.wait(timeout=15);shutil.rmtree(fixture.work/'origin-data');drop(source)
    fixture.targets=[]
    for _ in range(3):
        db='edgeai_restore_group_start_'+uuid.uuid4().hex
        restoring=Postgres(transport,diagnostics=fixture.work/('restore-'+uuid.uuid4().hex))
        restore(restoring,snapshot,db);remember(db);fixture.targets.append((db,restoring.directory/'restore-report.json'))


def check(pg,fixture,options,fingerprints,refused,passed,report,transport):
    with patch.dict(os.environ,fixture.environment):
        exercise(pg,fixture,options,fingerprints,refused,passed,report,transport)


def exercise(pg,fixture,options,fingerprints,refused,passed,report,transport):
    operation=fixture.operation;members=fixture.group_targets
    def opts(index=0,**changes):
        values=dict(unclaimed_jobs=True,offloads=True,runtime_start_backup=fixture.bundle,
            runtime_start_bucket=fixture.bucket,runtime_start_certificate_sha256=fixture.pin)
        values.update(changes);return options(fixture.targets[index],**values)
    def apply(index=0,plan=None,**changes):
        args=opts(index,**changes);args.output.mkdir(mode=0o700)
        return workflows.apply(pg,args,plan or workflows.prepare(pg,args))
    def row(table,rid,index=0):
        return json.loads(pg.sql('SELECT to_jsonb(t) FROM edgeai.'+table+' t WHERE id='+q(rid),fixture.targets[index][0]))
    for i,target in enumerate(fixture.targets):
        args=opts(i);args.output.mkdir(mode=0o700);producers.apply(pg,args,producers.prepare(pg,args))
        args=opts(i);args.output.mkdir(mode=0o700);routes.apply(pg,args,routes.prepare(pg,args))
    db=fixture.targets[0][0];pristine=fingerprints(db);plan=workflows.prepare(pg,opts())
    assert plan['groups']==[{'taskIds':sorted(t['task'] for t in members),'operationId':operation,'action':'COMPLETE_STREAM_OFFLOAD'}]
    records={rid:record for kind in plan['runtimeStartEvidence'].values() for rid,record in kind['records'].items()}
    assert {rid:r['authority'] for rid,r in records.items()}==fixture.authorities
    assert len(records)==2 and fixture.api_claim_verified and fixture.api_claim_source_stopped
    assert not (fixture.work/'origin-data').exists() and fingerprints(db)==pristine
    passed('both-original-stream-admissions-from-real-tls-api-and-tokenreview-survive-source-db-and-storage-removal')

    result=apply(runtime_start_backup=None,runtime_start_bucket=None,runtime_start_certificate_sha256=None)
    assert not result['databaseModified'] and result['unresolvedGroups'][0]['reason']=='STREAM_GROUP_START_AUTHORITY_NOT_PROVEN'
    assert fingerprints(db)==pristine
    passed('omitting-start-inspection-cannot-turn-a-restored-starting-stream-group-into-a-timeout')

    # Storage's deadline belongs to one observation, not the full integration
    # suite. Each independent fixture mutation gets its own bounded client.
    def storage():return Storage(SimpleNamespace(certificate_sha256=fixture.pin,timeout=30))
    peer=members[1];record=records[peer['runtime']];item=record['object'];path='/'+fixture.bucket+'/'+item['key']
    manifest=json.loads((fixture.bundle/'manifest.json').read_text())
    missing=fixture.work/'one-missing';missing.mkdir(mode=0o700)
    durable_json(missing/'manifest.json',{**manifest,'versions':[v for v in manifest['versions'] if v['key']!=item['key']]})
    code,headers,_=storage().request('DELETE',path);assert code==204;marker=header(headers,'x-amz-version-id')
    result=apply(runtime_start_backup=missing)
    assert not result['databaseModified'] and not result['groups'] and result['unresolvedGroups'][0]['reason']=='STREAM_GROUP_START_AUTHORITY_NOT_PROVEN'
    refused(lambda:workflows.prepare(pg,opts()),Blocked)
    assert storage().request('DELETE',path,{'versionId':marker})[0]==204 and fingerprints(db)==pristine
    passed('one-real-missing-peer-journal-blocks-the-entire-group-without-adopting-a-delete-marker-or-partial-success')

    import recovery_vd_starts as vd_starts
    validator=vd_starts if peer.get('vd') else starts
    original=validator.validate
    variants=[('allocationId' if peer.get('vd') else 'jobUid',str(uuid.uuid4())),('workDigest','sha256:'+'0'*64),('offloadId',str(uuid.uuid4())),
        ('admittedAt',record['authority']['startDeadline']),('startDeadline',record['authority']['expiresAt'])]
    if peer.get('vd'):
        variants += [('vdRuntimeId',str(uuid.uuid4())),('generation',1),('sessionId',str(uuid.uuid4())),
            ('slot',2),('assignedSequence',1),('configurationDigest','sha256:'+'0'*64),
            ('supervisorLeaseUntil',record['authority']['admittedAt'])]
    for field,value in variants:
        def altered(authority,*args):
            modified=deepcopy(authority)
            if modified['runtimeId']==peer['runtime']:modified[field]=value
            return original(modified,*args)
        with patch.object(validator,'validate',altered):refused(lambda:workflows.prepare(pg,opts()),Blocked)
        assert fingerprints(db)==pristine
    passed('peer-identity-work-operation-and-original-deadline-conflicts-prevent-group-completion')

    pg.sql("UPDATE edgeai.task SET cancellation_reason='RUN_CANCELLED' WHERE id="+q(peer['task']),db)
    changed=fingerprints(db);refused(lambda:workflows.prepare(pg,opts()),Blocked);assert fingerprints(db)==changed
    pg.sql('UPDATE edgeai.task SET cancellation_reason=NULL WHERE id='+q(peer['task']),db)
    newer=str(uuid.uuid4())
    pg.sql('INSERT INTO edgeai.task_attempt SELECT (jsonb_populate_record(NULL::edgeai.task_attempt,to_jsonb(a)||'+
        literal(json.dumps({'id':newer,'number':3,'epoch':3,'cause':'RETRY','state':'FAILED'}))+'::jsonb)).* FROM edgeai.task_attempt a WHERE id='+q(peer['attempt']),db)
    changed=fingerprints(db);result=apply()
    assert not result['databaseModified'] and result['unresolvedGroups'][0]['reason']=='NEWER_STREAM_TRANSFER_ATTEMPT_RECORDED' and fingerprints(db)==changed
    pg.sql('DELETE FROM edgeai.task_attempt WHERE id='+q(newer),db);assert fingerprints(db)==pristine
    passed('recorded-cancellation-and-newer-peer-attempt-cannot-be-overwritten-by-older-group-admissions')

    code,_,raw=storage().request('GET',path,{'versionId':item['versionId']},max_bytes=8192);assert code==200
    code,headers,_=storage().request('PUT',path,body=raw,extra={'content-type':validator.MEDIA_TYPE});assert code==200
    replacement=header(headers,'x-amz-version-id');args=opts();args.output.mkdir(mode=0o700)
    refused(lambda:workflows.apply(pg,args,plan),Blocked)
    assert not (args.output/'intent.json').exists() and fingerprints(db)==pristine
    assert storage().request('DELETE',path,{'versionId':replacement})[0]==204
    passed('replacement-version-of-one-peer-invalidates-the-whole-plan-even-with-identical-start-bytes')

    call=pg.call
    def race(tool,arguments,*a,**kw):
        if arguments[-2:]==['-f','-']:
            pg.sql("UPDATE edgeai.task_offload SET start_deadline=start_deadline+interval '1 second' WHERE id="+q(operation),db)
        return call(tool,arguments,*a,**kw)
    with patch.object(pg,'call',race):refused(lambda:apply(),RuntimeError)
    pg.sql('UPDATE edgeai.task_offload SET start_deadline='+literal(record['authority']['startDeadline'])+' WHERE id='+q(operation),db)
    assert fingerprints(db)==pristine
    passed('real-db-race-after-external-observation-aborts-the-entire-group-transaction')

    transaction=workflows.transaction_sql
    with patch.object(workflows,'transaction_sql',side_effect=lambda p:transaction(p).replace('SET CONSTRAINTS ALL IMMEDIATE;','SELECT 1/0; SET CONSTRAINTS ALL IMMEDIATE;')):
        refused(lambda:apply(),RuntimeError)
    assert fingerprints(db)==pristine
    passed('actual-final-sql-failure-rolls-back-group-admission-with-all-original-history-intact')

    before=row('task_offload',operation);args=opts()
    command=[sys.executable,'scripts/recovery_stream_workflows.py','--transport',transport]
    for key,value in vars(args).items():
        if value is True:command+=['--'+key.replace('_','-')]
        elif value is not None and value is not False:command+=['--'+key.replace('_','-'),str(value)]
    response=subprocess.run(command,capture_output=True,timeout=240)
    with private_file(fixture.work/'group-cli.log') as log:log.write(response.stdout+response.stderr)
    assert response.returncode==0,'Original STREAM group admission CLI failed; private diagnostics retained'
    result=json.loads((args.output/'workflows.json').read_text());after=fingerprints(db)
    assert result['offloadsCompleted']==1 and result['databaseModified'] and not result['activated'] and not result['globalQuiescenceProven']
    assert {table for table in pristine if pristine[table]!=after[table]}=={'task_offload'}
    current=row('task_offload',operation);assert current['state']=='SUCCEEDED'
    assert all(current[k]==v for k,v in before.items() if k not in ('state','updated_at'))
    assert not apply()['databaseModified'] and fingerprints(db)==after
    passed('actual-cli-completes-only-original-group-operation-and-replay-preserves-claims-attempts-checkpoints-routes-and-results')

    def lost(tool,arguments,*a,**kw):
        result=call(tool,arguments,*a,**kw)
        if arguments[-2:]==['-f','-']:raise OSError('Injected actual group COMMIT reply loss')
        return result
    with patch.object(pg,'call',lost):refused(lambda:apply(1),OSError)
    assert row('task_offload',operation,1)['state']=='SUCCEEDED' and not apply(1)['databaseModified']
    passed('actual-commit-reply-loss-reobserves-original-group-admissions-without-duplicate-history')

    changed_versions=[]
    def changed_after(tool,arguments,*a,**kw):
        result=call(tool,arguments,*a,**kw)
        if arguments[-2:]==['-f','-']:
            code,headers,_=storage().request('PUT',path,body=raw,extra={'content-type':validator.MEDIA_TYPE});assert code==200
            changed_versions.append(header(headers,'x-amz-version-id'))
        return result
    args=opts(2);args.output.mkdir(mode=0o700);plan=workflows.prepare(pg,args)
    with patch.object(pg,'call',changed_after):
        try:workflows.apply(pg,args,plan)
        except Blocked as error:blocked_reason=str(error)
        else:raise AssertionError('Post-commit replacement was accepted')
    diagnostic={'blockedReason':blocked_reason,'replacements':len(changed_versions),
        'intentRetained':(args.output/'intent.json').exists(),'successReportPresent':(args.output/'workflows.json').exists()}
    durable_json(fixture.work/'post-commit-observation.json',diagnostic)
    assert diagnostic['intentRetained'] and not diagnostic['successReportPresent'],str(diagnostic)
    assert len(changed_versions)==1,str(diagnostic)
    delete_status=storage().request('DELETE',path,{'versionId':changed_versions[0]})[0]
    assert delete_status==204,'Replacement removal returned HTTP '+str(delete_status)
    assert not apply(2)['databaseModified']
    passed('post-commit-peer-version-change-withholds-success-and-retains-quarantine-until-original-evidence-is-rechecked')
    report.update(streamStartAuthoritySource='ACTUAL_TLS_API_AND_POD_BOUND_TOKENREVIEW',streamStartMembers=2,
        streamStartRestoredDatabases=3,streamStartPreservedTables=len(pristine)-1,streamStartOffloadsCompleted=1,
        streamStartClaimsCreated=0,streamStartResultsCreated=0,streamStartSourcesRemoved=True,streamStartAlteredObservations=len(variants))
    report['streamStartVdPeers']=sum(bool(t.get('vd')) for t in members)
