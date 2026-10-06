"""Classify recorded BATCH transfers using the caller's retained Kubernetes termination proof."""
from postgres_backup import Blocked
from recovery_remote_start_receipts import instant

QUERY = """
SELECT coalesce(jsonb_agg(jsonb_build_object(
 'operation',to_jsonb(o),'task',to_jsonb(t),'source',to_jsonb(s),'target',to_jsonb(a),
 'runState',(SELECT state FROM edgeai.workflow_run WHERE id=t.run_id),
 'latestEpoch',(SELECT max(epoch) FROM edgeai.task_attempt WHERE task_id=t.id),
 'runtimes',(SELECT coalesce(jsonb_agg(to_jsonb(r)),'[]'::jsonb) FROM edgeai.runtime_instance r WHERE r.task_id=t.id),
 'hasResult',EXISTS(SELECT FROM edgeai.task_result WHERE task_id=t.id),
 'retryPending',EXISTS(SELECT FROM edgeai.task_retry WHERE task_id=t.id),
 'hasMembers',EXISTS(SELECT FROM edgeai.task_offload_member WHERE operation_id=o.id),
 'hasStream',(EXISTS(SELECT FROM edgeai.task_dependency WHERE workflow_version_id=t.workflow_version_id AND mode='STREAM')
   OR EXISTS(SELECT FROM edgeai.data_route WHERE run_id=t.run_id))
) ORDER BY o.id),'[]'::jsonb) FROM edgeai.task_offload o JOIN edgeai.task t ON t.id=o.task_id
JOIN edgeai.task_attempt s ON s.id=o.source_attempt_id LEFT JOIN edgeai.task_attempt a ON a.id=o.target_attempt_id
WHERE o.state IN ('DRAINING','STARTING','CANCELLING')
"""


def classify(rows, proven, namespace, remote_outcomes=None, remote_binding=None, runtime_starts=None):
    entries, unresolved, blocked_runs = [], [], set()
    for row in rows:
        op, task, source, target = (row[k] for k in ('operation','task','source','target'))
        runtimes = {r['attempt_id']:r for r in row['runtimes']}
        required = [source] + ([target] if target else [])
        reason,remote_failure,remote_success,kubernetes_start = None,None,False,False
        if op['namespace'] != namespace:
            reason='OUTSIDE_SELECTED_NAMESPACE'
        elif row['hasStream'] or row['hasMembers']:
            reason='STREAM_OFFLOAD_REQUIRES_SEPARATE_RECOVERY'
        elif remote_outcomes is None and (op['remote_provider_key'] is not None or any(r['runtime_kind']=='REMOTE' for r in row['runtimes'])):
            reason='REMOTE_OFFLOAD_REQUIRES_PROVIDER_EVIDENCE'
        elif any(a['id'] not in runtimes for a in required) or any(r['id'] not in proven for r in runtimes.values()):
            reason='OFFLOAD_PRODUCER_NOT_PROVEN'
        if reason:
            unresolved.append({'operationId':op['id'],'reason':reason}); blocked_runs.add(task['run_id']); continue
        if op['remote_provider_key'] is not None and (remote_binding is None or any(
                op['remote_'+key]!=remote_binding[key] for key in ('provider_key','configuration_digest','source_mode'))):
            raise Blocked('Offload destination differs from the freshly observed Remote binding')
        if (op['task_id'] != task['id'] or op['run_id'] != task['run_id'] or source['task_id'] != task['id'] or
                source['state'] != 'OFFLOADED' or op['failure_reason'] is not None or row['hasResult'] or row['retryPending'] or
                (runtimes[source['id']]['runtime_kind']!='REMOTE' and runtimes[source['id']]['producer_pod_uid'] is None) or
                any(r['runtime_kind']=='REMOTE' and r['id'] not in (remote_outcomes or {}) for r in runtimes.values()) or
                any(r['namespace'] != namespace or r['desired_state'] != 'STOPPED' or r['observed_state'] != 'TERMINATED'
                    for r in runtimes.values())):
            raise Blocked('Recorded offload contradicts its retired source, outcome or queue')
        if target:
            remote_target=op['remote_provider_key'] is not None
            if (target['task_id'] != task['id'] or target['cause'] != 'OFFLOAD' or target['epoch'] <= source['epoch'] or
                    target['mode'] != ('REMOTE' if remote_target else 'NODE' if op['target_node_id'] else 'AUTO') or
                    target['node_id'] != op['target_node_id'] or target['excluded_node_names'] != op['excluded_node_names'] or
                    any(target['remote_'+key]!=op['remote_'+key] for key in ('provider_key','configuration_digest','source_mode'))):
                raise Blocked('Offload target differs from its frozen placement')
            if remote_target and op['state']=='STARTING' and task['state'] in ('READY','RUNNING'):
                outcome=(remote_outcomes or {}).get(runtimes[target['id']]['id'],{})
                if outcome.get('state')=='FAILED':
                    if (task['state']!='RUNNING' or row['runState']!='RUNNING' or target['state'] not in ('DISPATCHING','RUNNING') or
                            task['cancellation_reason'] is not None or runtimes[target['id']]['failure_reason'] is not None):
                        raise Blocked('Remote target failure contradicts the recorded active work')
                    remote_failure={'LEASE_EXPIRED':'RUNTIME_TIMEOUT','PROVIDER_RESTART':'RUNTIME_LOST'}.get(
                        outcome['failureReason'],outcome['failureReason'])
                    if remote_failure not in ('RUNTIME_TIMEOUT','RUNTIME_LOST','WORKLOAD_FAILED','INPUT_INVALID','OUTPUT_INVALID'):
                        raise Blocked('Unsupported Remote target failure')
                elif outcome.get('state')=='SUCCEEDED':
                    receipt=outcome.get('startReceipt')
                    if receipt is None:
                        unresolved.append({'operationId':op['id'],'reason':'REMOTE_TARGET_OUTCOME_REQUIRES_RECONCILIATION'})
                        blocked_runs.add(task['run_id']);continue
                    authority=receipt['authority']
                    if (task['state']!='RUNNING' or row['runState']!='RUNNING' or target['state'] not in ('DISPATCHING','RUNNING') or
                            task['cancellation_reason'] is not None or runtimes[target['id']]['failure_reason'] is not None or
                            authority['offloadId']!=op['id'] or instant(authority['startDeadline'])!=instant(op['start_deadline']) or
                            instant(receipt['acceptedAt'])<max(instant(op['created_at']),instant(target['created_at']),instant(op['updated_at']))):
                        raise Blocked('Remote start admission contradicts its original transfer or active work')
                    remote_success=True
            if (not remote_target and op['state']=='STARTING' and task['state'] in ('READY','RUNNING') and
                    runtimes[target['id']]['runtime_kind']=='KUBERNETES' and runtime_starts is not None):
                record=runtime_starts.get(runtimes[target['id']]['id'])
                if record is None:
                    # Backup absence cannot prove that a post-snapshot claim never
                    # happened; do not turn uncertain admission into a timeout.
                    unresolved.append({'operationId':op['id'],'reason':'KUBERNETES_START_AUTHORITY_NOT_PROVEN'})
                    blocked_runs.add(task['run_id']);continue
                authority=record['authority']
                if (task['state']!='RUNNING' or row['runState']!='RUNNING' or target['state'] not in ('DISPATCHING','RUNNING') or
                        task['cancellation_reason'] is not None or runtimes[target['id']]['failure_reason'] is not None or
                        authority['offloadId']!=op['id']):
                    raise Blocked('Kubernetes admission contradicts its original transfer or active work')
                kubernetes_start=True
        if row['latestEpoch'] != (target or source)['epoch']:
            unresolved.append({'operationId':op['id'],'reason':'NEWER_ATTEMPT_RECORDED'}); blocked_runs.add(task['run_id']); continue
        if (op['state']=='DRAINING' and (target is not None or op['start_deadline'] is not None) or
                op['state']=='STARTING' and (target is None or op['start_deadline'] is None)):
            raise Blocked('Offload phase contradicts the recorded target/deadline')
        if task['state'] in ('CANCELLING','CANCELLED','SKIPPED'):
            if not task['cancellation_reason']:
                raise Blocked('Offload cancellation requires its recorded Task reason')
            action='CANCEL_OFFLOAD'
        elif task['state']=='FAILED':
            if target is None or target['state']!='FAILED' or not runtimes[target['id']]['failure_reason']:
                raise Blocked('Active offload failure requires a recorded failed target')
            action='CANCEL_OFFLOAD' if op['state']=='CANCELLING' else 'FAIL_OFFLOAD'
        elif op['state']=='DRAINING' and task['state']=='OFFLOADING':
            action='CHECK_OFFLOAD_DRAIN'
        elif remote_failure is not None:
            action='FAIL_REMOTE_OFFLOAD'
        elif remote_success:
            action='COMPLETE_REMOTE_OFFLOAD'
        elif kubernetes_start:
            action='COMPLETE_KUBERNETES_OFFLOAD'
        elif op['state']=='STARTING' and task['state'] in ('READY','RUNNING') and target['state'] in ('QUEUED','DISPATCHING'):
            action='CHECK_OFFLOAD_START'
        else:
            unresolved.append({'operationId':op['id'],'reason':'OFFLOAD_STATE_REQUIRES_SEPARATE_RECOVERY'})
            blocked_runs.add(task['run_id']); continue
        current=target or source
        entries.append({'runtimeId':runtimes[current['id']]['id'],'attemptId':current['id'],
            'taskId':task['id'],'runId':task['run_id'],'action':action,'reason':remote_failure,'operationId':op['id']})
    # Do not partially resolve a Run whose other transfers still lack producer authority.
    return [e for e in entries if e['runId'] not in blocked_runs], unresolved
