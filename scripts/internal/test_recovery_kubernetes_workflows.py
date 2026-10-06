"""Recorded workflow fixtures and checks within the real Kubernetes retirement harness."""
import json
import subprocess
import sys
import time
import uuid

from postgres_backup import Blocked, literal, private_file
import recovery_kubernetes_workflows as recovery


def seed(pg, db, api, namespace, vd, supervisor, service, existing, kube_attempt, kube_run):
    """Explicit backup history fixtures; physical termination is proved separately by the caller."""
    result = {}
    parent = json.loads(pg.sql('SELECT to_jsonb(v) FROM edgeai.vd_runtime v WHERE id='+literal(supervisor)+'::uuid',db))
    spec = json.loads(pg.sql('SELECT spec FROM edgeai.profile_version WHERE id='+literal(service)+'::uuid',db))
    child = api.request('POST','profiles/SERVICE',{'key':'recovery-child','version':'1.0.0',
        'spec':{**spec,'inputs':{'input':{'mediaType':'application/json','maxBytes':1048576,'required':True}}}},201)
    for name in ('retry','expired','final'):
        tasks=[{'key':'root','serviceProfileVersionId':service,'parameters':{'features':[2],'weights':[3]}}]
        dependencies=[]
        if name=='final':
            for key,previous in [('child','root'),('grandchild','child')]:
                tasks.append({'key':key,'serviceProfileVersionId':child['id'],'parameters':{}})
                dependencies.append({'fromTask':previous,'toTask':key,'fromPort':'output','toPort':'input','mode':'BATCH'})
        workflow=api.request('POST','workflows',{'key':'recovery-'+name,'displayName':'Recovery fixture'},201)
        version=api.request('POST','workflows/'+workflow['id']+'/versions',{'version':'1.0.0','tasks':tasks,'dependencies':dependencies},201)
        request={'workflowVersionId':version['id'],'execution':{'mode':'VD','vdId':vd},'parameters':{}}
        if name=='final': request['taskExecutions']={k:{'mode':'AUTO'} for k in ('child','grandchild')}
        else: request['retry']={'maxAttempts':3,'backoffSeconds':1,'maxElapsedSeconds':3600,'retryOn':['WORKLOAD_FAILED']}
        run=api.request('POST','workflow-runs',request,201,str(uuid.uuid4()))
        attempt=json.loads(pg.sql('SELECT to_jsonb(a) FROM edgeai.task_attempt a JOIN edgeai.task t ON t.id=a.task_id '
            'JOIN edgeai.task_definition d ON d.id=t.definition_id WHERE t.run_id='+literal(run['id'])+"::uuid AND d.task_key='root'",db))
        rid=pg.sql('SELECT id FROM edgeai.runtime_instance WHERE attempt_id='+literal(attempt['id'])+'::uuid',db)
        uuid.UUID(rid)
        aid=str(uuid.uuid4()); task=attempt['task_id']
        result[name]={'run':run['id'],'task':task,'attempt':attempt['id'],'runtime':rid,'allocation':aid}
        pg.sql('INSERT INTO edgeai.vd_task_allocation(id,runtime_id,vd_id,vd_runtime_id,generation,session_id,pod_uid,slot,assigned_sequence,assigned_at) VALUES ('+
            ','.join(literal(v)+'::uuid' for v in (aid,rid,vd,supervisor))+',1,'+literal(parent['session_id'])+'::uuid,'+
            literal(parent['pod_uid'])+'::uuid,4,0,now()); '+
            "UPDATE edgeai.runtime_instance SET desired_state='STOPPED',observed_state='TERMINATED',failure_reason='WORKLOAD_FAILED' WHERE id="+
            literal(rid)+"::uuid; UPDATE edgeai.vd_task_allocation SET closed_at=now(),close_reason='NOT_STARTED',completion_sequence=1 WHERE id="+literal(aid)+'::uuid',db)
        # NOT_STARTED does not imply WORKLOAD_FAILED: the recorded failure is a separate fixture input.
        pg.sql("UPDATE edgeai.task_attempt SET state='FAILED',updated_at=now() WHERE id="+literal(attempt['id'])+
            "::uuid; UPDATE edgeai.task SET state='FAILED' WHERE id="+literal(task)+'::uuid',db)
        if name!='final':
            if name=='expired':
                pg.sql("UPDATE edgeai.task_attempt SET created_at=created_at-interval '2 hours',updated_at=updated_at-interval '2 hours' WHERE id="+literal(attempt['id'])+'::uuid',db)
            pg.sql('INSERT INTO edgeai.task_retry(task_id,failed_attempt_id,namespace,available_at,deadline) SELECT t.id,a.id,'+
                literal(namespace)+',a.updated_at+make_interval(secs=>w.retry_backoff_seconds),a.created_at+make_interval(secs=>w.retry_max_elapsed_seconds) '
                'FROM edgeai.task t JOIN edgeai.task_attempt a ON a.task_id=t.id JOIN edgeai.workflow_run w ON w.id=t.run_id WHERE t.id='+literal(task)+'::uuid; '+
                "UPDATE edgeai.task SET state='RETRY_WAIT' WHERE id="+literal(task)+'::uuid',db)
    pg.sql("UPDATE edgeai.runtime_instance SET failure_reason='DISPATCH_TIMEOUT' WHERE id="+literal(existing['not-started']['runtime'])+
        "::uuid; UPDATE edgeai.workflow_run SET state='RUNNING' WHERE id="+literal(existing['not-started']['run'])+'::uuid',db)
    for task,attempt,run in [(kube_attempt['task_id'],kube_attempt['id'],kube_run),
                            (existing['assigned']['task'],existing['assigned']['attempt'],existing['assigned']['run'])]:
        pg.sql("UPDATE edgeai.task SET state='CANCELLING',cancellation_reason='RUN_CANCELLED' WHERE id="+literal(task)+
            "::uuid; UPDATE edgeai.task_attempt SET state='CANCELLING' WHERE id="+literal(attempt)+
            "::uuid; UPDATE edgeai.workflow_run SET state='CANCELLING' WHERE id="+literal(run)+'::uuid',db)
    return result


def check(pg, targets, options, fingerprints, passed, work, fixtures, vd_work, kube_attempt, report):
    db,receipt=targets[0]; second,receipt2=targets[1]
    def cli(a,expected=0):
        command=[sys.executable,'scripts/internal/recovery_kubernetes_workflows.py']
        for key,value in vars(a).items():
            if value is not None: command+=['--'+key.replace('_','-'),str(value)]
        response=subprocess.run(command,capture_output=True,timeout=180)
        with private_file(work/('workflow-command-'+uuid.uuid4().hex+'.log')) as log: log.write(response.stdout+response.stderr)
        assert response.returncode==expected,'Workflow recovery CLI differs; private diagnostics retained'
        return json.loads((a.output/('workflows.json' if expected==0 else 'failure.json')).read_text())
    def plan_for(database=db,receipt=receipt):
        a=options(database,receipt); a.output.mkdir(mode=0o700)
        return a,recovery.prepare(pg,a)
    def refused(a,plan):
        before=fingerprints(a.database)
        try: recovery.apply(pg,a,plan)
        except (Blocked,RuntimeError,OSError): pass
        else: raise AssertionError('Conflicting workflow recovery accepted')
        assert fingerprints(a.database)==before and not (a.output/'workflows.json').exists()
    def quoted(name,key): return literal(fixtures[name][key])+'::uuid'

    pristine,other=fingerprints(db),fingerprints(second)
    a=options(db,receipt2); assert cli(a,1)['databaseModified'] is False and fingerprints(db)==pristine
    passed('workflow-reconciliation-refuses-a-different-restored-database-receipt')
    pg.sql("UPDATE edgeai.task SET cancellation_reason=NULL WHERE id="+literal(kube_attempt['task_id'])+'::uuid',db)
    changed=fingerprints(db); assert cli(options(db,receipt),2)['databaseModified'] is False and fingerprints(db)==changed
    pg.sql("UPDATE edgeai.task SET cancellation_reason='RUN_CANCELLED' WHERE id="+literal(kube_attempt['task_id'])+'::uuid',db)
    assert fingerprints(db)==pristine
    passed('workflow-cancellation-requires-a-recorded-reason-and-does-not-infer-one-from-pod-exit')

    offload=str(uuid.uuid4())
    pg.sql('INSERT INTO edgeai.task_offload(id,task_id,run_id,source_attempt_id,idempotency_key,request_digest,namespace,state,drain_deadline,start_timeout_seconds,created_at,updated_at,trigger,excluded_node_names,decision) SELECT '+
        literal(offload)+'::uuid,t.id,t.run_id,'+literal(kube_attempt['id'])+'::uuid,'+literal(str(uuid.uuid4()))+'::uuid,'+
        literal('sha256:'+'a'*64)+','+literal(options(db,receipt).namespace)+",'DRAINING',now()+interval '1 minute',60,now(),now(),'CPU',ARRAY['fixture-node'],'{}'::jsonb FROM edgeai.task t WHERE t.id="+
        literal(kube_attempt['task_id'])+'::uuid',db)
    offload_before=fingerprints(db); _,pending=plan_for()
    assert any(v['reason']=='STREAM_OR_ACTIVE_OFFLOAD' for v in pending['unresolvedWorkflows'])
    assert not any(e['taskId']==kube_attempt['task_id'] for e in pending['entries'])
    assert fingerprints(db)==offload_before
    pg.sql('DELETE FROM edgeai.task_offload WHERE id='+literal(offload)+'::uuid',db)
    assert fingerprints(db)==pristine
    passed('an-active-offload-is-reported-separately-without-inferring-its-outcome-or-changing-its-deadline')

    pg.sql("UPDATE edgeai.task_retry SET deadline=deadline+interval '1 hour' WHERE task_id="+quoted('retry','task'),db)
    altered=fingerprints(db); cli(options(db,receipt),1); assert fingerprints(db)==altered
    pg.sql("UPDATE edgeai.task_retry SET deadline=deadline-interval '1 hour' WHERE task_id="+quoted('retry','task'),db)
    assert fingerprints(db)==pristine
    passed('original-retry-budget-is-enforced-and-invalid-deadline-rolls-back-all-workflow-writes')

    a,plan=plan_for(); original_call=pg.call
    def racing(tool,arguments,*positional,**keywords):
        if arguments[-2:]==['-f','-']:
            pg.sql("UPDATE edgeai.task_retry SET available_at=available_at+interval '1 second' WHERE task_id="+quoted('retry','task'),db)
        return original_call(tool,arguments,*positional,**keywords)
    pg.call=racing
    try:
        try: recovery.apply(pg,a,plan)
        except RuntimeError: pass
        else: raise AssertionError('Last-moment retry race was accepted')
    finally: pg.call=original_call
    raced=fingerprints(db); assert {t for t in pristine if pristine[t]!=raced[t]}=={'task_retry'}
    pg.sql("UPDATE edgeai.task_retry SET available_at=available_at-interval '1 second' WHERE task_id="+quoted('retry','task'),db)
    assert fingerprints(db)==pristine
    passed('retry-only-write-after-final-observation-is-rejected-by-the-transaction-guard')

    locker=None; lock_name='workflow-retry-lock-'+uuid.uuid4().hex
    try:
        with private_file(work/'workflow-lock.log') as log:
            locker=subprocess.Popen(pg.prefix+[pg.binaries['psql']]+pg.connection+['--dbname',db,'-X','-q','-v','ON_ERROR_STOP=1','-c',
                'BEGIN; LOCK TABLE edgeai.task_retry IN ROW EXCLUSIVE MODE; SELECT pg_sleep(60); COMMIT;'],
                env={**pg.env,'PGAPPNAME':lock_name},stdout=log,stderr=log)
        deadline=time.monotonic()+5
        while pg.sql("SELECT count(*) FROM pg_stat_activity a JOIN pg_locks l ON l.pid=a.pid WHERE a.application_name="+
                literal(lock_name)+" AND l.relation='edgeai.task_retry'::regclass AND l.granted",db)!='1':
            assert time.monotonic()<deadline; time.sleep(.05)
        a,plan=plan_for(); started=time.monotonic(); refused(a,plan)
        assert 4<=time.monotonic()-started<45
    finally:
        pg.sql('SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='+literal(lock_name),db)
        if locker is not None: locker.wait(timeout=10)
    passed('actual-retry-table-writer-times-out-without-partial-workflow-reconciliation')

    # An unobserved descendant must not become safely cancelled merely because DB state says TERMINATED.
    child=pg.sql('SELECT t.id FROM edgeai.task t JOIN edgeai.task_definition d ON d.id=t.definition_id WHERE t.run_id='+quoted('final','run')+" AND d.task_key='child'",db)
    child_attempt,child_runtime=str(uuid.uuid4()),str(uuid.uuid4())
    pg.sql('INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,cause,created_at,updated_at) VALUES ('+
        literal(child_attempt)+'::uuid,'+literal(child)+"::uuid,1,1,'CANCELLING','AUTO','INITIAL',now(),now()); "+
        "UPDATE edgeai.task SET state='CANCELLING',cancellation_reason='TASK_CANCELLED' WHERE id="+literal(child)+'::uuid; '+
        'INSERT INTO edgeai.runtime_instance(id,attempt_id,task_id,run_id,epoch,namespace,job_name,claim_nonce,desired_state,observed_state,created_at,updated_at) VALUES ('+
        ','.join(literal(v)+'::uuid' for v in (child_runtime,child_attempt,child,fixtures['final']['run']))+',1,'+
        literal(options(db,receipt).namespace)+','+literal('edgeai-'+child_attempt)+','+literal(str(uuid.uuid4()))+"::uuid,'STOPPED','TERMINATED',now(),now())",db)
    guarded=fingerprints(db); cli(options(db,receipt),1); assert fingerprints(db)==guarded
    pg.sql('DELETE FROM edgeai.runtime_instance WHERE id='+literal(child_runtime)+'::uuid; DELETE FROM edgeai.task_attempt WHERE id='+literal(child_attempt)+
        "::uuid; UPDATE edgeai.task SET state='WAITING',cancellation_reason=NULL WHERE id="+literal(child)+'::uuid',db)
    assert fingerprints(db)==pristine
    passed('database-terminal-descendant-without-live-producer-proof-blocks-the-whole-transaction')

    pg.sql("CREATE FUNCTION edgeai.reject_workflow_recovery() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned final write fault'; END $$; "
        'CREATE TRIGGER reject_workflow_recovery BEFORE UPDATE ON edgeai.workflow_run FOR EACH ROW EXECUTE FUNCTION edgeai.reject_workflow_recovery()',db)
    a,plan=plan_for(); refused(a,plan)
    pg.sql('DROP TRIGGER reject_workflow_recovery ON edgeai.workflow_run; DROP FUNCTION edgeai.reject_workflow_recovery()',db)
    assert fingerprints(db)==pristine
    passed('late-run-update-failure-rolls-back-cancellations-expiry-and-descendant-changes')

    initial=cli(options(db,receipt)); after=fingerprints(db)
    assert [initial[k] for k in ('attemptsFailed','retriesScheduled','retriesExpired','tasksCancelled','tasksSkipped','runsReconciled')]==[0,0,1,2,2,5]
    assert initial['pendingRetries']==1 and initial['historiesRetained']==4
    assert initial['unresolvedWorkflows']==[{'runtimeId':vd_work['claimed']['runtime'],'reason':'OUTCOME_NOT_RECORDED'}]
    assert len(initial['unresolvedProducers'])==1 and not initial['activated'] and not initial['globalQuiescenceProven']
    changed={'task_attempt','task','task_retry','workflow_run'}
    assert {t for t in pristine if pristine[t]!=after[t]}==changed and fingerprints(second)==other
    assert pg.sql('SELECT state FROM edgeai.task WHERE id='+literal(vd_work['claimed']['task'])+'::uuid',db)=='RUNNING'
    assert pg.sql('SELECT state FROM edgeai.task_attempt WHERE id='+quoted('expired','attempt'),db)=='FAILED'
    assert not cli(options(db,receipt))['databaseModified'] and fingerprints(db)==after
    report.update(workflowCases=True,workflowPreservedTables=40,workflowTasksCancelled=2,workflowTasksSkipped=2,
        workflowRetriesExpired=1,workflowPendingRetries=1,workflowRunsReconciled=5,workflowUnknownOutcomes=1)
    passed('recorded-cancellations-and-original-deadline-expiry-reconcile-five-runs-without-creating-attempts-or-changing-40-tables')
    passed('unknown-running-outcome-sealed-results-unassigned-work-and-future-retry-remain-pending-or-immutable-on-replay')

    # The old failed producer cannot overwrite a newer, already recorded retry attempt.
    pg.sql('DELETE FROM edgeai.task_retry WHERE task_id='+quoted('retry','task')+'; '+
        'INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,vd_id,cause,created_at,updated_at) SELECT gen_random_uuid(),task_id,2,2,\'QUEUED\',mode,vd_id,\'RETRY\',now(),now() FROM edgeai.task_attempt WHERE id='+quoted('retry','attempt')+
        "; UPDATE edgeai.task SET state='READY' WHERE id="+quoted('retry','task'),second)
    a,plan=plan_for(second,receipt2)
    def lost_reply(tool,arguments,*positional,**keywords):
        response=original_call(tool,arguments,*positional,**keywords)
        if arguments[-2:]==['-f','-']: raise OSError('Injected actual COMMIT reply loss')
        return response
    pg.call=lost_reply
    try:
        try: recovery.apply(pg,a,plan)
        except OSError: pass
        else: raise AssertionError('Lost reply was not injected')
    finally: pg.call=original_call
    assert (a.output/'intent.json').exists() and not (a.output/'workflows.json').exists()
    lost_after=fingerprints(second)
    assert not cli(options(second,receipt2))['databaseModified'] and fingerprints(second)==lost_after
    assert pg.sql('SELECT state FROM edgeai.task WHERE id='+quoted('retry','task'),second)=='READY'
    passed('actual-commit-reply-loss-resumes-with-zero-changes-and-preserves-a-newer-recorded-attempt')

    pg.sql("UPDATE edgeai.workflow_run SET state='RUNNING' WHERE id="+literal(vd_work['assigned']['run'])+
        "::uuid OR id="+literal(vd_work['completed']['run'])+'::uuid',second)
    retained=fingerprints(second); fixed=cli(options(second,receipt2))
    assert fixed['runsReconciled']==2 and fixed['tasksCancelled']==0 and fixed['attemptsFailed']==0
    checked=fingerprints(second); assert {t for t in retained if retained[t]!=checked[t]}=={'workflow_run'}
    assert pg.sql('SELECT state FROM edgeai.workflow_run WHERE id='+literal(vd_work['assigned']['run'])+'::uuid',second)=='CANCELLED'
    assert pg.sql('SELECT state FROM edgeai.workflow_run WHERE id='+literal(vd_work['completed']['run'])+'::uuid',second)=='SUCCEEDED'
    passed('already-terminal-task-and-result-history-completes-stale-active-runs-without-rewriting-outcomes')

    # Explicit backup-history clock aging; this does not claim elapsed wall-clock performance.
    pg.sql("UPDATE edgeai.task_attempt SET created_at=created_at-interval '2 hours',updated_at=updated_at-interval '2 hours' WHERE id="+quoted('retry','attempt')+
        "; UPDATE edgeai.task_retry SET available_at=available_at-interval '2 hours',deadline=deadline-interval '2 hours' WHERE task_id="+quoted('retry','task'),db)
    expired=cli(options(db,receipt)); assert expired['retriesExpired']==1 and expired['pendingRetries']==0 and expired['runsReconciled']==1
    snapshot=fingerprints(db); assert not cli(options(db,receipt))['databaseModified'] and fingerprints(db)==snapshot
    passed('future-retry-eventually-expires-without-extending-budget-or-creating-a-new-attempt')
