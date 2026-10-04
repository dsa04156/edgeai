"""Reconcile failed/cancelled reference Remote attempts and bounded retry queues without activating recovery."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path

from postgres_backup import Blocked, Postgres, literal, private_file
from recovery_kubernetes import database_inventory
from recovery_remote_inventory import DATABASE_QUERY, canonical
from recovery_remote_outputs import verify as verify_bundle
from recovery_remote_retire import durable_json
from recovery_remote_results import TABLES as RESULT_TABLES, CONTEXT_QUERY, retired_snapshot
from recovery_remote_storage import private_json

TABLES = (*RESULT_TABLES, 'task_offload', 'task_offload_member')
GUARD_QUERY = 'SELECT jsonb_build_object(' + ','.join(
    literal(name) + ", (SELECT encode(sha256(convert_to(coalesce(string_agg("
    "encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'),'' ORDER BY to_jsonb(t)::text)"
    ",''),'UTF8')),'hex') FROM edgeai." + name + ' t)' for name in TABLES) + ')'
CATALOG_SQL = ('BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'
               "SELECT value::jsonb || jsonb_build_object('guard',(" + GUARD_QUERY +
               "),'contexts',(" + CONTEXT_QUERY + "),'activeOffloadRuns',(SELECT coalesce(jsonb_agg(DISTINCT run_id),'[]'::jsonb)"
               " FROM edgeai.task_offload WHERE state IN ('DRAINING','STARTING','CANCELLING'))) FROM (" +
               DATABASE_QUERY.strip().removesuffix(';') + ') snapshot(value);\nCOMMIT;')


def bundle(args):
    verified = verify_bundle(args.bundle)
    manifest, digest = private_json(args.bundle / 'manifest.json')
    intent, _ = private_json(args.bundle / 'intent.json')
    if digest != verified['manifestSha256']: raise Blocked('Private recovery bundle changed')
    return manifest, intent, digest


def prepare(pg, args):
    manifest, intent, digest = bundle(args)
    catalog = database_inventory(pg, args.database, args.restore_report, CATALOG_SQL)
    actual, contexts = retired_snapshot(catalog, manifest, intent, args)
    if not contexts: raise Blocked('No recovered Remote inventory to reconcile')
    entries, retained, successes = [], 0, 0
    for key, context in contexts.items():
        row = actual[key]; attempt, task = context['attempt'], context['task']
        if attempt['epoch'] != context['latestEpoch']:
            retained += 1; continue  # Historical failures never overwrite their successor.
        if (context['hasStream'] or task['run_id'] in catalog['activeOffloadRuns'] or attempt['mode'] != 'REMOTE' or
                any(attempt['remote_' + field] != row[field] for field in ('provider_key', 'configuration_digest', 'source_mode'))):
            raise Blocked('STREAM/active transfers or changed Remote bindings require separate reconciliation')
        if context['result'] is not None:
            result = context['result']
            if not result['committed'] or task['state'] != 'SUCCEEDED' or attempt['state'] != 'SUCCEEDED' or context['retryPending']:
                raise Blocked('Existing Result conflicts with Task/Attempt state')
            retained += 1; continue
        action, reason = None, context['failureReason']
        if task['state'] == 'CANCELLING':
            if not task['cancellation_reason'] or attempt['state'] not in ('CANCELLING', 'CANCELLED', 'FAILED', 'OFFLOADED'):
                raise Blocked('Cancellation must be recorded before recovery')
            action = 'CANCEL'
        elif task['state'] == 'RETRY_WAIT':
            if attempt['state'] != 'FAILED' or not context['retryPending'] or reason is None:
                raise Blocked('Retry queue differs from failed attempt')
            action = 'CHECK_RETRY'
        elif task['state'] == 'FAILED':
            if attempt['state'] != 'FAILED' or context['retryPending'] or reason is None:
                raise Blocked('Failed Task has inconsistent attempt history')
            action = 'FINAL_FAILURE'
        elif task['state'] in ('CANCELLED', 'SKIPPED'):
            if attempt['state'] not in ('CANCELLED', 'FAILED', 'OFFLOADED') or context['retryPending']:
                raise Blocked('Cancelled Task has inconsistent attempt history')
            retained += 1; continue
        elif task['state'] == 'RUNNING' and attempt['state'] in ('DISPATCHING', 'RUNNING'):
            if context['runState'] != 'RUNNING' or task['cancellation_reason'] is not None or context['retryPending'] or reason is not None:
                raise Blocked('Active Task has contradictory cancellation or failure history')
            if row['provider_state'] == 'SUCCEEDED':
                successes += 1; continue
            reason = ('RUNTIME_LOST' if row['provider_state'] == 'CANCELLED' else
                      {'LEASE_EXPIRED': 'RUNTIME_TIMEOUT', 'PROVIDER_RESTART': 'RUNTIME_LOST'}.get(
                          row['observation']['failureReason'], row['observation']['failureReason']))
            if reason not in ('RUNTIME_LOST', 'RUNTIME_TIMEOUT', 'WORKLOAD_FAILED', 'INPUT_INVALID', 'OUTPUT_INVALID'):
                raise Blocked('Unsupported terminal provider failure')
            action = 'FAIL'
        else:
            raise Blocked('Unsupported current Remote Task/Attempt state')
        entries.append({'allocationId': key, 'runtimeId': context['runtimeId'], 'attemptId': attempt['id'],
                        'taskId': task['id'], 'runId': task['run_id'], 'action': action, 'reason': reason})
    return {'formatVersion': 1, 'scope': 'restored-reference-remote-failure-reconciliation',
            'targetDatabase': args.database, 'databaseOid': catalog['oid'], 'marker': catalog['marker'],
            'restoreReportSha256': catalog['restoreReportSha256'], 'remoteBundleSha256': digest,
            'providerId': manifest['providerId'], 'recoveryId': manifest['recoveryId'], 'beforeGuard': catalog['guard'],
            'entries': entries, 'historiesRetained': retained, 'successesPending': successes,
            'preparedAt': datetime.now(timezone.utc).isoformat()}


def transaction_sql(plan):
    identity = """
IF current_database()<>{database} OR NOT EXISTS (SELECT FROM pg_database WHERE datname=current_database()
 AND oid::text={oid} AND shobj_description(oid,'pg_database')={marker}) THEN
 RAISE EXCEPTION 'Restored database identity changed'; END IF;
""".format(database=literal(plan['targetDatabase']), oid=literal(plan['databaseOid']), marker=literal(plan['marker']))
    body = """
DECLARE entry jsonb; task_id_to_change uuid; affected_run uuid; victim edgeai.task; policy edgeai.workflow_run;
 runtime edgeai.runtime_instance; retry edgeai.task_retry; cutoff timestamptz; available timestamptz;
 first_created timestamptz; used integer; changed integer; terminal text; cancellation text; run_state text;
 failed_tasks uuid[]:='{{}}'; cancel_tasks uuid[]:='{{}}'; run_ids uuid[]:='{{}}';
 failures integer:=0; scheduled integer:=0; expired integer:=0; cancelled integer:=0; skipped integer:=0; runs integer:=0;
BEGIN
{identity}
IF ({guard}) IS DISTINCT FROM {before}::jsonb THEN
 RAISE EXCEPTION 'Restored database changed after recovery snapshot'; END IF;
FOR entry IN SELECT * FROM jsonb_array_elements({entries}::jsonb) LOOP
 task_id_to_change:=(entry->>'taskId')::uuid;
 run_ids:=array_append(run_ids,(entry->>'runId')::uuid);
 IF entry->>'action'='CANCEL' THEN
  cancel_tasks:=array_append(cancel_tasks,task_id_to_change);
 ELSIF entry->>'action'='CHECK_RETRY' THEN
  SELECT * INTO retry FROM edgeai.task_retry WHERE task_id=task_id_to_change;
  SELECT * INTO runtime FROM edgeai.runtime_instance WHERE id=(entry->>'runtimeId')::uuid;
  SELECT * INTO policy FROM edgeai.workflow_run WHERE id=(entry->>'runId')::uuid;
  SELECT created_at INTO first_created FROM edgeai.task_attempt WHERE task_id=task_id_to_change ORDER BY number LIMIT 1;
  SELECT count(*) INTO used FROM edgeai.task_attempt WHERE task_id=task_id_to_change AND cause<>'OFFLOAD';
  IF retry.failed_attempt_id IS DISTINCT FROM (entry->>'attemptId')::uuid OR retry.namespace IS DISTINCT FROM runtime.namespace OR
   retry.deadline IS DISTINCT FROM first_created+make_interval(secs=>policy.retry_max_elapsed_seconds) OR
   retry.available_at IS DISTINCT FROM (SELECT updated_at+make_interval(secs=>policy.retry_backoff_seconds)
    FROM edgeai.task_attempt WHERE id=retry.failed_attempt_id) OR
   NOT ((entry->>'reason')=ANY(policy.retry_on)) OR used>=policy.retry_max_attempts THEN
   RAISE EXCEPTION 'Retry does not belong to the current failed runtime'; END IF;
  IF transaction_timestamp()>=retry.deadline THEN
   DELETE FROM edgeai.task_retry WHERE task_id=task_id_to_change;
   UPDATE edgeai.task SET state='FAILED',updated_at=transaction_timestamp() WHERE id=task_id_to_change;
   failed_tasks:=array_append(failed_tasks,task_id_to_change); expired:=expired+1;
  END IF;
 ELSIF entry->>'action'='FINAL_FAILURE' THEN
  failed_tasks:=array_append(failed_tasks,task_id_to_change);
 ELSE
  SELECT * INTO policy FROM edgeai.workflow_run WHERE id=(entry->>'runId')::uuid;
  SELECT * INTO runtime FROM edgeai.runtime_instance WHERE id=(entry->>'runtimeId')::uuid;
  SELECT created_at INTO first_created FROM edgeai.task_attempt WHERE task_id=task_id_to_change ORDER BY number LIMIT 1;
  SELECT count(*) INTO used FROM edgeai.task_attempt WHERE task_id=task_id_to_change AND cause<>'OFFLOAD';
  cutoff:=first_created+make_interval(secs=>policy.retry_max_elapsed_seconds);
  available:=transaction_timestamp()+make_interval(secs=>policy.retry_backoff_seconds);
  UPDATE edgeai.task_attempt SET state='FAILED',updated_at=transaction_timestamp() WHERE id=(entry->>'attemptId')::uuid;
  UPDATE edgeai.runtime_instance SET failure_reason=entry->>'reason',updated_at=transaction_timestamp() WHERE id=runtime.id;
  failures:=failures+1;
  IF (entry->>'reason')=ANY(policy.retry_on) AND used<policy.retry_max_attempts AND available<cutoff THEN
   INSERT INTO edgeai.task_retry(task_id,failed_attempt_id,namespace,available_at,deadline)
   VALUES(task_id_to_change,(entry->>'attemptId')::uuid,runtime.namespace,available,cutoff);
   UPDATE edgeai.task SET state='RETRY_WAIT',updated_at=transaction_timestamp() WHERE id=task_id_to_change;
   scheduled:=scheduled+1;
  ELSE
   UPDATE edgeai.task SET state='FAILED',updated_at=transaction_timestamp() WHERE id=task_id_to_change;
   failed_tasks:=array_append(failed_tasks,task_id_to_change);
  END IF;
 END IF;
END LOOP;
-- Follow every BATCH descendant. Existing terminal outcomes and Results are never overwritten.
FOR victim IN
 WITH RECURSIVE descendants(run_id,definition_id) AS (
  SELECT t.run_id,d.to_task_id FROM edgeai.task t JOIN edgeai.task_dependency d ON d.from_task_id=t.definition_id
  WHERE t.id=ANY(failed_tasks)
  UNION
  SELECT x.run_id,d.to_task_id FROM descendants x JOIN edgeai.task_dependency d ON d.from_task_id=x.definition_id
 ) SELECT t.* FROM edgeai.task t WHERE t.id=ANY(cancel_tasks) OR EXISTS (
  SELECT FROM descendants x WHERE x.run_id=t.run_id AND x.definition_id=t.definition_id)
 LOOP
 IF victim.state IN ('SUCCEEDED','FAILED','CANCELLED','SKIPPED') THEN CONTINUE; END IF;
 IF EXISTS(SELECT FROM edgeai.task_result WHERE task_id=victim.id) OR EXISTS (
  SELECT FROM edgeai.runtime_instance r WHERE r.task_id=victim.id AND
   (r.desired_state<>'STOPPED' OR r.observed_state<>'TERMINATED' OR EXISTS (
    SELECT FROM edgeai.runtime_command c WHERE c.runtime_id=r.id AND (NOT c.completed OR c.lease_owner IS NOT NULL OR c.lease_until IS NOT NULL)))) THEN
  RAISE EXCEPTION 'Descendant result or unfinished producer prevents cancellation'; END IF;
 IF victim.state='CANCELLING' AND (victim.cancellation_reason IS NULL OR victim.cancellation_reason='') THEN
  RAISE EXCEPTION 'Existing descendant cancellation requires its recorded reason'; END IF;
 cancellation:=CASE WHEN victim.state='CANCELLING' THEN victim.cancellation_reason ELSE 'UPSTREAM_FAILED' END;
 terminal:=CASE WHEN cancellation LIKE 'UPSTREAM_%' THEN 'SKIPPED' ELSE 'CANCELLED' END;
 DELETE FROM edgeai.task_retry WHERE task_id=victim.id;
 UPDATE edgeai.task_attempt SET state='CANCELLED',updated_at=transaction_timestamp()
 WHERE task_id=victim.id AND state IN ('QUEUED','DISPATCHING','RUNNING','CANCELLING');
 UPDATE edgeai.task SET state=terminal,cancellation_reason=cancellation,updated_at=transaction_timestamp() WHERE id=victim.id;
 IF terminal='SKIPPED' THEN skipped:=skipped+1; ELSE cancelled:=cancelled+1; END IF;
END LOOP;
FOR affected_run IN SELECT DISTINCT unnest(run_ids) LOOP
 SELECT CASE WHEN bool_or(state='FAILED') THEN 'FAILED'
   WHEN bool_or(state IN ('CANCELLED','SKIPPED')) THEN 'CANCELLED' ELSE 'SUCCEEDED' END INTO run_state
 FROM edgeai.task WHERE run_id=affected_run HAVING bool_and(state IN ('SUCCEEDED','FAILED','CANCELLED','SKIPPED'));
 IF run_state IS NULL AND EXISTS(SELECT FROM edgeai.task WHERE run_id=affected_run AND state='CANCELLING') AND
  NOT EXISTS(SELECT FROM edgeai.task WHERE run_id=affected_run AND state IN ('WAITING','READY','RUNNING','RETRY_WAIT','OFFLOADING')) THEN
  run_state:='CANCELLING'; END IF;
 IF run_state IS NOT NULL THEN
  UPDATE edgeai.workflow_run SET state=run_state,updated_at=transaction_timestamp()
  WHERE id=affected_run AND state IN ('RUNNING','CANCELLING') AND state<>run_state;
  GET DIAGNOSTICS changed=ROW_COUNT; runs:=runs+changed;
 END IF;
END LOOP;
{identity}
INSERT INTO failure_result VALUES(jsonb_build_object('attemptsFailed',failures,'retriesScheduled',scheduled,
 'retriesExpired',expired,'tasksCancelled',cancelled,'tasksSkipped',skipped,'runsReconciled',runs,'afterGuard',({guard})));
END
""".format(identity=identity, guard=GUARD_QUERY, before=literal(canonical(plan['beforeGuard']).decode()),
           entries=literal(canonical(plan['entries']).decode()))
    return ("BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s'; LOCK TABLE " +
            ','.join('edgeai.' + name for name in TABLES) + ' IN SHARE ROW EXCLUSIVE MODE;\n'
            'CREATE TEMP TABLE failure_result(value jsonb) ON COMMIT DROP; DO ' + literal(body) +
            '; SELECT value FROM failure_result; COMMIT;\n')


def apply(pg, args, plan):
    durable_json(args.output / 'intent.json', plan)
    if bundle(args)[2] != plan['remoteBundleSha256']: raise Blocked('Recovery bundle changed before commit')
    sql = args.output / 'transaction.sql'
    with private_file(sql, 'w') as target:
        target.write(transaction_sql(plan)); target.flush(); os.fsync(target.fileno())
    with sql.open('rb') as source:
        result = json.loads(pg.call('psql', ['-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
                                   args.database, source=source, timeout=45, reject_stderr=True))
    after = prepare(pg, args)
    if (after['beforeGuard'] != result['afterGuard'] or any(after[key] != plan[key] for key in
            ('databaseOid', 'marker', 'remoteBundleSha256', 'restoreReportSha256'))):
        raise Blocked('Post-commit recovery state changed; keep quarantine')
    report = {**{key:plan[key] for key in ('formatVersion', 'scope', 'targetDatabase', 'databaseOid', 'providerId', 'recoveryId', 'remoteBundleSha256')},
              **result, 'status': 'REMOTE_FAILURES_RECONCILED', 'activated': False,
              'databaseModified': any(value for key,value in result.items() if key != 'afterGuard'),
              'pendingRetries': sum(entry['action']=='CHECK_RETRY' for entry in after['entries']),
              'successesPending': after['successesPending'], 'historiesRetained': after['historiesRetained'],
              'intentSha256': hashlib.sha256((args.output / 'intent.json').read_bytes()).hexdigest(),
              'verifiedAt': datetime.now(timezone.utc).isoformat(),
              'excluded': ['retry-dispatch', 'success-result-commit', 'stream-and-active-offload-recovery',
                           'device-journals', 'global-recovery-acceptance', 'service-activation']}
    durable_json(args.output / 'failures.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', required=True)
    for name in ('bundle', 'restore-report', 'output'): parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--transport', choices=('native', 'compose'), default='native')
    parser.add_argument('--pg-bin', type=Path)
    args = parser.parse_args(); args.output.mkdir(mode=0o700, parents=True, exist_ok=False); submitted = False
    try:
        pg = Postgres(args.transport, args.pg_bin, diagnostics=args.output / 'postgres')
        plan = prepare(pg, args); submitted = True; report = apply(pg, args, plan)
        print(report['status'] + ': ' + str(report['pendingRetries']) + ' retries remain queued; database quarantined')
        return 0
    except Exception as error:
        blocked = isinstance(error, (Blocked, OSError)); status = 'BLOCKED' if blocked else 'FAIL'
        durable_json(args.output / 'failure.json', {'status': status, 'failureType': type(error).__name__,
            'databaseModified': None if submitted else False, 'activated': False,
            'instruction': 'Retain quarantine and intent; rerun the same bundle in a new output directory'})
        print(status + ': Remote failure recovery; private evidence retained; keep quarantine')
        return 2 if blocked else 1


if __name__ == '__main__': raise SystemExit(main())
