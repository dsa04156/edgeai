"""Historical completion import against real removed-source mixed NODE/VD fixtures."""
from copy import deepcopy
import json
from pathlib import Path
import subprocess
import sys
from unittest.mock import patch
import uuid

from postgres_backup import Blocked, literal, private_file
import recovery_stream_completions as completions
from recovery_stream_completion_history import same_receipt, instant


def check(pg, options, fingerprints, passed, report):
    source = options(0)
    identity = pg.sql('SELECT id FROM edgeai.stream_completion_publication',source.database)
    args = options(5,completion_id=identity,vd_tasks=True)
    db = args.database
    before = fingerprints(db)
    assert pg.sql('SELECT count(*) FROM edgeai.stream_checkpoint',db) == '0'
    assert pg.sql('SELECT count(*) FROM edgeai.stream_task_completion',db) == '0'
    plan = completions.prepare(pg,args)
    assert len(plan['changes']['checkpoints']) == 5
    assert plan['changes']['publication'] and len(plan['changes']['taskCompletions']) == 2
    assert len(plan['changes']['deviceCompletions']) == 1
    assert fingerprints(db) == before
    passed('original independent completion, all five receipts, admissions and live retirement proofs produce a read-only missing-history plan')

    def refused_sql(sql, expected, expected_state=None):
        offset=pg.log.stat().st_size
        try:pg.sql(sql,db)
        except RuntimeError:
            with pg.log.open('rb') as log:
                log.seek(offset)
                assert expected.encode() in log.read(),'Historical refusal had a different cause; private diagnostics retained'
        else:raise AssertionError('Invalid historical insertion transaction was accepted')
        assert fingerprints(db) == (before if expected_state is None else expected_state)

    sql = completions.transaction_sql(plan)
    variants = [
        ('the original transaction identity cannot be reused in another transaction', 'txid_current()::text', '(txid_current()+1)::text',
         'Historical STREAM scope belongs to another restore or transaction'),
        ('recovery cannot create a live generation', 'generation.fenced_at:=transaction_timestamp();', 'generation.fenced_at:=NULL;',
         'Historical generation must preserve original authority'),
        ('recovery cannot substitute another original checkpoint receipt', 'INSERT INTO edgeai.stream_checkpoint SELECT checkpoint.*;',
         "checkpoint.sha256:=repeat('a',64); INSERT INTO edgeai.stream_checkpoint SELECT checkpoint.*;",'Historical checkpoint differs from its exact independent receipt'),
        ('ordinary insertion still rejects historical retired generations without explicit restore scope',
         "set_config('edgeai.stream_history_recovery','on',true)", "set_config('edgeai.stream_history_recovery','off',true)",
         'Stream generation must begin with the next pending lease'),
        ('a later checkpoint failure rolls back restored producer identities, generations and earlier checkpoints',
         'checkpoints_count:=checkpoints_count+1;', "checkpoints_count:=checkpoints_count+1; IF checkpoints_count=2 THEN RAISE EXCEPTION 'Injected late recovery failure'; END IF;",
         'Injected late recovery failure')]
    for label, old, new, expected in variants:
        # The generated DO body is already an SQL string literal. Preserve its
        # quoting so a syntax error cannot masquerade as the intended refusal.
        old,new=old.replace("'","''"),new.replace("'","''")
        assert sql.count(old) == 1
        refused_sql(sql.replace(old,new,1),expected)
        passed(label+'; all original tables are preserved')

    task=plan['document']['taskIds'][0]
    updated=pg.sql('SELECT updated_at FROM edgeai.task WHERE id='+literal(task),db)
    try:
        pg.sql("UPDATE edgeai.task SET updated_at=updated_at+interval '1 microsecond' WHERE id="+literal(task),db)
        raced=fingerprints(db)
        refused_sql(sql,'Completion recovery database changed after verification',raced)
    finally:
        pg.sql('UPDATE edgeai.task SET updated_at='+literal(updated)+' WHERE id='+literal(task),db)
    assert fingerprints(db)==before
    passed('a real competing Task update rejects the prepared transaction and preserves the competing state')

    bad = deepcopy(plan);bad['document']['grantedAt']='2000-01-01T00:00:00Z'
    args.output.mkdir(mode=0o700)
    try:completions.apply(pg,args,bad)
    except Blocked:pass
    else:raise AssertionError('Changed prepared completion authority was accepted')
    assert fingerprints(db) == before
    passed('changed prepared authority is rejected before mutation and cannot replace the original grant time')

    def cli(index=5):
        current=options(index,completion_id=identity,vd_tasks=True)
        command=[sys.executable,'scripts/recovery_stream_completions.py']
        for k,v in vars(current).items():
            if k in ('stream_results','runtime_id','vd_tasks'):continue
            flag='--'+k.replace('_','-')
            if v is True:command.append(flag)
            elif v is not None and v is not False:command.extend([flag,str(v)])
        result=subprocess.run(command,capture_output=True,timeout=240)
        with private_file(current.output.parent/('completion-command-'+uuid.uuid4().hex+'.log')) as out:
            out.write(result.stdout+result.stderr)
        if result.returncode:
            failure=current.output/'failure.json'
            if failure.exists():
                detail=json.loads(failure.read_text())
                print('COMPLETION_RECOVERY_FAILURE '+json.dumps({k:detail[k] for k in ('status','errorType','failureLocation')}),flush=True)
        assert result.returncode == 0,'Completion CLI failed; private diagnostics retained'
        return json.loads((current.output/'completion.json').read_text())

    result=cli()
    assert result['status']=='STREAM_COMPLETION_HISTORY_RESTORED' and result['activated'] is False
    assert result['generationsRestored']==len(plan['changes']['generations'])
    assert result['checkpointsRestored']==5 and result['grantsRestored']==3 and result['publicationsRestored']==1
    actual=json.loads(pg.sql('SELECT json_agg(to_jsonb(c) ORDER BY task_id,serial) FROM edgeai.stream_checkpoint c',db))
    assert len(actual)==5 and all(same_receipt(a,b) for a,b in zip(actual,plan['checkpointHistory']['receipts']))
    for table,rows,key in [('stream_task_completion',plan['document']['taskCompletions'],'attempt_id'),
                           ('stream_device_completion',plan['document']['deviceCompletions'],'generation_id')]:
        for row in rows:
            saved=json.loads(pg.sql('SELECT to_jsonb(c) FROM edgeai.'+table+' c WHERE '+key+'='+literal(row[key]),db))
            assert completions.same_row(saved,row,('created_at','granted_at'))
    assert json.loads(pg.sql('SELECT document FROM edgeai.stream_completion_publication',db))==plan['document']
    assert pg.sql("SELECT count(*) FROM edgeai.route_generation WHERE state<>'CLOSED'",db)=='0'
    assert pg.sql("SELECT count(*) FROM edgeai.runtime_instance WHERE desired_state<>'STOPPED' OR observed_state<>'TERMINATED'",db)=='0'
    after=fingerprints(db)
    allowed={'route_generation','stream_checkpoint','stream_task_completion','stream_device_completion','stream_completion_publication','runtime_instance'}
    assert all(after[k]==v for k,v in before.items() if k not in allowed)
    passed('actual completion CLI restores all five original checkpoint IDs/times, one group grant and only CLOSED generations while preserving every unrelated table')
    replay=cli()
    assert replay['databaseModified'] is False and fingerprints(db)==after
    passed('identical independent evidence is idempotent after commit and cannot issue new producer authority')

    lost=options(6,completion_id=identity,vd_tasks=True)
    lost.output.mkdir(mode=0o700)
    original=fingerprints(lost.database);lost_plan=completions.prepare(pg,lost)
    assert len(lost_plan['changes']['checkpoints'])==5
    original_call=pg.call
    def lost_reply(tool,arguments,*positional,**kwargs):
        value=original_call(tool,arguments,*positional,**kwargs)
        if tool=='psql' and kwargs.get('source') is not None:
            raise OSError('Owned completion COMMIT response loss')
        return value
    with patch.object(pg,'call',lost_reply):
        try:completions.apply(pg,lost,lost_plan)
        except OSError:pass
        else:raise AssertionError('Completion COMMIT response loss was not exercised')
    assert not (lost.output/'completion.json').exists()
    committed=fingerprints(lost.database)
    assert committed!=original and all(committed[k]==v for k,v in original.items() if k not in allowed)
    assert pg.sql('SELECT count(*) FROM edgeai.stream_checkpoint',lost.database)=='5'
    assert pg.sql('SELECT count(*) FROM edgeai.stream_task_completion WHERE granted_at IS NOT NULL',lost.database)=='2'
    assert pg.sql('SELECT count(*) FROM edgeai.stream_device_completion WHERE granted_at IS NOT NULL',lost.database)=='1'
    assert not cli(6)['databaseModified'] and fingerprints(lost.database)==committed
    passed('lost completion COMMIT response retains all five receipts and three grants; actual CLI replay creates no duplicates')
    report.update(missingCompletionHistoryRestored=True,originalCheckpointReceiptsRestored=5,
                  restoredCompletionGrants=3,historicalInsertionNeverActivated=True,
                  completionCommitReplyLossRecovered=True,heartbeatHistoryUnchanged=True)
