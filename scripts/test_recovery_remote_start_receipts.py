"""Actual TLS admission -> quarantined mixed transfer -> fixed S3 Result acceptance."""
import copy
from datetime import datetime, timedelta, timezone
import json
from types import SimpleNamespace
from unittest.mock import patch
import uuid

from postgres_backup import Blocked, literal
from recovery_remote_fence import Client
from recovery_remote_start_receipts import instant
import recovery_kubernetes_workflows as workflows
import recovery_remote_results as results
import recovery_remote_storage as storage


def q(value):return literal(value)+'::uuid'


def check(pg,db,options,cli,fingerprints,passed,fixture,row,report,result_args):
    pending=fixture['fixtures']['success-pending'];target=pending['target'];operation=pending['operation']
    provider=fixture['provider'];pristine=fingerprints(db);provider_before=provider.rows()
    original_operation=row('task_offload',operation)
    original_source=row('runtime_instance',pending['source']['runtime'])
    original_attempt=row('task_attempt',target['attempt'])
    def opts():return options(remote_start_receipts=True)
    a=opts();plan=workflows.prepare(pg,a)
    entries=[e for e in plan['entries'] if e.get('operationId')==operation]
    assert len(entries)==1 and entries[0]['action']=='COMPLETE_REMOTE_OFFLOAD'
    receipts=plan['remoteEvidence']['startReceipts'];receipt=receipts[target['allocation']]
    assert len(receipts)==6 and sum(v is not None for v in receipts.values())==1
    assert instant(receipt['acceptedAt'])<instant(original_operation['start_deadline'])<datetime.now(timezone.utc)
    assert fingerprints(db)==pristine
    passed('actual-timely-sqlite-start-receipt-proves-original-transfer-admission-after-its-deadline-has-passed')

    original_request=Client.request
    def intercepted(transform):
        def request(client,credential,*args,**kwargs):
            code,value=original_request(client,credential,*args,**kwargs)
            if '/'+target['allocation']+'/start-receipt?' in kwargs.get('path',''):
                assert code==200
                return transform(copy.deepcopy(value))
            return code,value
        return request
    with patch.object(Client,'request',intercepted(lambda value:(404,{'code':'START_RECEIPT_NOT_RECORDED'}))):
        missing=workflows.prepare(pg,opts())
        assert missing['unresolvedOffloads']==[{'operationId':operation,'reason':'REMOTE_TARGET_OUTCOME_REQUIRES_RECONCILIATION'}]
        assert not any(e.get('operationId')==operation for e in missing['entries'])
    passed('explicit-missing-start-receipt-retains-successful-transfer-as-unresolved-without-inventing-history')

    variants=[('unsupported-endpoint',lambda v:(404,{'code':'NOT_FOUND'}))]
    def changed(path,value):
        def change(document):
            target_value=document
            for key in path[:-1]:target_value=target_value[key]
            target_value[path[-1]]=value
            return 200,document
        return change
    for name,path,value in [
        ('provider',['providerId'],str(uuid.uuid4())),('recovery',['recoveryId'],str(uuid.uuid4())),
        ('attempt',['authority','identity','attemptId'],str(uuid.uuid4())),
        ('boolean-epoch',['authority','identity','epoch'],True),
        ('digest',['authority','requestDigest'],'sha256:'+'0'*64),
        ('lease',['authority','expiresAt'],(instant(receipt['authority']['expiresAt'])+timedelta(seconds=1)).isoformat()),
        ('operation',['authority','offloadId'],str(uuid.uuid4())),
        ('deadline',['authority','startDeadline'],(instant(receipt['authority']['startDeadline'])+timedelta(seconds=1)).isoformat()),
        ('late-admission',['acceptedAt'],receipt['authority']['startDeadline']),
        ('predates-operation',['acceptedAt'],(instant(original_operation['created_at'])-timedelta(seconds=1)).isoformat()),
        ('non-utc',['acceptedAt'],'2020-01-01T00:00:00+01:00'),
        ('unknown-schema',['extra'],True)]:
        variants.append((name,changed(path,value)))
    for name,transform in variants:
        with patch.object(Client,'request',intercepted(transform)):
            try:workflows.prepare(pg,opts())
            except Blocked:pass
            else:raise AssertionError('Invalid observed start evidence accepted: '+name)
        assert fingerprints(db)==pristine
    passed('unsupported-or-altered-start-evidence-refuses-provider-recovery-work-digest-lease-operation-deadline-and-schema-mismatches')

    def newer_admission(value):
        value['acceptedAt']=(instant(value['acceptedAt'])+timedelta(microseconds=1)).isoformat()
        return 200,value
    a.output.mkdir(mode=0o700)
    with patch.object(Client,'request',intercepted(newer_admission)):
        try:workflows.apply(pg,a,plan)
        except Blocked:pass
        else:raise AssertionError('Start evidence changed after prepare was accepted')
    assert fingerprints(db)==pristine and not (a.output/'intent.json').exists()
    passed('fresh-start-receipt-change-after-prepare-is-rejected-before-transaction-submission')

    # These are actual DB changes, distinct from the malformed HTTP observation fixtures above.
    for table,rid,field,value in [('task_offload',operation,'start_deadline',
            (instant(original_operation['start_deadline'])+timedelta(seconds=1)).isoformat()),
            ('task',target['task'],'cancellation_reason','RUN_CANCELLED')]:
        old=row(table,rid)[field]
        pg.sql('UPDATE edgeai.'+table+' SET '+field+'='+literal(value)+' WHERE id='+q(rid),db)
        changed_db=fingerprints(db)
        try:workflows.prepare(pg,opts())
        except Blocked:pass
        else:raise AssertionError('Changed original deadline or cancellation was ignored')
        assert fingerprints(db)==changed_db
        pg.sql('UPDATE edgeai.'+table+' SET '+field+'='+('NULL' if old is None else literal(old))+' WHERE id='+q(rid),db)
    assert fingerprints(db)==pristine
    passed('actual-original-deadline-change-and-recorded-cancellation-block-success-without-rewriting-history')

    newer=str(uuid.uuid4())
    pg.sql('INSERT INTO edgeai.task_attempt SELECT (jsonb_populate_record(NULL::edgeai.task_attempt,to_jsonb(a)||'+
        literal(json.dumps({'id':newer,'number':3,'epoch':3,'cause':'RETRY','state':'FAILED'}))+
        '::jsonb)).* FROM edgeai.task_attempt a WHERE id='+q(target['attempt']),db)
    changed_db=fingerprints(db);newer_plan=workflows.prepare(pg,opts())
    assert newer_plan['unresolvedOffloads']==[{'operationId':operation,'reason':'NEWER_ATTEMPT_RECORDED'}]
    assert not any(e.get('operationId')==operation for e in newer_plan['entries']) and fingerprints(db)==changed_db
    pg.sql('DELETE FROM edgeai.task_attempt WHERE id='+q(newer),db)
    assert fingerprints(db)==pristine
    passed('a-real-newer-attempt-row-blocks-older-success-even-when-its-original-admission-receipt-is-valid')

    original_call=pg.call
    def racing(tool,arguments,*args,**kwargs):
        if arguments[-2:]==['-f','-']:
            pg.sql("UPDATE edgeai.task_offload SET start_deadline=start_deadline+interval '1 second' WHERE id="+q(operation),db)
        return original_call(tool,arguments,*args,**kwargs)
    a=opts();a.output.mkdir(mode=0o700);plan=workflows.prepare(pg,a)
    with patch.object(pg,'call',racing):
        try:workflows.apply(pg,a,plan)
        except RuntimeError:pass
        else:raise AssertionError('Transfer race escaped the transaction guard')
    pg.sql('UPDATE edgeai.task_offload SET start_deadline='+literal(original_operation['start_deadline'])+' WHERE id='+q(operation),db)
    assert fingerprints(db)==pristine and not (a.output/'workflows.json').exists()
    passed('actual-transfer-write-after-final-observation-rolls-back-the-whole-guarded-success-transaction')

    pg.sql("CREATE FUNCTION edgeai.reject_start_recovery() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned success write fault'; END $$; "
        'CREATE TRIGGER reject_start_recovery AFTER UPDATE ON edgeai.task_offload FOR EACH ROW EXECUTE FUNCTION edgeai.reject_start_recovery()',db)
    a=opts();a.output.mkdir(mode=0o700);plan=workflows.prepare(pg,a)
    try:workflows.apply(pg,a,plan)
    except RuntimeError:pass
    else:raise AssertionError('Actual success write error was not observed')
    pg.sql('DROP TRIGGER reject_start_recovery ON edgeai.task_offload; DROP FUNCTION edgeai.reject_start_recovery()',db)
    assert fingerprints(db)==pristine and not (a.output/'workflows.json').exists()
    passed('actual-late-transfer-write-error-preserves-all-44-tables-and-original-admission-receipt')

    a=opts();a.output.mkdir(mode=0o700);plan=workflows.prepare(pg,a);committed={}
    def lost_reply(tool,arguments,*args,**kwargs):
        value=original_call(tool,arguments,*args,**kwargs)
        if arguments[-2:]==['-f','-']:committed.update(json.loads(value));raise OSError('Injected success COMMIT reply loss')
        return value
    with patch.object(pg,'call',lost_reply):
        try:workflows.apply(pg,a,plan)
        except OSError:pass
        else:raise AssertionError('Actual success COMMIT reply loss not injected')
    assert committed['offloadsCompleted']==1 and all(v==0 for k,v in committed.items() if k not in ('offloadsCompleted','afterGuard'))
    after=fingerprints(db);assert {t for t in pristine if pristine[t]!=after[t]}=={'task_offload'}
    assert (a.output/'intent.json').exists() and not (a.output/'workflows.json').exists()
    completed=row('task_offload',operation)
    assert completed['state']=='SUCCEEDED' and all(completed[k]==v for k,v in original_operation.items() if k not in ('state','updated_at'))
    assert row('runtime_instance',pending['source']['runtime'])==original_source and row('task_attempt',target['attempt'])==original_attempt
    replay=cli(opts());assert not replay['databaseModified'] and replay['offloadsCompleted']==0 and not replay['activated']
    assert replay['remoteEvidence']['startReceipts']==receipts and fingerprints(db)==after and provider.rows()==provider_before
    passed('actual-success-commit-reply-loss-replays-with-zero-changes-and-preserves-43-tables-original-deadline-and-stopped-producers')

    # Only after authority reconciliation may the existing fixed-version S3 protocol commit a Result.
    a=result_args;store=storage.Storage(a);plan=results.prepare(pg,store,a)
    completed_result=results.apply(pg,store,a,plan)
    assert completed_result['resultsCreated']==1 and not completed_result['activated']
    assert row('task',target['task'])['state']=='SUCCEEDED' and row('task_attempt',target['attempt'])['state']=='SUCCEEDED'
    assert row('task_offload',operation)==completed and row('runtime_instance',pending['source']['runtime'])==original_source
    final=fingerprints(db)
    assert final['task_retry']==after['task_retry'] and final['remote_allocation']==after['remote_allocation']
    again=SimpleNamespace(**vars(a));again.output=a.output.parent/('start-result-replay-'+uuid.uuid4().hex);again.output.mkdir(mode=0o700)
    replay=results.apply(pg,storage.Storage(again),again,results.prepare(pg,storage.Storage(again),again))
    assert not replay['databaseModified'] and replay['resultsCreated']==0 and fingerprints(db)==final and provider.rows()==provider_before
    passed('actual-fixed-version-s3-result-commits-once-after-start-authority-reconciliation-without-new-executions-or-activation')
    report.update(remoteStartReceiptsVerified=True,remoteStartReceiptsObserved=1,remoteStartMissingReceipts=5,
        remoteStartObservationRejections=len(variants),remoteStartTransfersCompleted=1,remoteStartResultsCreated=1,
        remoteStartPreservedTables=43,remoteStartActivation=False,mixedRemoteSuccessesPending=0)
