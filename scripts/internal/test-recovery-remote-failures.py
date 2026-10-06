"""Real API/Remote terminal outcomes and restored PostgreSQL retry/cancellation recovery, with sources offline."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy
import secrets
import ssl
import subprocess
import sys
import time
import traceback
from types import SimpleNamespace
import urllib.error
import uuid

from postgres_backup import Blocked, Postgres, ROOT, backup, restore, identifier, literal, private_file
from recovery_remote_inventory import canonical
import recovery_remote_retire as retirement
import recovery_remote_outputs as outputs
import recovery_remote_failures as recovery

sys.path.insert(0, str(ROOT / 'simulator/tests'))
from test_remote_recovery import RemoteRecoveryTest
Api = runpy.run_path(str(ROOT / 'scripts/internal/test-postgres-backup.py'))['Api']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transport', choices=('native', 'compose'), default='native')
    parser.add_argument('--report', type=Path, default=ROOT / '.tools/recovery-remote-failures-test.json')
    args = parser.parse_args()
    work = ROOT / '.tools' / ('recovery-remote-failures-test-' + uuid.uuid4().hex); work.mkdir(mode=0o700)
    pg = Postgres(args.transport, diagnostics=work / 'postgres')
    fixture = RemoteRecoveryTest('test_separate_credentials_identity_tls_pin_and_inspection')
    owned, apis = {}, []
    report = {'status': 'RUNNING', 'scope': 'restored-reference-remote-failure-reconciliation-tests', 'sourceMode': 'SYNTHETIC', 'cases': []}

    def passed(name): report['cases'].append(name); print('PASS: ' + name, flush=True)
    def remember(db): owned[db] = pg.sql('SELECT oid FROM pg_database WHERE datname=' + literal(db), 'postgres')
    def drop(db):
        assert pg.sql('SELECT oid FROM pg_database WHERE datname=' + literal(db), 'postgres') == owned[db]
        pg.sql('DROP DATABASE ' + identifier(db), 'postgres'); del owned[db]
    def fingerprints(db):
        names = json.loads(pg.sql("SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='edgeai'", db))
        return {name: hashlib.sha256(pg.sql('SELECT to_jsonb(t)::text FROM edgeai.' + identifier(name) +
                    ' t ORDER BY to_jsonb(t)::text', db).encode()).hexdigest() for name in names}
    def remote_options(db, receipt):
        output = work / ('remote-' + uuid.uuid4().hex); output.mkdir(mode=0o700)
        return SimpleNamespace(database=db, restore_report=receipt, output=output, endpoint=origin,
             provider_key='reference', provider_id=binding['providerId'], recovery_id=binding['recoveryId'],
             ca_file=fixture.root / 'cert.pem', certificate_sha256=pin, recovery_token_file=fixture.root / 'operator', timeout=60, page_size=2)
    def options(index=0):
        db, receipt, bundle = targets[index]
        return SimpleNamespace(database=db, restore_report=receipt, bundle=bundle, transport=args.transport,
                               output=work / ('failure-recovery-' + uuid.uuid4().hex))
    def cli(a, expected=0):
        command = [sys.executable, 'scripts/internal/recovery_remote_failures.py']
        for key,value in vars(a).items(): command += ['--' + key.replace('_', '-'), str(value)]
        response = subprocess.run(command, capture_output=True, timeout=90)
        with private_file(work / ('command-' + uuid.uuid4().hex + '.log')) as log: log.write(response.stdout + response.stderr)
        assert response.returncode == expected, 'Failure recovery CLI differs; private diagnostics retained'
        assert fixture.token.encode() not in response.stdout + response.stderr and fixture.operator.encode() not in response.stdout + response.stderr
        return json.loads((a.output / ('failures.json' if expected==0 else 'failure.json')).read_text())
    def refused(a, plan):
        before = fingerprints(a.database)
        try: recovery.apply(pg, a, plan)
        except (Blocked, RuntimeError, OSError): pass
        else: raise AssertionError('Conflicting recovery write was accepted')
        assert fingerprints(a.database) == before and not (a.output / 'failures.json').exists()
    def quoted(mode, key): return literal(bodies[mode]['identity'][key]) + '::uuid'

    code = 1
    try:
        fixture.setUp(); binding = fixture.binding(); origin = 'https://127.0.0.1:' + str(fixture.port)
        pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert((fixture.root / 'cert.pem').read_text())).hexdigest()
        source = 'edgeai_backup_failures_' + uuid.uuid4().hex
        pg.sql('CREATE DATABASE ' + identifier(source), 'postgres'); remember(source)
        with private_file(work / 'runtime-key', 'w') as target: target.write(secrets.token_hex(32))
        api = Api(source, work, extra_env={'EDGEAI_REMOTE_ENABLED': 'true', 'EDGEAI_REMOTE_URL': origin,
            'EDGEAI_REMOTE_TOKEN_FILE': str(fixture.root / 'token'), 'EDGEAI_REMOTE_CA_FILE': str(fixture.root / 'cert.pem'),
            'EDGEAI_RUNTIME_ENABLED': 'true', 'EDGEAI_RUNTIME_WORKER_ENABLED': 'false',
            'EDGEAI_RUNNER_KEY_FILE': str(work / 'runtime-key')}); apis.append(api)
        spec = json.loads((ROOT / 'contracts/profiles/service-execution.example.json').read_text())
        parent = api.request('POST', 'profiles/SERVICE', {'key': 'failure-root', 'version': '1.0.0', 'spec': spec}, 201)
        child = api.request('POST', 'profiles/SERVICE', {'key': 'failure-child', 'version': '1.0.0',
                'spec': {**spec, 'inputs': {'input': {'mediaType': 'application/json', 'maxBytes': 1048576, 'required': True}}}}, 201)
        bodies = {}
        for mode in ('retry', 'final', 'lost', 'user-cancel', 'expired', 'limit', 'cached', 'pending', 'task-cancel'):
            failed = mode in ('retry', 'final', 'expired', 'limit')
            tasks = [{'key': 'root', 'serviceProfileVersionId': parent['id'],
                      'parameters': {'features': [] if failed else [2, 3], 'weights': [4, 5]}}]
            dependencies = []
            if mode in ('final', 'task-cancel'):
                for name, previous in (('child', 'root'), ('grandchild', 'child')):
                    tasks.append({'key': name, 'serviceProfileVersionId': child['id'], 'parameters': {}})
                    dependencies.append({'fromTask': previous, 'toTask': name, 'fromPort': 'output', 'toPort': 'input', 'mode': 'BATCH'})
            workflow = api.request('POST', 'workflows', {'key': 'failure-' + mode, 'displayName': 'Failure recovery'}, 201)
            version = api.request('POST', 'workflows/' + workflow['id'] + '/versions',
                                 {'version': '1.0.0', 'tasks': tasks, 'dependencies': dependencies}, 201)
            request = {'workflowVersionId': version['id'], 'execution': {'mode': 'REMOTE', 'providerKey': 'reference'}, 'parameters': {}}
            if mode == 'final': request['taskExecutions'] = {'child': {'mode': 'AUTO'}}
            if mode in ('retry', 'lost', 'expired'):
                request['retry'] = {'maxAttempts': 3, 'backoffSeconds': 1, 'maxElapsedSeconds': 1 if mode=='expired' else 3600,
                                    'retryOn': ['RUNTIME_LOST'] if mode=='lost' else ['WORKLOAD_FAILED']}
            run = api.request('POST', 'workflow-runs', request, 201, str(uuid.uuid4()))
            body = json.loads(pg.sql('SELECT a.work FROM edgeai.remote_allocation a JOIN edgeai.runtime_instance r ON r.id=a.runtime_id '
                              'WHERE r.run_id=' + literal(run['id']) + '::uuid', source)); bodies[mode] = body
            identity = body['identity']; raw = canonical(body)
            headers = {'X-EdgeAI-Run-Id': identity['runId'], 'X-EdgeAI-Task-Id': identity['taskId'],
                 'X-EdgeAI-Attempt-Id': identity['attemptId'], 'X-EdgeAI-Epoch': str(identity['epoch']),
                 'X-EdgeAI-Request-Digest': 'sha256:' + hashlib.sha256(b'edgeai-reference-allocation-v1\n' + raw).hexdigest()}
            path = '/reference/v1/allocations/' + identity['allocationId']
            assert fixture.rpc(path, 'PUT', raw, headers, fixture.token)[0] == 201
            if mode not in ('lost', 'task-cancel'):
                assert fixture.rpc(path + '/start', 'POST', headers=headers, credential=fixture.token)[0] == 200
                deadline = time.monotonic() + 5
                while True:
                    status = fixture.rpc(path, headers=headers, credential=fixture.token)[1]
                    if status['state'] in ('SUCCEEDED', 'FAILED'): break
                    assert time.monotonic() < deadline; time.sleep(.02)
                assert status['state'] == ('FAILED' if failed else 'SUCCEEDED')
                if failed: assert status['failureReason'] == 'WORKLOAD_FAILED'
            if mode == 'user-cancel': api.request('POST', 'workflow-runs/' + run['id'] + '/cancel', {})
            if mode == 'task-cancel': api.request('POST', 'tasks/' + identity['taskId'] + '/cancel', {})
            if mode == 'cached':
                # Explicit immutable Result fixture; file recovery is covered by the separate real S3 gate.
                result_id = literal(str(uuid.uuid4())) + '::uuid'
                pg.sql('UPDATE edgeai.remote_allocation SET provider_revision=' + str(status['revision']) +
                       ",provider_state='SUCCEEDED',observed_at=now(),observation=" + literal(json.dumps(status)) +
                       '::jsonb WHERE id=' + quoted(mode, 'allocationId'), source)
                pg.sql('INSERT INTO edgeai.task_result(id,task_id,attempt_id,runtime_id,epoch,remote_allocation_id,manifest_digest,created_at) '
                       'SELECT ' + result_id + ',task_id,attempt_id,id,epoch,remote_allocation_id,' + literal('sha256:' + 'a'*64) +
                       ',now() FROM edgeai.runtime_instance WHERE remote_allocation_id=' + quoted(mode, 'allocationId'), source)
                pg.sql('INSERT INTO edgeai.result_artifact(id,result_id,port,bucket,object_key,object_version,sha256,bytes,media_type) VALUES (' +
                       literal(str(uuid.uuid4())) + '::uuid,' + result_id + ",'output','failure-fixture','retained.json','fixed-version'," +
                       literal(status['outputs'][0]['sha256']) + ',' + str(status['outputs'][0]['bytes']) + ",'application/json')", source)
                pg.sql('UPDATE edgeai.task_result SET committed=true WHERE id=' + result_id +
                       "; UPDATE edgeai.task_attempt SET state='SUCCEEDED' WHERE id=" + quoted(mode, 'attemptId') +
                       "; UPDATE edgeai.task SET state='SUCCEEDED' WHERE id=" + quoted(mode, 'taskId') +
                       "; UPDATE edgeai.workflow_run SET state='SUCCEEDED' WHERE id=" + quoted(mode, 'runId'), source)
        api.close(); backup(pg, source, work / 'backup'); drop(source); fixture.cli(binding)
        targets = []
        for _ in range(2):
            db = 'edgeai_restore_failures_' + uuid.uuid4().hex
            restoring = Postgres(args.transport, diagnostics=work / ('restore-' + uuid.uuid4().hex))
            restore(restoring, work / 'backup', db); remember(db); receipt = restoring.directory / 'restore-report.json'
            a = remote_options(db, receipt); retirement.apply(pg, a, retirement.prepare(pg, a))
            a = remote_options(db, receipt); outputs.recover(pg, a); targets.append((db, receipt, a.output))
        fixture.stop(); db = targets[0][0]
        pristine, other = fingerprints(db), fingerprints(targets[1][0])
        passed('actual API creates nine Remote allocations with workload failures, provider stop, user cancellation and successful history; two DBs restore and source DB/provider stop')

        wrong = options(); wrong.bundle = targets[1][2]
        assert cli(wrong, 2)['databaseModified'] is False and fingerprints(db) == pristine
        passed('a private bundle from another restored database cannot authorize failure or retry writes')

        a = options(); a.output.mkdir(mode=0o700); plan = recovery.prepare(pg, a)
        pg.sql('UPDATE edgeai.runtime_command SET attempts=attempts+1', db); refused(a, plan)
        pg.sql('UPDATE edgeai.runtime_command SET attempts=attempts-1', db)
        passed('a real outbox change after the snapshot invalidates the whole recovery transaction')

        a = options(); a.output.mkdir(mode=0o700); plan = recovery.prepare(pg, a)
        pg.sql('COMMENT ON DATABASE ' + identifier(db) + " IS 'owned-wrong-marker'", db); refused(a, plan)
        pg.sql('COMMENT ON DATABASE ' + identifier(db) + ' IS ' + literal(plan['marker']), db)
        passed('the restored OID/marker is rechecked under the transaction before any state change')

        lock_name='failure-recovery-lock-'+uuid.uuid4().hex;locker=None
        try:
            with private_file(work/'offload-lock.log') as log:
                locker=subprocess.Popen(pg.prefix+[pg.binaries['psql']]+pg.connection+['--dbname',db,
                    '-X','-q','-v','ON_ERROR_STOP=1','-c',
                    'BEGIN; LOCK TABLE edgeai.task_offload IN ROW EXCLUSIVE MODE; SELECT pg_sleep(60); COMMIT;'],
                    env={**pg.env,'PGAPPNAME':lock_name},stdout=log,stderr=log)
            deadline=time.monotonic()+5
            while pg.sql("SELECT count(*) FROM pg_stat_activity a JOIN pg_locks l ON l.pid=a.pid WHERE a.application_name="+
                    literal(lock_name)+" AND l.relation='edgeai.task_offload'::regclass AND l.granted",db)!='1':
                assert time.monotonic()<deadline;time.sleep(.05)
            a=options();a.output.mkdir(mode=0o700);plan=recovery.prepare(pg,a)
            started=time.monotonic();refused(a,plan);assert 4<=time.monotonic()-started<30
        finally:
            pg.sql('SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='+literal(lock_name),db)
            if locker is not None:locker.wait(timeout=10)
        passed('an actual competing offload-table writer hits the five-second lock bound without partial changes')

        # Explicit DB fixture for an unreaped Kubernetes descendant; no cluster object is created or claimed stopped.
        child_id=pg.sql("SELECT t.id FROM edgeai.task t JOIN edgeai.task_definition d ON d.id=t.definition_id WHERE t.run_id="+
                        quoted('final','runId')+" AND d.task_key='child'",db)
        child_attempt,child_runtime=str(uuid.uuid4()),str(uuid.uuid4())
        pg.sql('INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,cause,created_at,updated_at) VALUES ('+
               literal(child_attempt)+'::uuid,'+literal(child_id)+"::uuid,1,1,'DISPATCHING','AUTO','INITIAL',now(),now()); "+
               "UPDATE edgeai.task SET state='RUNNING' WHERE id="+literal(child_id)+'::uuid; '+
               'INSERT INTO edgeai.runtime_instance(id,attempt_id,task_id,run_id,epoch,namespace,job_name,claim_nonce,desired_state,observed_state,created_at,updated_at) VALUES ('+
               literal(child_runtime)+'::uuid,'+literal(child_attempt)+'::uuid,'+literal(child_id)+'::uuid,'+quoted('final','runId')+
               ",1,'owned-recovery-fixture',"+literal('edgeai-'+child_runtime)+','+literal(str(uuid.uuid4()))+"::uuid,'RUNNING','PENDING',now(),now())",db)
        before=fingerprints(db);assert cli(options(),1)['databaseModified'] is None and fingerprints(db)==before
        pg.sql('DELETE FROM edgeai.runtime_instance WHERE id='+literal(child_runtime)+'::uuid; DELETE FROM edgeai.task_attempt WHERE id='+
               literal(child_attempt)+"::uuid; UPDATE edgeai.task SET state='WAITING' WHERE id="+literal(child_id)+'::uuid',db)
        assert fingerprints(db)==pristine
        passed('an unfinished descendant producer DB fixture prevents the entire failure transaction, including earlier failure/retry writes')

        pg.sql("CREATE FUNCTION edgeai.owned_retry_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned queue fault'; END $$; CREATE TRIGGER owned_retry_fault BEFORE INSERT ON edgeai.task_retry FOR EACH ROW EXECUTE FUNCTION edgeai.owned_retry_fault()", db)
        a = options(); a.output.mkdir(mode=0o700); plan = recovery.prepare(pg, a); refused(a, plan)
        pg.sql('DROP TRIGGER owned_retry_fault ON edgeai.task_retry; DROP FUNCTION edgeai.owned_retry_fault()', db)
        assert fingerprints(db) == pristine
        passed('actual retry INSERT failure rolls back preceding Attempt/runtime/Task changes and all dependent outcomes')

        initial = cli(options())
        assert [initial[k] for k in ('attemptsFailed','retriesScheduled','retriesExpired','tasksCancelled','tasksSkipped','runsReconciled')]==[5,2,0,2,2,5]
        assert initial['pendingRetries']==2 and initial['successesPending']==1
        after = fingerprints(db)
        changed = {'runtime_instance','task_attempt','task','task_retry','workflow_run'}
        assert {name for name in pristine if pristine[name]!=after[name]}==changed
        assert fingerprints(targets[1][0])==other
        assert pg.sql("SELECT count(*) FROM edgeai.task_retry q JOIN edgeai.task_attempt p ON p.id=q.failed_attempt_id JOIN edgeai.task t ON t.id=q.task_id JOIN edgeai.workflow_run w ON w.id=t.run_id WHERE q.deadline=p.created_at+make_interval(secs=>w.retry_max_elapsed_seconds) AND q.available_at=p.updated_at+make_interval(secs=>w.retry_backoff_seconds)", db)=='2'
        assert pg.sql("SELECT count(*) FROM edgeai.runtime_instance WHERE desired_state<>'STOPPED' OR observed_state<>'TERMINATED'", db)=='0'
        assert pg.sql("SELECT count(*) FROM edgeai.runtime_command WHERE NOT completed", db)=='0'
        passed('five failures yield two original-budget retry queues, two requested cancellations, two skipped descendants and five final Runs without dispatch or changing 38 other tables')

        assert pg.sql('SELECT failure_reason FROM edgeai.runtime_instance WHERE remote_allocation_id=' + quoted('lost','allocationId'), db)=='RUNTIME_LOST'
        assert pg.sql('SELECT state FROM edgeai.task WHERE id=' + quoted('user-cancel','taskId'), db)=='CANCELLED'
        assert pg.sql('SELECT state FROM edgeai.task WHERE id=' + quoted('pending','taskId'), db)=='RUNNING'
        assert pg.sql('SELECT count(*) FROM edgeai.task_result', db)=='1'
        replay=cli(options());assert not replay['databaseModified'] and fingerprints(db)==after
        passed('user cancellation wins over provider success, lost work maps to RUNTIME_LOST, existing/pending successes survive and replay preserves timestamps/deadlines')

        pg.sql("UPDATE edgeai.task_retry SET deadline=deadline+interval '1 hour' WHERE task_id=" + quoted('retry','taskId'), db)
        before=fingerprints(db);assert cli(options(),1)['databaseModified'] is None and fingerprints(db)==before
        pg.sql("UPDATE edgeai.task_retry SET deadline=deadline-interval '1 hour' WHERE task_id=" + quoted('retry','taskId'), db)
        assert fingerprints(db)==after
        passed('a modified retry deadline cannot extend the frozen Task retry window')

        second=targets[1][0]
        pg.sql("UPDATE edgeai.task_attempt SET state='FAILED' WHERE id=" + quoted('limit','attemptId') +
               '; INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,cause,created_at,updated_at,remote_provider_key,remote_configuration_digest,remote_source_mode) SELECT gen_random_uuid(),task_id,2,2,\'QUEUED\',mode,\'RETRY\',now(),now(),remote_provider_key,remote_configuration_digest,remote_source_mode FROM edgeai.task_attempt WHERE id=' + quoted('limit','attemptId') +
               "; UPDATE edgeai.task SET state='READY' WHERE id=" + quoted('limit','taskId'), second)
        a=options(1);a.output.mkdir(mode=0o700);plan=recovery.prepare(pg,a)
        original_call=pg.call
        def lost_reply(tool, arguments, *positional, **keywords):
            response=original_call(tool,arguments,*positional,**keywords)
            if arguments[-2:]==['-f','-']:raise OSError('Injected lost actual COMMIT reply')
            return response
        pg.call=lost_reply
        try:
            try:recovery.apply(pg,a,plan)
            except OSError:pass
            else:raise AssertionError('Lost reply was not injected')
        finally:pg.call=original_call
        assert (a.output/'intent.json').exists() and not (a.output/'failures.json').exists()
        assert not cli(options(1))['databaseModified']
        assert pg.sql('SELECT state FROM edgeai.task WHERE id='+quoted('limit','taskId'),second)=='READY'
        assert pg.sql('SELECT state FROM edgeai.task_attempt WHERE task_id='+quoted('limit','taskId')+' AND epoch=2',second)=='QUEUED'
        passed('lost reply after actual COMMIT resumes without duplicates and an explicitly newer attempt is never overwritten by old Remote failure')

        # A pre-restoration cancellation can leave a FAILED attempt with a CANCELLING Task until retirement.
        pg.sql('DELETE FROM edgeai.task_retry WHERE task_id='+quoted('retry','taskId')+
               "; UPDATE edgeai.task SET state='CANCELLING',cancellation_reason='TASK_CANCELLED' WHERE id="+quoted('retry','taskId'),second)
        cancelled=cli(options(1));assert cancelled['tasksCancelled']==1 and cancelled['pendingRetries']==1
        assert pg.sql('SELECT state FROM edgeai.task_attempt WHERE id='+quoted('retry','attemptId'),second)=='FAILED'
        assert not cli(options(1))['databaseModified']
        passed('a recorded cancellation after failure closes the Task without rewriting the old FAILED attempt or scheduling another retry')

        # An already recorded cancellation of a non-Remote child must keep its reason when a parent fails.
        # This is a restored-state DB fixture, not evidence that a real Kubernetes producer was stopped.
        pg.sql('INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,cause,created_at,updated_at) VALUES ('+
               literal(child_attempt)+'::uuid,'+literal(child_id)+"::uuid,1,1,'CANCELLING','AUTO','INITIAL',now(),now()); "+
               "UPDATE edgeai.task SET state='CANCELLING',cancellation_reason='RUN_CANCELLED' WHERE id="+literal(child_id)+'::uuid; '+
               "UPDATE edgeai.workflow_run SET state='CANCELLING' WHERE id="+quoted('final','runId')+'; '+
               'INSERT INTO edgeai.runtime_instance(id,attempt_id,task_id,run_id,epoch,namespace,job_name,claim_nonce,desired_state,observed_state,created_at,updated_at) VALUES ('+
               literal(child_runtime)+'::uuid,'+literal(child_attempt)+'::uuid,'+literal(child_id)+'::uuid,'+quoted('final','runId')+
               ",1,'owned-recovery-fixture',"+literal('edgeai-'+child_runtime)+','+literal(str(uuid.uuid4()))+"::uuid,'STOPPED','TERMINATED',now(),now())",second)
        inherited=cli(options(1));assert inherited['tasksCancelled']==1 and inherited['tasksSkipped']==0
        assert pg.sql('SELECT state||\':\'||cancellation_reason FROM edgeai.task WHERE id='+literal(child_id)+'::uuid',second)=='CANCELLED:RUN_CANCELLED'
        assert not cli(options(1))['databaseModified']
        passed('a retired non-Remote descendant DB fixture retains its pre-existing user cancellation instead of being rewritten as upstream failure')

        # Explicit clock-aging fixture, not a wall-clock performance claim; shift all related timestamps together.
        pg.sql("UPDATE edgeai.task_attempt SET created_at=created_at-interval '2 hours',updated_at=updated_at-interval '2 hours' WHERE task_id IN (SELECT task_id FROM edgeai.task_retry); UPDATE edgeai.task_retry SET available_at=available_at-interval '2 hours',deadline=deadline-interval '2 hours'",db)
        expired=cli(options());assert expired['retriesExpired']==2 and expired['pendingRetries']==0 and expired['runsReconciled']==2
        after_expiry=fingerprints(db);assert not cli(options())['databaseModified'] and fingerprints(db)==after_expiry
        passed('existing retry deadlines expire into failure without a fresh window or Attempt; repeated expiry remains zero-change')

        inspector=Api(db,work,inspection=True);apis.append(inspector)
        for mode,state in (('retry','FAILED'),('lost','FAILED'),('user-cancel','CANCELLED'),('cached','SUCCEEDED'),('pending','RUNNING')):
            view=inspector.request('GET','workflow-runs/'+bodies[mode]['identity']['runId']);assert view['run']['state']==state
        try:inspector.request('POST','workflow-runs/'+bodies['pending']['identity']['runId']+'/cancel',{})
        except urllib.error.HTTPError as error:assert error.code==403
        else:raise AssertionError('Inspection permitted a write')
        inspector.close()
        passed('packaged inspection API exposes final and pending Run states while management writes remain forbidden')
        report.update(status='PASS',allocations=9,restoredDatabases=2,attemptsFailed=5,retriesScheduled=2,retriesExpired=2,
                      tasksCancelled=2,tasksSkipped=2,preservedTables=39,retainedResultFixtures=1,successesPending=1,
                      sourceProviderOffline=True,jarSha256=hashlib.sha256((ROOT/'backend/app/build/libs/edgeai-control-plane.jar').read_bytes()).hexdigest())
        code=0
    except Exception as error:
        report.update(status='FAIL',failureType=type(error).__name__)
        with private_file(work/'failure.log','w') as log:traceback.print_exc(file=log)
        print('FAIL: actual Remote failure recovery; private diagnostics: '+str(work),flush=True)
    finally:
        for api in apis:api.close()
        fixture.doCleanups()
        for db in list(owned):
            try:drop(db)
            except Exception:report['status']='FAIL';code=1
        report.update(ownedDatabasesRemoved=not owned,ownedApisStopped=all(api.process is None or api.process.poll() is not None for api in apis))
        args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,indent=2)+'\n')
    print(report['status']+': '+str(len(report['cases']))+' actual Remote failure recovery cases',flush=True)
    return code


if __name__=='__main__':raise SystemExit(main())
