"""Real API/Remote execution, PostgreSQL archive/restore and TLS S3 result recovery with sources offline."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import runpy
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import time
import traceback
from types import SimpleNamespace
import urllib.error
import urllib.request
import uuid
import zipfile

from postgres_backup import Blocked, Postgres, ROOT, backup, restore, identifier, literal, private_file
from recovery_remote_inventory import canonical
import recovery_remote_retire as retirement
import recovery_remote_outputs as outputs
import recovery_remote_storage as storage
import recovery_remote_results as results
from storage_backup import Client, backup as storage_backup, verify as verify_backup

sys.path.insert(0, str(ROOT / 'simulator/tests'))
from test_remote_recovery import RemoteRecoveryTest
Api = runpy.run_path(str(ROOT / 'scripts/internal/test-postgres-backup.py'))['Api']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transport', choices=('native', 'compose'), default='native')
    parser.add_argument('--minio-binary', type=Path, default=ROOT / '.tools/minio')
    parser.add_argument('--report', type=Path, default=ROOT / '.tools/recovery-remote-results-test.json')
    args = parser.parse_args()
    work = ROOT / '.tools' / ('recovery-remote-results-test-' + uuid.uuid4().hex); work.mkdir(mode=0o700)
    previous_env = os.environ.copy(); owned, apis, processes = {}, [], []
    fixture = RemoteRecoveryTest('test_separate_credentials_identity_tls_pin_and_inspection')
    pg = Postgres(args.transport, diagnostics=work / 'postgres')
    bucket = 'edgeai-result-recovery-test'
    report = {'status': 'RUNNING', 'scope': 'restored-reference-remote-result-commit-tests', 'sourceMode': 'SYNTHETIC', 'cases': []}

    def passed(name): report['cases'].append(name); print('PASS: ' + name, flush=True)

    def remember(db): owned[db] = pg.sql('SELECT oid FROM pg_database WHERE datname=' + literal(db), 'postgres')

    def drop(db):
        assert pg.sql('SELECT oid FROM pg_database WHERE datname=' + literal(db), 'postgres') == owned[db]
        pg.sql('DROP DATABASE ' + identifier(db), 'postgres'); del owned[db]

    def fingerprints(db):
        names = json.loads(pg.sql("SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='edgeai'", db))
        return {name: hashlib.sha256(pg.sql('SELECT to_jsonb(t)::text FROM edgeai.' + identifier(name) +
                    ' t ORDER BY to_jsonb(t)::text', db).encode()).hexdigest() for name in names}

    def run(command, body=None, expected=0):
        value = subprocess.run(list(map(str, command)), input=body, capture_output=True, timeout=180)
        with private_file(work / ('command-' + uuid.uuid4().hex + '.log')) as log: log.write(value.stdout + value.stderr)
        assert value.returncode == expected, 'Recovery test command outcome differs; private diagnostics retained'
        return value

    def mc(*command, body=None): return run([ROOT / '.tools/mc', '--json', *command], body)

    def start_storage(name):
        certs = work / (name + '-certs'); certs.mkdir(mode=0o700)
        shutil.copyfile(fixture.root / 'cert.pem', certs / 'public.crt')
        shutil.copyfile(fixture.root / 'key.pem', certs / 'private.key'); (certs / 'private.key').chmod(0o600)
        (certs / 'CAs').mkdir(); shutil.copyfile(fixture.root / 'cert.pem', certs / 'CAs/root.crt')
        with socket.socket() as one, socket.socket() as two:
            one.bind(('127.0.0.1', 0)); two.bind(('127.0.0.1', 0)); port, console = one.getsockname()[1], two.getsockname()[1]
        user, password = 'recovery-test', secrets.token_hex(24)
        origin = 'https://localhost:' + str(port)
        os.environ['MC_HOST_' + name] = 'https://' + user + ':' + password + '@localhost:' + str(port)
        prefix = 'EDGEAI_BACKUP_SOURCE_' if name == 'origin' else 'EDGEAI_BACKUP_STORAGE_'
        os.environ.update({prefix + 'URL': origin, prefix + 'USER': user, prefix + 'PASSWORD': password})
        with private_file(work / (name + '.log')) as log:
            proc = subprocess.Popen([str(args.minio_binary.resolve()), 'server', str(work / (name + '-data')),
                    '--address', '127.0.0.1:' + str(port), '--console-address', '127.0.0.1:' + str(console),
                    '--certs-dir', str(certs), '--quiet'], stdout=log, stderr=log,
                    env={**os.environ, 'MINIO_ROOT_USER': user, 'MINIO_ROOT_PASSWORD': password})
        processes.append(proc); deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            assert proc.poll() is None
            try:
                with urllib.request.urlopen(origin + '/minio/health/live', context=fixture.tls, timeout=1) as response:
                    if response.status == 200: return proc
            except OSError: time.sleep(.1)
        raise AssertionError('Owned MinIO readiness timed out')

    def remote_options(db, receipt):
        output = work / ('remote-' + uuid.uuid4().hex); output.mkdir(mode=0o700)
        return SimpleNamespace(database=db, restore_report=receipt, output=output, endpoint=remote_origin,
             provider_key='reference', provider_id=binding['providerId'], recovery_id=binding['recoveryId'],
             ca_file=fixture.root / 'cert.pem', certificate_sha256=pin, recovery_token_file=fixture.root / 'operator',
             timeout=60, page_size=2)

    def options(index=0):
        db, receipt, bundle, publication = targets[index]
        return SimpleNamespace(database=db, restore_report=receipt, bundle=bundle, receipt=publication,
             storage_backup=work / 'storage-backup', bucket=bucket, certificate_sha256=pin,
             timeout=120, transport=args.transport, output=work / ('results-' + uuid.uuid4().hex))

    def cli(a, expected=0):
        command = [sys.executable, 'scripts/internal/recovery_remote_results.py']
        for key, value in vars(a).items(): command += ['--' + key.replace('_', '-'), str(value)]
        response = run(command, expected=expected)
        for secret in (fixture.token, fixture.operator, os.environ['EDGEAI_BACKUP_STORAGE_PASSWORD']):
            assert secret.encode() not in response.stdout + response.stderr
        return json.loads((a.output / ('results.json' if not expected else 'failure.json')).read_text())

    def refuse_prepared(a, plan):
        before = fingerprints(a.database)
        started = time.monotonic()
        try: results.apply(pg, storage.Storage(a), a, plan)
        except (RuntimeError, Blocked, OSError): pass
        else: raise AssertionError('Conflicting recovery transaction was accepted')
        elapsed = time.monotonic() - started
        assert fingerprints(a.database) == before and not (a.output / 'results.json').exists()
        return elapsed

    code = 1
    try:
        fixture.setUp(); binding = fixture.binding()
        remote_origin = 'https://127.0.0.1:' + str(fixture.port)
        pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert((fixture.root / 'cert.pem').read_text())).hexdigest()
        os.environ.update(MC_CONFIG_DIR=str(work / 'mc'), MC_NO_COLOR='1', MC_DISABLE_PAGER='1',
                          EDGEAI_BACKUP_CA_FILE=str(fixture.root / 'cert.pem'))
        ca = work / 'mc/certs/CAs'; ca.mkdir(parents=True); shutil.copyfile(fixture.root / 'cert.pem', ca / 'root.crt')
        source_storage = start_storage('origin'); start_storage('replica')
        mc('mb', 'origin/' + bucket); mc('version', 'enable', 'origin/' + bucket)
        mc('pipe', 'origin/' + bucket + '/retained.bin', body=b'original fixed bytes')
        store_backup = work / 'storage-backup'; store_backup.mkdir(mode=0o700)
        backup_client = Client(store_backup, source=True); storage_backup(backup_client, [bucket], 120)
        source_storage.terminate(); source_storage.wait(timeout=15)

        source = 'edgeai_backup_results_' + uuid.uuid4().hex
        pg.sql('CREATE DATABASE ' + identifier(source), 'postgres'); remember(source)
        with private_file(work / 'runtime-key', 'w') as target: target.write(secrets.token_hex(32))
        api = Api(source, work, extra_env={'EDGEAI_REMOTE_ENABLED': 'true', 'EDGEAI_REMOTE_URL': remote_origin,
            'EDGEAI_REMOTE_TOKEN_FILE': str(fixture.root / 'token'), 'EDGEAI_REMOTE_CA_FILE': str(fixture.root / 'cert.pem'),
            'EDGEAI_RUNTIME_ENABLED': 'true', 'EDGEAI_RUNTIME_WORKER_ENABLED': 'false',
            'EDGEAI_RUNNER_KEY_FILE': str(work / 'runtime-key')}); apis.append(api)
        spec = json.loads((ROOT / 'contracts/profiles/service-execution.example.json').read_text())
        parent = api.request('POST', 'profiles/SERVICE', {'key': 'result-root', 'version': '1.0.0', 'spec': spec}, 201)
        child_spec = {**spec, 'inputs': {'input': {'mediaType': 'application/json', 'maxBytes': 1048576, 'required': True}}}
        child_profile = api.request('POST', 'profiles/SERVICE', {'key': 'result-child', 'version': '1.0.0', 'spec': child_spec}, 201)
        bodies = []
        for dag in (False, True):
            workflow = api.request('POST', 'workflows', {'key': 'result-' + str(dag).lower(), 'displayName': 'Result recovery'}, 201)
            tasks = [{'key': 'root', 'serviceProfileVersionId': parent['id'], 'parameters': {'features': [2, 3], 'weights': [4, 5]}}]
            dependencies = []
            if dag:
                tasks.append({'key': 'child', 'serviceProfileVersionId': child_profile['id'], 'parameters': {}})
                dependencies.append({'fromTask': 'root', 'toTask': 'child', 'fromPort': 'output', 'toPort': 'input', 'mode': 'BATCH'})
            v = api.request('POST', 'workflows/' + workflow['id'] + '/versions',
                            {'version': '1.0.0', 'tasks': tasks, 'dependencies': dependencies}, 201)
            created = api.request('POST', 'workflow-runs', {'workflowVersionId': v['id'],
                'execution': {'mode': 'REMOTE', 'providerKey': 'reference'}, 'parameters': {}}, 201, str(uuid.uuid4()))
            body = json.loads(pg.sql('SELECT a.work FROM edgeai.remote_allocation a JOIN edgeai.runtime_instance r ON r.id=a.runtime_id '
                                      'WHERE r.run_id=' + literal(created['id']) + '::uuid', source)); bodies.append(body)
            identity = body['identity']; raw = canonical(body)
            headers = {'X-EdgeAI-Run-Id': identity['runId'], 'X-EdgeAI-Task-Id': identity['taskId'],
                 'X-EdgeAI-Attempt-Id': identity['attemptId'], 'X-EdgeAI-Epoch': str(identity['epoch']),
                 'X-EdgeAI-Request-Digest': 'sha256:' + hashlib.sha256(b'edgeai-reference-allocation-v1\n' + raw).hexdigest()}
            path = '/reference/v1/allocations/' + identity['allocationId']
            assert fixture.rpc(path, 'PUT', raw, headers, fixture.token)[0] == 201
            assert fixture.rpc(path + '/start', 'POST', headers=headers, credential=fixture.token)[0] == 200
            deadline = time.monotonic() + 5
            while fixture.rpc(path, headers=headers, credential=fixture.token)[1]['state'] != 'SUCCEEDED':
                assert time.monotonic() < deadline; time.sleep(.02)
        api.close(); backup(pg, source, work / 'db-backup'); drop(source)
        fixture.cli(binding)
        targets = []
        for _ in range(2):
            db = 'edgeai_restore_results_' + uuid.uuid4().hex
            restoring = Postgres(args.transport, diagnostics=work / ('restore-' + uuid.uuid4().hex))
            restore(restoring, work / 'db-backup', db); remember(db); receipt = restoring.directory / 'restore-report.json'
            a = remote_options(db, receipt); retirement.apply(pg, a, retirement.prepare(pg, a))
            a = remote_options(db, receipt); outputs.recover(pg, a); bundle = a.output
            a = SimpleNamespace(bundle=bundle, storage_backup=store_backup, bucket=bucket, certificate_sha256=pin,
                                timeout=120, output=work / ('publish-' + uuid.uuid4().hex)); a.output.mkdir(mode=0o700)
            storage.publish(storage.Storage(a), a)
            targets.append((db, receipt, bundle, a.output / 'publication.json'))
        fixture.stop()
        db = targets[0][0]; pristine = fingerprints(db); other_before = fingerprints(targets[1][0])
        passed('actual API creates two Remote successes and a waiting BATCH child; two restored DBs retain retired history and real S3 files with original sources offline')

        for mutation in ('cancel', 'failed', 'run', 'newer', 'runtime'):
            task = literal(bodies[0]['identity']['taskId']) + '::uuid'
            attempt = literal(bodies[0]['identity']['attemptId']) + '::uuid'
            run_id = literal(bodies[0]['identity']['runId']) + '::uuid'
            if mutation == 'cancel': change = "UPDATE edgeai.task SET state='CANCELLING',cancellation_reason='USER_CANCELLED' WHERE id=" + task
            elif mutation == 'failed': change = "UPDATE edgeai.task SET state='FAILED' WHERE id=" + task
            elif mutation == 'run': change = "UPDATE edgeai.workflow_run SET state='CANCELLING' WHERE id=" + run_id
            elif mutation == 'newer': change = "UPDATE edgeai.task_attempt SET state='FAILED' WHERE id=" + attempt + "; INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,cause,created_at,updated_at,remote_provider_key,remote_configuration_digest,remote_source_mode) SELECT gen_random_uuid(),task_id,2,2,'QUEUED',mode,'RETRY',now(),now(),remote_provider_key,remote_configuration_digest,remote_source_mode FROM edgeai.task_attempt WHERE id=" + attempt
            else: change = "UPDATE edgeai.runtime_command SET completed=false WHERE runtime_id=(SELECT runtime_id FROM edgeai.remote_allocation WHERE id=" + literal(bodies[0]['identity']['allocationId']) + '::uuid)'
            pg.sql(change, db); before = fingerprints(db)
            assert cli(options(), 2)['databaseModified'] is False; assert fingerprints(db) == before
            pg.sql("UPDATE edgeai.task SET state='RUNNING',cancellation_reason=NULL WHERE id=" + task +
                   "; UPDATE edgeai.workflow_run SET state='RUNNING' WHERE id=" + run_id +
                   '; DELETE FROM edgeai.task_attempt WHERE task_id=' + task + ' AND epoch=2' +
                   "; UPDATE edgeai.task_attempt SET state='DISPATCHING' WHERE id=" + attempt +
                   '; UPDATE edgeai.runtime_command SET completed=true WHERE runtime_id=(SELECT runtime_id FROM edgeai.remote_allocation WHERE id=' + literal(bodies[0]['identity']['allocationId']) + '::uuid)', db)
        assert fingerprints(db) == pristine
        passed('late cancellation, failed Task, cancelling Run, newer attempt and incomplete retirement block writes without overriding history')

        wrong = options(); wrong.bundle = targets[1][2]; wrong.receipt = targets[1][3]
        assert cli(wrong, 2)['databaseModified'] is False
        wrong = options(); wrong.certificate_sha256 = '0' * 64
        assert cli(wrong, 2)['databaseModified'] is False
        assert fingerprints(db) == pristine
        passed('another restored database bundle and wrong actual TLS certificate are rejected before database writes')

        a = options(); a.output.mkdir(mode=0o700); plan = results.prepare(pg, storage.Storage(a), a)
        pg.sql('UPDATE edgeai.runtime_command SET attempts=attempts+1', db)
        refuse_prepared(a, plan)
        pg.sql('UPDATE edgeai.runtime_command SET attempts=attempts-1', db)
        passed('actual outbox update between live verification and commit invalidates the complete row guard')

        a = options(); a.output.mkdir(mode=0o700); plan = results.prepare(pg, storage.Storage(a), a)
        operation = str(uuid.uuid4())
        pg.sql('INSERT INTO edgeai.task_offload(id,task_id,run_id,source_attempt_id,idempotency_key,request_digest,namespace,'
               'state,drain_deadline,start_timeout_seconds,remote_provider_key,remote_configuration_digest,remote_source_mode,created_at,updated_at) '
               'SELECT ' + literal(operation) + '::uuid,r.task_id,r.run_id,r.attempt_id,gen_random_uuid(),'
               + literal('sha256:' + '1' * 64) + ",r.namespace,'DRAINING',now()+interval '1 minute',60,"
               'p.remote_provider_key,p.remote_configuration_digest,p.remote_source_mode,now(),now() '
               'FROM edgeai.runtime_instance r JOIN edgeai.task_attempt p ON p.id=r.attempt_id WHERE p.id='
               + literal(bodies[0]['identity']['attemptId']) + '::uuid', db)
        refuse_prepared(a, plan)
        for state in ('DRAINING', 'CANCELLING'):
            pg.sql('UPDATE edgeai.task_offload SET state=' + literal(state) + ' WHERE id=' + literal(operation) + '::uuid', db)
            before = fingerprints(db)
            assert cli(options(), 2)['databaseModified'] is False and fingerprints(db) == before
        pg.sql('DELETE FROM edgeai.task_offload WHERE id=' + literal(operation) + '::uuid', db)
        assert fingerprints(db) == pristine
        passed('actual transfer inserted after verification invalidates the transaction guard and active transfer states block result recovery')

        a = options(); a.output.mkdir(mode=0o700); plan = results.prepare(pg, storage.Storage(a), a)
        pg.sql('COMMENT ON DATABASE ' + identifier(db) + " IS 'owned-wrong-marker'", db)
        refuse_prepared(a, plan)
        pg.sql('COMMENT ON DATABASE ' + identifier(db) + ' IS ' + literal(plan['marker']), db)
        passed('database restore marker is rechecked inside the write transaction')

        locker_name = 'result-recovery-lock-' + uuid.uuid4().hex
        locker = None
        try:
            with private_file(work / 'lock-holder.log') as log:
                locker = subprocess.Popen(pg.prefix + [pg.binaries['psql']] + pg.connection + ['--dbname', db,
                    '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-c',
                    'BEGIN; LOCK TABLE edgeai.task_result IN ROW EXCLUSIVE MODE; SELECT pg_sleep(60); COMMIT;'],
                    env={**pg.env, 'PGAPPNAME': locker_name}, stdout=log, stderr=log)
            deadline = time.monotonic() + 5
            while pg.sql("SELECT count(*) FROM pg_stat_activity a JOIN pg_locks l ON l.pid=a.pid WHERE a.application_name=" +
                    literal(locker_name) + " AND l.relation='edgeai.task_result'::regclass AND l.granted", db) != '1':
                assert time.monotonic() < deadline; time.sleep(.05)
            a = options(); a.output.mkdir(mode=0o700); plan = results.prepare(pg, storage.Storage(a), a)
            started = time.monotonic(); elapsed = refuse_prepared(a, plan)
            # Full-table before/after checks make independent client connections. Their
            # transport cost is not time spent waiting for the recovery transaction.
            report.update(lockRejectionSeconds=round(elapsed, 3),
                          lockCheckSeconds=round(time.monotonic() - started, 3))
            assert 4 <= elapsed < 15
        finally:
            pg.sql('SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name=' + literal(locker_name), db)
            if locker is not None: locker.wait(timeout=10)
        passed('an actual competing table writer causes the five-second lock timeout without partial recovery changes')

        pg.sql("CREATE FUNCTION edgeai.owned_recovery_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned fault after Result insertion'; END $$; CREATE TRIGGER owned_recovery_fault BEFORE UPDATE ON edgeai.task FOR EACH ROW EXECUTE FUNCTION edgeai.owned_recovery_fault()", db)
        a = options(); a.output.mkdir(mode=0o700); plan = results.prepare(pg, storage.Storage(a), a)
        refuse_prepared(a, plan)
        assert pg.sql('SELECT count(*) FROM edgeai.task_result', db) == '0'
        pg.sql('DROP TRIGGER owned_recovery_fault ON edgeai.task; DROP FUNCTION edgeai.owned_recovery_fault()', db)
        passed('a real PostgreSQL error after Result/artifact sealing rolls back Result, artifacts, Attempt, Task and Run atomically')

        parallel = []
        for _ in range(2):
            a = options(); a.output.mkdir(mode=0o700)
            client = Postgres(args.transport, diagnostics=work / ('concurrent-pg-' + uuid.uuid4().hex))
            parallel.append((client, storage.Storage(a), a, results.prepare(client, storage.Storage(a), a)))
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(results.apply, *entry) for entry in parallel]
            successes, conflicts = [], 0
            for future in futures:
                try: successes.append(future.result(timeout=90))
                except RuntimeError: conflicts += 1
        assert len(successes) == 1 and conflicts == 1
        committed = successes[0]
        assert (committed['resultsCreated'], committed['childrenReadied'], committed['runsReconciled']) == (2, 1, 1)
        after = fingerprints(db)
        changed = {'task_result', 'result_artifact', 'task_attempt', 'task', 'workflow_run'}
        assert {name for name in pristine if pristine[name] != after[name]} == changed
        assert fingerprints(targets[1][0]) == other_before
        assert pg.sql("SELECT count(*) FROM edgeai.runtime_instance WHERE desired_state<>'STOPPED' OR observed_state<>'TERMINATED'", db) == '0'
        assert pg.sql("SELECT count(*) FROM edgeai.runtime_command WHERE NOT completed", db) == '0'
        assert pg.sql("SELECT count(*) FROM edgeai.task_attempt p JOIN edgeai.task t ON t.id=p.task_id WHERE t.state='READY' AND p.state='QUEUED' AND p.mode=t.initial_mode AND p.remote_configuration_digest=t.initial_remote_configuration_digest", db) == '1'
        passed('two competing recoveries create exactly two immutable Results, one finished Run and one queued child; the stale writer rolls back and all unrelated tables/databases remain unchanged')

        replay = cli(options()); assert not replay['databaseModified']
        assert (replay['resultsCreated'], replay['childrenReadied'], replay['runsReconciled']) == (0, 0, 0)
        assert fingerprints(db) == after
        passed('same-input replay retains Result IDs, exact fixed versions, timestamps and the single queued child')

        # Run the packaged Java canonicalizer against the actual S3 manifest, not a copied Python formula.
        java_dir = work / 'java-digest'; java_dir.mkdir(mode=0o700)
        with zipfile.ZipFile(ROOT / 'backend/app/build/libs/edgeai-control-plane.jar') as archive:
            for member in archive.namelist():
                if not member.endswith('/') and (member.startswith('BOOT-INF/classes/') or
                        (member.startswith('BOOT-INF/lib/') and member.endswith('.jar'))):
                    archive.extract(member, java_dir)
        java_source = java_dir / 'Digest.java'
        java_source.write_text('import io.edgeai.app.support.JsonDocuments; import java.nio.charset.StandardCharsets; '
            'class Digest { public static void main(String[] args) throws Exception { var json=new JsonDocuments(); '
            'System.out.print(json.digest("edgeai-result-v1",json.parse(new String(System.in.readAllBytes(),StandardCharsets.UTF_8),65536))); }}')
        classpath = str(java_dir / 'BOOT-INF/classes') + os.pathsep + str(java_dir / 'BOOT-INF/lib/*')
        for entry in parallel[0][3]['entries']:
            manifest = [{k:item[k] for k in ('port', 'bytes', 'sha256', 'mediaType', 'versionId')} for item in entry['outputs']]
            assert run(['java', '--class-path', classpath, java_source], canonical(manifest)).stdout.decode() == entry['manifestDigest']
        passed('the packaged Java JsonDocuments canonicalizer produces the exact recovered manifest digests for both actual S3 results')

        published = json.loads(targets[0][3].read_text()); item = published['objects'][0]
        raw = (targets[0][2] / 'objects' / (item['sha256'] + '.bin')).read_bytes()
        status, headers, _ = storage.Storage(options()).request('PUT', '/' + bucket + '/' + item['key'], body=raw,
                                                               extra={'content-type': item['mediaType']})
        assert status == 200
        item['versionId'] = storage.header(headers, 'x-amz-version-id')
        changed_receipt = work / 'conflicting-valid-version.json'; retirement.durable_json(changed_receipt, published)
        a = options(); a.receipt = changed_receipt
        assert cli(a, 2)['databaseModified'] is False and fingerprints(db) == after
        assert not cli(options())['databaseModified']
        passed('another real version containing identical bytes cannot replace the already committed Result; original pinned receipt still verifies despite changed latest')

        a = options(1); a.output.mkdir(mode=0o700); plan = results.prepare(pg, storage.Storage(a), a)
        original_call = pg.call
        def lost_reply(tool, arguments, *positional, **keywords):
            response = original_call(tool, arguments, *positional, **keywords)
            if arguments[-2:] == ['-f', '-']: raise OSError('Injected response loss after actual COMMIT')
            return response
        pg.call = lost_reply
        try:
            try: results.apply(pg, storage.Storage(a), a, plan)
            except OSError: pass
            else: raise AssertionError('Lost reply not injected')
        finally: pg.call = original_call
        assert (a.output / 'intent.json').exists() and not (a.output / 'results.json').exists()
        assert not cli(options(1))['databaseModified']
        passed('lost reply after actual COMMIT retains intent and a fresh retry verifies existing results without duplicate writes')

        inspection = Api(db, work, inspection=True); apis.append(inspection)
        for body in bodies:
            result = inspection.request('GET', 'tasks/' + body['identity']['taskId'] + '/results')
            assert result['taskId'] == body['identity']['taskId'] and len(result['items']) == 1
            assert result['items'][0]['remoteAllocationId'] == body['identity']['allocationId']
            assert result['items'][0]['remoteSourceMode'] == 'SYNTHETIC' and len(result['items'][0]['artifacts']) == 1
        try: inspection.request('POST', 'workflows', {'key': 'blocked', 'displayName': 'blocked'}, 201)
        except urllib.error.HTTPError as error: assert error.code == 403
        else: raise AssertionError('Inspection allowed writes')
        inspection.close()
        passed('packaged inspection API reads both recovered Results and still refuses management writes')

        item = json.loads(targets[0][3].read_text())['objects'][0]
        mc('rm', '--force', '--version-id', item['versionId'], 'replica/' + bucket + '/' + item['key'])
        assert cli(options(), 2)['databaseModified'] is False and fingerprints(db) == after
        verify_backup(backup_client, json.loads((store_backup / 'manifest.json').read_text()))
        passed('missing pinned version blocks even committed-result replay; committed DB history and original backup object stay intact')
        report.update(status='PASS', resultsCreated=2, childrenReadied=1, runsReconciled=1, restoredDatabases=2,
                      preservedTables=len(pristine)-len(changed), sourceProviderOffline=True, concurrentRecoveryWinners=1,
                      javaManifestDigestsVerified=2,
                      minioBinarySha256=hashlib.sha256(args.minio_binary.read_bytes()).hexdigest(),
                      jarSha256=hashlib.sha256((ROOT / 'backend/app/build/libs/edgeai-control-plane.jar').read_bytes()).hexdigest())
        code = 0
    except Exception as error:
        report.update(status='FAIL', failureType=type(error).__name__,
                      failureLocations=[Path(frame.filename).name + ':' + str(frame.lineno)
                          for frame in traceback.extract_tb(error.__traceback__)[-5:]])
        with private_file(work / 'failure.log', 'w') as log: traceback.print_exc(file=log)
        print('FAIL: real Remote result recovery; private diagnostics: ' + str(work), flush=True)
    finally:
        for api in apis: api.close()
        fixture.doCleanups()
        for process in processes:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        for db in list(owned):
            try: drop(db)
            except Exception: report['status'] = 'FAIL'; code = 1
        os.environ.clear(); os.environ.update(previous_env)
        report.update(ownedDatabasesRemoved=not owned, ownedProcessesStopped=all(p.poll() is not None for p in processes))
        args.report.parent.mkdir(parents=True, exist_ok=True); args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(report['status'] + ': ' + str(len(report['cases'])) + ' actual Remote result recovery cases', flush=True)
    return code


if __name__ == '__main__': raise SystemExit(main())
