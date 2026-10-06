"""Restore recorded atomic STREAM target failures without creating a new retry budget."""
from unittest.mock import patch

from postgres_backup import Blocked,literal
from test_recovery_stream_offloads import q
import recovery_stream_workflows as workflows


def check(pg,fixture,options,apply,cli,fingerprints,refused,passed,report):
    targets=fixture['targets'];successors=fixture['successors'];operation=fixture['operation']
    def opts(target):return options(target,offloads=True,unclaimed_jobs=True)
    def run(target,plan=None):return apply(target,plan,offloads=True,unclaimed_jobs=True)
    def command(target):return cli(target,offloads=True,unclaimed_jobs=True)
    def history(db):
        return pg.sql("SELECT jsonb_build_object('operation',(SELECT to_jsonb(o) FROM edgeai.task_offload o WHERE id="+q(operation)+
            "),'sources',(SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id) FROM edgeai.task_attempt a WHERE cause='INITIAL'),"
            "'sourceRuntimes',(SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id) FROM edgeai.runtime_instance r JOIN edgeai.task_attempt a ON a.id=r.attempt_id WHERE a.cause='INITIAL'),"
            "'members',(SELECT jsonb_agg(to_jsonb(m) ORDER BY m.task_id) FROM edgeai.task_offload_member m),"
            "'checkpoints',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM edgeai.stream_checkpoint c))",db)
    for name,index in [('selected-failed',0),('peer-failed',1),('failure-unproven',0)]:
        db=targets[name][0];failed=successors[index];peer=successors[1-index]
        reason='WORKLOAD_FAILED' if index==0 or failed.get('vd') else 'JOB_FAILED'
        # RuntimeLifecycleService.recordFailure atomically marks the operation TARGET_FAILED,
        # fails this member, cancels its connected peers/downstream, and never retries a transfer.
        pg.sql("BEGIN; UPDATE edgeai.task_offload SET state='FAILED',failure_reason='TARGET_FAILED',updated_at=now(); "
            "UPDATE edgeai.task_attempt SET state='FAILED',updated_at=now() WHERE id="+q(failed['attempt'])+
            '; UPDATE edgeai.runtime_instance SET failure_reason='+literal(reason)+' WHERE id='+q(failed['runtime'])+
            "; UPDATE edgeai.task SET state='FAILED',updated_at=now() WHERE id="+q(failed['task'])+
            "; UPDATE edgeai.task SET state='CANCELLING',cancellation_reason='UPSTREAM_FAILED',updated_at=now() WHERE id="+q(peer['task'])+
            "; UPDATE edgeai.task_attempt SET state='CANCELLING',updated_at=now() WHERE id="+q(peer['attempt'])+
            "; UPDATE edgeai.task SET state='SKIPPED',cancellation_reason='UPSTREAM_FAILED',updated_at=now() WHERE state='WAITING'; "
            "UPDATE edgeai.workflow_run SET state='CANCELLING',updated_at=now(); COMMIT",db)
    selected=targets['selected-failed'];failed=successors[0];peer=successors[1]
    original=fingerprints(selected[0])
    default=cli(selected,unclaimed_jobs=True)
    assert not default['databaseModified'] and fingerprints(selected[0])==original
    assert default['unresolvedGroups'][0]['reason']=='RECORDED_STREAM_TARGET_FAILURE_REQUIRES_RECONCILIATION'
    def unchanged_unresolved(target):
        before=fingerprints(target[0])
        try:
            result=run(target)
        except Blocked:
            # A proof refusal is acceptable only if the entire restored database is unchanged.
            assert fingerprints(target[0])==before
            return
        assert not result['databaseModified'] and result['unresolvedGroups'] and fingerprints(target[0])==before
    for value in (None,'OFFLOADED'):
        pg.sql('UPDATE edgeai.runtime_instance SET failure_reason='+('NULL' if value is None else literal(value))+' WHERE id='+q(failed['runtime']),selected[0])
        unchanged_unresolved(selected)
    pg.sql("UPDATE edgeai.runtime_instance SET failure_reason='WORKLOAD_FAILED' WHERE id="+q(failed['runtime']),selected[0]);assert fingerprints(selected[0])==original
    passed('recorded-target-failure-requires-its-original-runtime-failure-reason-before-peer-or-run-reconciliation')
    pg.sql("INSERT INTO edgeai.task_retry(task_id,failed_attempt_id,namespace,available_at,deadline) SELECT a.task_id,a.id,r.namespace,now()+interval '1 second',"
        "(SELECT min(created_at)+interval '3600 seconds' FROM edgeai.task_attempt WHERE task_id=a.task_id) FROM edgeai.task_attempt a JOIN edgeai.runtime_instance r ON r.attempt_id=a.id WHERE a.id="+q(failed['attempt']),selected[0])
    unchanged_unresolved(selected);pg.sql('DELETE FROM edgeai.task_retry',selected[0]);assert fingerprints(selected[0])==original
    passed('recorded-transfer-failure-rejects-a-conflicting-retry-even-when-original-run-policy-allows-the-reason')
    pg.sql("UPDATE edgeai.task_attempt SET state='RUNNING' WHERE id="+q(failed['attempt']),selected[0])
    unchanged_unresolved(selected);pg.sql("UPDATE edgeai.task_attempt SET state='FAILED' WHERE id="+q(failed['attempt']),selected[0]);assert fingerprints(selected[0])==original
    passed('failed-stream-task-cannot-hide-a-nonfailed-target-attempt')
    prepared=workflows.prepare(pg,opts(selected))
    pg.sql("UPDATE edgeai.task_offload SET updated_at=updated_at+interval '1 second'",selected[0]);changed=fingerprints(selected[0])
    refused(lambda:run(selected,prepared),Blocked);assert fingerprints(selected[0])==changed
    pg.sql("UPDATE edgeai.task_offload SET updated_at=updated_at-interval '1 second'",selected[0]);assert fingerprints(selected[0])==original
    transaction=workflows.transaction_sql
    with patch.object(workflows,'transaction_sql',side_effect=lambda p:transaction(p).replace('SET CONSTRAINTS ALL IMMEDIATE;','SELECT 1/0; SET CONSTRAINTS ALL IMMEDIATE;')):
        refused(lambda:run(selected),RuntimeError)
    assert fingerprints(selected[0])==original
    passed('recorded-failure-operation-drift-and-late-sql-error-preserve-the-entire-component-and-run')
    for name,index in [('selected-failed',0),('peer-failed',1)]:
        target=targets[name];before=fingerprints(target[0]);retained=history(target[0]);failed=successors[index]
        failed_history=pg.sql('SELECT to_jsonb(a)::text FROM edgeai.task_attempt a WHERE id='+q(failed['attempt']),target[0])
        failure_runtime=pg.sql('SELECT to_jsonb(r)::text FROM edgeai.runtime_instance r WHERE id='+q(failed['runtime']),target[0])
        if index==0:
            call=pg.call
            def lost(tool,arguments,*a,**kw):
                value=call(tool,arguments,*a,**kw)
                if '-f' in arguments:raise OSError('Injected recorded STREAM target failure COMMIT reply loss')
                return value
            with patch.object(pg,'call',side_effect=lost):refused(lambda:run(target),OSError)
        else:
            result=command(target);assert result['tasksSkipped']==1 and result['tasksCancelled']==0 and result['runsReconciled']==1
        after=fingerprints(target[0]);assert all(before[k]==after[k] for k in before if k not in ('task','task_attempt','workflow_run'))
        assert pg.sql('SELECT state FROM edgeai.workflow_run',target[0])=='FAILED'
        assert pg.sql('SELECT count(*) FROM edgeai.task_retry',target[0])=='0'
        assert pg.sql("SELECT count(*) FROM edgeai.task_attempt",target[0])=='4'
        assert pg.sql('SELECT to_jsonb(a)::text FROM edgeai.task_attempt a WHERE id='+q(failed['attempt']),target[0])==failed_history
        assert pg.sql('SELECT to_jsonb(r)::text FROM edgeai.runtime_instance r WHERE id='+q(failed['runtime']),target[0])==failure_runtime
        assert history(target[0])==retained
        result=command(target);assert not result['databaseModified'] and not result['unresolvedGroups'] and fingerprints(target[0])==after
        passed('selected-target-failure-survives-commit-response-loss-without-retry-or-rewriting-history' if index==0 else
            'peer-target-failure-reconciles-the-selected-member-and-run-with-original-failure-operation-intact')
    missing=targets['failure-unproven'];unproven=successors[0] if successors[1].get('vd') else successors[1]
    pg.sql("UPDATE edgeai.runtime_instance SET job_uid=NULL,producer_pod_uid=NULL,node_uid=NULL,node_name=NULL,observed_state='SUBMITTED' WHERE id="+q(unproven['runtime']),missing[0])
    unchanged_unresolved(missing)
    passed('one-unproven-member-producer-blocks-completion-of-an-already-failed-stream-transfer')
    report.update(streamRecordedTargetFailureCases=7,streamRecordedTargetFailures=2,
        streamRecordedTargetFailureHistoriesPreserved=True,streamRecordedTargetFailureRetries=0)
