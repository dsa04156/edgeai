"""Explicit restored BATCH transfer histories using the harness's actual retained producer evidence."""
import json
import subprocess
import sys
import uuid

from postgres_backup import Blocked, literal, private_file
import recovery_kubernetes_workflows as recovery


def check(pg, db, receipt, options, fingerprints, passed, work, vd_work, kube_attempt, report):
    namespace=options(db,receipt).namespace
    def q(value): return literal(value)+'::uuid'
    def opts(): return options(db,receipt,offloads=True)
    def cli(a):
        command=[sys.executable,'scripts/recovery_kubernetes_workflows.py']
        for key,value in vars(a).items():
            if value is True: command+=['--'+key.replace('_','-')]
            elif value is not None and value is not False: command+=['--'+key.replace('_','-'),str(value)]
        response=subprocess.run(command,capture_output=True,timeout=180)
        with private_file(work/('offload-command-'+uuid.uuid4().hex+'.log')) as log: log.write(response.stdout+response.stderr)
        assert response.returncode==0,'Offload recovery CLI differs; private diagnostics retained'
        return json.loads((a.output/'workflows.json').read_text())
    def prepared():
        a=opts(); a.output.mkdir(mode=0o700); return a,recovery.prepare(pg,a)

    # Manual target identity is immutable; no scheduler/worker is started by this fixture.
    node=str(uuid.uuid4())
    pg.sql('INSERT INTO edgeai.execution_node(id,name,architecture,operating_system,observed_status,cpu,memory,labels,observed_at) VALUES ('+
        q(node)+",'offload-target-fixture','amd64','linux','READY','1','1Gi','{}',now())",db)
    kube=json.loads(pg.sql('SELECT jsonb_build_object(\'runtime\',r.id,\'task\',r.task_id,\'attempt\',r.attempt_id,\'run\',r.run_id) '
        'FROM edgeai.runtime_instance r WHERE r.attempt_id='+q(kube_attempt['id']),db))
    fixtures={'cancel-kube':kube,'cancel-vd':vd_work['assigned'],'expired':vd_work['claimed'],'future':vd_work['unassigned']}
    for name in ('assigned','unassigned'):
        # Recorded pre-offload claim fixture, tied to the same actually terminated VD supervisor.
        pg.sql('UPDATE edgeai.runtime_instance r SET producer_pod_uid=s.producer_pod_uid,node_uid=s.node_uid,node_name=s.node_name '
            'FROM edgeai.runtime_instance s WHERE s.id='+q(vd_work['claimed']['runtime'])+' AND r.id='+q(vd_work[name]['runtime']),db)
    for name,row in fixtures.items():
        op=str(uuid.uuid4()); row={**row,'operation':op}; fixtures[name]=row
        cancelling=name.startswith('cancel')
        pg.sql("UPDATE edgeai.task_attempt SET state='OFFLOADED' WHERE id="+q(row['attempt'])+
            '; UPDATE edgeai.task SET state='+literal('CANCELLING' if cancelling else 'OFFLOADING')+',cancellation_reason='+
            ("'RUN_CANCELLED'" if cancelling else 'NULL')+' WHERE id='+q(row['task'])+
            '; UPDATE edgeai.workflow_run SET state='+literal('CANCELLING' if cancelling else 'RUNNING')+' WHERE id='+q(row['run']),db)
        pg.sql('INSERT INTO edgeai.task_offload(id,task_id,run_id,source_attempt_id,target_node_id,idempotency_key,request_digest,namespace,state,'
            'drain_deadline,start_timeout_seconds,created_at,updated_at) VALUES ('+
            ','.join(q(v) for v in (op,row['task'],row['run'],row['attempt'],node,str(uuid.uuid4())))+','+literal('sha256:'+'c'*64)+','+
            literal(namespace)+','+literal('CANCELLING' if name=='cancel-kube' else 'DRAINING')+
            ",now()+interval '"+('-1 second' if name=='expired' else '500 seconds')+"',60,now()-interval '1 minute',now())",db)
    pristine=fingerprints(db)
    normal=recovery.prepare(pg,options(db,receipt))
    assert not any(e.get('operationId') for e in normal['entries']) and len(normal['unresolvedWorkflows'])==4
    assert fingerprints(db)==pristine
    passed('offload-recovery-is-explicit-and-keeps-frozen-targets-and-deadlines-in-the-restored-history')

    future=fixtures['future']; target,missing=str(uuid.uuid4()),str(uuid.uuid4())
    pg.sql('UPDATE edgeai.runtime_instance SET producer_pod_uid=NULL,node_uid=NULL,node_name=NULL WHERE id='+q(future['runtime']),db)
    unclaimed=fingerprints(db)
    try: recovery.prepare(pg,opts())
    except Blocked: pass
    else: raise AssertionError('An offload source without a recorded claim was accepted')
    assert fingerprints(db)==unclaimed
    pg.sql('UPDATE edgeai.runtime_instance r SET producer_pod_uid=s.producer_pod_uid,node_uid=s.node_uid,node_name=s.node_name '
        'FROM edgeai.runtime_instance s WHERE s.id='+q(vd_work['claimed']['runtime'])+' AND r.id='+q(future['runtime']),db)
    assert fingerprints(db)==pristine
    passed('a-terminated-supervisor-allocation-does-not-substitute-for-the-recorded-offload-source-claim')

    pg.sql('INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,node_id,cause,created_at,updated_at) VALUES ('+
        q(target)+','+q(future['task'])+",2,2,'DISPATCHING','NODE',"+q(node)+",'OFFLOAD',now(),now()); "+
        'INSERT INTO edgeai.runtime_instance(id,attempt_id,task_id,run_id,epoch,namespace,job_name,claim_nonce,desired_state,observed_state,created_at,updated_at) VALUES ('+
        ','.join(q(v) for v in (missing,target,future['task'],future['run']))+',2,'+literal(namespace)+','+literal('edgeai-'+target)+','+
        q(str(uuid.uuid4()))+",'STOPPED','TERMINATED',now(),now()); UPDATE edgeai.task SET state='RUNNING' WHERE id="+q(future['task'])+
        "; UPDATE edgeai.task_offload SET state='STARTING',target_attempt_id="+q(target)+",start_deadline=now()-interval '1 second' WHERE id="+q(future['operation']),db)
    unknown=fingerprints(db); pending=recovery.prepare(pg,opts())
    assert pending['unresolvedOffloads']==[{'operationId':future['operation'],'reason':'OFFLOAD_PRODUCER_NOT_PROVEN'}]
    assert not any(e.get('operationId')==future['operation'] for e in pending['entries']) and fingerprints(db)==unknown
    pg.sql("UPDATE edgeai.task_offload SET state='DRAINING',target_attempt_id=NULL,start_deadline=NULL WHERE id="+q(future['operation'])+
        '; DELETE FROM edgeai.runtime_instance WHERE id='+q(missing)+'; DELETE FROM edgeai.task_attempt WHERE id='+q(target)+
        "; UPDATE edgeai.task SET state='OFFLOADING' WHERE id="+q(future['task']),db)
    assert fingerprints(db)==pristine
    passed('an-expired-starting-target-without-recorded-producer-proof-remains-unresolved-without-adopting-an-unknown-pod')

    newer=str(uuid.uuid4())
    pg.sql('INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,vd_id,cause,created_at,updated_at) SELECT '+q(newer)+
        ",task_id,2,2,'QUEUED',mode,vd_id,'RETRY',now(),now() FROM edgeai.task_attempt WHERE id="+q(future['attempt'])+
        "; UPDATE edgeai.task SET state='READY' WHERE id="+q(future['task']),db)
    newer_before=fingerprints(db); pending=recovery.prepare(pg,opts())
    assert pending['unresolvedOffloads']==[{'operationId':future['operation'],'reason':'NEWER_ATTEMPT_RECORDED'}]
    assert not any(e.get('operationId')==future['operation'] for e in pending['entries']) and fingerprints(db)==newer_before
    pg.sql('DELETE FROM edgeai.task_attempt WHERE id='+q(newer)+"; UPDATE edgeai.task SET state='OFFLOADING' WHERE id="+q(future['task']),db)
    assert fingerprints(db)==pristine
    passed('a-newer-recorded-attempt-is-never-overwritten-by-an-older-offload-operation')

    a,plan=prepared(); original_call=pg.call
    def racing(tool,arguments,*positional,**keywords):
        if arguments[-2:]==['-f','-']:
            pg.sql("UPDATE edgeai.task_offload SET drain_deadline=drain_deadline+interval '1 second' WHERE id="+q(future['operation']),db)
        return original_call(tool,arguments,*positional,**keywords)
    pg.call=racing
    try:
        try: recovery.apply(pg,a,plan)
        except RuntimeError: pass
        else: raise AssertionError('Offload-only race was accepted')
    finally: pg.call=original_call
    raced=fingerprints(db); assert {t for t in pristine if pristine[t]!=raced[t]}=={'task_offload'}
    pg.sql("UPDATE edgeai.task_offload SET drain_deadline=drain_deadline-interval '1 second' WHERE id="+q(future['operation']),db)
    assert fingerprints(db)==pristine
    passed('an-operation-only-deadline-change-after-live-observation-rolls-back-the-entire-recovery')

    pg.sql("CREATE FUNCTION edgeai.reject_offload_recovery() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned last write fault'; END $$; "
        'CREATE TRIGGER reject_offload_recovery BEFORE UPDATE ON edgeai.workflow_run FOR EACH ROW EXECUTE FUNCTION edgeai.reject_offload_recovery()',db)
    a,plan=prepared()
    try: recovery.apply(pg,a,plan)
    except RuntimeError: pass
    else: raise AssertionError('Late offload transaction fault was not observed')
    pg.sql('DROP TRIGGER reject_offload_recovery ON edgeai.workflow_run; DROP FUNCTION edgeai.reject_offload_recovery()',db)
    assert fingerprints(db)==pristine and not (a.output/'workflows.json').exists()
    passed('late-run-write-failure-rolls-back-operation-terminal-state-task-cancellation-and-deadline-expiry')

    a,plan=prepared(); committed={}
    def lost_reply(tool,arguments,*positional,**keywords):
        result=original_call(tool,arguments,*positional,**keywords)
        if arguments[-2:]==['-f','-']:
            committed.update(json.loads(result)); raise OSError('Injected actual COMMIT reply loss')
        return result
    pg.call=lost_reply
    try:
        try: recovery.apply(pg,a,plan)
        except OSError: pass
        else: raise AssertionError('Actual offload COMMIT reply loss not injected')
    finally: pg.call=original_call
    assert [committed[k] for k in ('offloadsFailed','offloadsCancelled','tasksCancelled','tasksSkipped','retriesExpired','runsReconciled')]==[1,2,2,2,1,6]
    assert committed['attemptsFailed']==0 and committed['retriesScheduled']==0
    after=fingerprints(db); assert {t for t in pristine if pristine[t]!=after[t]}=={'task','workflow_run','task_retry','task_offload'}
    assert (a.output/'intent.json').exists() and not (a.output/'workflows.json').exists()
    replay=cli(opts()); assert not replay['databaseModified'] and replay['pendingOffloads']==1 and not replay['unresolvedOffloads']
    assert fingerprints(db)==after
    assert pg.sql('SELECT state FROM edgeai.task_attempt WHERE id='+q(fixtures['expired']['attempt']),db)=='OFFLOADED'
    assert pg.sql('SELECT coalesce(failure_reason,\'NONE\') FROM edgeai.runtime_instance WHERE id='+q(fixtures['expired']['runtime']),db)=='NONE'
    assert pg.sql('SELECT state||\':\'||failure_reason FROM edgeai.task_offload WHERE id='+q(fixtures['expired']['operation']),db)=='FAILED:SOURCE_DRAIN_TIMEOUT'
    passed('two-recorded-cancellations-and-one-original-drain-expiry-preserve-source-attempts-targets-40-tables-and-the-future-operation')
    passed('actual-offload-commit-reply-loss-retains-intent-and-replays-without-new-attempt-or-deadline')

    pg.sql("UPDATE edgeai.task_offload SET created_at=created_at-interval '1 hour',drain_deadline=drain_deadline-interval '1 hour' WHERE id="+q(future['operation']),db)
    ended=cli(opts()); assert ended['offloadsFailed']==1 and ended['pendingOffloads']==0 and ended['runsReconciled']==1
    aged=fingerprints(db); assert not cli(opts())['databaseModified'] and fingerprints(db)==aged
    assert not cli(options(db,receipt))['databaseModified'] and fingerprints(db)==aged
    passed('future-drain-eventually-expires-under-the-recorded-budget-and-ordinary-workflow-recovery-accepts-the-offloaded-failed-history')
    report.update(offloadCases=True,offloadOperationsCancelled=2,offloadOperationsExpired=2,
        offloadUnknownTargetsPreserved=1,offloadPreservedTables=40)
