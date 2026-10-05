"""Real public API allocations, PostgreSQL archive/restore and fenced TLS reference-provider inventory."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import time
import traceback
import uuid

from postgres_backup import Postgres, ROOT, backup, restore, identifier, literal, private_file
from recovery_remote_inventory import canonical, binding_digest
from types import SimpleNamespace

sys.path.insert(0, str(ROOT / 'simulator/tests'))
from test_remote_recovery import RemoteRecoveryTest
Api = runpy.run_path(str(ROOT / 'scripts/test-postgres-backup.py'))['Api']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transport', choices=['native', 'compose'], default='native')
    parser.add_argument('--report', type=Path, default=ROOT / '.tools/recovery-remote-inventory-test.json')
    args = parser.parse_args()
    work = ROOT / '.tools' / ('recovery-remote-inventory-test-' + uuid.uuid4().hex)
    work.mkdir(mode=0o700)
    pg = Postgres(args.transport, diagnostics=work / 'postgres')
    owned, fixtures, apis = {}, [], []
    report = {'status': 'RUNNING', 'scope': 'restored-database-reference-remote-inventory', 'cases': [],
              'sourceMode': 'SYNTHETIC', 'ownedDatabasesRemoved': False, 'ownedProcessesStopped': False}

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

    def create_work(api, source, provider_key='reference'):
        service = api.request('POST', 'profiles/SERVICE', {'key': 'inventory-' + uuid.uuid4().hex, 'version': '1.0.0',
                              'spec': json.loads((ROOT / 'contracts/profiles/service-execution.example.json').read_text())}, 201)
        workflow = api.request('POST', 'workflows', {'key': 'inventory-' + uuid.uuid4().hex, 'displayName': 'Remote recovery'}, 201)
        version = api.request('POST', 'workflows/' + workflow['id'] + '/versions', {'version': '1.0.0',
                              'tasks': [{'key': 'root', 'serviceProfileVersionId': service['id'],
                                         'parameters': {'features': [2, 3], 'weights': [4, 5]}}], 'dependencies': []}, 201)
        run = api.request('POST', 'workflow-runs', {'workflowVersionId': version['id'],
                          'execution': {'mode': 'REMOTE', 'providerKey': provider_key}, 'parameters': {}}, 201, str(uuid.uuid4()))
        row = json.loads(pg.sql("SELECT json_build_object('work',a.work,'digest',a.request_digest,'binding',a.configuration_digest) "
                                'FROM edgeai.remote_allocation a JOIN edgeai.runtime_instance r ON r.id=a.runtime_id WHERE r.run_id=' +
                                literal(run['id']) + '::uuid', source))
        return row

    def submit(fixture, row, mode):
        body = copy.deepcopy(row['work'])
        if mode == 'IDENTITY_CONFLICT': body['identity']['runId'] = str(uuid.uuid4())
        if mode == 'REQUEST_CONFLICT': body['parameters']['weights'] = [1, 9]
        identity = body['identity']; path = '/reference/v1/allocations/' + identity['allocationId']
        headers = {'X-EdgeAI-Run-Id': identity['runId'], 'X-EdgeAI-Task-Id': identity['taskId'],
                   'X-EdgeAI-Attempt-Id': identity['attemptId'], 'X-EdgeAI-Epoch': str(identity['epoch'])}
        if mode == 'CANCELLED_BEFORE_RESERVATION':
            assert fixture.rpc(path + '/cancel', 'POST', headers=headers, credential=fixture.token)[0] == 200
            return None
        raw = canonical(body)
        headers['X-EdgeAI-Request-Digest'] = 'sha256:' + hashlib.sha256(b'edgeai-reference-allocation-v1\n' + raw).hexdigest()
        if mode not in {'REQUEST_CONFLICT', 'IDENTITY_CONFLICT'}:
            assert headers['X-EdgeAI-Request-Digest'] == row['digest'], 'Actual Java allocation digest differs'
        code, status = fixture.rpc(path, 'PUT', raw, headers, fixture.token)
        assert code == 201, 'Real provider reservation rejected'
        if mode in {'MATCHED_TERMINAL', 'OBSERVATION_CONFLICT'}:
            assert fixture.rpc(path + '/start', 'POST', headers=headers, credential=fixture.token)[0] == 200
            deadline = time.monotonic() + 5
            while status['state'] != 'SUCCEEDED':
                assert time.monotonic() < deadline, 'Real synthetic computation did not finish'
                _, status = fixture.rpc(path, headers=headers, credential=fixture.token); time.sleep(.01)
        return status

    def scenario(conflicts):
        identity = uuid.uuid4().hex
        source, target = 'edgeai_backup_remote_' + identity, 'edgeai_restore_remote_' + identity
        pg.sql('CREATE DATABASE ' + identifier(source), 'postgres'); remember(source)
        fixture = RemoteRecoveryTest(); fixtures.append(fixture); fixture.setUp()
        origin = 'https://127.0.0.1:' + str(fixture.port)
        with private_file(work / ('runtime-' + identity), 'w') as key: key.write(os.urandom(32).hex())
        env = {'EDGEAI_REMOTE_ENABLED': 'true', 'EDGEAI_REMOTE_URL': origin,
               'EDGEAI_REMOTE_TOKEN_FILE': str(fixture.root / 'token'), 'EDGEAI_REMOTE_CA_FILE': str(fixture.root / 'cert.pem'),
               'EDGEAI_RUNTIME_ENABLED': 'true', 'EDGEAI_RUNTIME_WORKER_ENABLED': 'false',
               'EDGEAI_RUNNER_KEY_FILE': str(work / ('runtime-' + identity))}
        api = Api(source, work, extra_env=env); apis.append(api)
        modes = ['MATCHED_TERMINAL', 'CANCELLED_BEFORE_RESERVATION']
        if conflicts: modes += ['ABSENT_FROM_PROVIDER', 'REQUEST_CONFLICT', 'IDENTITY_CONFLICT', 'OBSERVATION_CONFLICT']
        for mode in modes:
            row = create_work(api, source)
            expected_binding = binding_digest(SimpleNamespace(endpoint=origin, ca_file=fixture.root / 'cert.pem'))
            assert row['binding'] == expected_binding, 'Actual Java origin/trust binding differs'
            if mode == 'ABSENT_FROM_PROVIDER': continue
            status = submit(fixture, row, mode)
            if mode in {'MATCHED_TERMINAL', 'OBSERVATION_CONFLICT'}:
                # Explicit persisted-observation fixture: do not alter immutable identity/work or disable triggers.
                advanced = {**status, 'revision': status['revision'] + (1 if mode == 'OBSERVATION_CONFLICT' else 0)}
                pg.sql('UPDATE edgeai.remote_allocation SET provider_revision=' + str(advanced['revision']) +
                       ",provider_state='SUCCEEDED',observed_at=now(),observation=" + literal(json.dumps(advanced)) +
                       '::jsonb WHERE id=' + literal(row['work']['identity']['allocationId']) + '::uuid', source)
        backup(pg, source, work / ('backup-' + identity))
        restoring = Postgres(args.transport, diagnostics=work / ('restore-' + identity))
        restore(restoring, work / ('backup-' + identity), target); remember(target)
        receipt = restoring.directory / 'restore-report.json'
        if conflicts:
            # A real API allocation created after the actual archive is absent from the restored DB.
            submit(fixture, create_work(api, source), 'POST_SNAPSHOT')
        api.close(); drop(source)
        binding = fixture.binding(); fixture.cli(binding)
        before = fingerprints(target)
        assert len(before) == 45 and 'stream_completion_publication' in before
        before_provider = fixture.rows()
        pin = hashlib.sha256(__import__('ssl').PEM_cert_to_DER_cert((fixture.root / 'cert.pem').read_text())).hexdigest()

        def cli(extra=(), expected=0, output=None):
            output = output or work / ('inventory-' + uuid.uuid4().hex)
            command = [sys.executable, 'scripts/recovery_remote_inventory.py', '--transport', args.transport,
                       '--database', target, '--restore-report', str(receipt), '--endpoint', origin,
                       '--provider-key', 'reference', '--provider-id', binding['providerId'], '--recovery-id', binding['recoveryId'],
                       '--ca-file', str(fixture.root / 'cert.pem'), '--certificate-sha256', pin,
                       '--recovery-token-file', str(fixture.root / 'operator'), '--page-size', '2', '--output', str(output), *extra]
            result = subprocess.run(command, capture_output=True, timeout=120)
            with private_file(work / ('command-' + uuid.uuid4().hex + '.log')) as log: log.write(result.stdout + result.stderr)
            assert result.returncode == expected, 'Remote inventory CLI outcome differs; private diagnostics retained'
            assert fixture.token.encode() not in result.stdout + result.stderr and fixture.operator.encode() not in result.stdout + result.stderr
            return output, json.loads((output / ('inventory.json' if (output / 'inventory.json').exists() else 'failure.json')).read_text())

        output, observed = cli(expected=2 if conflicts else 0)
        counts = {name: 1 for name in modes}
        if conflicts: counts['ABSENT_FROM_RESTORED_DATABASE'] = 1
        assert observed['counts'] == counts
        assert not observed['activated'] and not observed['databaseModified'] and not observed['globalQuiescenceProven']
        assert observed['otherTargets'] == []
        assert (output / 'inventory.json').stat().st_mode & 0o777 == 0o600
        passed('actual archive, Java binding and TLS inventory ' + ('classify missing/conflicting/post-snapshot allocations' if conflicts else 'match terminal work and prior cancellation'))
        if not conflicts:
            for extra in (['--provider-id', str(uuid.uuid4())], ['--recovery-id', str(uuid.uuid4())], ['--certificate-sha256', '0' * 64]):
                assert cli(extra, expected=2)[1]['status'] == 'BLOCKED'
            passed('wrong installation, recovery and TLS identity rejected')
            wrong = json.loads(receipt.read_text()); wrong['databaseOid'] = '0'
            with private_file(work / 'wrong-restore.json', 'w') as out: json.dump(wrong, out)
            assert cli(['--restore-report', str(work / 'wrong-restore.json')], expected=1)[1]['status'] == 'FAIL'
            passed('restore report identity must match the actual inactive database')
            pg.sql("INSERT INTO edgeai.flyway_schema_history(installed_rank,version,description,type,script,installed_by,execution_time,success) "
                   "VALUES (999,'999','future fixture','SQL','V999__fixture.sql',current_user,0,true)", target)
            assert cli(expected=2)[1]['status'] == 'BLOCKED'
            pg.sql('DELETE FROM edgeai.flyway_schema_history WHERE installed_rank=999', target)
            passed('unknown schema refused before a partial inventory can pass')
            previous = (output / 'inventory.json').read_bytes()
            cli(expected=1, output=output)
            assert (output / 'inventory.json').read_bytes() == previous
            passed('existing private inventory is never overwritten')
            outside = cli(['--provider-key', 'different-provider'], expected=2)[1]
            assert len(outside['otherTargets']) == 1 and outside['counts'] == {'BINDING_CONFLICT': 2}
            passed('different provider binding is explicit and requires review')
            fixture.process.kill(); fixture.process.wait(timeout=5); fixture.start()
            _, again = cli()
            assert again['providerInventorySha256'] == observed['providerInventorySha256'] and again['counts'] == observed['counts']
            passed('frozen inventory survives actual provider SIGKILL restart')
        assert fixture.rows() == before_provider and fingerprints(target) == before
        passed('all 45 restored tables and provider rows preserved by observation')
        assert fixture.doCleanups(), 'Owned TLS fixture cleanup failed'
        drop(target)

    code = 1
    try:
        scenario(False); scenario(True)
        report.update(status='PASS', restoredTables=45, pageSize=2, restoredDatabases=2, providerInstallations=2,
                      serverMajor=int(pg.sql('SHOW server_version_num', 'postgres')) // 10000,
                      jarSha256=hashlib.sha256((ROOT / 'backend/app/build/libs/edgeai-control-plane.jar').read_bytes()).hexdigest())
        code = 0
    except Exception as error:
        report.update(status='FAIL', failureType=type(error).__name__,
                      failureLocation=[{'file':Path(f.filename).name,'function':f.name,'line':f.lineno}
                                       for f in traceback.extract_tb(error.__traceback__)[-5:]])
        with private_file(work / 'failure.log', 'w') as log: traceback.print_exc(file=log)
        print('FAIL: actual Remote recovery inventory; private diagnostics: ' + str(work), flush=True)
    finally:
        for api in apis: api.close()
        for fixture in fixtures:
            if not fixture.doCleanups(): report.update(status='FAIL', cleanupFailed=True); code = 1
        for database in list(owned):
            try: drop(database)
            except Exception: report.update(status='FAIL', cleanupFailed=True); code = 1
        report['ownedDatabasesRemoved'] = not owned
        report['ownedProcessesStopped'] = all(api.process.poll() is not None for api in apis) and all(
            getattr(fixture, 'process', None) is None or fixture.process.poll() is not None for fixture in fixtures)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(report['status'] + ': ' + str(len(report['cases'])) + ' actual Remote inventory cases', flush=True)
    return code


if __name__ == '__main__':
    raise SystemExit(main())
