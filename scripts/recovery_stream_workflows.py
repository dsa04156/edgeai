"""Reconcile recorded STREAM component cancellation and retry expiry after fresh producer retirement proof."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import uuid

from postgres_backup import Blocked, Postgres, literal, private_file
from recovery_kubernetes import database_inventory
from recovery_remote_inventory import canonical
from recovery_remote_retire import durable_json
import recovery_kubernetes_retire as producers
import recovery_stream_retire as broker
import recovery_stream_offloads as offloads

TABLES=tuple(dict.fromkeys((*producers.TABLES,*broker.TABLES,'workflow_version','task_dependency',
    'stream_finalization_recovery','remote_allocation')))
GUARD='SELECT jsonb_build_object('+','.join(literal(name)+
    ",(SELECT encode(sha256(convert_to(coalesce(string_agg(encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'),'' "
    "ORDER BY to_jsonb(t)::text),''),'UTF8')),'hex') FROM edgeai."+name+' t)' for name in TABLES)+')'


def query(run):
    if str(uuid.UUID(run))!=run:raise ValueError('Canonical Run UUID required')
    rid=literal(run)+'::uuid'
    return ("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT jsonb_build_object("
        "'database',current_database(),'oid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),"
        "'marker',(SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()),"
        "'readOnly',current_setting('transaction_read_only'),'migrations',(SELECT jsonb_agg(jsonb_build_object("
        "'version',version,'success',success)) FROM edgeai.flyway_schema_history WHERE version IS NOT NULL),"
        "'guard',("+GUARD+"),'producerGuard',("+producers.GUARD_QUERY+"),'brokerGuard',("+broker.GUARD+"),"
        "'run',(SELECT to_jsonb(w) FROM edgeai.workflow_run w WHERE id="+rid+"),"
        "'configuration',(SELECT to_jsonb(c) FROM edgeai.stream_run_configuration c WHERE run_id="+rid+"),"
        "'frozen',EXISTS(SELECT FROM edgeai.stream_run_binding WHERE run_id="+rid+"),"
        "'activeOffload',EXISTS(SELECT FROM edgeai.task_offload WHERE run_id="+rid+" AND state IN ('DRAINING','STARTING','CANCELLING')),"
        "'offloads',(SELECT coalesce(jsonb_agg(to_jsonb(o)||jsonb_build_object('members',"
        "(SELECT coalesce(jsonb_agg(to_jsonb(m) ORDER BY m.task_id),'[]'::jsonb) FROM edgeai.task_offload_member m WHERE m.operation_id=o.id)) ORDER BY o.id),'[]'::jsonb)"
        " FROM edgeai.task_offload o WHERE o.run_id="+rid+"),"
        "'checkpoints',(SELECT coalesce(jsonb_agg(to_jsonb(c) ORDER BY c.id),'[]'::jsonb) FROM edgeai.stream_checkpoint c WHERE c.run_id="+rid+"),"
        "'routes',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]'::jsonb) FROM edgeai.data_route r WHERE run_id="+rid+"),"
        "'generations',(SELECT coalesce(jsonb_agg(to_jsonb(g) ORDER BY g.id),'[]'::jsonb) FROM edgeai.route_generation g WHERE run_id="+rid+"),"
        "'tasks',(SELECT coalesce(jsonb_agg(jsonb_build_object('task',to_jsonb(t),"
        "'attempts',(SELECT coalesce(jsonb_agg(to_jsonb(a) ORDER BY a.epoch DESC),'[]'::jsonb) FROM edgeai.task_attempt a WHERE task_id=t.id),"
        "'runtimes',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]'::jsonb) FROM edgeai.runtime_instance r WHERE task_id=t.id),"
        "'retry',(SELECT to_jsonb(q) FROM edgeai.task_retry q WHERE task_id=t.id),"
        "'result',(SELECT to_jsonb(s) FROM edgeai.task_result s WHERE task_id=t.id),"
        "'finalizing',EXISTS(SELECT FROM edgeai.stream_task_completion c JOIN edgeai.task_attempt a ON a.id=c.attempt_id WHERE a.task_id=t.id AND c.granted_at IS NOT NULL),"
        "'pendingCommands',EXISTS(SELECT FROM edgeai.runtime_command c JOIN edgeai.runtime_instance r ON r.id=c.runtime_id WHERE r.task_id=t.id AND (NOT c.completed OR c.lease_owner IS NOT NULL OR c.lease_until IS NOT NULL)),"
        "'openAllocation',EXISTS(SELECT FROM edgeai.vd_task_allocation v JOIN edgeai.runtime_instance r ON r.id=v.runtime_id WHERE r.task_id=t.id AND v.closed_at IS NULL)"
        ") ORDER BY t.id),'[]'::jsonb) FROM edgeai.task t WHERE t.run_id="+rid+")); COMMIT;")


def components(routes):
    adjacency={}
    for row in routes:
        source=('task:'+row['source_task_id']) if row['source_task_id'] else 'device:'+row['source_device_id']
        target='task:'+row['consumer_task_id']
        adjacency.setdefault(source,set()).add(target);adjacency.setdefault(target,set()).add(source)
    visited=set();result=[]
    for node in sorted(adjacency):
        if node in visited:continue
        pending=[node];tasks=set()
        while pending:
            current=pending.pop()
            if current in visited:continue
            visited.add(current)
            if current.startswith('task:'):tasks.add(current[5:])
            pending.extend(adjacency[current]-visited)
        result.append(sorted(tasks))
    return sorted(result)


def prepare(pg,args):
    retired=producers.prepare(pg,args)
    authority=broker.prepare(pg,args)
    catalog=database_inventory(pg,args.database,args.restore_report,query(args.run_id))
    if (catalog['producerGuard']!=retired['beforeGuard'] or catalog['brokerGuard']!=authority['beforeGuard'] or
            any(catalog[k]!=other[v] for other in (retired,authority) for k,v in
                [('oid','databaseOid'),('marker','marker'),('restoreReportSha256','restoreReportSha256')])):
        raise Blocked('Recovery inventories changed before STREAM component inspection')
    if (catalog['run'] is None or not catalog['frozen'] or catalog['configuration'] is None or
            catalog['configuration']['broker_digest']!=args.broker_digest or
            catalog['configuration']['namespace']!=args.namespace):
        raise Blocked('Exact frozen STREAM Run configuration required')
    proven=set(retired['selected']['runtimes']+retired['selected']['vdTasks'])
    tasks={row['task']['id']:row for row in catalog['tasks']};groups=[];unresolved=[]
    for ids in components(catalog['routes']):
        members=[tasks[identity] for identity in ids];reason=None
        routes={r['id'] for r in catalog['routes'] if r['consumer_task_id'] in ids}
        generations=[g for g in catalog['generations'] if g['route_id'] in routes]
        if catalog['activeOffload'] and not getattr(args,'offloads',False):reason='ACTIVE_STREAM_TRANSFER_REQUIRES_RECONCILIATION'
        elif any(g['closed_at'] is None or g['broker_digest']!=args.broker_digest for g in generations):
            reason='ORIGINAL_STREAM_GENERATIONS_NOT_RETIRED'
        elif any(row['pendingCommands'] or row['openAllocation'] or any(
                r['id'] not in proven or r['desired_state']!='STOPPED' or r['observed_state']!='TERMINATED'
                for r in row['runtimes']) for row in members):reason='COMPLETE_COMPONENT_PRODUCERS_NOT_RETIRED'
        states={row['task']['state'] for row in members}
        for row in members:
            result=row['result'];latest=row['attempts'][0] if row['attempts'] else None
            if row['task']['state']=='SUCCEEDED':
                if (result is None or latest is None or not result['committed'] or latest['state']!='SUCCEEDED' or
                        result['attempt_id']!=latest['id'] or result['epoch']!=latest['epoch'] or
                        not any(r['id']==result['runtime_id'] and r['attempt_id']==latest['id'] for r in row['runtimes'])):
                    raise Blocked('STREAM success must retain its exact immutable Result')
            elif result is not None:raise Blocked('STREAM outcome conflicts with an existing Result')
        action=None
        if reason is None and catalog['activeOffload']:
            transfer,reason=offloads.classify(catalog,ids,members)
            if transfer is not None:groups.append(transfer);continue
        elif reason is None and states=={'RETRY_WAIT'}:
            if catalog['run']['state']!='RUNNING' or any(not row['attempts'] or row['retry'] is None or row['result'] or
                    row['finalizing'] or row['attempts'][0]['state']!='FAILED' or
                    not any(r['attempt_id']==row['attempts'][0]['id'] for r in row['runtimes']) for row in members):
                reason='COMPONENT_RETRY_OR_FINALIZATION_HISTORY_REQUIRES_RECONCILIATION'
            else:action='CHECK_GROUP_RETRY'
        elif reason is None and 'CANCELLING' in states and states<= {'CANCELLING','CANCELLED','SKIPPED','SUCCEEDED','FAILED'}:
            if any(row['task']['state']=='CANCELLING' and (not row['task']['cancellation_reason'] or row['result'] or
                    not row['attempts'] or row['attempts'][0]['state'] not in ('CANCELLING','CANCELLED','FAILED','OFFLOADED')) for row in members):
                raise Blocked('STREAM cancellation must retain its recorded reason and attempt history')
            action='CANCEL_GROUP'
        elif reason is None and states<= {'CANCELLED','SKIPPED','SUCCEEDED','FAILED'}:action='RECONCILE_RUN'
        elif reason is None:reason='COMPONENT_OUTCOME_REQUIRES_SEPARATE_RECOVERY'
        if reason:unresolved.append({'taskIds':ids,'reason':reason})
        else:groups.append({'taskIds':ids,'action':action})
    return {'formatVersion':1,'scope':'restored-stream-workflow-reconciliation','targetDatabase':args.database,
        'databaseOid':catalog['oid'],'marker':catalog['marker'],'restoreReportSha256':catalog['restoreReportSha256'],
        'runId':args.run_id,'beforeGuard':catalog['guard'],'groups':groups,'unresolvedGroups':unresolved,
        'reconcileOffloads':getattr(args,'offloads',False),
        'producerEvidence':retired['evidence'],'provenRuntimes':sorted(proven),'brokerEvidence':authority['broker'],
        'recoveryId':args.recovery_id,'preparedAt':datetime.now(timezone.utc).isoformat()}


def transaction_sql(plan):
    identity=("IF current_database()<>"+literal(plan['targetDatabase'])+" OR NOT EXISTS(SELECT FROM pg_database WHERE datname=current_database() "
        "AND oid::text="+literal(plan['databaseOid'])+" AND shobj_description(oid,'pg_database')="+literal(plan['marker'])+
        ") THEN RAISE EXCEPTION 'Restored database identity changed'; END IF;")
    body="""
DECLARE component jsonb; ids uuid[]; affected uuid[]:='{}'; victim edgeai.task;
 policy edgeai.workflow_run; group_cutoff timestamptz; terminal text; changed integer;
 expired integer:=0; cancelled integer:=0; skipped integer:=0; runs integer:=0;
 operation edgeai.task_offload; offload_failures integer:=0; offload_cancellations integer:=0; attempt_failures integer:=0;
BEGIN
__IDENTITY__
IF (__GUARD__) IS DISTINCT FROM __BEFORE__::jsonb THEN RAISE EXCEPTION 'STREAM component snapshot changed'; END IF;
SELECT * INTO STRICT policy FROM edgeai.workflow_run WHERE id=__RUN__::uuid;
FOR component IN SELECT * FROM jsonb_array_elements(__GROUPS__::jsonb) LOOP
 SELECT array_agg(value::uuid) INTO ids FROM jsonb_array_elements_text(component->'taskIds');
 IF component->>'action' IN ('CHECK_OFFLOAD_DRAIN','CHECK_OFFLOAD_START') THEN
  SELECT * INTO STRICT operation FROM edgeai.task_offload WHERE id=(component->>'operationId')::uuid;
  group_cutoff:=CASE component->>'action' WHEN 'CHECK_OFFLOAD_DRAIN' THEN operation.drain_deadline ELSE operation.start_deadline END;
  IF transaction_timestamp()>=group_cutoff THEN
   terminal:=CASE component->>'action' WHEN 'CHECK_OFFLOAD_DRAIN' THEN 'SOURCE_DRAIN_TIMEOUT' ELSE 'TARGET_START_TIMEOUT' END;
   IF operation.target_attempt_id IS NOT NULL THEN
    UPDATE edgeai.runtime_instance SET failure_reason=terminal,updated_at=transaction_timestamp() WHERE attempt_id=operation.target_attempt_id;
    UPDATE edgeai.task_attempt SET state='FAILED',updated_at=transaction_timestamp() WHERE id=operation.target_attempt_id;
    GET DIAGNOSTICS changed=ROW_COUNT; attempt_failures:=attempt_failures+changed;
   END IF;
   UPDATE edgeai.task SET state='FAILED',updated_at=transaction_timestamp() WHERE id=operation.task_id;
   UPDATE edgeai.task_offload SET state='FAILED',failure_reason=terminal,updated_at=transaction_timestamp() WHERE id=operation.id;
   offload_failures:=offload_failures+1; affected:=affected||ids;
  END IF;
 ELSIF component->>'action'='CHECK_GROUP_RETRY' THEN
  SELECT min(a.created_at+make_interval(secs=>policy.retry_max_elapsed_seconds)) INTO group_cutoff
  FROM edgeai.task_attempt a WHERE a.task_id=ANY(ids) AND a.number=(SELECT min(initial.number) FROM edgeai.task_attempt initial WHERE initial.task_id=a.task_id);
  IF (SELECT count(*) FROM edgeai.task_retry WHERE task_id=ANY(ids))<>cardinality(ids) OR
   (SELECT count(DISTINCT available_at) FROM edgeai.task_retry WHERE task_id=ANY(ids))<>1 OR
   EXISTS(SELECT FROM edgeai.task_retry q JOIN edgeai.task_attempt a ON a.id=q.failed_attempt_id
    LEFT JOIN edgeai.runtime_instance r ON r.attempt_id=a.id WHERE q.task_id=ANY(ids) AND
    (a.task_id<>q.task_id OR a.epoch<>(SELECT max(epoch) FROM edgeai.task_attempt WHERE task_id=a.task_id) OR
     a.state<>'FAILED' OR r.id IS NULL OR q.namespace IS DISTINCT FROM r.namespace OR q.deadline IS DISTINCT FROM group_cutoff OR
     q.available_at IS DISTINCT FROM a.updated_at+make_interval(secs=>policy.retry_backoff_seconds) OR
     r.failure_reason IS NULL OR NOT (r.failure_reason='STREAM_GROUP_RESTART' OR r.failure_reason=ANY(policy.retry_on)) OR
     (SELECT count(*) FROM edgeai.task_attempt WHERE task_id=a.task_id AND cause<>'OFFLOAD')>=policy.retry_max_attempts)) OR
   NOT EXISTS(SELECT FROM edgeai.task_retry q JOIN edgeai.runtime_instance r ON r.attempt_id=q.failed_attempt_id
    WHERE q.task_id=ANY(ids) AND r.failure_reason=ANY(policy.retry_on)) THEN
   RAISE EXCEPTION 'Recorded STREAM retry does not match original group budget'; END IF;
  IF transaction_timestamp()>=group_cutoff THEN
   DELETE FROM edgeai.task_retry WHERE task_id=ANY(ids);
   UPDATE edgeai.task SET state='FAILED',updated_at=transaction_timestamp() WHERE id=ANY(ids);
   GET DIAGNOSTICS changed=ROW_COUNT; expired:=expired+changed; affected:=affected||ids;
  END IF;
 ELSIF component->>'action' IN ('CANCEL_GROUP','CANCEL_OFFLOAD_GROUP') THEN
  IF component->>'action'='CANCEL_OFFLOAD_GROUP' THEN
   UPDATE edgeai.task_offload SET state='CANCELLED',failure_reason=NULL,updated_at=transaction_timestamp() WHERE id=(component->>'operationId')::uuid;
   offload_cancellations:=offload_cancellations+1;
  END IF;
  FOR victim IN SELECT * FROM edgeai.task WHERE id=ANY(ids) AND state='CANCELLING' LOOP
   terminal:=CASE WHEN victim.cancellation_reason LIKE 'UPSTREAM_%' THEN 'SKIPPED' ELSE 'CANCELLED' END;
   DELETE FROM edgeai.task_retry WHERE task_id=victim.id;
   UPDATE edgeai.task_attempt SET state='CANCELLED',updated_at=transaction_timestamp()
    WHERE task_id=victim.id AND state IN ('QUEUED','DISPATCHING','RUNNING','CANCELLING');
   UPDATE edgeai.task SET state=terminal,updated_at=transaction_timestamp() WHERE id=victim.id;
   IF terminal='SKIPPED' THEN skipped:=skipped+1; ELSE cancelled:=cancelled+1; END IF;
  END LOOP;
 ELSIF component->>'action'<>'RECONCILE_RUN' THEN RAISE EXCEPTION 'Unsupported STREAM component action'; END IF;
END LOOP;
-- Match StreamRunService.affected: all peers of each affected component and all downstream work.
FOR victim IN WITH RECURSIVE edges(source,target) AS (
 SELECT a.id,b.id FROM edgeai.task_dependency d JOIN edgeai.task a ON a.definition_id=d.from_task_id
 JOIN edgeai.task b ON b.definition_id=d.to_task_id AND b.run_id=a.run_id WHERE a.run_id=policy.id
 UNION SELECT source_task_id,consumer_task_id FROM edgeai.data_route WHERE run_id=policy.id AND source_task_id IS NOT NULL
 UNION SELECT consumer_task_id,source_task_id FROM edgeai.data_route WHERE run_id=policy.id AND source_task_id IS NOT NULL
 UNION SELECT a.consumer_task_id,b.consumer_task_id FROM edgeai.data_route a JOIN edgeai.data_route b
 ON b.run_id=a.run_id AND b.source_device_id=a.source_device_id WHERE a.run_id=policy.id AND a.source_device_id IS NOT NULL
), descendants(task_id) AS (
 SELECT unnest(affected) UNION SELECT e.target FROM descendants d JOIN edges e ON e.source=d.task_id
) SELECT t.* FROM edgeai.task t WHERE t.run_id=policy.id AND EXISTS(SELECT FROM descendants d WHERE d.task_id=t.id)
LOOP
 IF victim.state IN ('SUCCEEDED','FAILED','CANCELLED','SKIPPED') THEN CONTINUE; END IF;
 IF EXISTS(SELECT FROM edgeai.task_result WHERE task_id=victim.id) OR EXISTS(SELECT FROM edgeai.runtime_instance r WHERE r.task_id=victim.id AND
  (r.id NOT IN (SELECT value::uuid FROM jsonb_array_elements_text(__PROVEN__::jsonb)) OR r.desired_state<>'STOPPED' OR r.observed_state<>'TERMINATED'
   OR EXISTS(SELECT FROM edgeai.runtime_command c WHERE c.runtime_id=r.id AND (NOT c.completed OR c.lease_owner IS NOT NULL OR c.lease_until IS NOT NULL))
   OR EXISTS(SELECT FROM edgeai.vd_task_allocation v WHERE v.runtime_id=r.id AND v.closed_at IS NULL))) OR
  EXISTS(SELECT FROM edgeai.task_offload WHERE task_id=victim.id AND state IN ('DRAINING','STARTING','CANCELLING')) OR
  EXISTS(SELECT FROM edgeai.route_generation WHERE closed_at IS NULL AND (source_task_id=victim.id OR consumer_task_id=victim.id)) THEN
  RAISE EXCEPTION 'Unproven descendant prevents STREAM retry expiry'; END IF;
 IF victim.state='CANCELLING' AND (victim.cancellation_reason IS NULL OR victim.cancellation_reason='') THEN
  RAISE EXCEPTION 'Recorded descendant cancellation reason required'; END IF;
 terminal:=CASE WHEN victim.state='CANCELLING' AND victim.cancellation_reason NOT LIKE 'UPSTREAM_%' THEN 'CANCELLED' ELSE 'SKIPPED' END;
 DELETE FROM edgeai.task_retry WHERE task_id=victim.id;
 UPDATE edgeai.task_attempt SET state='CANCELLED',updated_at=transaction_timestamp() WHERE task_id=victim.id AND state IN ('QUEUED','DISPATCHING','RUNNING','CANCELLING');
 UPDATE edgeai.task SET state=terminal,cancellation_reason=CASE WHEN victim.state='CANCELLING' THEN victim.cancellation_reason ELSE 'UPSTREAM_FAILED' END,
  updated_at=transaction_timestamp() WHERE id=victim.id;
 IF terminal='SKIPPED' THEN skipped:=skipped+1; ELSE cancelled:=cancelled+1; END IF;
END LOOP;
SELECT CASE WHEN bool_or(state='FAILED') THEN 'FAILED' WHEN bool_or(state IN ('CANCELLED','SKIPPED')) THEN 'CANCELLED' ELSE 'SUCCEEDED' END
 INTO terminal FROM edgeai.task WHERE run_id=policy.id HAVING bool_and(state IN ('SUCCEEDED','FAILED','CANCELLED','SKIPPED'));
IF terminal IS NOT NULL AND jsonb_array_length(__GROUPS__::jsonb)>0 AND __UNRESOLVED__=0 THEN
 UPDATE edgeai.workflow_run SET state=terminal,updated_at=transaction_timestamp() WHERE id=policy.id AND state IN ('RUNNING','CANCELLING') AND state<>terminal;
 GET DIAGNOSTICS runs=ROW_COUNT;
END IF;
__IDENTITY__
INSERT INTO stream_workflow_result VALUES(jsonb_build_object('retriesExpired',expired,'tasksCancelled',cancelled,
 'tasksSkipped',skipped,'runsReconciled',runs,'offloadsFailed',offload_failures,'offloadsCancelled',offload_cancellations,
 'attemptsFailed',attempt_failures,'afterGuard',(__GUARD__)));
END
"""
    for name,value in {'IDENTITY':identity,'GUARD':GUARD,'BEFORE':literal(canonical(plan['beforeGuard']).decode()),
            'RUN':literal(plan['runId']),'GROUPS':literal(canonical(plan['groups']).decode()),
            'UNRESOLVED':str(len(plan['unresolvedGroups'])),
            'PROVEN':literal(canonical(plan['provenRuntimes']).decode())}.items():body=body.replace('__'+name+'__',value)
    return ("BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s'; LOCK TABLE "+
        ','.join('edgeai.'+name for name in TABLES)+' IN SHARE ROW EXCLUSIVE MODE;\n'
        'CREATE TEMP TABLE stream_workflow_result(value jsonb) ON COMMIT DROP; DO '+literal(body)+
        '; SET CONSTRAINTS ALL IMMEDIATE; SELECT value FROM stream_workflow_result; COMMIT;\n')


def apply(pg,args,plan):
    current=prepare(pg,args)
    if any(current[k]!=plan[k] for k in plan if k!='preparedAt'):raise Blocked('STREAM recovery inputs changed')
    durable_json(args.output/'intent.json',plan);sql=args.output/'transaction.sql'
    with private_file(sql,'w') as out:out.write(transaction_sql(plan));out.flush();os.fsync(out.fileno())
    with sql.open('rb') as source:
        result=json.loads(pg.call('psql',['-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-f','-'],args.database,source=source,timeout=45,reject_stderr=True))
    after=prepare(pg,args)
    if (after['beforeGuard']!=result['afterGuard'] or any(after[k]!=plan[k] for k in
            ('targetDatabase','databaseOid','marker','restoreReportSha256','runId','producerEvidence','provenRuntimes','brokerEvidence','recoveryId'))):
        raise Blocked('Post-commit STREAM recovery proof changed; retain quarantine')
    counts=('retriesExpired','tasksCancelled','tasksSkipped','runsReconciled','offloadsFailed','offloadsCancelled','attemptsFailed')
    report={k:plan[k] for k in ('formatVersion','scope','targetDatabase','databaseOid','runId','recoveryId')}
    report.update(status='RECORDED_STREAM_WORKFLOWS_RECONCILED',**result,databaseModified=any(result[k] for k in counts),
        unresolvedGroups=after['unresolvedGroups'],groups=after['groups'],activated=False,globalQuiescenceProven=False,
        intentSha256=hashlib.sha256((args.output/'intent.json').read_bytes()).hexdigest(),verifiedAt=datetime.now(timezone.utc).isoformat(),
        reconcileOffloads=plan['reconcileOffloads'],
        excluded=['unrecorded-outcomes','new-retry-attempts','new-offload-attempts','finalization-recovery',
            'unproven-stream-offloads' if plan['reconcileOffloads'] else 'active-stream-offloads','device-authority','service-activation'])
    durable_json(args.output/'workflows.json',report);return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('context','namespace','namespace-uid','recovery-id','database','run-id','broker-digest'):parser.add_argument('--'+name,required=True)
    for name in ('restore-report','termination-report','mqtt-state-directory','mqtt-ca-file','mqtt-original-password-file','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--transport',choices=['native','compose'],default='native');parser.add_argument('--pg-bin',type=Path)
    parser.add_argument('--timeout',type=int,default=60);parser.add_argument('--unclaimed-jobs',action='store_true')
    parser.add_argument('--offloads',action='store_true',help='Reconcile recorded STREAM group transfer cancellations and original deadline expiry')
    args=parser.parse_args();args.output.mkdir(mode=0o700);submitted=False
    try:
        pg=Postgres(args.transport,args.pg_bin,diagnostics=args.output/'postgres');plan=prepare(pg,args);submitted=True
        report=apply(pg,args,plan);print(report['status']+': no new attempts or grants; database remains quarantined');return 0
    except Exception as error:
        blocked=isinstance(error,(Blocked,OSError))
        durable_json(args.output/'failure.json',{'status':'BLOCKED' if blocked else 'FAIL','failureType':type(error).__name__,
            'activated':False,'databaseModified':None if submitted else False,'instruction':'Retain all recovery fences and quarantine; retry the same operation in a new output directory'})
        print(('BLOCKED' if blocked else 'FAIL')+': STREAM workflow recovery; private evidence retained');return 2 if blocked else 1


if __name__=='__main__':raise SystemExit(main())
