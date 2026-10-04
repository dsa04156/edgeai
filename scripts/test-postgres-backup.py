"""Real public API data, pg_dump/pg_restore, refusal and owned-database cleanup tests."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
import traceback
import urllib.request
import uuid

from postgres_backup import Postgres, identifier, literal, private_file

ROOT = Path(__file__).resolve().parents[1]


class Api:
    def __init__(self, database, directory):
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
        user, password = 'backup-test', secrets.token_hex(24)
        self.url = 'http://127.0.0.1:'+str(port)
        self.headers = {'Authorization': 'Basic '+base64.b64encode((user+':'+password).encode()).decode()}
        env = {**os.environ, 'EDGEAI_DB_NAME': database, 'EDGEAI_API_PORT': str(port), 'EDGEAI_BIND_ADDRESS': '127.0.0.1',
            'EDGEAI_API_USER': user, 'EDGEAI_API_PASSWORD': password}
        env.update({k:'false' for k in ['EDGEAI_RUNTIME_ENABLED','EDGEAI_VD_ENABLED','EDGEAI_KUBE_ENABLED',
            'EDGEAI_REMOTE_ENABLED','EDGEAI_STREAM_ENABLED','EDGEAI_STREAM_BINDINGS_ENABLED','EDGEAI_STREAM_RUNS_ENABLED','EDGEAI_API_TLS_ENABLED']})
        self.process = None
        try:
            with private_file(directory/('api-'+database+'.log')) as log:
                self.process = subprocess.Popen(['java','-Xmx512m','-jar',str(ROOT/'backend/app/build/libs/edgeai-control-plane.jar')],
                    env=env, stdout=log, stderr=log)
            deadline = time.monotonic()+90
            while True:
                assert self.process.poll() is None, 'Owned API exited; private log retained'
                try:
                    with urllib.request.urlopen(self.url+'/actuator/health/readiness', timeout=2) as r:
                        if r.status == 200: break
                except OSError: pass
                assert time.monotonic() < deadline, 'Owned API readiness timed out'
                time.sleep(.2)
            request = urllib.request.Request(self.url+'/api/v1/csrf', headers=self.headers)
            with urllib.request.urlopen(request, timeout=5) as r:
                token = json.load(r)
                cookies = SimpleCookie()
                for value in r.headers.get_all('Set-Cookie', []): cookies.load(value)
                self.headers.update({'Cookie': '; '.join(k+'='+v.value for k,v in cookies.items()), 'X-CSRF-TOKEN':token['token']})
        except Exception:
            self.close()
            raise

    def request(self, method, path, value=None, expected=200, key=None):
        data = None if value is None else json.dumps(value).encode()
        headers = {**self.headers, 'Content-Type':'application/json'}
        if key is not None: headers['Idempotency-Key'] = key
        request = urllib.request.Request(self.url+'/api/v1/'+path, data=data, headers=headers, method=method)
        with urllib.request.urlopen(request, timeout=10) as r:
            assert r.status == expected, 'Unexpected public API status'
            return json.load(r)

    def close(self):
        if self.process:
            self.process.terminate()
            try: self.process.wait(15)
            except subprocess.TimeoutExpired: self.process.kill(); self.process.wait(5)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--transport', choices=['native','compose'], default='native')
    p.add_argument('--report', type=Path, default=ROOT/'.tools/postgres-backup-test.json')
    a = p.parse_args()
    identity = uuid.uuid4().hex
    work = ROOT/'.tools'/('postgres-recovery-test-'+identity)
    work.mkdir(mode=0o700)
    pg = Postgres(a.transport, diagnostics=work/'diagnostics')
    source = 'edgeai_backup_'+identity
    target = 'edgeai_restore_'+identity
    apis, owned = [], {}
    report = {'scope':'postgresql-backup-restore','sourceMode':'SYNTHETIC','status':'RUNNING',
              'cases':[], 'ownedDatabasesRemoved':False, 'ownedApisStopped':False}
    def remember(database):
        owned[database] = pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(database), 'postgres')
        assert owned[database]
    def exists(database):
        return pg.sql('SELECT EXISTS(SELECT FROM pg_database WHERE datname='+literal(database)+')','postgres') == 't'
    def cli(action, options, expected=0, env=None):
        r = subprocess.run([sys.executable, 'scripts/postgres_backup.py', action, '--transport', a.transport, *map(str,options)],
            env=env, capture_output=True, timeout=360)
        with private_file(work/('command-'+uuid.uuid4().hex+'.log')) as log:
            log.write(r.stdout); log.write(r.stderr)
        assert r.returncode == expected, action+' unexpected exit; private CLI diagnostics retained'
        return r
    def fingerprints(database):
        tables = json.loads(pg.sql("SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='edgeai'",database))
        result = {}
        for table in tables:
            name = '"'+table.replace('"','""')+'"'
            rows = pg.sql('SELECT to_jsonb(t)::text FROM edgeai.'+name+' t ORDER BY to_jsonb(t)::text',database)
            result[table] = hashlib.sha256(rows.encode()).hexdigest()
        return result
    def passed(name):
        report['cases'].append(name)
        print('PASS: '+name, flush=True)
    code = 1
    try:
        pg.check_versions('postgres')
        pg.sql('CREATE DATABASE '+identifier(source), 'postgres'); remember(source)
        api = Api(source,work); apis.append(api)
        profile_key = 'backup-'+identity
        profile = api.request('POST','profiles/DEVICE',{'key':profile_key,'version':'1.0.0',
            'spec':{'protocol':'synthetic','serial':9007199254740993,'label':'백업 검증'}},201)
        device = api.request('POST','devices',{'key':'backup-device-'+identity,'displayName':'복원 시험',
            'profileVersionId':profile['id'],'sourceMode':'SYNTHETIC'},201)
        session = api.request('POST','devices/'+device['id']+'/sessions',{'bootId':str(uuid.uuid4())},201)
        def observe(sequence):
            return api.request('POST','devices/'+device['id']+'/observations',{'sessionId':session['id'],'sequence':sequence,
                'observedAt':datetime.now(timezone.utc).isoformat(),'status':'ONLINE',
                'attributes':{'serial':9007199254740993,'source':'backup-test','sequence':sequence}},201)
        for sequence in range(3): observe(sequence)
        service = api.request('POST','profiles/SERVICE',{'key':'backup-service-'+identity,'version':'1.0.0',
            'spec':json.loads((ROOT/'contracts/profiles/service-execution.example.json').read_text())},201)
        workflow = api.request('POST','workflows',{'key':'backup-workflow-'+identity,'displayName':'Restore history'},201)
        version = api.request('POST','workflows/'+workflow['id']+'/versions',{'version':'1.0.0',
            'tasks':[{'key':name,'serviceProfileVersionId':service['id'],'parameters':{'serial':9007199254740993}} for name in ['root','child']],
            'dependencies':[{'fromTask':'root','toTask':'child','fromPort':'output','toPort':'input','mode':'BATCH'}]},201)
        run = api.request('POST','workflow-runs',{'workflowVersionId':version['id'],'execution':{'mode':'AUTO'},'parameters':{}},201,str(uuid.uuid4()))
        api.request('POST','workflow-runs/'+run['id']+'/cancel',{})
        fixed_paths = ['profiles/DEVICE/'+profile_key+'/versions/1.0.0','workflows/'+workflow['id'],'workflow-runs/'+run['id']]
        expected_api = {path:api.request('GET',path) for path in fixed_paths}
        before = fingerprints(source)
        bundle = work/'snapshot'
        cli('backup',['--database',source,'--output',bundle])
        manifest = json.loads((bundle/'manifest.json').read_text())
        assert all((bundle/name).stat().st_mode & 0o077 == 0 for name in ['database.dump','manifest.json'])
        assert bundle.stat().st_mode & 0o077 == 0
        observe(3)  # This acknowledged write is after the completed backup and must not appear in the restore.
        source_after = fingerprints(source)
        assert source_after != before
        cli('restore',['--input',bundle,'--target-database',target]); remember(target)
        assert fingerprints(target) == before
        assert fingerprints(source) == source_after
        assert pg.sql('SELECT count(*) FROM edgeai.device_observation',target) == '3'
        report.update(tableCount=len(before),archiveBytes=manifest['archiveBytes'],serverMajor=manifest['sourceServerVersion']//10000,
            migrationCount=int(pg.sql('SELECT count(*) FROM edgeai.flyway_schema_history',target)),
            jarSha256=hashlib.sha256((ROOT/'backend/app/build/libs/edgeai-control-plane.jar').read_bytes()).hexdigest())
        passed('actual archive preserves all table contents, migrations and snapshot boundary; source remains unchanged')
        restored = Api(target,work); apis.append(restored)
        assert {path:restored.request('GET',path) for path in fixed_paths} == expected_api
        assert fingerprints(target) == before
        for sql in ["UPDATE edgeai.profile_version SET version='9.9.9' WHERE id='"+profile['id']+"'",
                    'DELETE FROM edgeai.profile_version']:
            try: pg.sql(sql,target)
            except RuntimeError: pass
            else: raise AssertionError('Restored immutable profile constraints were bypassed')
        assert fingerprints(target) == before
        passed('restored packaged API reads identical registry/workflow/run history and immutable constraints remain active')
        cli('restore',['--input',bundle,'--target-database',target],1)
        assert fingerprints(target) == before
        passed('existing restore target is refused without changing any row')
        cli('restore',['--input',bundle,'--target-database',source],1)
        assert fingerprints(source) == source_after
        passed('non-isolated database name is refused without changing the source')
        cli('backup',['--database',source,'--output',bundle],1)
        assert hashlib.sha256((bundle/'database.dump').read_bytes()).hexdigest() == manifest['archiveSha256']
        passed('existing backup directory is never overwritten')
        bad = work/'corrupt'; shutil.copytree(bundle,bad)
        with (bad/'database.dump').open('ab') as f: f.write(b'corruption')
        bad_target = 'edgeai_restore_corrupt_'+identity[:24]
        cli('restore',['--input',bad,'--target-database',bad_target],1)
        assert not exists(bad_target)
        passed('corrupt archive is refused before database creation')
        link = work/'symlink'; link.mkdir(mode=0o700)
        shutil.copy2(bundle/'manifest.json',link/'manifest.json')
        (link/'database.dump').symlink_to(bundle/'database.dump')
        cli('restore',['--input',link,'--target-database',bad_target],1)
        assert not exists(bad_target)
        passed('symlink archive is refused before database creation')
        public = work/'readable'; shutil.copytree(bundle,public)
        (public/'database.dump').chmod(0o644)
        cli('restore',['--input',public,'--target-database',bad_target],1)
        assert not exists(bad_target)
        (public/'database.dump').chmod(0o600)
        passed('archive with non-owner read permissions is refused before database creation')
        # Real pg_restore COPY fails on the new database, after CREATE DATABASE succeeds.
        # All objects belong to this test's isolated source, never to a shared database.
        pg.sql("CREATE TABLE public.restore_failure_probe(value integer CONSTRAINT fail_on_restore "
               "CHECK(current_database() NOT LIKE 'edgeai_restore_%')); "
               "INSERT INTO public.restore_failure_probe VALUES(1)",source)
        failing_bundle = work/'restore-failure'
        cli('backup',['--database',source,'--output',failing_bundle])
        fail_target = 'edgeai_restore_failed_'+identity[:24]
        r = cli('restore',['--input',failing_bundle,'--target-database',fail_target],1)
        diag = Path(r.stdout.decode().split('Private diagnostics: ')[1].strip())
        failed = json.loads((diag/'restore-report.json').read_text())
        assert 'fail_on_restore' in (diag/'postgres.log').read_text()
        assert failed['created'] and failed['failedDatabaseRemoved'] and not exists(fail_target)
        pg.sql('DROP TABLE public.restore_failure_probe',source)
        assert fingerprints(source) == source_after and fingerprints(target) == before
        passed('actual pg_restore constraint failure removes only its newly created database')
        binaries = work/'failing-client'; binaries.mkdir(mode=0o700)
        failure_env = os.environ.copy()
        if a.transport == 'native':
            for tool in ['psql','pg_restore']: (binaries/tool).symlink_to(pg.binaries[tool])
            executable, original = binaries/'pg_dump', pg.binaries['pg_dump']
            client_options = ['--pg-bin',binaries]
        else:
            executable, original = binaries/'docker', shutil.which('docker')
            failure_env['PATH'] = str(binaries)+os.pathsep+failure_env['PATH']
            client_options = []
        with private_file(executable,'w') as f:
            f.write('#!/usr/bin/env python3\nimport subprocess,sys\n')
            f.write('result=subprocess.call(['+repr(original)+']+sys.argv[1:])\n')
            f.write('if result==0 and "--format=custom" in sys.argv: print("Injected dump warning",file=sys.stderr)\n')
            f.write('sys.exit(result)\n')
        executable.chmod(0o700)
        warning_bundle = work/'dump-warning'
        cli('backup',['--database',source,'--output',warning_bundle,*client_options],1,failure_env)
        assert (warning_bundle/'database.dump').stat().st_size > 0 and not (warning_bundle/'manifest.json').exists()
        assert fingerprints(source) == source_after and fingerprints(target) == before
        passed('successful dump with injected stderr warning cannot publish a backup manifest')
        report['status'], code = 'PASS', 0
    except Exception as error:
        report.update(status='FAIL',failureType=type(error).__name__,
            failureLocations=[Path(f.filename).name+':'+str(f.lineno) for f in traceback.extract_tb(error.__traceback__)])
        print('FAIL: PostgreSQL backup test; private diagnostics retained',flush=True)
    finally:
        for api in reversed(apis): api.close()
        report['ownedApisStopped'] = True
        try:
            for database, oid in owned.items():
                assert pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(database),'postgres') == oid
                pg.sql('DROP DATABASE '+identifier(database),'postgres')
            report['ownedDatabasesRemoved'] = True
        except Exception as error:
            report.update(status='FAIL',cleanupFailureType=type(error).__name__); code=1
        a.report.parent.mkdir(parents=True,exist_ok=True)
        a.report.write_text(json.dumps(report,indent=2)+'\n')
    print(report['status']+': '+str(len(report['cases']))+' database backup cases; full platform recovery remains separate',flush=True)
    return code


if __name__ == '__main__':
    raise SystemExit(main())
