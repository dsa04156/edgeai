"""Recorded STREAM transfer outcomes. Producer and broker proof is supplied by the caller."""
from datetime import datetime,timedelta

from postgres_backup import Blocked


def instant(value):
    return datetime.fromisoformat(value.replace('Z','+00:00'))


def recorded_failures(catalog,ids):
    return [o for o in catalog['offloads'] if o['state']=='FAILED' and o['failure_reason']=='TARGET_FAILED' and
        (o['task_id'] in ids or any(m['task_id'] in ids for m in o['members']))]


def classify(catalog,ids,members):
    operations=[o for o in catalog['offloads'] if o['state'] in ('DRAINING','STARTING','CANCELLING') and
        (o['task_id'] in ids or any(m['task_id'] in ids for m in o['members']))]
    operations+=recorded_failures(catalog,ids)
    if len(operations)!=1:return None,'STREAM_TRANSFER_IDENTITY_REQUIRES_RECONCILIATION'
    operation=operations[0];plan=operation['members'];tasks={r['task']['id']:r for r in members}
    failed=operation['state']=='FAILED'
    if set(ids)!={m['task_id'] for m in plan}:return None,'INCOMPLETE_STREAM_TRANSFER_MEMBERSHIP'
    if (operation['task_id'] not in ids or operation['remote_provider_key'] is not None or
            operation['namespace']!=catalog['configuration']['namespace'] or
            operation['failure_reason']!=('TARGET_FAILED' if failed else None)):
        raise Blocked('Recorded STREAM transfer identity or state is inconsistent')
    selected=next(m for m in plan if m['task_id']==operation['task_id'])
    if any(selected[k]!=operation[k] for k in ('source_attempt_id','target_attempt_id','target_node_id','excluded_node_names')):
        raise Blocked('Selected STREAM transfer member differs from its operation')
    if selected['target_vd_id'] is not None:raise Blocked('Selected STREAM transfer cannot invent a VD destination')
    if operation['trigger']!='MANUAL' and (catalog['run']['offload_policy'] is None or
            not isinstance(operation['decision'],dict) or operation['decision'].get('policy')!=catalog['run']['offload_policy']):
        raise Blocked('Automatic STREAM transfer must retain its original decision policy')
    has_targets=[m['target_attempt_id'] is not None for m in plan]
    if any(has_targets)!=all(has_targets):return None,'INCOMPLETE_STREAM_TRANSFER_TARGETS'
    targets=all(has_targets)
    if (operation['state']=='DRAINING' and (targets or operation['start_deadline'] is not None) or
            (operation['state']=='STARTING' or failed) and (not targets or operation['start_deadline'] is None)):
        raise Blocked('Recorded STREAM transfer phase differs from its target history')
    checkpoints={c['id']:c for c in catalog['checkpoints']};starts=set()
    for member in plan:
        row=tasks[member['task_id']];attempts={a['id']:a for a in row['attempts']}
        source=attempts.get(member['source_attempt_id']);target=attempts.get(member['target_attempt_id']) if targets else None
        expected=member['target_attempt_id'] or member['source_attempt_id']
        if not row['attempts'] or row['attempts'][0]['id']!=expected:return None,'NEWER_STREAM_TRANSFER_ATTEMPT_RECORDED'
        if (source is None or source['state']!='OFFLOADED' or source['mode'] not in ('AUTO','NODE','VD') or
                row['retry'] is not None or row['result'] is not None or row['finalizing']):
            return None,'STREAM_TRANSFER_OUTCOME_OR_FINALIZATION_REQUIRES_RECONCILIATION'
        if source['mode']=='VD' and operation['trigger']!='MANUAL':raise Blocked('Automatic VD STREAM transfers are unsupported')
        runtime=next((r for r in row['runtimes'] if r['attempt_id']==source['id']),None)
        checkpoint=checkpoints.get(member['checkpoint_id'])
        if (runtime is None or runtime['producer_pod_uid'] is None or checkpoint is None or
                any(checkpoint[k]!=v for k,v in {'task_id':member['task_id'],'attempt_id':source['id'],'runtime_id':runtime['id'],
                    'epoch':source['epoch'],'producer_pod_uid':runtime['producer_pod_uid'],'run_id':operation['run_id']}.items())):
            raise Blocked('STREAM transfer must preserve the pinned source checkpoint and producer')
        if member['task_id']!=operation['task_id'] and any(member[k]!=source[v] for k,v in
                [('target_node_id','node_id'),('target_vd_id','vd_id'),('excluded_node_names','excluded_node_names')]):
            raise Blocked('STREAM transfer peer placement changed')
        if targets:
            mode='VD' if member['target_vd_id'] else 'NODE' if member['target_node_id'] else 'AUTO'
            if (target is None or target['cause']!='OFFLOAD' or target['epoch']!=source['epoch']+1 or
                    target['number']!=source['number']+1 or target['mode']!=mode or any(target[k]!=member[v] for k,v in
                    [('node_id','target_node_id'),('vd_id','target_vd_id'),('excluded_node_names','excluded_node_names')]) or
                    not any(r['attempt_id']==target['id'] for r in row['runtimes'])):
                raise Blocked('STREAM transfer successor differs from its frozen member plan')
            starts.add(instant(target['created_at']))
    if targets and (operation['start_deadline'] is None or len(starts)!=1 or instant(operation['start_deadline'])!=next(iter(starts))+timedelta(seconds=operation['start_timeout_seconds'])):
        raise Blocked('STREAM transfer start deadline differs from its original group start')
    states={r['task']['state'] for r in members}
    action=None
    if failed:
        # RuntimeLifecycleService.recordFailure and JdbcOffloadRepository.failedAttempt
        # record this outcome atomically. Never repair a contradictory snapshot by guessing.
        reasons={'WORKLOAD_FAILED','TIMEOUT','INPUT_INVALID','OUTPUT_INVALID','STORAGE_FAILED','CANCELLED',
            'RUNNER_FAILED','DISPATCH_TIMEOUT','RUNTIME_TIMEOUT','RUNTIME_LOST','JOB_FAILED','RESULT_MISSING','OWNERSHIP_CONFLICT'}
        if 'FAILED' not in states or not states<= {'FAILED','CANCELLING','CANCELLED','SKIPPED'}:
            return None,'INCOMPLETE_RECORDED_STREAM_TARGET_FAILURE'
        for row in members:
            attempt=row['attempts'][0]
            runtime=next(r for r in row['runtimes'] if r['attempt_id']==attempt['id'])
            if row['task']['state']=='FAILED':
                if attempt['state']!='FAILED' or runtime['failure_reason'] not in reasons:
                    return None,'INCONSISTENT_RECORDED_STREAM_TARGET_FAILURE'
            elif (not row['task']['cancellation_reason'] or
                    attempt['state'] not in ('CANCELLING','CANCELLED','FAILED')):
                return None,'INCOMPLETE_RECORDED_STREAM_FAILURE_CANCELLATION'
        # The failed Operation, Attempt and runtime already contain the authoritative result.
        # Only recorded peer cancellation and the stale Run state may need completion.
        return {'taskIds':ids,'action':'CANCEL_GROUP','recordedFailureOperationId':operation['id']},None
    if operation['state']=='CANCELLING' or states & {'CANCELLING','CANCELLED','SKIPPED'}:
        if not states<= {'CANCELLING','CANCELLED','SKIPPED','FAILED'} or any(
                r['task']['state'] in ('CANCELLING','CANCELLED','SKIPPED') and not r['task']['cancellation_reason'] for r in members):
            return None,'INCOMPLETE_STREAM_TRANSFER_CANCELLATION'
        action='CANCEL_OFFLOAD_GROUP'
    elif catalog['run']['state']!='RUNNING':return None,'STREAM_TRANSFER_RUN_REQUIRES_RECONCILIATION'
    elif operation['state']=='DRAINING' and states=={'OFFLOADING'}:action='CHECK_OFFLOAD_DRAIN'
    elif operation['state']=='STARTING' and states<= {'READY','RUNNING'} and all(
            r['attempts'][0]['state'] in ('QUEUED','DISPATCHING','RUNNING') and all(
                runtime['failure_reason'] is None for runtime in r['runtimes'] if runtime['attempt_id']==r['attempts'][0]['id'])
            for r in members):
        if any(r['task']['cancellation_reason'] is not None for r in members):
            raise Blocked('STREAM admission conflicts with a recorded cancellation reason')
        for row in members:
            runtime=next(r for r in row['runtimes'] if r['attempt_id']==row['attempts'][0]['id'])
            kind={'KUBERNETES':'kubernetes','VD':'vd'}.get(runtime['runtime_kind'])
            evidence=catalog['startEvidence'].get(kind)
            record=None if evidence is None else evidence['records'].get(runtime['id'])
            if record is None:return None,'STREAM_GROUP_START_AUTHORITY_NOT_PROVEN'
            if record['authority']['offloadId']!=operation['id']:
                raise Blocked('STREAM member admission belongs to another transfer')
        action='COMPLETE_STREAM_OFFLOAD'
    else:return None,'STREAM_TRANSFER_OUTCOME_REQUIRES_SEPARATE_RECOVERY'
    return {'taskIds':ids,'action':action,'operationId':operation['id']},None
