"""Exercise sealed finalizer retry expiry on actual restored PostgreSQL databases."""
import json
from unittest.mock import patch

from postgres_backup import Blocked, literal
import recovery_stream_workflows as workflows


def check(pg, targets, members, options, apply, cli, fingerprints, refused, passed, report):
    pending, expired = targets
    def q(value): return literal(value) + '::uuid'
    def run(target, plan=None): return apply(target, plan, finalizers=True)
    def command(target): return cli(target, finalizers=True)
    for target, budget in ((pending, 3600), (expired, 12)):
        db = target[0]
        # Explicit binding fixtures retain the actual terminal checkpoint/S3 objects.
        # Each task has its own first-attempt timestamp and failure/backoff window.
        for member in members:
            checkpoint = q(member['checkpoint']); attempt = q(member['attempt']); task = q(member['task'])
            pg.sql("BEGIN; INSERT INTO edgeai.stream_task_completion(attempt_id,checkpoint_id,created_at) "
                "SELECT attempt_id,id,created_at+interval '1 microsecond' FROM edgeai.stream_checkpoint WHERE id="+checkpoint+"; "
                "UPDATE edgeai.stream_task_completion SET granted_at=created_at+interval '1 microsecond' WHERE attempt_id="+attempt+"; "
                "UPDATE edgeai.task_attempt SET created_at=(SELECT created_at-interval '10 seconds' FROM edgeai.stream_checkpoint WHERE id="+checkpoint+"),"
                "updated_at=(SELECT created_at+interval '3 microseconds' FROM edgeai.stream_checkpoint WHERE id="+checkpoint+") WHERE id="+attempt+"; "
                "UPDATE edgeai.runtime_instance SET failure_reason='WORKLOAD_FAILED' WHERE attempt_id="+attempt+"; "
                "UPDATE edgeai.task_retry SET available_at=(SELECT updated_at+interval '1 second' FROM edgeai.task_attempt WHERE id="+attempt+"),"
                "deadline=(SELECT created_at+make_interval(secs=>"+str(budget)+") FROM edgeai.task_attempt WHERE id="+attempt+") WHERE task_id="+task+"; "
                "UPDATE edgeai.workflow_run SET retry_max_elapsed_seconds="+str(budget)+"; COMMIT", db)
    db = pending[0]; original = fingerprints(db)
    default = cli(pending)
    assert not default['databaseModified'] and default['unresolvedGroups'] and fingerprints(db) == original
    passed('sealed-finalizer-retry-cannot-bypass-explicit-finalization-recovery-option')
    result = command(pending)
    assert not result['databaseModified'] and not result['unresolvedGroups'] and fingerprints(db) == original
    assert result['groups'][0]['action'] == 'CHECK_FINALIZER_RETRIES' and len(result['groups'][0]['retryTaskIds']) == 2
    assert pg.sql('SELECT count(DISTINCT deadline) FROM edgeai.task_retry', db) == '2'
    passed('two-sealed-finalizers-retain-distinct-original-task-deadlines-without-group-retry-or-new-grants')
    victim = members[0]; task = q(victim['task']); runtime = q(victim['runtime']); attempt = q(victim['attempt'])
    for column in ('deadline', 'available_at'):
        change = 'UPDATE edgeai.task_retry SET '+column+'='+column+"+interval '1 second' WHERE task_id="+task
        pg.sql(change, db); before = fingerprints(db)
        refused(lambda: run(pending), Blocked); assert fingerprints(db) == before
        pg.sql(change.replace('+interval', '-interval'), db)
    assert fingerprints(db) == original
    passed('finalizer-retry-deadline-and-backoff-drift-refuse-all-writes')
    pg.sql("UPDATE edgeai.runtime_instance SET failure_reason='STREAM_GROUP_RESTART' WHERE id="+runtime, db)
    before = fingerprints(db); refused(lambda: run(pending), Blocked); assert fingerprints(db) == before
    pg.sql("UPDATE edgeai.runtime_instance SET failure_reason='WORKLOAD_FAILED' WHERE id="+runtime, db)
    pg.sql("UPDATE edgeai.task_attempt SET state='RUNNING' WHERE id="+attempt, db)
    before = fingerprints(db); refused(lambda: run(pending), Blocked); assert fingerprints(db) == before
    pg.sql("UPDATE edgeai.task_attempt SET state='FAILED' WHERE id="+attempt, db)
    assert fingerprints(db) == original
    passed('sealed-finalizer-rejects-group-restart-reason-and-nonfailed-latest-attempt')
    plan = workflows.prepare(pg, options(pending, finalizers=True))
    drift = 'UPDATE edgeai.task_retry SET deadline=deadline+interval \'1 second\' WHERE task_id='+task
    pg.sql(drift, db); before = fingerprints(db)
    refused(lambda: run(pending, plan), Blocked); assert fingerprints(db) == before
    pg.sql(drift.replace('+interval', '-interval'), db)
    call = pg.call
    def raced(tool, arguments, *a, **kw):
        if '-f' in arguments: pg.sql(drift, db)
        return call(tool, arguments, *a, **kw)
    with patch.object(pg, 'call', side_effect=raced): refused(lambda: run(pending), RuntimeError)
    after = fingerprints(db); assert {k for k in after if after[k] != original[k]} == {'task_retry'}
    pg.sql(drift.replace('+interval', '-interval'), db); assert fingerprints(db) == original
    passed('finalizer-history-races-before-plan-use-and-after-observation-are-rejected')
    db = expired[0]
    assert pg.sql('SELECT bool_and(deadline<now()) FROM edgeai.task_retry', db) == 't'
    before = fingerprints(db); transaction = workflows.transaction_sql
    with patch.object(workflows, 'transaction_sql', side_effect=lambda p: transaction(p).replace(
            'SET CONSTRAINTS ALL IMMEDIATE;', 'SELECT 1/0; SET CONSTRAINTS ALL IMMEDIATE;')):
        refused(lambda: run(expired), RuntimeError)
    assert fingerprints(db) == before
    passed('late-sql-error-rolls-back-finalizer-expiry-peer-cancellation-and-descendant-changes')
    committed = {}
    def lost_expiry(tool, arguments, *a, **kw):
        value = call(tool, arguments, *a, **kw)
        if '-f' in arguments:
            committed.update(json.loads(value))
            raise OSError('Injected sealed finalizer expiry COMMIT response loss')
        return value
    with patch.object(pg, 'call', side_effect=lost_expiry): refused(lambda: run(expired), OSError)
    result = committed
    assert result['finalizerRetriesExpired'] == result['retriesExpired'] == 1
    assert result['tasksSkipped'] == 2 and result['runsReconciled'] == 1
    after = fingerprints(db)
    assert all(before[k] == after[k] for k in before if k not in ('task', 'task_retry', 'workflow_run'))
    assert pg.sql('SELECT count(*) FROM edgeai.task_retry', db) == '0'
    assert pg.sql('SELECT count(*) FROM edgeai.stream_task_completion WHERE granted_at IS NOT NULL', db) == '2'
    assert pg.sql('SELECT count(*) FROM edgeai.stream_finalization_recovery', db) == '0'
    assert pg.sql("SELECT count(*) FROM edgeai.task_attempt WHERE state='FAILED'", db) == '2'
    assert not command(expired)['databaseModified'] and fingerprints(db) == after
    passed('expired-finalizer-survives-commit-reply-loss-and-preserves-sealed-history-without-new-attempts')
    # Reuse the pending database for a separately recorded user cancellation. A real
    # COMMIT response loss must retain its immutable finalization authority and be replayable.
    db = pending[0]
    pg.sql("UPDATE edgeai.task SET state='CANCELLING',cancellation_reason='RUN_CANCELLED' WHERE state='RETRY_WAIT'; "
        "UPDATE edgeai.task SET state='SKIPPED',cancellation_reason='UPSTREAM_CANCELLED' WHERE state='WAITING'; "
        "UPDATE edgeai.workflow_run SET state='CANCELLING'", db)
    before = fingerprints(db)
    def lost(tool, arguments, *a, **kw):
        value = call(tool, arguments, *a, **kw)
        if '-f' in arguments: raise OSError('Injected sealed finalizer cancellation COMMIT response loss')
        return value
    with patch.object(pg, 'call', side_effect=lost): refused(lambda: run(pending), OSError)
    after = fingerprints(db)
    assert all(before[k] == after[k] for k in before if k not in ('task', 'task_retry', 'workflow_run'))
    assert pg.sql('SELECT state FROM edgeai.workflow_run', db) == 'CANCELLED'
    assert not command(pending)['databaseModified'] and fingerprints(db) == after
    passed('sealed-finalizer-cancellation-preserves-grants-and-failed-attempts-after-real-commit-reply-loss')
    report.update(streamFinalizerCases=8, streamFinalizerRetriesExpired=1,
        streamFinalizerHistoryPreserved=True, streamFinalizerNewAttempts=0)
