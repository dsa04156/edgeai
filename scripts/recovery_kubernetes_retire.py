"""Reconcile retained Kubernetes termination evidence with a quarantined restored database."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

from postgres_backup import Blocked, Postgres, literal, private_file
from recovery_kubernetes import Kubernetes, database_inventory, classify, MANAGER, RUNTIME, VD
from recovery_references import read_json
from recovery_remote_inventory import canonical
from recovery_remote_retire import durable_json
from recovery_stop_kubernetes import Stop, FENCE, FINALIZER, OPERATION, HARD, object_path, termination_proof

TABLES = {name: 'id' for name in ('runtime_instance', 'runtime_command', 'task_attempt', 'task',
    'workflow_run', 'task_result', 'result_artifact', 'virtual_device', 'vd_runtime',
    'vd_runtime_command', 'vd_runtime_binding', 'vd_operation', 'vd_task_allocation')}
TABLES['flyway_schema_history'] = 'installed_rank'
GUARD_QUERY = 'SELECT jsonb_build_object(' + ','.join(
    literal(name) + ", (SELECT encode(sha256(convert_to(coalesce(string_agg("
    "encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'),'' ORDER BY " + key +
    "),''),'UTF8')),'hex') FROM edgeai." + name + ' t)' for name, key in TABLES.items()) + ')'
CATALOG_SQL = """
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
 'database',current_database(), 'oid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),
 'marker',(SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()),
 'readOnly',current_setting('transaction_read_only'),
 'migrations',(SELECT json_agg(json_build_object('version',version,'success',success))
   FROM edgeai.flyway_schema_history WHERE version IS NOT NULL),
 'runtimes',(SELECT coalesce(json_agg(to_jsonb(r)),'[]'::json) FROM
   (SELECT id,run_id,task_id,attempt_id,epoch,namespace,job_name,job_uid,producer_pod_uid,node_uid,node_name,
    desired_state,observed_state FROM edgeai.runtime_instance WHERE runtime_kind='KUBERNETES') r),
 'vds',(SELECT coalesce(json_agg(to_jsonb(v)),'[]'::json) FROM
   (SELECT id,vd_id,generation,session_id,namespace,pod_name,pod_uid,node_uid,node_name,desired_state,observed_state FROM edgeai.vd_runtime) v),
 'vdTasks',(SELECT coalesce(json_agg(to_jsonb(r) || jsonb_build_object('allocation',to_jsonb(a))),'[]'::json) FROM
   (SELECT id,vd_id,namespace,producer_pod_uid,node_uid,node_name,desired_state,observed_state
    FROM edgeai.runtime_instance WHERE runtime_kind='VD') r LEFT JOIN edgeai.vd_task_allocation a ON a.runtime_id=r.id),
 'guard',(""" + GUARD_QUERY + '));\nCOMMIT;'


def live_evidence(args):
    receipt, digest = read_json(args.termination_report)
    required = {'formatVersion': 1, 'scope': 'observed-kubernetes-producer-termination',
        'status': 'OBSERVED_KUBERNETES_PRODUCERS_TERMINATED', 'namespace': args.namespace,
        'namespaceUid': args.namespace_uid, 'recoveryId': args.recovery_id,
        'activated': False, 'globalQuiescenceProven': False, 'fenceRetained': True}
    if any(receipt.get(k) != v for k,v in required.items()):
        raise ValueError('Successful termination report for this exact recovery scope is required')
    operation = Stop(Kubernetes(args.context), args.namespace, args.namespace_uid, args.recovery_id, args.timeout)
    operation.quota_uid = receipt['quotaUid']
    operation.scope()
    fence = operation.kube.read(object_path('ResourceQuota', args.namespace, FENCE))
    operation.check_fence(fence)
    if fence.get('status', {}).get('hard') != HARD or not operation.probe('Pod') or not operation.probe('Job'):
        raise Blocked('Retained admission fence is not effective')
    jobs, pods = operation.snapshot()
    if any(j['spec'].get('suspend') is not True for j in jobs):
        raise Blocked('A Job has been resumed after termination')
    suspended = sorted([{'name': j['metadata']['name'], 'uid': j['metadata']['uid']} for j in jobs], key=lambda r:r['uid'])
    if suspended != sorted(receipt['suspendedJobs'], key=lambda r:r['uid']):
        raise Blocked('Job roster differs from retained termination report')
    proofs = []
    for pod in pods:
        meta = pod['metadata']
        if FINALIZER not in meta.get('finalizers', []) or meta.get('annotations', {}).get(OPERATION) != args.recovery_id:
            raise Blocked('Pod termination evidence is no longer retained by this recovery')
        proof = termination_proof(pod)
        if proof is None:
            raise Blocked('A producer has no conclusive termination evidence')
        proofs.append({'name': meta['name'], 'uid': meta['uid'], **proof})
    proofs.sort(key=lambda r:r['uid'])
    if receipt['observedPods'] != len(proofs) or proofs != sorted(receipt['terminatedPods'], key=lambda r:r['uid']):
        raise Blocked('Pod roster or actual container termination differs from the report')
    operation.scope()
    # Only identities and terminal observations are persisted, never Pod env, tokens or logs.
    identities = []
    for kind, items in [('Job', jobs), ('Pod', pods)]:
        for item in items:
            classified = operation.owned(kind, item)
            identities.append({k:v for k,v in classified.items() if k not in ('resourceVersion', 'classification')})
    evidence = {'namespace': args.namespace, 'namespaceUid': args.namespace_uid, 'recoveryId': args.recovery_id,
        'quotaUid': receipt['quotaUid'], 'terminationReportSha256': digest, 'jobs': suspended, 'pods': proofs,
        'identities': sorted(identities, key=lambda r:(r['kind'],r['uid']))}
    return evidence, jobs, pods


def unclaimed_job(row, job, pods, classified):
    """Prove retained children of the recorded Job without turning observation into a producer claim."""
    if row['node_uid'] is not None or row['node_name'] is not None or row['observed_state'] not in ('SUBMITTED','TERMINATED'):
        raise Blocked('Unclaimed runtime has contradictory claim or submission history')
    meta, spec, status = job['metadata'], job['spec'], job.get('status',{})
    if (meta.get('deletionTimestamp') or classified[('Job',meta['uid'])]['classification']!='DATABASE_UID_MATCH' or
            spec.get('parallelism',1)!=1 or spec.get('completions',1)!=1 or spec.get('backoffLimit')!=0 or
            spec.get('completionMode','NonIndexed')!='NonIndexed' or
            spec.get('manualSelector',False) or spec.get('managedBy','kubernetes.io/job-controller')!='kubernetes.io/job-controller' or
            spec.get('template',{}).get('spec',{}).get('restartPolicy')!='Never'):
        raise Blocked('Recorded unclaimed Job must retain the single-execution contract')
    children=[]
    for pod in pods:
        owners=[r for r in pod['metadata'].get('ownerReferences',[]) if r.get('controller') is True]
        owns=any(r.get('uid')==meta['uid'] for r in owners)
        same_attempt=classified[('Pod',pod['metadata']['uid'])].get('identity',{}).get('attempt-id')==row['attempt_id']
        if same_attempt and not owns:
            raise Blocked('An observed runtime Pod belongs to another Job UID')
        if not owns: continue
        if (len(owners)!=1 or owners[0].get('apiVersion')!='batch/v1' or owners[0].get('kind')!='Job' or
                owners[0].get('name')!=meta['name'] or
                classified[('Pod',pod['metadata']['uid'])]['classification']!='DATABASE_UID_NOT_RECORDED'):
            raise Blocked('Unclaimed Job child ownership differs from the restored attempt')
        children.append(pod)
    if not children:
        return None  # A missing child is not evidence that no producer ever ran.
    ids={p['metadata']['uid'] for p in children}
    accounted=status.get('uncountedTerminatedPods',{})
    if (status.get('active',0) or status.get('ready',0) or status.get('terminating',0)>len(children) or
            any(status.get(key,0)>sum(p.get('status',{}).get('phase')==phase for p in children)
                for key,phase in [('succeeded','Succeeded'),('failed','Failed')]) or
            any(uid not in ids for key in ('succeeded','failed') for uid in accounted.get(key,[]))):
        raise Blocked('Job controller still reports activity or unretained terminal children')
    # live_evidence already proves every regular/init container or the never-bound state.
    return {'runtimeId':row['id'],'jobUid':meta['uid'],'podUids':sorted(ids),
            'proof':'OBSERVED_RETAINED_JOB_CHILDREN','producerClaimRecorded':False}


def prepare(pg, args):
    catalog = database_inventory(pg, args.database, args.restore_report, CATALOG_SQL)
    evidence, jobs, pods = live_evidence(args)
    job_uids = {j['metadata']['uid'] for j in jobs}
    classified = {}
    for kind, items in [('Job', jobs), ('Pod', pods)]:
        for item in items:
            row = classify(kind, item, catalog, job_uids)
            if row is None or row['classification'] not in ('DATABASE_UID_MATCH', 'DATABASE_UID_NOT_RECORDED',
                                                           'ABSENT_FROM_RESTORED_DATABASE'):
                raise Blocked('Live producer and restored database ownership or identity conflict')
            classified[(kind,item['metadata']['uid'])] = row
    selected = {'runtimes': [], 'vds': [], 'vdTasks': [], 'vdAllocations': []}
    unresolved, unclaimed = [], []
    for group, rows in [('runtimes', catalog['runtimes']), ('vds', catalog['vds'])]:
        for row in rows:
            reason = None
            if row['namespace'] != args.namespace:
                reason = 'OUTSIDE_SELECTED_NAMESPACE'
            elif group == 'runtimes':
                job = next((j for j in jobs if j['metadata']['uid'] == row['job_uid']), None)
                pod = next((p for p in pods if p['metadata']['uid'] == row['producer_pod_uid']), None)
                if job is not None and row['producer_pod_uid'] is None and getattr(args,'unclaimed_jobs',False):
                    proof=unclaimed_job(row,job,pods,classified)
                    if proof is None: reason='RETAINED_JOB_CHILDREN_NOT_PROVEN'
                    else: unclaimed.append(proof)
                elif job is None or pod is None:
                    reason = 'JOB_OR_CLAIMED_PRODUCER_NOT_PROVEN'
                elif (classified[('Job',row['job_uid'])]['classification'] != 'DATABASE_UID_MATCH' or
                      classified[('Pod',row['producer_pod_uid'])]['classification'] != 'DATABASE_UID_MATCH' or
                      pod['spec'].get('nodeName') != row['node_name']):
                    raise Blocked('Claimed runtime producer binding differs')
            else:
                pod = next((p for p in pods if p['metadata']['uid'] == row['pod_uid']), None)
                if pod is None:
                    reason = 'VD_PRODUCER_NOT_PROVEN'
                elif (classified[('Pod',row['pod_uid'])]['classification'] != 'DATABASE_UID_MATCH' or
                      row['node_name'] is not None and pod['spec'].get('nodeName') != row['node_name']):
                    raise Blocked('VD producer binding differs')
            if reason:
                unresolved.append({'kind':group, 'id':row['id'], 'reason':reason,
                    'alreadyTerminal':row['desired_state']=='STOPPED' and row['observed_state']=='TERMINATED'})
            else:
                selected[group].append(row['id'])
    for row in catalog['vdTasks']:
        allocation=row['allocation']
        reason=None
        if row['namespace'] != args.namespace:
            reason='OUTSIDE_SELECTED_NAMESPACE'
        elif allocation is None:
            reason='VD_ALLOCATION_NOT_RECORDED'
        else:
            supervisor=next((v for v in catalog['vds'] if v['id']==allocation['vd_runtime_id']),None)
            if supervisor is None or (allocation['runtime_id'],allocation['vd_id'],allocation['generation'],
                    allocation['session_id'],allocation['pod_uid'],row['namespace']) != (
                    row['id'],row['vd_id'],supervisor['generation'],supervisor['session_id'],supervisor['pod_uid'],supervisor['namespace']):
                raise Blocked('VD task allocation and supervisor identity differ')
            if supervisor['vd_id'] != row['vd_id'] or row['producer_pod_uid'] is not None and (
                    row['producer_pod_uid'],row['node_uid'],row['node_name']) != (
                    allocation['pod_uid'],supervisor['node_uid'],supervisor['node_name']):
                raise Blocked('Claimed VD task producer differs from its supervisor')
            if allocation['closed_at'] is not None and (row['desired_state']!='STOPPED' or row['observed_state']!='TERMINATED'):
                raise Blocked('A closed VD allocation has inconsistent runtime history')
            if supervisor['id'] not in selected['vds']:
                reason='VD_SUPERVISOR_NOT_PROVEN'
        if reason:
            unresolved.append({'kind':'vdTasks','id':row['id'],'reason':reason,
                'alreadyTerminal':row['desired_state']=='STOPPED' and row['observed_state']=='TERMINATED'})
        else:
            selected['vdTasks'].append(row['id']); selected['vdAllocations'].append(allocation['id'])
    return {'formatVersion':1, 'scope':'restored-database-kubernetes-retirement',
        'targetDatabase':args.database, 'databaseOid':catalog['oid'], 'marker':catalog['marker'],
        'restoreReportSha256':catalog['restoreReportSha256'], 'beforeGuard':catalog['guard'],
        'evidence':evidence, 'selected':{k:sorted(v) for k,v in selected.items()},
        'unclaimedJobs':sorted(unclaimed,key=lambda r:r['runtimeId']),
        'unresolved':sorted(unresolved,key=lambda r:(r['kind'],r['id'])),
        'objectsAbsentFromDatabase':sum(r['classification']=='ABSENT_FROM_RESTORED_DATABASE' for r in classified.values()),
        'preparedAt':datetime.now(timezone.utc).isoformat()}


def transaction_sql(plan):
    identity = """
IF current_database()<>{database} OR NOT EXISTS (SELECT FROM pg_database WHERE datname=current_database()
 AND oid::text={oid} AND shobj_description(oid,'pg_database')={marker}) THEN
 RAISE EXCEPTION 'Restored database identity changed'; END IF;
""".format(database=literal(plan['targetDatabase']),oid=literal(plan['databaseOid']),marker=literal(plan['marker']))
    # Complete existing CREATE commands before the VD trigger accepts terminal evidence. Close its
    # binding in the same transaction: the existing deferred lifecycle constraint remains enabled.
    statements = []
    for group, runtime, command in [('runtimes','runtime_instance','runtime_command'),
            ('vdTasks','runtime_instance','runtime_command'), ('vds','vd_runtime','vd_runtime_command')]:
        ids = literal(json.dumps(plan['selected'][group])) + '::jsonb'
        where = '(SELECT value::uuid FROM jsonb_array_elements_text(' + ids + '))'
        statements.append("UPDATE edgeai." + command + " SET completed=true,lease_owner=NULL,lease_until=NULL,updated_at=transaction_timestamp()"
            " WHERE runtime_id IN " + where + " AND (NOT completed OR lease_owner IS NOT NULL OR lease_until IS NOT NULL);"
            " GET DIAGNOSTICS changed=ROW_COUNT; commands:=commands+changed;\n"
            "UPDATE edgeai." + runtime + " SET desired_state='STOPPED',observed_state='TERMINATED',updated_at=transaction_timestamp()"
            " WHERE id IN " + where + " AND (desired_state<>'STOPPED' OR observed_state<>'TERMINATED');"
            " GET DIAGNOSTICS changed=ROW_COUNT; " + group + ':=' + group + '+changed;')
        if group == 'vds':
            statements.append("UPDATE edgeai.vd_runtime_binding b SET closed_at=transaction_timestamp(),closed_revision=v.revision"
                " FROM edgeai.virtual_device v WHERE b.vd_id=v.id AND b.runtime_id IN " + where +
                " AND b.closed_at IS NULL; GET DIAGNOSTICS bindings=ROW_COUNT;")
        statements.append("IF EXISTS (SELECT FROM edgeai." + runtime + " WHERE id IN " + where +
            " AND (desired_state<>'STOPPED' OR observed_state<>'TERMINATED')) OR EXISTS (SELECT FROM edgeai." +
            command + " WHERE runtime_id IN " + where + " AND (NOT completed OR lease_owner IS NOT NULL OR lease_until IS NOT NULL))"
            " THEN RAISE EXCEPTION 'Kubernetes retirement postcondition differs'; END IF;")
    allocations='(SELECT value::uuid FROM jsonb_array_elements_text('+literal(json.dumps(plan['selected']['vdAllocations']))+'::jsonb))'
    statements.append("UPDATE edgeai.vd_task_allocation SET closed_at=transaction_timestamp(),close_reason='POD_GONE'"
        ",completion_sequence=NULL,exit_code=NULL WHERE id IN " + allocations + " AND closed_at IS NULL;"
        " GET DIAGNOSTICS allocationsClosed=ROW_COUNT;"
        " IF EXISTS (SELECT FROM edgeai.vd_task_allocation WHERE id IN " + allocations + " AND closed_at IS NULL) THEN"
        " RAISE EXCEPTION 'VD allocation retirement postcondition differs'; END IF;")
    body = "DECLARE changed integer; runtimes integer:=0; vds integer:=0; vdTasks integer:=0; commands integer:=0; bindings integer:=0; allocationsClosed integer:=0; BEGIN\n" + identity + (
        'IF (' + GUARD_QUERY + ') IS DISTINCT FROM ' + literal(canonical(plan['beforeGuard']).decode()) + "::jsonb THEN"
        " RAISE EXCEPTION 'Restored database changed after termination inventory'; END IF;\n") + '\n'.join(statements) + identity + (
        "INSERT INTO retirement_result VALUES (jsonb_build_object('runtimesRetired',runtimes,'vdRuntimesRetired',vds,"
        "'vdTaskRuntimesRetired',vdTasks,'allocationsClosed',allocationsClosed,"
        "'commandsCompleted',commands,'bindingsClosed',bindings,'afterGuard',(" + GUARD_QUERY + '))); END')
    return ("BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s';\nLOCK TABLE " +
        ','.join('edgeai.'+name for name in TABLES) + ' IN SHARE ROW EXCLUSIVE MODE;\n'
        'CREATE TEMP TABLE retirement_result(value jsonb) ON COMMIT DROP;\nDO ' + literal(body) + ';\n'
        'SET CONSTRAINTS ALL IMMEDIATE; SELECT value FROM retirement_result; COMMIT;\n')


def apply(pg, args, plan):
    # Re-observe immediately before mutation; a cached file alone can never authorize DB retirement.
    before = prepare(pg,args)
    if any(before[k] != plan[k] for k in plan if k != 'preparedAt'):
        raise Blocked('Recovery inputs changed before database retirement')
    durable_json(args.output/'intent.json', plan)
    sql = args.output/'transaction.sql'
    with private_file(sql,'w') as target:
        target.write(transaction_sql(plan))
    with sql.open('rb') as source:
        result = json.loads(pg.call('psql',['-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-f','-'],args.database,
                                   source=source,timeout=45,reject_stderr=True))
    after = prepare(pg,args)
    if (after['beforeGuard'] != result['afterGuard'] or any(after[k] != plan[k] for k in
            ('targetDatabase','databaseOid','marker','restoreReportSha256','evidence','selected','unresolved','unclaimedJobs','objectsAbsentFromDatabase'))):
        raise Blocked('Post-commit evidence changed; keep restored database quarantined')
    counters = ('runtimesRetired','vdRuntimesRetired','vdTaskRuntimesRetired','allocationsClosed','commandsCompleted','bindingsClosed')
    report = {k:plan[k] for k in ('formatVersion','scope','targetDatabase','databaseOid','selected','unresolved','unclaimedJobs','objectsAbsentFromDatabase')}
    report.update(status='OBSERVED_KUBERNETES_RUNTIMES_RETIRED', activated=False,globalQuiescenceProven=False,
        databaseModified=any(result[k] for k in counters), **result,
        intentSha256=hashlib.sha256((args.output/'intent.json').read_bytes()).hexdigest(),
        namespaceUid=args.namespace_uid,recoveryId=args.recovery_id,verifiedAt=datetime.now(timezone.utc).isoformat(),
        preserved=['task-attempt-run-outcomes','committed-results','runtime-identities','VD-operation-outcomes','existing-VD-allocation-closures',
                   'database-quarantine','admission-fence','retained-pod-evidence'],
        excluded=['unobserved-producers','workflow-and-offload-reconciliation','journals','global-writer-fence','service-activation'])
    durable_json(args.output/'retirement.json',report)
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('context','namespace','namespace-uid','recovery-id','database'):
        parser.add_argument('--'+name,required=True)
    for name in ('restore-report','termination-report','output'):
        parser.add_argument('--'+name,required=True,type=Path)
    parser.add_argument('--transport',choices=['native','compose'],default='native')
    parser.add_argument('--pg-bin',type=Path)
    parser.add_argument('--timeout',type=int,default=120)
    parser.add_argument('--unclaimed-jobs',action='store_true',help='Retire unclaimed runtimes only from their recorded Job and retained terminal children')
    args=parser.parse_args()
    args.output.mkdir(mode=0o700,parents=True,exist_ok=False)
    submitted=False
    try:
        if not 1<=args.timeout<=1800: raise ValueError('Timeout must be between 1 and 1800 seconds')
        pg=Postgres(args.transport,args.pg_bin,diagnostics=args.output/'postgres')
        plan=prepare(pg,args); submitted=True
        report=apply(pg,args,plan)
        print(report['status']+': database remains quarantined; unresolved records '+str(len(report['unresolved'])))
        return 0
    except Exception as error:
        blocked=isinstance(error,(Blocked,OSError))
        durable_json(args.output/'failure.json',{'status':'BLOCKED' if blocked else 'FAIL',
            'failureType':type(error).__name__,'activated':False,'databaseModified':None if submitted else False,
            'instruction':'Keep database quarantined and preserve fence/Pods; repeat the same recovery in a new output directory'})
        print(('BLOCKED' if blocked else 'FAIL')+': Kubernetes retirement; private evidence retained; keep database quarantined')
        return 2 if blocked else 1


if __name__=='__main__': raise SystemExit(main())
