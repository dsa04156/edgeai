"""Validate sealed finalizer retry history before applying its original expiry policy."""
from datetime import datetime, timedelta

from postgres_backup import Blocked


def instant(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def classify(catalog, ids, members):
    policy = catalog['run']
    if policy['state'] != 'RUNNING':
        return None, 'FINALIZER_RETRY_RUN_REQUIRES_RECONCILIATION'
    if any(row['task']['state'] not in ('RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED') for row in members):
        return None, 'FINALIZER_PEER_OUTCOME_REQUIRES_RECONCILIATION'
    checkpoints = {c['id']: c for c in catalog['checkpoints']}
    completions = {c['attempt_id']: c for c in catalog['completions']}
    inherited = {r['attempt_id']: r for r in catalog['finalizationRecoveries']}
    retries = []
    for row in members:
        task = row['task']; queue = row['retry']
        if task['state'] != 'RETRY_WAIT':
            if queue is not None:
                raise Blocked('Terminal finalizer peer retains a contradictory retry queue')
            continue
        attempts = {a['id']: a for a in row['attempts']}
        if not row['attempts'] or queue is None or row['result'] is not None:
            raise Blocked('Finalizer retry requires its failed attempt and original queue')
        latest = row['attempts'][0]
        grant_id = inherited.get(latest['id'], {}).get('granted_attempt_id', latest['id'])
        grant = completions.get(grant_id)
        if grant is None or grant['granted_at'] is None:
            return None, 'UNSEALED_RETRY_CANNOT_USE_FINALIZER_RECOVERY'
        checkpoint = checkpoints.get(grant['checkpoint_id'])
        original = attempts.get(grant_id)
        if original is None or checkpoint is None:
            raise Blocked('Sealed finalizer source is absent from its own task history')
        source = next((r for r in row['runtimes'] if r['attempt_id'] == grant_id), None)
        current = next((r for r in row['runtimes'] if r['attempt_id'] == latest['id']), None)
        if (source is None or current is None or latest['state'] != 'FAILED' or
                queue['failed_attempt_id'] != latest['id'] or queue['task_id'] != task['id'] or
                any(checkpoint[k] != v for k, v in {
                    'task_id': task['id'], 'run_id': policy['id'], 'attempt_id': grant_id,
                    'runtime_id': source['id'], 'epoch': original['epoch'],
                    'producer_pod_uid': source['producer_pod_uid']}.items()) or
                source['producer_pod_uid'] is None or
                checkpoint['id'] != max((c for c in catalog['checkpoints'] if c['task_id'] == task['id']), key=lambda c: c['serial'])['id'] or
                instant(grant['granted_at']) < instant(grant['created_at']) or
                instant(grant['created_at']) < instant(checkpoint['created_at']) or
                instant(grant['granted_at']) > instant(latest['updated_at'])):
            raise Blocked('Finalizer retry contradicts its sealed checkpoint or producer')
        seen = set(); cursor = latest
        while cursor['id'] != grant_id:
            link = inherited.get(cursor['id'])
            if cursor['id'] in seen or link is None or link['granted_attempt_id'] != grant_id:
                raise Blocked('Finalizer inheritance does not reach the original grant')
            seen.add(cursor['id']); predecessor = attempts.get(link['predecessor_attempt_id'])
            if (predecessor is None or predecessor['state'] != 'FAILED' or cursor['cause'] != 'RETRY' or
                    cursor['mode'] not in ('AUTO', 'NODE', 'VD') or
                    cursor['epoch'] != predecessor['epoch'] + 1 or cursor['number'] != predecessor['number'] + 1 or
                    instant(link['created_at']) < instant(grant['granted_at']) or
                    instant(link['created_at']) != instant(cursor['created_at'])):
                raise Blocked('Finalizer successor differs from its recorded inheritance')
            cursor = predecessor
        if {a for a in inherited if a in attempts} != seen:
            raise Blocked('Finalizer inheritance contains an unaccounted successor')
        first = min(row['attempts'], key=lambda a: a['number'])
        deadline = instant(first['created_at']) + timedelta(seconds=policy['retry_max_elapsed_seconds'])
        available = instant(latest['updated_at']) + timedelta(seconds=policy['retry_backoff_seconds'])
        if (queue['namespace'] != current['namespace'] or instant(queue['deadline']) != deadline or
                instant(queue['available_at']) != available or available >= deadline or
                current['failure_reason'] not in policy['retry_on'] or
                sum(a['cause'] != 'OFFLOAD' for a in row['attempts']) >= policy['retry_max_attempts']):
            raise Blocked('Finalizer retry differs from its original task budget')
        retries.append(task['id'])
    if not retries:
        return None, 'NO_RECORDED_FINALIZER_RETRY'
    return {'taskIds': ids, 'action': 'CHECK_FINALIZER_RETRIES', 'retryTaskIds': sorted(retries)}, None
