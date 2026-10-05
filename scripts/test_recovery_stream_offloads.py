"""Real source/target container histories and restored immutable STREAM transfer plans."""
from copy import deepcopy
import json
from pathlib import Path
import subprocess
import time
import uuid
from unittest.mock import patch

from postgres_backup import Blocked,Postgres,backup,restore,identifier,literal
from recovery_kubernetes import PART,MANAGER,RUNTIME
from test_recovery_unclaimed_jobs import candidate_nodes
import recovery_kubernetes_retire as producers
import recovery_stream_retire as routes
import recovery_stream_workflows as workflows


def q(value):return literal(value)+'::uuid'


def seed(pg,transport,work,running_backup,remember,drop,kube,create,namespace,pod_spec,members,run_id,on_targets=None):
    seed_db='edgeai_restore_stream_seed_'+uuid.uuid4().hex
    restoring=Postgres(transport,diagnostics=work/'offload-seed-restore');restore(restoring,running_backup,seed_db);remember(seed_db)
    selected=members[0];source_node=pg.sql('SELECT node_uid::text FROM edgeai.runtime_instance WHERE id='+q(selected['runtime']),seed_db)
    candidates=candidate_nodes(kube,source_node);assert candidates,'A separate healthy NODE transfer target is required'
    node=candidates[0];node_id=node['metadata']['uid'];node_name=node['metadata']['name']
    pg.sql('INSERT INTO edgeai.execution_node(id,name,architecture,operating_system,observed_status,cpu,memory,labels,observed_at) VALUES ('+
        q(node_id)+','+literal(node_name)+",'amd64','linux','READY','1','1Gi','{}',now()) ON CONFLICT(id) DO NOTHING",seed_db)
    operation=str(uuid.uuid4())
    pg.sql('INSERT INTO edgeai.task_offload(id,task_id,run_id,source_attempt_id,target_node_id,idempotency_key,request_digest,namespace,'
        'state,drain_deadline,start_timeout_seconds,created_at,updated_at) VALUES ('+
        ','.join(q(v) for v in (operation,selected['task'],run_id,selected['attempt'],node_id,str(uuid.uuid4())))+','+
        literal('sha256:'+'f'*64)+','+literal(namespace)+",'DRAINING',now()+interval '10 minutes',600,now(),now())",seed_db)
    for member in members:
        pg.sql('INSERT INTO edgeai.task_offload_member(operation_id,run_id,task_id,source_attempt_id,checkpoint_id,target_node_id,target_vd_id,excluded_node_names) VALUES ('+
            ','.join(q(v) for v in (operation,run_id,member['task'],member['attempt'],member['checkpoint']))+','+
            (q(node_id) if member is selected else 'NULL')+','+(q(member['vd']) if member.get('vd') and member is not selected else 'NULL')+",'{}')",seed_db)
    pg.sql("UPDATE edgeai.task_attempt SET state='OFFLOADED'; UPDATE edgeai.task SET state='OFFLOADING' WHERE id IN ("+
        ','.join(q(m['task']) for m in members)+"); UPDATE edgeai.runtime_instance SET desired_state='STOPPED',failure_reason='OFFLOADED'",seed_db)
    drain=work/'offload-drain-backup';backup(pg,seed_db,drain)
    # End both real source processes before creating their real successor containers.
    pods=kube.items(namespace,'Pod')[0]
    source_uids={m['pod'] for m in members}
    for pod in pods:
        if pod['metadata']['uid'] in source_uids:
            subprocess.run(kube.command+['-n',namespace,'exec',pod['metadata']['name'],'--','python3','-c',
                'import os,signal;os.kill(1,signal.SIGTERM)'],capture_output=True,timeout=15)
    deadline=time.monotonic()+60
    while time.monotonic()<deadline:
        old=[p for p in kube.items(namespace,'Pod')[0] if p['metadata']['uid'] in source_uids]
        if len(old)==2 and all(p.get('status',{}).get('containerStatuses') and all(
                c.get('state',{}).get('terminated',{}).get('message')=='CHILD_REAPED' for c in p['status']['containerStatuses']) for p in old):break
        time.sleep(.2)
    else:raise AssertionError('Actual original STREAM transfer children did not terminate')
    pg.sql("UPDATE edgeai.runtime_instance SET observed_state='TERMINATED'; "
        "UPDATE edgeai.vd_runtime SET desired_state='STOPPED',observed_state='TERMINATED'; "
        "UPDATE edgeai.vd_runtime_binding SET closed_at=now(),closed_revision=opened_revision WHERE closed_at IS NULL; "
        "UPDATE edgeai.vd_task_allocation SET closed_at=now(),close_reason='POD_GONE' WHERE closed_at IS NULL; "
        "UPDATE edgeai.runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL; "
        "UPDATE edgeai.route_generation SET fenced_at=now(),fence_reason='REPLACED',updated_at=now(); "
        "UPDATE edgeai.route_generation SET closed_at=now(),updated_at=now()",seed_db)
    target_rows=[];created=pg.sql('SELECT now()::text',seed_db)
    for member in members:
        target={'task':member['task'],'attempt':str(uuid.uuid4()),'runtime':str(uuid.uuid4())};target_rows.append(target)
        mode='NODE' if member is selected else 'VD' if member.get('vd') else 'AUTO';placement=q(node_id) if member is selected else 'NULL'
        if mode=='VD':target.update(vd=member['vd'],service=member['service'])
        pg.sql('INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,node_id,vd_id,cause,created_at,updated_at) VALUES ('+
            q(target['attempt'])+','+q(member['task'])+",2,2,'QUEUED',"+literal(mode)+','+placement+','+(q(target['vd']) if target.get('vd') else 'NULL')+",'OFFLOAD',"+
            literal(created)+'::timestamptz,'+literal(created)+'::timestamptz); UPDATE edgeai.task_offload_member SET target_attempt_id='+
            q(target['attempt'])+' WHERE operation_id='+q(operation)+' AND task_id='+q(member['task']),seed_db)
    pg.sql('UPDATE edgeai.task_offload SET target_attempt_id='+q(target_rows[0]['attempt'])+",state='STARTING',start_deadline="+
        literal(created)+"::timestamptz+interval '600 seconds',updated_at="+literal(created)+"::timestamptz WHERE id="+q(operation)+
        "; UPDATE edgeai.task SET state='READY' WHERE state='OFFLOADING'",seed_db)
    for i,target in enumerate(target_rows):
        if target.get('vd'):
            from test_vd_supervisor_fixture import supervisor,allocate
            _,vr,pod=supervisor(None,pg,seed_db,namespace,target['service'],create,kube,
                pod_spec['containers'][0]['image'],pod_spec['containers'][0]['command'][-1],vd={'id':target['vd']},generation=2)
            target.update(vd_runtime=vr,pod=pod['metadata']['uid'])
            pg.sql('INSERT INTO edgeai.runtime_instance(id,task_id,attempt_id,run_id,epoch,namespace,vd_id,claim_nonce,desired_state,observed_state,expires_at,created_at,updated_at) VALUES ('+
                ','.join(q(target[k]) for k in ('runtime','task','attempt'))+','+q(run_id)+',2,'+literal(namespace)+','+q(target['vd'])+','+q(str(uuid.uuid4()))+
                ",'RUNNING','PENDING',now()+interval '1 hour',now(),now()); UPDATE edgeai.task_attempt SET state='DISPATCHING' WHERE id="+q(target['attempt'])+
                "; UPDATE edgeai.task SET state='RUNNING' WHERE id="+q(target['task']),seed_db)
            allocate(pg,seed_db,target['runtime'],target['vd'],vr,pod)
            continue
        spec=deepcopy(pod_spec)
        if i==0:spec['nodeName']=node_name
        labels={PART:'edgeai',MANAGER:RUNTIME,'edgeai.io/run-id':run_id,'edgeai.io/task-id':target['task'],
            'edgeai.io/attempt-id':target['attempt'],'edgeai.io/epoch':'2'}
        name='edgeai-'+target['attempt'];target['job']=create({'apiVersion':'batch/v1','kind':'Job',
            'metadata':{'namespace':namespace,'name':name,'labels':labels},
            'spec':{'backoffLimit':0,'template':{'metadata':{'labels':labels},'spec':spec}}})['metadata']['uid']
        pg.sql('INSERT INTO edgeai.runtime_instance(id,task_id,attempt_id,run_id,epoch,namespace,job_name,job_uid,claim_nonce,desired_state,observed_state,created_at,updated_at) VALUES ('+
            ','.join(q(target[k]) for k in ('runtime','task','attempt'))+','+q(run_id)+',2,'+literal(namespace)+','+literal(name)+','+
            q(target['job'])+','+q(str(uuid.uuid4()))+",'RUNNING','SUBMITTED',now(),now()); UPDATE edgeai.task_attempt SET state='DISPATCHING' WHERE id="+q(target['attempt']),seed_db)
    deadline=time.monotonic()+150
    while time.monotonic()<deadline:
        current=[p for p in kube.items(namespace,'Pod')[0] if p['metadata']['labels'].get('edgeai.io/epoch')=='2' or
            p['metadata']['uid'] in {t.get('pod') for t in target_rows if t.get('vd')}]
        if len(current)==2 and all(p.get('status',{}).get('phase')=='Running' for p in current) and all(subprocess.run(
                kube.command+['-n',namespace,'exec',p['metadata']['name'],'--','python3','-c',
                "from pathlib import Path;import os;os.kill(int(Path('/tmp/child-ready').read_text()),0)"],capture_output=True,timeout=15).returncode==0 for p in current):break
        time.sleep(.3)
    else:raise AssertionError('Actual STREAM transfer target parents and children did not start')
    for i,target in enumerate(target_rows):
        pod=next(p for p in current if (p['metadata']['uid']==target.get('pod') if target.get('vd') else p['metadata']['labels'].get('edgeai.io/attempt-id')==target['attempt']));target['pod']=pod['metadata']['uid']
        if i==0:
            actual_node=kube.read('/api/v1/nodes/'+pod['spec']['nodeName'])
            pg.sql("UPDATE edgeai.runtime_instance SET observed_state='RUNNING',producer_pod_uid="+q(target['pod'])+',node_uid='+
                q(actual_node['metadata']['uid'])+',node_name='+literal(pod['spec']['nodeName'])+",expires_at=now()+interval '1 hour' WHERE id="+q(target['runtime'])+
                "; UPDATE edgeai.task_attempt SET state='RUNNING' WHERE id="+q(target['attempt'])+"; UPDATE edgeai.task SET state='RUNNING' WHERE id="+q(target['task']),seed_db)
    if on_targets is not None:on_targets(seed_db,target_rows,operation)
    starting=work/'offload-start-backup';backup(pg,seed_db,starting)
    targets={}
    for name,bundle in [('drain-pending',drain),('drain-expired',drain),('drain-cancel',drain),
            ('start-pending',starting),('start-expired',starting),('start-cancel',starting),('start-missing',starting),
            ('selected-failed',starting),('peer-failed',starting),('failure-unproven',starting)]:
        db='edgeai_restore_stream_transfer_'+uuid.uuid4().hex
        tool=Postgres(transport,diagnostics=work/('offload-restore-'+name));restore(tool,bundle,db);remember(db)
        targets[name]=(db,tool.directory/'restore-report.json')
    drop(seed_db)
    return {'operation':operation,'targets':targets,'members':members,'successors':target_rows,
        'sourceProcessesEndedBeforeSuccessors':True,'selectedTargetNode':node_id}


def check(pg,fixture,options,apply,cli,fingerprints,refused,passed,report):
    operation=fixture['operation'];targets=fixture['targets'];members=fixture['members'];successors=fixture['successors']
    def opts(target):return options(target,offloads=True,unclaimed_jobs=True)
    def run(target,plan=None):return apply(target,plan,offloads=True,unclaimed_jobs=True)
    def command(target):return cli(target,offloads=True,unclaimed_jobs=True)
    missing=targets['start-missing']
    unproven=successors[0] if successors[1].get('vd') else successors[1]
    pg.sql("UPDATE edgeai.runtime_instance SET job_uid=NULL,producer_pod_uid=NULL,node_uid=NULL,node_name=NULL,observed_state='SUBMITTED' WHERE id="+q(unproven['runtime']),missing[0])
    for name,target in targets.items():
        values=opts(target);values.output.mkdir(mode=0o700);producers.apply(pg,values,producers.prepare(pg,values))
        values=opts(target);values.output.mkdir(mode=0o700);routes.apply(pg,values,routes.prepare(pg,values))
    def retained(db):
        ids=','.join(q(m['attempt']) for m in members)
        return pg.sql("SELECT jsonb_build_object('sources',(SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id) FROM edgeai.task_attempt a WHERE a.id IN ("+ids+
            ")),'sourceRuntimes',(SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id) FROM edgeai.runtime_instance r WHERE r.attempt_id IN ("+ids+
            ")),'members',(SELECT jsonb_agg(to_jsonb(m) ORDER BY m.task_id) FROM edgeai.task_offload_member m),"
            "'checkpoints',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM edgeai.stream_checkpoint c))",db)
    retained_before={name:retained(target[0]) for name,target in targets.items()}
    def frozen_operation(db):
        return pg.sql("SELECT to_jsonb(o)-ARRAY['state','failure_reason','updated_at'] FROM edgeai.task_offload o WHERE id="+q(operation),db)
    passed('stream-transfer-history-pins-two-checkpoints-and-starts-two-real-successors-after-both-original-children-end')
    for name in ('drain-pending','start-pending'):
        target=targets[name];before=fingerprints(target[0]);result=command(target)
        assert not result['databaseModified'] and fingerprints(target[0])==before
        assert (not result['unresolvedGroups']) if name=='drain-pending' else result['unresolvedGroups'][0]['reason']=='STREAM_GROUP_START_AUTHORITY_NOT_PROVEN'
    passed('pending-drain-preserves-budget-and-starting-without-original-admissions-stays-unresolved')
    before=fingerprints(missing[0]);result=command(missing)
    assert not result['databaseModified'] and result['unresolvedGroups'] and fingerprints(missing[0])==before
    passed('one-unrecorded-starting-producer-keeps-the-entire-stream-transfer-unresolved')
    pg.sql('UPDATE edgeai.runtime_instance SET job_uid='+q(unproven['job'])+' WHERE id='+q(unproven['runtime']),missing[0])
    values=opts(missing);values.output.mkdir(mode=0o700);producers.apply(pg,values,producers.prepare(pg,values))
    pending=targets['start-pending'];before=fingerprints(pending[0])
    pg.sql("UPDATE edgeai.task_offload SET start_deadline=start_deadline+interval '1 second'",pending[0])
    altered=fingerprints(pending[0]);refused(lambda:run(pending),Blocked);assert fingerprints(pending[0])==altered
    pg.sql("UPDATE edgeai.task_offload SET start_deadline=start_deadline-interval '1 second'",pending[0]);assert fingerprints(pending[0])==before
    passed('stream-transfer-start-deadline-must-match-the-original-successor-creation-time-and-timeout')
    # A newer attempt can never be overwritten by a previous operation's timeout.
    target=targets['start-missing'];m=successors[1]
    pg.sql("UPDATE edgeai.task_attempt SET state='FAILED' WHERE id="+q(m['attempt'])+
        "; UPDATE edgeai.runtime_instance SET failure_reason='WORKLOAD_FAILED' WHERE id="+q(m['runtime'])+
        '; INSERT INTO edgeai.task_attempt SELECT (jsonb_populate_record(NULL::edgeai.task_attempt,to_jsonb(a)||'+
        literal(json.dumps({'id':str(uuid.uuid4()),'number':3,'epoch':3,'cause':'RETRY','state':'QUEUED'}))+'::jsonb)).* FROM edgeai.task_attempt a WHERE id='+q(m['attempt']),target[0])
    before=fingerprints(target[0]);result=command(target);assert not result['databaseModified'] and fingerprints(target[0])==before
    assert result['unresolvedGroups'][0]['reason']=='NEWER_STREAM_TRANSFER_ATTEMPT_RECORDED'
    passed('newer-stream-attempt-remains-untouched-by-old-transfer-recovery')
    pending=targets['drain-pending'];before=fingerprints(pending[0]);prepared=workflows.prepare(pg,opts(pending))
    pg.sql("UPDATE edgeai.task_offload SET drain_deadline=drain_deadline+interval '1 second'",pending[0])
    changed=fingerprints(pending[0]);refused(lambda:run(pending,prepared),Blocked);assert fingerprints(pending[0])==changed
    pg.sql("UPDATE edgeai.task_offload SET drain_deadline=drain_deadline-interval '1 second'",pending[0]);assert fingerprints(pending[0])==before
    passed('actual-operation-only-change-invalidates-the-entire-prepared-stream-transfer')
    extra=str(uuid.uuid4())
    changes={'id':extra,'task_id':members[1]['task'],'source_attempt_id':members[1]['attempt'],'idempotency_key':str(uuid.uuid4())}
    pg.sql('INSERT INTO edgeai.task_offload SELECT (jsonb_populate_record(NULL::edgeai.task_offload,to_jsonb(o)||'+
        literal(json.dumps(changes))+'::jsonb)).* FROM edgeai.task_offload o WHERE id='+q(operation),pending[0])
    changed=fingerprints(pending[0]);result=command(pending);assert not result['databaseModified'] and fingerprints(pending[0])==changed
    assert result['unresolvedGroups'][0]['reason']=='STREAM_TRANSFER_IDENTITY_REQUIRES_RECONCILIATION'
    pg.sql('DELETE FROM edgeai.task_offload WHERE id='+q(extra),pending[0]);assert fingerprints(pending[0])==before
    passed('overlapping-recorded-transfers-cannot-partially-reconcile-the-same-stream-component')
    for name in ('drain-expired','start-expired'):
        target=targets[name]
        pg.sql("UPDATE edgeai.task_offload SET created_at=created_at-interval '20 minutes',drain_deadline=drain_deadline-interval '20 minutes',"
            "start_deadline=start_deadline-interval '20 minutes',updated_at=updated_at-interval '20 minutes'; "
            "UPDATE edgeai.task_attempt SET created_at=created_at-interval '20 minutes',updated_at=updated_at-interval '20 minutes' WHERE cause='OFFLOAD'",target[0])
    expired=targets['drain-expired'];before=fingerprints(expired[0]);transaction=workflows.transaction_sql
    with patch.object(workflows,'transaction_sql',side_effect=lambda p:transaction(p).replace('SET CONSTRAINTS ALL IMMEDIATE;','SELECT 1/0; SET CONSTRAINTS ALL IMMEDIATE;')):
        refused(lambda:run(expired),RuntimeError)
    assert fingerprints(expired[0])==before
    passed('actual-late-sql-error-rolls-back-stream-drain-operation-failure-peer-and-batch-descendant-together')
    target=targets['start-expired'];before=fingerprints(target[0]);result=command(target)
    assert not result['databaseModified'] and fingerprints(target[0])==before
    assert result['unresolvedGroups'][0]['reason']=='STREAM_GROUP_START_AUTHORITY_NOT_PROVEN'
    for name,reason in [('drain-expired','SOURCE_DRAIN_TIMEOUT')]:
        target=targets[name];before=fingerprints(target[0]);frozen=frozen_operation(target[0]);result=command(target)
        assert result['offloadsFailed']==1 and result['tasksSkipped']==2 and result['runsReconciled']==1
        assert result['attemptsFailed']==int(name.startswith('start')) and result['retriesExpired']==0
        assert pg.sql('SELECT failure_reason FROM edgeai.task_offload WHERE id='+q(operation),target[0])==reason
        assert pg.sql("SELECT count(*) FROM edgeai.task_attempt WHERE state='OFFLOADED'",target[0])=='2'
        after=fingerprints(target[0]);allowed={'task','task_offload','workflow_run'}|({'task_attempt','runtime_instance'} if name.startswith('start') else set())
        assert all(before[k]==after[k] for k in before if k not in allowed)
        assert pg.sql('SELECT count(*) FROM edgeai.task_retry',target[0])=='0'
        assert frozen_operation(target[0])==frozen
        assert not command(target)['databaseModified'] and fingerprints(target[0])==after
    passed('original-drain-timeout-reconciles-but-expired-start-without-admission-proof-never-invents-failure')
    for name in ('drain-cancel','start-cancel'):
        target=targets[name]
        pg.sql("UPDATE edgeai.task_offload SET state='CANCELLING',updated_at=now(); UPDATE edgeai.workflow_run SET state='CANCELLING'; "
            "UPDATE edgeai.task SET state='CANCELLING',cancellation_reason='RUN_CANCELLED' WHERE id IN ("+
            ','.join(q(m['task']) for m in members)+"); UPDATE edgeai.task SET state='SKIPPED',cancellation_reason='UPSTREAM_CANCELLED' WHERE state='WAITING'",target[0])
    cancelled=targets['start-cancel'];call=pg.call;frozen=frozen_operation(cancelled[0])
    def lost(tool,arguments,*a,**kw):
        value=call(tool,arguments,*a,**kw)
        if '-f' in arguments:raise OSError('Injected actual STREAM offload COMMIT response loss')
        return value
    with patch.object(pg,'call',side_effect=lost):refused(lambda:run(cancelled),OSError)
    assert pg.sql('SELECT state FROM edgeai.task_offload WHERE id='+q(operation),cancelled[0])=='CANCELLED'
    assert not command(cancelled)['databaseModified']
    assert frozen_operation(cancelled[0])==frozen
    passed('stream-starting-cancellation-survives-real-commit-response-loss-with-original-source-and-member-plan-intact')
    cancelled=targets['drain-cancel'];before=fingerprints(cancelled[0]);frozen=frozen_operation(cancelled[0]);result=command(cancelled)
    assert result['offloadsCancelled']==1 and result['tasksCancelled']==2 and result['runsReconciled']==1
    after=fingerprints(cancelled[0]);assert all(before[k]==after[k] for k in before if k not in ('task','task_offload','workflow_run'))
    assert not command(cancelled)['databaseModified'] and fingerprints(cancelled[0])==after
    assert frozen_operation(cancelled[0])==frozen
    passed('stream-drain-cancellation-preserves-offloaded-sources-pinned-checkpoints-placement-and-deadlines')
    for name,target in targets.items():
        assert retained(target[0])==retained_before[name]
        assert pg.sql('SELECT count(*) FROM edgeai.task_offload_member',target[0])=='2'
        assert pg.sql('SELECT count(*) FROM edgeai.stream_checkpoint',target[0])=='2'
    report.update(streamOffloadCases=True,streamOffloadRestoredDatabases=len(targets),streamOffloadIntermediateRestores=1,
        streamOffloadsFailed=1,streamOffloadsCancelled=2,streamOffloadSourcesPreserved=2,streamOffloadCheckpointsPreserved=2,
        streamOffloadMemberPlansPreserved=2,streamOffloadPendingOperations=1,streamOffloadUnprovenOperations=3,
        streamSuccessorContainers=2,streamOffloadFullSourceHistoryPreserved=True,
        sourceProcessesEndedBeforeSuccessors=fixture['sourceProcessesEndedBeforeSuccessors'])
