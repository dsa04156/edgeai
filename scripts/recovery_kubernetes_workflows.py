"""Reconcile recorded BATCH outcomes after proven Kubernetes/VD retirement; retain quarantine."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path

from postgres_backup import Blocked, Postgres, literal, private_file
from recovery_kubernetes import database_inventory
import recovery_kubernetes_retire as retirement
from recovery_remote_inventory import canonical, DATABASE_QUERY as REMOTE_QUERY
from recovery_remote_retire import durable_json
from recovery_remote_results import CONTEXT_QUERY as REMOTE_CONTEXT_QUERY
from recovery_remote_workflow_evidence import observe as observe_remote
from recovery_workflow_failures import transaction_sql as workflow_transaction
import recovery_batch_offloads as offloads

TABLES = (*retirement.TABLES, 'task_retry', 'task_definition', 'task_dependency',
          'workflow_version', 'profile_version', 'task_offload', 'task_offload_member', 'remote_allocation')
GUARD_QUERY = 'SELECT jsonb_build_object(' + ','.join(
    literal(name) + ", (SELECT encode(sha256(convert_to(coalesce(string_agg("
    "encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'),'' ORDER BY " +
    retirement.TABLES.get(name,'to_jsonb(t)::text') + "),''),'UTF8')),'hex') FROM edgeai." + name + ' t)' for name in TABLES) + ')'
CONTEXT_QUERY = """
SELECT coalesce(jsonb_agg(jsonb_build_object(
 'runtime',to_jsonb(r),'attempt',to_jsonb(a),'task',to_jsonb(t),'runState',w.state,
 'latestEpoch',(SELECT max(epoch) FROM edgeai.task_attempt WHERE task_id=t.id),
 'retryPending',EXISTS(SELECT FROM edgeai.task_retry WHERE task_id=t.id),
 'hasStream',EXISTS(SELECT FROM edgeai.task_dependency WHERE workflow_version_id=w.workflow_version_id AND mode='STREAM'),
 'activeOffload',EXISTS(SELECT FROM edgeai.task_offload WHERE run_id=w.id AND state IN ('DRAINING','STARTING','CANCELLING')),
 'failedDrain',EXISTS(SELECT FROM edgeai.task_offload WHERE task_id=t.id AND source_attempt_id=a.id
   AND target_attempt_id IS NULL AND state='FAILED' AND failure_reason='SOURCE_DRAIN_TIMEOUT'),
 'pendingCommands',EXISTS(SELECT FROM edgeai.runtime_command WHERE runtime_id=r.id
   AND (NOT completed OR lease_owner IS NOT NULL OR lease_until IS NOT NULL)),
 'allocation',(SELECT to_jsonb(v) FROM edgeai.vd_task_allocation v WHERE v.runtime_id=r.id),
 'result',(SELECT to_jsonb(s) FROM edgeai.task_result s WHERE s.task_id=t.id)
) ORDER BY r.id),'[]'::jsonb)
FROM edgeai.runtime_instance r JOIN edgeai.task_attempt a ON a.id=r.attempt_id
JOIN edgeai.task t ON t.id=r.task_id JOIN edgeai.workflow_run w ON w.id=t.run_id
WHERE r.runtime_kind IN ('KUBERNETES','VD')
"""
CATALOG_SQL = ("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT jsonb_build_object("
    "'database',current_database(),'oid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),"
    "'marker',(SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()),"
    "'readOnly',current_setting('transaction_read_only'),'migrations',(SELECT json_agg(json_build_object("
    "'version',version,'success',success)) FROM edgeai.flyway_schema_history WHERE version IS NOT NULL),"
    "'guard',(" + GUARD_QUERY + "),'contexts',(" + CONTEXT_QUERY + "),'offloads',(" + offloads.QUERY +
    "),'remoteSnapshot',("+REMOTE_QUERY.strip().removesuffix(';')+"),'remoteContexts',("+REMOTE_CONTEXT_QUERY+')); COMMIT;')


def entry_for(context, action):
    runtime, attempt, task = (context[k] for k in ('runtime','attempt','task'))
    return {'runtimeId':runtime['id'], 'attemptId':attempt['id'], 'taskId':task['id'],
            'runId':task['run_id'], 'action':action, 'reason':runtime['failure_reason']}


def prepare(pg, args):
    retired = retirement.prepare(pg, args)
    catalog = database_inventory(pg, args.database, args.restore_report, CATALOG_SQL)
    if (any(retired[key] != catalog[other] for key, other in
            [('databaseOid','oid'), ('marker','marker'), ('restoreReportSha256','restoreReportSha256')]) or
            any(retired['beforeGuard'][t] != catalog['guard'][t] for t in retirement.TABLES)):
        raise Blocked('Restored retirement inventory changed during workflow snapshot')
    proven = set(retired['selected']['runtimes'] + retired['selected']['vdTasks'])
    remote_evidence,remote_outcomes=observe_remote(catalog,args)
    proven.update(remote_outcomes)
    entries, unresolved, retained = [], [], 0
    reconcile_offloads=getattr(args,'offloads',False)
    offload_entries, unresolved_offloads = (offloads.classify(catalog['offloads'],proven,args.namespace,
        remote_outcomes if remote_evidence is not None else None,
        remote_evidence['binding'] if remote_evidence is not None else None)
        if reconcile_offloads else ([],[]))
    for context in catalog['contexts']:
        runtime, attempt, task = (context[k] for k in ('runtime','attempt','task'))
        rid = runtime['id']
        if rid not in proven:
            continue  # The retirement inventory already identifies the unproven producer.
        if (runtime['desired_state'] != 'STOPPED' or runtime['observed_state'] != 'TERMINATED' or
                context['pendingCommands'] or runtime['runtime_kind'] == 'VD' and
                (context['allocation'] is None or context['allocation']['closed_at'] is None)):
            raise Blocked('Complete physical retirement before reconciling workflow state')
        if (runtime['task_id'] != attempt['task_id'] or task['run_id'] != runtime['run_id'] or
                runtime['epoch'] != attempt['epoch'] or
                (runtime['runtime_kind'] == 'VD' and (attempt['mode'] != 'VD' or attempt['vd_id'] != runtime['vd_id'])) or
                (runtime['runtime_kind'] == 'KUBERNETES' and attempt['mode'] not in ('AUTO','NODE'))):
            raise Blocked('Frozen attempt and retired runtime identity differ')
        if attempt['epoch'] != context['latestEpoch']:
            retained += 1; continue
        if context['hasStream'] or context['activeOffload']:
            unresolved.append({'runtimeId':rid, 'reason':'STREAM_OR_ACTIVE_OFFLOAD'}); continue
        result = context['result']
        if result is not None:
            if (not result['committed'] or result['runtime_id'] != rid or result['attempt_id'] != attempt['id'] or
                    result['epoch'] != attempt['epoch'] or task['state'] != 'SUCCEEDED' or
                    attempt['state'] != 'SUCCEEDED' or context['retryPending']):
                raise Blocked('Existing immutable Result conflicts with current Task/Attempt')
            entries.append(entry_for(context,'RECONCILE_RUN')); retained += 1; continue
        action, reason = None, runtime['failure_reason']
        if task['state'] == 'CANCELLING':
            if not task['cancellation_reason'] or attempt['state'] not in ('CANCELLING','CANCELLED','FAILED','OFFLOADED'):
                raise Blocked('Cancellation must retain its recorded reason and attempt state')
            action = 'CANCEL'
        elif task['state'] == 'RETRY_WAIT':
            if attempt['state'] != 'FAILED' or not context['retryPending'] or not reason:
                raise Blocked('Retry queue differs from recorded failure')
            action = 'CHECK_RETRY'
        elif task['state'] == 'FAILED':
            failed_drain=attempt['state']=='OFFLOADED' and context['failedDrain']
            if context['retryPending'] or not failed_drain and (attempt['state'] != 'FAILED' or not reason):
                raise Blocked('Final failure differs from recorded attempt')
            action = 'FINAL_FAILURE'
        elif task['state'] in ('CANCELLED','SKIPPED'):
            if attempt['state'] not in ('CANCELLED','FAILED','OFFLOADED') or context['retryPending']:
                raise Blocked('Cancelled history conflicts with current attempt')
            entries.append(entry_for(context,'RECONCILE_RUN')); retained += 1; continue
        elif task['state'] == 'RUNNING' and attempt['state'] in ('DISPATCHING','RUNNING'):
            if (context['runState'] != 'RUNNING' or task['cancellation_reason'] is not None or
                    context['retryPending'] or reason is not None):
                raise Blocked('Active work has contradictory failure or cancellation history')
            unresolved.append({'runtimeId':rid, 'reason':'OUTCOME_NOT_RECORDED'}); continue
        else:
            unresolved.append({'runtimeId':rid, 'reason':'WORKFLOW_STATE_REQUIRES_SEPARATE_RECOVERY'}); continue
        entries.append(entry_for(context,action))
    return {'formatVersion':1, 'scope':'restored-kubernetes-workflow-reconciliation',
        'targetDatabase':args.database, 'databaseOid':catalog['oid'], 'marker':catalog['marker'],
        'restoreReportSha256':catalog['restoreReportSha256'], 'beforeGuard':catalog['guard'],
        'evidence':retired['evidence'], 'provenRuntimes':sorted(proven), 'entries':entries+offload_entries,
        'unclaimedJobs':retired['unclaimedJobs'],
        'remoteEvidence':remote_evidence,
        'unresolvedProducers':retired['unresolved'], 'unresolvedWorkflows':unresolved,
        'reconcileOffloads':reconcile_offloads, 'unresolvedOffloads':unresolved_offloads,
        'historiesRetained':retained, 'preparedAt':datetime.now(timezone.utc).isoformat()}


def transaction_sql(plan):
    return workflow_transaction(plan, TABLES, GUARD_QUERY, plan['provenRuntimes'])


def apply(pg, args, plan):
    before = prepare(pg, args)
    if any(before[k] != plan[k] for k in plan if k != 'preparedAt'):
        raise Blocked('Recovery inputs changed before workflow reconciliation')
    durable_json(args.output/'intent.json', plan)
    sql = args.output/'transaction.sql'
    with private_file(sql, 'w') as target:
        target.write(transaction_sql(plan)); target.flush(); os.fsync(target.fileno())
    with sql.open('rb') as source:
        result = json.loads(pg.call('psql',['-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-f','-'],
                                   args.database,source=source,timeout=45,reject_stderr=True))
    after = prepare(pg, args)
    if (after['beforeGuard'] != result['afterGuard'] or any(after[k] != plan[k] for k in
            ('targetDatabase','databaseOid','marker','restoreReportSha256','evidence','provenRuntimes','unresolvedProducers','unclaimedJobs','remoteEvidence'))):
        raise Blocked('Post-commit recovery evidence changed; retain quarantine')
    report = {k:plan[k] for k in ('formatVersion','scope','targetDatabase','databaseOid')}
    report.update(**result, status='RECORDED_KUBERNETES_WORKFLOWS_RECONCILED', activated=False,
        globalQuiescenceProven=False, databaseModified=any(v for k,v in result.items() if k != 'afterGuard'),
        namespaceUid=args.namespace_uid, recoveryId=args.recovery_id,
        pendingRetries=sum(e['action']=='CHECK_RETRY' for e in after['entries']),
        unresolvedProducers=after['unresolvedProducers'], unresolvedWorkflows=after['unresolvedWorkflows'],
        unclaimedJobs=after['unclaimedJobs'],
        remoteEvidence=after['remoteEvidence'],
        unresolvedOffloads=after['unresolvedOffloads'],
        pendingOffloads=sum(e['action'].startswith('CHECK_OFFLOAD_') for e in after['entries']),
        reconcileOffloads=after['reconcileOffloads'],
        historiesRetained=after['historiesRetained'],
        intentSha256=hashlib.sha256((args.output/'intent.json').read_bytes()).hexdigest(),
        verifiedAt=datetime.now(timezone.utc).isoformat(),
        excluded=['unknown-outcomes','retry-dispatch','result-recovery',
                  'unproven-offloads-and-stream-recovery' if after['reconcileOffloads'] else 'active-offload-and-stream-recovery',
                  'device-journals','global-recovery-acceptance','service-activation'])
    durable_json(args.output/'workflows.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('context','namespace','namespace-uid','recovery-id','database'):
        parser.add_argument('--'+name,required=True)
    for name in ('restore-report','termination-report','output'):
        parser.add_argument('--'+name,required=True,type=Path)
    parser.add_argument('--transport',choices=['native','compose'],default='native')
    parser.add_argument('--pg-bin',type=Path)
    parser.add_argument('--timeout',type=int,default=120)
    parser.add_argument('--offloads',action='store_true',help='Also reconcile proven BATCH transfer cancellations and original deadlines')
    parser.add_argument('--unclaimed-jobs',action='store_true',help='Accept retained terminal children of recorded Jobs without inventing producer claims')
    parser.add_argument('--remote-connection',type=Path,help='Private reference Remote connection for fresh mixed BATCH offload evidence')
    args=parser.parse_args(); args.output.mkdir(mode=0o700,parents=True,exist_ok=False); submitted=False
    try:
        if not 1<=args.timeout<=1800: raise ValueError('Timeout must be between 1 and 1800 seconds')
        pg=Postgres(args.transport,args.pg_bin,diagnostics=args.output/'postgres')
        plan=prepare(pg,args); submitted=True; report=apply(pg,args,plan)
        print(report['status']+': '+str(report['pendingRetries'])+' retries remain queued; database quarantined')
        return 0
    except Exception as error:
        blocked=isinstance(error,(Blocked,OSError))
        durable_json(args.output/'failure.json',{'status':'BLOCKED' if blocked else 'FAIL',
            'failureType':type(error).__name__,'activated':False,'databaseModified':None if submitted else False,
            'instruction':'Keep quarantine/fence/Pods and intent; repeat the same recovery in a new output directory'})
        print(('BLOCKED' if blocked else 'FAIL')+': Kubernetes workflow recovery; private evidence retained; keep quarantine')
        return 2 if blocked else 1


if __name__=='__main__': raise SystemExit(main())
