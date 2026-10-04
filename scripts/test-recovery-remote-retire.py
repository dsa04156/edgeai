"""Real restored PostgreSQL transactions and fenced TLS Remote history, including rollback and lost reply."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy
import shutil
import ssl
import subprocess
import sys
import time
import traceback
from types import SimpleNamespace
import urllib.error
import uuid

from postgres_backup import Postgres, ROOT, backup, restore, identifier, literal, private_file
from recovery_remote_inventory import canonical
from recovery_remote_retire import prepare, apply, durable_json
import recovery_remote_outputs as output_recovery

sys.path.insert(0, str(ROOT / 'simulator/tests'))
from test_remote_recovery import RemoteRecoveryTest
Api = runpy.run_path(str(ROOT / 'scripts/test-postgres-backup.py'))['Api']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transport', choices=['native', 'compose'], default='native')
    parser.add_argument('--report', type=Path, default=ROOT / '.tools/recovery-remote-retire-test.json')
    parser.add_argument('--bundle-output', type=Path, help='Optional new private .tools path for downstream storage verification')
    args = parser.parse_args()
    work = ROOT / '.tools' / ('recovery-remote-retire-test-' + uuid.uuid4().hex)
    work.mkdir(mode=0o700)
    pg = Postgres(args.transport, diagnostics=work / 'postgres')
    owned, apis = {}, []
    fixture = RemoteRecoveryTest('test_separate_credentials_identity_tls_pin_and_inspection')
    report = {'status': 'RUNNING', 'scope': 'restored-database-reference-remote-retirement',
              'cases': [], 'sourceMode': 'SYNTHETIC'}

    def passed(name):
        report['cases'].append(name); print('PASS: ' + name, flush=True)

    def remember(database):
        owned[database] = pg.sql('SELECT oid FROM pg_database WHERE datname=' + literal(database), 'postgres')

    def drop(database):
        assert pg.sql('SELECT oid FROM pg_database WHERE datname=' + literal(database), 'postgres') == owned[database]
        pg.sql('DROP DATABASE ' + identifier(database), 'postgres'); del owned[database]

    def fingerprints(database):
        tables = json.loads(pg.sql("SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='edgeai'", database))
        return {name: hashlib.sha256(pg.sql('SELECT to_jsonb(t)::text FROM edgeai.' + identifier(name) +
                                          ' t ORDER BY to_jsonb(t)::text', database).encode()).hexdigest() for name in tables}

    def create_work(api, source):
        service = api.request('POST', 'profiles/SERVICE', {'key': 'retire-' + uuid.uuid4().hex, 'version': '1.0.0',
                              'spec': json.loads((ROOT / 'contracts/profiles/service-execution.example.json').read_text())}, 201)
        workflow = api.request('POST', 'workflows', {'key': 'retire-' + uuid.uuid4().hex, 'displayName': 'Remote recovery'}, 201)
        version = api.request('POST', 'workflows/' + workflow['id'] + '/versions', {'version': '1.0.0',
                              'tasks': [{'key': 'root', 'serviceProfileVersionId': service['id'],
                                         'parameters': {'features': [2, 3], 'weights': [4, 5]}}], 'dependencies': []}, 201)
        run = api.request('POST', 'workflow-runs', {'workflowVersionId': version['id'],
                          'execution': {'mode': 'REMOTE', 'providerKey': 'reference'}, 'parameters': {}}, 201, str(uuid.uuid4()))
        return json.loads(pg.sql('SELECT a.work FROM edgeai.remote_allocation a JOIN edgeai.runtime_instance r ON r.id=a.runtime_id '
                                 'WHERE r.run_id=' + literal(run['id']) + '::uuid', source))

    def submit(body, mode):
        identity = body['identity']; path = '/reference/v1/allocations/' + identity['allocationId']
        raw = canonical(body)
        headers = {'X-EdgeAI-Run-Id': identity['runId'], 'X-EdgeAI-Task-Id': identity['taskId'],
                   'X-EdgeAI-Attempt-Id': identity['attemptId'], 'X-EdgeAI-Epoch': str(identity['epoch']),
                   'X-EdgeAI-Request-Digest': 'sha256:' + hashlib.sha256(b'edgeai-reference-allocation-v1\n' + raw).hexdigest()}
        if mode == 'tombstone':
            assert fixture.rpc(path + '/cancel', 'POST', headers=headers, credential=fixture.token)[0] == 200
            return None
        assert fixture.rpc(path, 'PUT', raw, headers, fixture.token)[0] == 201
        if mode == 'cancelled': return None
        assert fixture.rpc(path + '/start', 'POST', headers=headers, credential=fixture.token)[0] == 200
        deadline = time.monotonic() + 5
        while True:
            code, status = fixture.rpc(path, headers=headers, credential=fixture.token)
            assert code == 200
            if status['state'] == 'SUCCEEDED': return status
            assert time.monotonic() < deadline
            time.sleep(.01)

    def options(database, receipt, output=None):
        output = output or work / ('retirement-' + uuid.uuid4().hex)
        return SimpleNamespace(transport=args.transport, pg_bin=None, database=database, restore_report=receipt,
                               endpoint=origin, provider_key='reference', provider_id=binding['providerId'],
                               recovery_id=binding['recoveryId'], ca_file=fixture.root / 'cert.pem',
                               certificate_sha256=pin, recovery_token_file=fixture.root / 'operator',
                               timeout=10, page_size=2, output=output)

    def cli(a, expected=0, extra=()):
        command = [sys.executable, 'scripts/recovery_remote_retire.py']
        for key, value in vars(a).items():
            if value is not None: command += ['--' + key.replace('_', '-'), str(value)]
        result = subprocess.run(command + list(extra), capture_output=True, timeout=90)
        with private_file(work / ('command-' + uuid.uuid4().hex + '.log')) as log: log.write(result.stdout + result.stderr)
        assert result.returncode == expected, 'Remote retirement CLI outcome differs; private diagnostics retained'
        assert fixture.token.encode() not in result.stdout + result.stderr and fixture.operator.encode() not in result.stdout + result.stderr
        report_file = a.output / 'retirement.json'
        if not report_file.exists(): report_file = a.output / 'failure.json'
        return json.loads(report_file.read_text()) if report_file.exists() else None

    def refused_apply(a, plan, database):
        before = fingerprints(database)
        try: apply(pg, a, plan)
        except RuntimeError: pass
        else: raise AssertionError('Stale or faulted transaction was accepted')
        assert fingerprints(database) == before and not (a.output / 'retirement.json').exists()

    def output_cli(a=None, bundle=None, expected=0, extra=()):
        command = [sys.executable, 'scripts/recovery_remote_outputs.py']
        if bundle is not None: command += ['verify', '--input', str(bundle)]
        else:
            command += ['recover']
            for key, value in vars(a).items():
                if value is not None: command += ['--' + key.replace('_', '-'), str(value)]
        result = subprocess.run(command + list(extra), capture_output=True, timeout=90,
            env={k:v for k,v in os.environ.items() if bundle is None or not (k.startswith('EDGEAI_DB_') or k.startswith('PG'))})
        with private_file(work / ('output-command-' + uuid.uuid4().hex + '.log')) as log: log.write(result.stdout + result.stderr)
        assert result.returncode == expected, 'Remote output CLI outcome differs; private diagnostics retained'
        assert fixture.token.encode() not in result.stdout + result.stderr and fixture.operator.encode() not in result.stdout + result.stderr

    code = 1
    try:
        fixture.setUp()
        origin = 'https://127.0.0.1:' + str(fixture.port)
        pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert((fixture.root / 'cert.pem').read_text())).hexdigest()
        binding = fixture.binding()
        source = 'edgeai_backup_retire_' + uuid.uuid4().hex
        pg.sql('CREATE DATABASE ' + identifier(source), 'postgres'); remember(source)
        with private_file(work / 'runtime-key', 'w') as key: key.write(os.urandom(32).hex())
        api = Api(source, work, extra_env={
            'EDGEAI_REMOTE_ENABLED': 'true', 'EDGEAI_REMOTE_URL': origin,
            'EDGEAI_REMOTE_TOKEN_FILE': str(fixture.root / 'token'), 'EDGEAI_REMOTE_CA_FILE': str(fixture.root / 'cert.pem'),
            'EDGEAI_RUNTIME_ENABLED': 'true', 'EDGEAI_RUNTIME_WORKER_ENABLED': 'false',
            'EDGEAI_RUNNER_KEY_FILE': str(work / 'runtime-key')})
        apis.append(api)
        bodies = {}
        for mode in ('cached', 'success', 'tombstone', 'cancelled'):
            body = create_work(api, source); bodies[mode] = body
            status = submit(body, mode)
            if mode == 'cached':
                # Explicit committed-result/observation fixture: keep all immutable triggers enabled.
                allocation = literal(body['identity']['allocationId']) + '::uuid'
                pg.sql('UPDATE edgeai.remote_allocation SET provider_revision=' + str(status['revision']) +
                       ",provider_state='SUCCEEDED',observed_at=now(),observation=" + literal(json.dumps(status)) +
                       '::jsonb WHERE id=' + allocation, source)
                result_id = literal(str(uuid.uuid4())) + '::uuid'
                pg.sql('INSERT INTO edgeai.task_result(id,task_id,attempt_id,runtime_id,epoch,remote_allocation_id,manifest_digest,created_at) '
                       'SELECT ' + result_id + ',task_id,attempt_id,id,epoch,remote_allocation_id,' + literal('sha256:' + 'a' * 64) +
                       ',now() FROM edgeai.runtime_instance WHERE remote_allocation_id=' + allocation, source)
                pg.sql('INSERT INTO edgeai.result_artifact(id,result_id,port,bucket,object_key,object_version,sha256,bytes,media_type) VALUES (' +
                       literal(str(uuid.uuid4())) + '::uuid,' + result_id + ",'output','retirement-fixture','retained.json','fixed-version'," +
                       literal(status['outputs'][0]['sha256']) + ',' + str(status['outputs'][0]['bytes']) + ",'application/json')", source)
                pg.sql('UPDATE edgeai.task_result SET committed=true WHERE id=' + result_id, source)
                pg.sql("UPDATE edgeai.runtime_instance SET desired_state='STOPPED',observed_state='TERMINATED' WHERE remote_allocation_id=" + allocation +
                       '; UPDATE edgeai.runtime_command SET completed=true WHERE runtime_id=(SELECT runtime_id FROM edgeai.remote_allocation WHERE id=' + allocation + ')' +
                       "; UPDATE edgeai.task_attempt SET state='SUCCEEDED' WHERE id=" + literal(body['identity']['attemptId']) + '::uuid' +
                       "; UPDATE edgeai.task SET state='SUCCEEDED' WHERE id=" + literal(body['identity']['taskId']) + '::uuid' +
                       "; UPDATE edgeai.workflow_run SET state='SUCCEEDED' WHERE id=" + literal(body['identity']['runId']) + '::uuid', source)
        pg.sql("INSERT INTO edgeai.runtime_command(id,runtime_id,kind,completed,attempts,available_at,lease_owner,lease_until,created_at,updated_at) "
               'SELECT ' + literal(str(uuid.uuid4())) + "::uuid,runtime_id,'DELETE',false,3,now()," + literal(str(uuid.uuid4())) +
               '::uuid,now()+interval \'1 hour\',now(),now() FROM edgeai.remote_allocation WHERE id=' +
               literal(bodies['success']['identity']['allocationId']) + '::uuid', source)
        api.close()
        backup(pg, source, work / 'backup')
        targets = []
        for _ in range(2):
            target = 'edgeai_restore_retire_' + uuid.uuid4().hex
            restoring = Postgres(args.transport, diagnostics=work / ('restore-' + uuid.uuid4().hex))
            restore(restoring, work / 'backup', target); remember(target)
            targets.append((target, restoring.directory / 'restore-report.json'))
        drop(source)
        target, receipt = targets[0]; before = fingerprints(target)
        assert len(before) == 43
        assert cli(options(target, receipt), expected=2)['databaseModified'] is False
        assert fingerprints(target) == before
        passed('live unfenced provider blocks all restored database writes')
        fixture.cli(binding); provider_before = fixture.rows()
        for extra in (['--provider-id', str(uuid.uuid4())], ['--recovery-id', str(uuid.uuid4())],
                      ['--certificate-sha256', '0' * 64], ['--provider-key', 'other-provider']):
            assert cli(options(target, receipt), expected=2, extra=extra)['databaseModified'] is False
        assert fingerprints(target) == before
        passed('wrong provider, recovery, TLS pin or frozen binding refuses retirement')
        wrong = json.loads(receipt.read_text()); wrong['databaseOid'] = '0'
        durable_json(work / 'wrong-restore.json', wrong)
        assert cli(options(target, work / 'wrong-restore.json'), expected=1)['databaseModified'] is False
        pg.sql("INSERT INTO edgeai.flyway_schema_history(installed_rank,version,description,type,script,installed_by,execution_time,success) "
               "VALUES (999,'999','future fixture','SQL','V999__fixture.sql',current_user,0,true)", target)
        assert cli(options(target, receipt), expected=2)['databaseModified'] is False
        pg.sql('DELETE FROM edgeai.flyway_schema_history WHERE installed_rank=999', target)
        assert fingerprints(target) == before
        passed('wrong restore identity and unsupported schema cannot mutate the restored database')

        a = options(target, receipt); a.output.mkdir(mode=0o700); plan = prepare(pg, a)
        pg.sql("UPDATE edgeai.runtime_command SET attempts=attempts+1 WHERE kind='CREATE' AND NOT completed", target)
        refused_apply(a, plan, target)
        passed('actual concurrent outbox change after inspection rejects the entire transaction')

        a = options(target, receipt); a.output.mkdir(mode=0o700); plan = prepare(pg, a)
        pg.sql('COMMENT ON DATABASE ' + identifier(target) + " IS 'owned-wrong-restore-marker'", target)
        refused_apply(a, plan, target)
        pg.sql('COMMENT ON DATABASE ' + identifier(target) + ' IS ' + literal(plan['marker']), target)
        passed('restore marker changed after inspection is rechecked inside the write transaction')

        locker_name = 'retirement-lock-' + uuid.uuid4().hex
        locker = None
        try:
            with private_file(work / 'lock-holder.log') as output:
                locker = subprocess.Popen(pg.prefix + [pg.binaries['psql']] + pg.connection + ['--dbname', target,
                    '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-c',
                    'BEGIN; LOCK TABLE edgeai.runtime_command IN ROW EXCLUSIVE MODE; SELECT pg_sleep(60); COMMIT;'],
                    env={**pg.env, 'PGAPPNAME': locker_name}, stdout=output, stderr=output)
            deadline = time.monotonic() + 5
            while pg.sql("SELECT EXISTS(SELECT FROM pg_locks l JOIN pg_stat_activity a USING(pid) "
                         "WHERE a.application_name=" + literal(locker_name) + " AND l.relation='edgeai.runtime_command'::regclass "
                         "AND l.mode='RowExclusiveLock' AND l.granted)", target) != 't':
                assert time.monotonic() < deadline and locker.poll() is None
                time.sleep(.05)
            locked_before = fingerprints(target)
            assert cli(options(target, receipt), expected=1)['databaseModified'] is None
            assert fingerprints(target) == locked_before
        finally:
            if locker is not None:
                pg.sql('SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE application_name=' + literal(locker_name), target)
                locker.wait(timeout=10)
        passed('a real competing table lock times out without partial changes or unbounded waiting')

        pg.sql("CREATE FUNCTION edgeai.retirement_test_fault() RETURNS trigger LANGUAGE plpgsql AS " +
               literal("BEGIN RAISE EXCEPTION 'owned transaction rollback fixture'; END") + "; "
               "CREATE TRIGGER retirement_test_fault BEFORE UPDATE ON edgeai.runtime_command FOR EACH ROW EXECUTE FUNCTION edgeai.retirement_test_fault()", target)
        a = options(target, receipt); a.output.mkdir(mode=0o700)
        refused_apply(a, prepare(pg, a), target)
        pg.sql('DROP TRIGGER retirement_test_fault ON edgeai.runtime_command; DROP FUNCTION edgeai.retirement_test_fault()', target)
        passed('a real PostgreSQL failure after observation and runtime writes rolls back all three tables')

        preserved_before = fingerprints(target)
        other_before = fingerprints(targets[1][0])
        identities_query = "SELECT json_agg(to_jsonb(r)-ARRAY['desired_state','observed_state','updated_at'] ORDER BY id) FROM edgeai.runtime_instance r"
        commands_query = "SELECT json_agg(to_jsonb(c)-ARRAY['completed','lease_owner','lease_until','updated_at'] ORDER BY id) FROM edgeai.runtime_command c"
        identities, commands = pg.sql(identities_query, target), pg.sql(commands_query, target)
        a = options(target, receipt); result = cli(a)
        assert [result[k] for k in ('observationsUpdated', 'runtimesRetired', 'commandsCompleted', 'allocationsVerified')] == [3, 3, 4, 4]
        assert result['databaseModified'] and not result['activated'] and not result['globalQuiescenceProven']
        assert all((a.output / name).stat().st_mode & 0o777 == 0o600 for name in ('intent.json', 'retirement.json', 'transaction.sql'))
        after = fingerprints(target)
        assert {k for k in after if after[k] != preserved_before[k]} == {'remote_allocation', 'runtime_instance', 'runtime_command'}
        assert fingerprints(targets[1][0]) == other_before and pg.sql(identities_query, target) == identities and pg.sql(commands_query, target) == commands
        assert pg.sql('SELECT count(*) FROM edgeai.task_result WHERE committed', target) == '1'
        assert pg.sql('SELECT count(*) FROM edgeai.result_artifact', target) == '1'
        assert pg.sql("SELECT count(*) FROM edgeai.runtime_instance WHERE desired_state<>'STOPPED' OR observed_state<>'TERMINATED'", target) == '0'
        assert pg.sql('SELECT count(*) FROM edgeai.runtime_command WHERE NOT completed OR lease_owner IS NOT NULL OR lease_until IS NOT NULL', target) == '0'
        passed('actual TLS evidence atomically imports three observations, retires three runtimes and completes four commands')
        passed('committed result and artifact, 40 other tables, runtime identities, command history and another restored DB survive')

        repeated = cli(options(target, receipt))
        assert not repeated['databaseModified'] and fingerprints(target) == after
        assert [repeated[k] for k in ('observationsUpdated', 'runtimesRetired', 'commandsCompleted')] == [0, 0, 0]
        old_receipt = (a.output / 'retirement.json').read_bytes()
        cli(a, expected=1)
        assert (a.output / 'retirement.json').read_bytes() == old_receipt and fingerprints(target) == after
        passed('same recovery is timestamp-stable and existing private receipts cannot be overwritten')

        # Execute the real COMMIT, then inject a lost reply at the process boundary. No SQL rollback is claimed.
        target2, receipt2 = targets[1]
        a2 = options(target2, receipt2); a2.output.mkdir(mode=0o700); plan2 = prepare(pg, a2)
        class LostReply:
            def call(self, *positional, **keywords):
                pg.call(*positional, **keywords)
                raise OSError('Injected reply loss after actual COMMIT')
        try: apply(LostReply(), a2, plan2)
        except OSError: pass
        else: raise AssertionError('Lost reply was not propagated')
        assert (a2.output / 'intent.json').exists() and not (a2.output / 'retirement.json').exists()
        lost_after = fingerprints(target2)
        assert lost_after != other_before
        resumed = cli(options(target2, receipt2))
        assert not resumed['databaseModified'] and fingerprints(target2) == lost_after
        passed('lost COMMIT reply leaves private intent and a fresh live rerun proves completion without duplicate changes')

        recovered = options(target, receipt); output_cli(a=recovered)
        manifest = json.loads((recovered.output / 'manifest.json').read_text())
        assert len(manifest['allocations']) == 1 and manifest['committedResultsRetained'] == 1
        recovered_status = manifest['allocations'][0]
        assert recovered_status['identity'] == bodies['success']['identity']
        output_meta = recovered_status['outputs'][0]
        source_file = fixture.root / 'state' / bodies['success']['identity']['allocationId'] / 'outputs/output'
        original_bytes = source_file.read_bytes()
        object_file = recovered.output / 'objects' / (output_meta['sha256'] + '.bin')
        assert object_file.read_bytes() == original_bytes and object_file.stat().st_mode & 0o777 == 0o600
        assert fingerprints(target) == after and fixture.rows() == provider_before
        passed('actual restored DB and fenced TLS success file form a private verified bundle while committed results remain untouched')

        for extra in (['--provider-id', str(uuid.uuid4())], ['--certificate-sha256', '0' * 64], ['--provider-key', 'other-provider']):
            rejected = options(target, receipt); output_cli(a=rejected, expected=2, extra=extra)
            assert not (rejected.output / 'manifest.json').exists()
        source_file.write_bytes(b'x' * len(original_bytes))
        rejected = options(target, receipt); output_cli(a=rejected, expected=2)
        assert not (rejected.output / 'manifest.json').exists()
        source_file.write_bytes(original_bytes)
        assert fingerprints(target) == after and fixture.rows() == provider_before
        passed('output recovery refuses wrong live identity, binding, TLS and corrupt source content without a completed manifest')

        changed = options(target, receipt); changed.output.mkdir(mode=0o700)
        original_download = output_recovery.download
        download_race_applied = False
        def racing_download(*positional, **keywords):
            nonlocal download_race_applied
            raw = original_download(*positional, **keywords)
            pg.sql('UPDATE edgeai.runtime_command SET attempts=attempts+1', target)
            download_race_applied = True
            return raw
        output_recovery.download = racing_download
        try:
            try: output_recovery.recover(pg, changed)
            except output_recovery.Blocked: pass
            else: raise AssertionError('Database changes during file download were accepted')
        finally:
            output_recovery.download = original_download
            if download_race_applied: pg.sql('UPDATE edgeai.runtime_command SET attempts=attempts-1', target)
        assert not (changed.output / 'manifest.json').exists() and fingerprints(target) == after
        passed('real database changes during a real TLS download prevent publishing a complete recovery bundle')

        fixture.process.kill(); fixture.process.wait(timeout=5)
        output_cli(bundle=recovered.output)
        object_file.write_bytes(b'x' * len(original_bytes)); output_cli(bundle=recovered.output, expected=1)
        object_file.unlink(); object_file.symlink_to(source_file); output_cli(bundle=recovered.output, expected=2); object_file.unlink()
        with private_file(object_file) as output: output.write(original_bytes)
        manifest_path = recovered.output / 'manifest.json'; saved_manifest = manifest_path.read_bytes()
        manifest['allocations'] = []; manifest_path.write_text(json.dumps(manifest))
        output_cli(bundle=recovered.output, expected=1); manifest_path.write_bytes(saved_manifest)
        extra_file = recovered.output / 'objects' / 'unreferenced.bin'
        with private_file(extra_file) as output: output.write(b'not part of the snapshot')
        output_cli(bundle=recovered.output, expected=1); extra_file.unlink()
        output_cli(bundle=recovered.output)
        assert fingerprints(target) == after
        passed('offline bundle verification works without source provider or DB credentials and rejects altered, symlinked, omitted and unreferenced data')
        assert cli(options(target, receipt), expected=2)['databaseModified'] is False
        fixture.start()
        assert not cli(options(target, receipt))['databaseModified'] and fingerprints(target) == after
        assert fixture.rows() == provider_before
        passed('cached intent cannot bypass unavailable TLS provider and SIGKILL restart preserves all provider history')

        try: unexpected = Api(target, work)
        except AssertionError as error: assert str(error) == 'Owned API exited; private log retained'
        else: unexpected.close(); raise AssertionError('Retirement lifted database quarantine')
        inspector = Api(target, work, inspection=True); apis.append(inspector)
        inspector.request('GET', 'workflow-runs/' + bodies['cached']['identity']['runId'])
        try: inspector.request('POST', 'workflow-runs/' + bodies['success']['identity']['runId'] + '/cancel', {})
        except urllib.error.HTTPError as error: assert error.code == 403
        else: raise AssertionError('Inspection API accepted a mutation')
        inspector.close()
        assert fingerprints(target) == after
        passed('ordinary packaged API remains blocked while inspection permits reads and rejects writes')
        if args.bundle_output is not None:
            destination = args.bundle_output.resolve()
            if not destination.is_relative_to(ROOT / '.tools'): raise ValueError('Test bundle must remain under private .tools')
            shutil.copytree(recovered.output, destination)
            assert output_recovery.verify(destination)['uniqueFiles'] == 1
        report.update(status='PASS', restoredTables=43, restoredDatabases=2, providerInstallations=1, allocations=4,
                      observationsUpdated=3, runtimesRetired=3, commandsCompleted=4,
                      retainedResults=1, retainedArtifactReferences=1,
                      recoveredFiles=1, recoveredOutputBytes=len(original_bytes), offlineBundleVerified=True,
                      serverMajor=int(pg.sql('SHOW server_version_num', 'postgres')) // 10000,
                      jarSha256=hashlib.sha256((ROOT / 'backend/app/build/libs/edgeai-control-plane.jar').read_bytes()).hexdigest())
        code = 0
    except Exception as error:
        report.update(status='FAIL', failureType=type(error).__name__)
        with private_file(work / 'failure.log', 'w') as log: traceback.print_exc(file=log)
        print('FAIL: actual Remote retirement; private diagnostics: ' + str(work), flush=True)
    finally:
        for api in apis: api.close()
        if not fixture.doCleanups(): report.update(status='FAIL', cleanupFailed=True); code = 1
        for database in list(owned):
            try: drop(database)
            except Exception: report.update(status='FAIL', cleanupFailed=True); code = 1
        report['ownedDatabasesRemoved'] = not owned
        report['ownedProcessesStopped'] = all(api.process.poll() is not None for api in apis) and (
            getattr(fixture, 'process', None) is None or fixture.process.poll() is not None)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(report['status'] + ': ' + str(len(report['cases'])) + ' actual Remote retirement cases', flush=True)
    return code


if __name__ == '__main__':
    raise SystemExit(main())
