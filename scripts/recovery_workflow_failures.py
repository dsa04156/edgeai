"""Shared quarantined BATCH failure/cancellation/retry transaction; callers supply producer evidence."""
from postgres_backup import literal
from recovery_remote_inventory import canonical


def transaction_sql(plan, tables, guard_query, proven_runtimes=None):
    offload_actions=('CANCEL_OFFLOAD','FAIL_OFFLOAD','CHECK_OFFLOAD_DRAIN','CHECK_OFFLOAD_START','FAIL_REMOTE_OFFLOAD','COMPLETE_REMOTE_OFFLOAD')
    if any(entry['action'] not in ('CANCEL', 'CHECK_RETRY', 'FINAL_FAILURE', 'FAIL', 'RECONCILE_RUN', *offload_actions) for entry in plan['entries']):
        raise ValueError('Unsupported failure reconciliation action')
    if any(e['action'] in offload_actions for e in plan['entries']) and (proven_runtimes is None or not plan.get('reconcileOffloads')):
        raise ValueError('Offload recovery requires explicit producer evidence')
    producer_guard = '' if proven_runtimes is None else (
        'r.id NOT IN (SELECT value::uuid FROM jsonb_array_elements_text(' +
        literal(canonical(sorted(proven_runtimes)).decode()) + '::jsonb)) OR EXISTS ('
        'SELECT FROM edgeai.vd_task_allocation a WHERE a.runtime_id=r.id AND a.closed_at IS NULL) OR ')
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
 operation edgeai.task_offload; offloads_failed integer:=0; offloads_cancelled integer:=0; offloads_completed integer:=0;
BEGIN
{identity}
IF ({guard}) IS DISTINCT FROM {before}::jsonb THEN
 RAISE EXCEPTION 'Restored database changed after recovery snapshot'; END IF;
FOR entry IN SELECT * FROM jsonb_array_elements({entries}::jsonb) LOOP
 task_id_to_change:=(entry->>'taskId')::uuid;
 run_ids:=array_append(run_ids,(entry->>'runId')::uuid);
 IF entry->>'action'='FAIL_REMOTE_OFFLOAD' THEN
  UPDATE edgeai.task_offload SET state='FAILED',failure_reason='TARGET_FAILED',updated_at=transaction_timestamp()
  WHERE id=(entry->>'operationId')::uuid;
  offloads_failed:=offloads_failed+1;
 END IF;
 IF entry->>'action'='COMPLETE_REMOTE_OFFLOAD' THEN
  UPDATE edgeai.task_offload SET state='SUCCEEDED',updated_at=transaction_timestamp()
  WHERE id=(entry->>'operationId')::uuid AND state='STARTING' AND failure_reason IS NULL;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed<>1 THEN RAISE EXCEPTION 'Original Remote transfer is no longer pending'; END IF;
  offloads_completed:=offloads_completed+1;
 ELSIF entry->>'action' IN ('CANCEL_OFFLOAD','FAIL_OFFLOAD','CHECK_OFFLOAD_DRAIN','CHECK_OFFLOAD_START') THEN
  SELECT * INTO STRICT operation FROM edgeai.task_offload WHERE id=(entry->>'operationId')::uuid;
  IF entry->>'action'='CANCEL_OFFLOAD' THEN
   UPDATE edgeai.task_offload SET state='CANCELLED',failure_reason=NULL,updated_at=transaction_timestamp() WHERE id=operation.id;
   offloads_cancelled:=offloads_cancelled+1;
   IF EXISTS(SELECT FROM edgeai.task WHERE id=task_id_to_change AND state='CANCELLING') THEN
    cancel_tasks:=array_append(cancel_tasks,task_id_to_change);
   ELSIF EXISTS(SELECT FROM edgeai.task WHERE id=task_id_to_change AND state='FAILED') THEN
    failed_tasks:=array_append(failed_tasks,task_id_to_change);
   END IF;
  ELSIF entry->>'action'='FAIL_OFFLOAD' OR
   (entry->>'action'='CHECK_OFFLOAD_DRAIN' AND transaction_timestamp()>=operation.drain_deadline) OR
   (entry->>'action'='CHECK_OFFLOAD_START' AND transaction_timestamp()>=operation.start_deadline) THEN
   cancellation:=CASE entry->>'action' WHEN 'FAIL_OFFLOAD' THEN 'TARGET_FAILED'
    WHEN 'CHECK_OFFLOAD_DRAIN' THEN 'SOURCE_DRAIN_TIMEOUT' ELSE 'TARGET_START_TIMEOUT' END;
   IF entry->>'action'='CHECK_OFFLOAD_START' THEN
    UPDATE edgeai.runtime_instance SET failure_reason=cancellation,updated_at=transaction_timestamp() WHERE attempt_id=operation.target_attempt_id;
    UPDATE edgeai.task_attempt SET state='FAILED',updated_at=transaction_timestamp() WHERE id=operation.target_attempt_id;
    failures:=failures+1;
   END IF;
   IF entry->>'action'<>'FAIL_OFFLOAD' THEN
    UPDATE edgeai.task SET state='FAILED',updated_at=transaction_timestamp() WHERE id=task_id_to_change;
   END IF;
   UPDATE edgeai.task_offload SET state='FAILED',failure_reason=cancellation,updated_at=transaction_timestamp() WHERE id=operation.id;
   offloads_failed:=offloads_failed+1; failed_tasks:=array_append(failed_tasks,task_id_to_change);
  END IF;
 ELSIF entry->>'action'='CANCEL' THEN
  cancel_tasks:=array_append(cancel_tasks,task_id_to_change);
 ELSIF entry->>'action'='RECONCILE_RUN' THEN
  NULL; -- Retain immutable terminal Task/Attempt history; complete only a stale active Run.
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
   ({producer_guard}r.desired_state<>'STOPPED' OR r.observed_state<>'TERMINATED' OR EXISTS (
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
 {cancel_offloads}
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
 'retriesExpired',expired,'tasksCancelled',cancelled,'tasksSkipped',skipped,'runsReconciled',runs,
 'offloadsFailed',offloads_failed,'offloadsCancelled',offloads_cancelled,'offloadsCompleted',offloads_completed,'afterGuard',({guard})));
END
""".format(identity=identity, guard=guard_query, producer_guard=producer_guard, before=literal(canonical(plan['beforeGuard']).decode()),
           entries=literal(canonical(plan['entries']).decode()),
           cancel_offloads=("UPDATE edgeai.task_offload SET state='CANCELLED',failure_reason=NULL,updated_at=transaction_timestamp()"
               " WHERE task_id=victim.id AND state IN ('DRAINING','STARTING','CANCELLING');"
               " GET DIAGNOSTICS changed=ROW_COUNT; offloads_cancelled:=offloads_cancelled+changed;"
               if plan.get('reconcileOffloads') else ''))
    return ("BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s'; LOCK TABLE " +
            ','.join('edgeai.' + name for name in tables) + ' IN SHARE ROW EXCLUSIVE MODE;\n'
            'CREATE TEMP TABLE failure_result(value jsonb) ON COMMIT DROP; DO ' + literal(body) +
            '; SET CONSTRAINTS ALL IMMEDIATE; SELECT value FROM failure_result; COMMIT;\n')
