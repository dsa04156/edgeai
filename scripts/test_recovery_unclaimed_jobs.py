"""Real retained Job children and explicit restored STARTING/offload database history."""
import copy
import json
import subprocess
import sys
import time
import uuid
from unittest.mock import patch

from postgres_backup import Blocked, literal, private_file
from recovery_kubernetes import PART, MANAGER, RUNTIME
from recovery_stop_kubernetes import FINALIZER, OPERATION
import recovery_kubernetes_retire as retirement
import recovery_kubernetes_workflows as workflows


def q(value): return literal(value)+'::uuid'


def seed(pg, db, kube, create, namespace, operation, pod_spec, source, original_attempt, original_run):
    source_node=pg.sql('SELECT node_uid::text FROM edgeai.runtime_instance WHERE id='+q(source['runtime']),db)
    nodes=kube.read('/api/v1/nodes')['items']
    candidates=[n for n in nodes if n['metadata']['uid']!=source_node and
        n['metadata']['labels'].get('kubernetes.io/arch')=='amd64' and not n['spec'].get('unschedulable') and
        any(c['type']=='Ready' and c['status']=='True' for c in n['status']['conditions'])]
    assert candidates,'A separate Ready amd64 target is required for this owned fixture'
    node=sorted(candidates,key=lambda n:n['metadata']['name'])[0]
    node_id,node_name=node['metadata']['uid'],node['metadata']['name']
    pg.sql('INSERT INTO edgeai.execution_node(id,name,architecture,operating_system,observed_status,cpu,memory,labels,observed_at) VALUES ('+
        q(node_id)+','+literal(node_name)+",'amd64','linux','READY','1','1Gi','{}',now()) ON CONFLICT(id) DO NOTHING",db)
    target={'runtime':str(uuid.uuid4()),'attempt':str(uuid.uuid4()),'task':source['task'],'run':source['run']}
    # This is recorded history, not a claim that the synthetic parent executed a VD SDK Task.
    pg.sql("UPDATE edgeai.runtime_instance SET desired_state='STOPPED',observed_state='TERMINATED' WHERE id="+q(source['runtime'])+
        "; UPDATE edgeai.vd_task_allocation SET closed_at=now(),close_reason='PROCESS_EXIT',completion_sequence=1,exit_code=0 WHERE runtime_id="+q(source['runtime'])+
        "; UPDATE edgeai.task_attempt SET state='OFFLOADED' WHERE id="+q(source['attempt'])+
        '; INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,node_id,cause,created_at,updated_at) VALUES ('+
        q(target['attempt'])+','+q(source['task'])+",2,2,'DISPATCHING','NODE',"+q(node_id)+",'OFFLOAD',now(),now())",db)
    empty={k:str(uuid.uuid4()) for k in ('runtime','attempt','task','run')}
    def clone(table,origin,changes):
        pg.sql('INSERT INTO edgeai.'+table+' SELECT (jsonb_populate_record(NULL::edgeai.'+table+',to_jsonb(t)||'+
            literal(json.dumps(changes))+'::jsonb)).* FROM edgeai.'+table+' t WHERE id='+q(origin),db)
    clone('workflow_run',original_run,{'id':empty['run'],'state':'RUNNING','idempotency_key':str(uuid.uuid4())})
    clone('task',original_attempt['task_id'],{'id':empty['task'],'run_id':empty['run'],'state':'RUNNING','cancellation_reason':None})
    clone('task_attempt',original_attempt['id'],{'id':empty['attempt'],'task_id':empty['task'],'state':'DISPATCHING'})
    for row,epoch,suspended in [(target,2,False),(empty,1,True)]:
        name='edgeai-'+row['attempt']
        labels={PART:'edgeai',MANAGER:RUNTIME,'edgeai.io/run-id':row['run'],'edgeai.io/task-id':row['task'],
            'edgeai.io/attempt-id':row['attempt'],'edgeai.io/epoch':str(epoch)}
        spec=copy.deepcopy(pod_spec);spec['nodeName']=node_name
        job=create({'apiVersion':'batch/v1','kind':'Job','metadata':{'namespace':namespace,'name':name,'labels':labels},
            'spec':{'parallelism':1,'completions':1,'backoffLimit':0,'suspend':suspended,
                    'template':{'metadata':{'labels':labels},'spec':spec}}})
        row['job']=job
        pg.sql('INSERT INTO edgeai.runtime_instance(id,attempt_id,task_id,run_id,epoch,namespace,job_name,job_uid,claim_nonce,desired_state,observed_state,created_at,updated_at) VALUES ('+
            ','.join(q(row[k]) for k in ('runtime','attempt','task','run'))+','+str(epoch)+','+literal(namespace)+','+literal(name)+','+
            q(job['metadata']['uid'])+','+q(str(uuid.uuid4()))+",'RUNNING','SUBMITTED',now(),now())",db)
        for kind in ('CREATE','DELETE'):
            pg.sql('INSERT INTO edgeai.runtime_command(id,runtime_id,kind,attempts,available_at,lease_owner,lease_until,created_at,updated_at) VALUES ('+
                q(str(uuid.uuid4()))+','+q(row['runtime'])+','+literal(kind)+',3,now(),'+q(str(uuid.uuid4()))+",now()+interval '5 minutes',now(),now())",db)
    operation_id=str(uuid.uuid4())
    pg.sql('INSERT INTO edgeai.task_offload(id,task_id,run_id,source_attempt_id,target_attempt_id,target_node_id,idempotency_key,request_digest,namespace,state,'
        'drain_deadline,start_timeout_seconds,start_deadline,created_at,updated_at) VALUES ('+
        ','.join(q(v) for v in (operation_id,source['task'],source['run'],source['attempt'],target['attempt'],node_id,str(uuid.uuid4())))+','+
        literal('sha256:'+'d'*64)+','+literal(namespace)+",'STARTING',now()-interval '2 minutes',60,now()-interval '1 second',now()-interval '3 minutes',now())",db)
    deadline=time.monotonic()+150
    while time.monotonic()<deadline:
        pods=[p for p in kube.items(namespace,'Pod')[0] if any(r.get('uid')==target['job']['metadata']['uid'] for r in p['metadata'].get('ownerReferences',[]))]
        if len(pods)==1 and pods[0].get('status',{}).get('phase')=='Running':
            ready=subprocess.run(kube.command+['-n',namespace,'exec',pods[0]['metadata']['name'],'--','python3','-c',
                "from pathlib import Path;import os;os.kill(int(Path('/tmp/child-ready').read_text()),0)"],capture_output=True,timeout=15)
            if ready.returncode==0: break
        time.sleep(.3)
    else: raise AssertionError('Unclaimed target parent and child did not become ready')
    # A second real API child exercises complete enumeration. It is deliberately
    # never bound; this is not a claim that a Job replacement was scheduled.
    sibling_spec=copy.deepcopy(pod_spec);sibling_spec['schedulingGates']=[{'name':'edgeai.io/recovery-test'}]
    sibling=create({'apiVersion':'v1','kind':'Pod','metadata':{'namespace':namespace,
        'name':target['job']['metadata']['name']+'-retained','labels':target['job']['spec']['template']['metadata']['labels'],
        'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job','name':target['job']['metadata']['name'],
            'uid':target['job']['metadata']['uid'],'controller':True,'blockOwnerDeletion':True}],
        'finalizers':[FINALIZER],'annotations':{OPERATION:operation}},'spec':sibling_spec})
    target['podUids']=sorted([pods[0]['metadata']['uid'],sibling['metadata']['uid']])
    return {'source':source,'target':target,'empty':empty,'operation':operation_id}


def check(pg, db, receipt, options, retire_cli, fingerprints, passed, work, fixture, report):
    target,empty,source=(fixture[k] for k in ('target','empty','source'))
    def opts(**kwargs): return options(db,receipt,unclaimed_jobs=True,**kwargs)
    def state(rid): return json.loads(pg.sql('SELECT to_jsonb(r) FROM edgeai.runtime_instance r WHERE id='+q(rid),db))
    pristine=fingerprints(db)
    default=retirement.prepare(pg,options(db,receipt))
    assert target['runtime'] not in default['selected']['runtimes'] and not default['unclaimedJobs']
    assert fingerprints(db)==pristine
    passed('recorded-unclaimed-job-recovery-requires-explicit-opt-in')
    selected=retirement.prepare(pg,opts())
    expected={'runtimeId':target['runtime'],'jobUid':target['job']['metadata']['uid'],
        'podUids':target['podUids'],'proof':'OBSERVED_RETAINED_JOB_CHILDREN','producerClaimRecorded':False}
    assert selected['unclaimedJobs']==[expected] and target['runtime'] in selected['selected']['runtimes']
    assert any(r['id']==empty['runtime'] and r['reason']=='RETAINED_JOB_CHILDREN_NOT_PROVEN' for r in selected['unresolved'])
    assert fingerprints(db)==pristine
    passed('actual-running-and-never-bound-children-are-enumerated-while-a-suspended-job-without-pods-remains-unresolved')

    pg.sql('UPDATE edgeai.runtime_instance SET job_uid=NULL WHERE id='+q(target['runtime']),db)
    unknown=fingerprints(db); missing=retirement.prepare(pg,opts())
    assert target['runtime'] not in missing['selected']['runtimes'] and not missing['unclaimedJobs']
    assert fingerprints(db)==unknown
    pg.sql('UPDATE edgeai.runtime_instance SET job_uid='+q(target['job']['metadata']['uid'])+' WHERE id='+q(target['runtime']),db)
    assert fingerprints(db)==pristine
    passed('an-observed-job-never-substitutes-for-a-missing-recorded-job-uid')

    # Mutate actual snapshots at the observation boundary, not the shared cluster.
    original=retirement.live_evidence
    def reject_snapshot(mutator):
        def altered(a):
            evidence,jobs,pods=original(a);mutator(jobs,pods);return evidence,jobs,pods
        with patch.object(retirement,'live_evidence',altered):
            try: retirement.prepare(pg,opts())
            except Blocked: pass
            else: raise AssertionError('Contradictory Job observation was accepted')
        assert fingerprints(db)==pristine
    def target_job(jobs): return next(j for j in jobs if j['metadata']['uid']==target['job']['metadata']['uid'])
    reject_snapshot(lambda jobs,pods:target_job(jobs)['spec'].update(backoffLimit=1))
    reject_snapshot(lambda jobs,pods:target_job(jobs).setdefault('status',{}).update(active=1))
    reject_snapshot(lambda jobs,pods:target_job(jobs).setdefault('status',{}).update(failed=3))
    reject_snapshot(lambda jobs,pods:target_job(jobs).setdefault('status',{}).update(uncountedTerminatedPods={'failed':[str(uuid.uuid4())]}))
    passed('retry-enabled-active-and-unretained-job-history-observations-block-all-database-writes')
    def wrong_owner(jobs,pods):
        pod=next(p for p in pods if p['metadata']['uid']==target['podUids'][0])
        pod['metadata']['ownerReferences'][0]['apiVersion']='untrusted/v1'
    reject_snapshot(wrong_owner)
    passed('child-controller-api-identity-is-checked-in-addition-to-labels-and-job-uid')

    a=opts();a.output.mkdir(mode=0o700);plan=retirement.prepare(pg,a)
    class LostReply:
        def call(self,*positional,**keywords):
            result=pg.call(*positional,**keywords)
            if keywords.get('source') is not None: raise OSError('Injected actual unclaimed retirement COMMIT reply loss')
            return result
    try: retirement.apply(LostReply(),a,plan)
    except OSError: pass
    else: raise AssertionError('Actual unclaimed retirement COMMIT reply loss not injected')
    assert (a.output/'intent.json').exists() and not (a.output/'retirement.json').exists()
    retired=fingerprints(db);replay=retire_cli(opts())
    assert not replay['databaseModified'] and replay['unclaimedJobs']==[expected] and fingerprints(db)==retired
    actual=state(target['runtime'])
    assert actual['desired_state']=='STOPPED' and actual['observed_state']=='TERMINATED'
    assert all(actual[k] is None for k in ('producer_pod_uid','node_uid','node_name','failure_reason'))
    assert state(empty['runtime'])['observed_state']=='SUBMITTED'
    assert pg.sql('SELECT count(*) FROM edgeai.runtime_command WHERE runtime_id='+q(empty['runtime'])+' AND NOT completed',db)=='2'
    assert pg.sql('SELECT count(*) FROM edgeai.runtime_command WHERE runtime_id='+q(target['runtime'])+' AND NOT completed',db)=='0'
    assert {t for t in pristine if pristine[t]!=retired[t]}=={'runtime_instance','runtime_command','vd_runtime','vd_runtime_command','vd_runtime_binding','vd_task_allocation'}
    passed('actual-unclaimed-retirement-commit-reply-loss-recovers-without-inventing-claim-node-result-or-completing-the-empty-job')

    def workflow_cli(a):
        command=[sys.executable,'scripts/recovery_kubernetes_workflows.py']
        for key,value in vars(a).items():
            if value is True:command+=['--'+key.replace('_','-')]
            elif value is not None and value is not False:command+=['--'+key.replace('_','-'),str(value)]
        result=subprocess.run(command,capture_output=True,timeout=180)
        with private_file(work/('unclaimed-workflow-'+uuid.uuid4().hex+'.log')) as log:log.write(result.stdout+result.stderr)
        assert result.returncode==0,'Unclaimed offload recovery CLI differs; private diagnostics retained'
        return json.loads((a.output/'workflows.json').read_text())
    def operation():return json.loads(pg.sql('SELECT to_jsonb(o) FROM edgeai.task_offload o WHERE id='+q(fixture['operation']),db))
    before_operation=operation();source_before=state(source['runtime'])
    ordinary=workflows.prepare(pg,options(db,receipt,offloads=True))
    assert ordinary['unresolvedOffloads']==[{'operationId':fixture['operation'],'reason':'OFFLOAD_PRODUCER_NOT_PROVEN'}]
    a=opts(offloads=True);plan=workflows.prepare(pg,a)
    assert [e['action'] for e in plan['entries'] if e.get('operationId')==fixture['operation']]==['CHECK_OFFLOAD_START']
    passed('starting-offload-requires-fresh-source-and-unclaimed-target-termination-proof-before-deadline-reconciliation')

    pg.sql("CREATE FUNCTION edgeai.reject_unclaimed_recovery() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned final write fault'; END $$; "
        'CREATE TRIGGER reject_unclaimed_recovery BEFORE UPDATE ON edgeai.workflow_run FOR EACH ROW EXECUTE FUNCTION edgeai.reject_unclaimed_recovery()',db)
    a.output.mkdir(mode=0o700)
    try:workflows.apply(pg,a,plan)
    except RuntimeError:pass
    else:raise AssertionError('Late STARTING recovery fault was not observed')
    pg.sql('DROP TRIGGER reject_unclaimed_recovery ON edgeai.workflow_run; DROP FUNCTION edgeai.reject_unclaimed_recovery()',db)
    assert fingerprints(db)==retired and operation()==before_operation and not (a.output/'workflows.json').exists()
    passed('late-run-failure-rolls-back-target-failure-reason-attempt-task-and-starting-operation-together')

    completed=workflow_cli(opts(offloads=True));after=fingerprints(db)
    assert completed['offloadsFailed']==1 and completed['attemptsFailed']==1 and completed['pendingOffloads']==0
    assert not completed['activated'] and not completed['globalQuiescenceProven'] and completed['unclaimedJobs']==[expected]
    after_operation=operation()
    assert after_operation['state']=='FAILED' and after_operation['failure_reason']=='TARGET_START_TIMEOUT'
    assert all(after_operation[k]==v for k,v in before_operation.items() if k not in ('state','failure_reason','updated_at'))
    assert state(source['runtime'])==source_before
    assert pg.sql('SELECT state FROM edgeai.task_attempt WHERE id='+q(source['attempt']),db)=='OFFLOADED'
    assert pg.sql('SELECT state FROM edgeai.task_attempt WHERE id='+q(target['attempt']),db)=='FAILED'
    assert pg.sql('SELECT state FROM edgeai.task WHERE id='+q(target['task']),db)=='FAILED'
    actual=state(target['runtime'])
    assert actual['failure_reason']=='TARGET_START_TIMEOUT' and all(actual[k] is None for k in ('producer_pod_uid','node_uid','node_name'))
    assert {t for t in retired if retired[t]!=after[t]}=={'runtime_instance','task_attempt','task','workflow_run','task_retry','task_offload'}
    passed('real-unclaimed-target-start-expiry-preserves-source-offloaded-state-placement-deadlines-and-37-other-tables')
    assert not workflow_cli(opts(offloads=True))['databaseModified'] and fingerprints(db)==after
    assert not workflow_cli(opts())['databaseModified'] and fingerprints(db)==after
    passed('expired-starting-recovery-and-ordinary-workflow-replay-change-no-history-or-timestamps')
    report.update(unclaimedJobsVerified=True,unclaimedTargetJobsRetired=1,unclaimedTargetPodsProven=2,
        emptyJobsPreserved=1,unclaimedClaimsCreated=0,targetStartTimeoutsReconciled=1,unclaimedPreservedTables=37)
