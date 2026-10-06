"""Owned local API + isolated PostgreSQL management benchmark; reports partial failures."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.client
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import platform
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'simulator'))
from device_load import Client, observation, run_window


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--counts', type=int, nargs='+', default=[100, 300, 1000])
    p.add_argument('--seconds', type=int, default=60)
    p.add_argument('--interval', type=int, default=10)
    p.add_argument('--workers', type=int, default=32)
    mode = p.add_mutually_exclusive_group()
    mode.add_argument('--measure-only', action='store_true')
    mode.add_argument('--max-p95-ms', type=float)
    p.add_argument('--report', type=Path, default=ROOT/'.tools/device-load.json')
    a = p.parse_args()
    if a.counts != sorted(set(a.counts)) or not all(1 <= n <= 1000 for n in a.counts):
        p.error('counts must be increasing, distinct values between 1 and 1000')
    if not 1 <= a.interval <= 30 or not 2 <= a.seconds <= 600 or a.seconds % a.interval or not 1 <= a.workers <= 64:
        p.error('seconds must be 2..600 and a multiple of interval (1..30); workers must be 1..64')
    if a.max_p95_ms is not None and not 0 < a.max_p95_ms < 60000:
        p.error('p95 budget must be positive and less than 60000ms')
    env = os.environ.copy()
    native = ROOT/'.tools/postgres/usr/lib/postgresql/16/bin/psql'
    psql = shutil.which('psql') or (str(native) if native.exists() else None)
    jar = ROOT/'backend/app/build/libs/edgeai-control-plane.jar'
    if not psql or not jar.exists() or not all(env.get(k) for k in ('EDGEAI_DB_PASSWORD', 'EDGEAI_DB_USER', 'EDGEAI_DB_PORT')):
        print('BLOCKED: PostgreSQL client/connection environment and built API JAR required')
        return 2
    env['PGPASSWORD'] = env['EDGEAI_DB_PASSWORD']
    env['LD_LIBRARY_PATH'] = str(ROOT/'.tools/postgres/usr/lib/x86_64-linux-gnu')+':'+env.get('LD_LIBRARY_PATH', '')
    base = [psql, '-h', env.get('EDGEAI_DB_HOST', '127.0.0.1'), '-p', env['EDGEAI_DB_PORT'],
            '-U', env['EDGEAI_DB_USER'], '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1']
    identity = uuid.uuid4().hex
    database = 'edgeai_load_'+identity
    work = ROOT/'.tools'/database
    work.mkdir(mode=0o700)
    log = work/'api.log'
    log.touch(mode=0o600)
    report = {'scope': 'device-management-http', 'sourceMode': 'SYNTHETIC', 'status': 'RUNNING',
              'startedAt': datetime.now(timezone.utc).isoformat(),
              'fullScaleSequence': a.counts == [100, 300, 1000], 'stages': [],
              'performanceBudgetMs': a.max_p95_ms, 'performanceAcceptance': 'UNSET',
              'environment': {'os': platform.system(), 'architecture': platform.machine(), 'logicalCpus': os.cpu_count(),
                              'apiHeapMaxMiB': 512, 'apiPoolSize': 5, 'cpuQuota': None,
                              'httpIdleConnectionRecycleSeconds': 5,
                              'isolation': 'owned API process/database; host and PostgreSQL instance shared',
                              'apiJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest()},
              'apiResourceSamples': [], 'ownedApiStopped': False, 'ownedDatabaseRemoved': False}
    a.report.parent.mkdir(parents=True, exist_ok=True)
    def save():
        a.report.write_text(json.dumps(report, indent=2)+'\n')
    def sql(text, name=database):
        r = subprocess.run(base+['-d', name, '-c', text], env=env, capture_output=True, text=True, timeout=20)
        assert r.returncode == 0, 'Owned PostgreSQL operation failed; private details suppressed'
        return r.stdout.strip()
    created, db_oid = False, None
    api = client = monitor = None
    stopped = threading.Event()
    code = 1
    try:
        sql('CREATE DATABASE '+database, 'postgres'); created = True
        db_oid = sql("SELECT oid FROM pg_database WHERE datname='"+database+"'", 'postgres')
        report['environment']['postgresVersion'] = sql('SHOW server_version')
        if Path('/proc/meminfo').exists():
            report['environment']['hostMemoryBytes'] = int(next(line for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemTotal:')).split()[1])*1024
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        user, password = 'load-test', secrets.token_hex(24)
        api_env = {**env, 'EDGEAI_DB_NAME': database, 'EDGEAI_API_PORT': str(port), 'EDGEAI_BIND_ADDRESS': '127.0.0.1',
                   'EDGEAI_API_USER': user, 'EDGEAI_API_PASSWORD': password, 'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': '5'}
        api_env.update({key: 'false' for key in ['EDGEAI_RUNTIME_ENABLED', 'EDGEAI_VD_ENABLED', 'EDGEAI_STREAM_ENABLED',
                       'EDGEAI_STREAM_BINDINGS_ENABLED', 'EDGEAI_STREAM_RUNS_ENABLED', 'EDGEAI_KUBE_ENABLED',
                       'EDGEAI_REMOTE_ENABLED', 'EDGEAI_API_TLS_ENABLED']})
        with log.open('w') as output:
            api = subprocess.Popen(['java', '-Xmx512m', '-jar', str(jar)], env=api_env, stdout=output, stderr=output)
        deadline = time.monotonic()+90
        while True:
            assert api.poll() is None, 'Owned API exited; private log retained'
            try:
                with urllib.request.urlopen('http://127.0.0.1:'+str(port)+'/actuator/health/readiness', timeout=2) as r:
                    if r.status == 200: break
            except OSError:
                pass
            assert time.monotonic() < deadline, 'Owned API readiness timed out'
            time.sleep(.2)
        client = Client(port, 'Basic '+base64.b64encode((user+':'+password).encode()).decode())
        status, token, headers = client.request('GET', 'csrf')
        assert status == 200
        cookies = SimpleCookie()
        for key, value in headers:
            if key.lower() == 'set-cookie': cookies.load(value)
        client.csrf = token['token']; client.cookie = '; '.join(k+'='+v.value for k, v in cookies.items())
        monitor_start = time.monotonic()
        def sample_resources():
            while not stopped.is_set():
                try:
                    values = (Path('/proc')/str(api.pid)/'stat').read_text().rsplit(') ', 1)[1].split()
                    report['apiResourceSamples'].append({'seconds': time.monotonic()-monitor_start,
                        'cpuSeconds': (int(values[11])+int(values[12]))/os.sysconf('SC_CLK_TCK'),
                        'rssBytes': int(values[21])*os.sysconf('SC_PAGE_SIZE')})
                except (OSError, ValueError, IndexError):
                    report['apiResourceSamplingError'] = True
                    return
                stopped.wait(1)
        monitor = threading.Thread(target=sample_resources); monitor.start()
        profile = client.expect('POST', 'profiles/DEVICE', {'key': 'load-'+identity, 'version': '1.0.0',
            'spec': {'protocol': 'synthetic', 'purpose': 'management-load'}}, 201)
        # Exercise the packaged Basic/CSRF chain after successful authentication has warmed any cache.
        def guard_status(method, authorization=None):
            connection = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
            try:
                headers = {'Cookie': client.cookie}
                if authorization is not None: headers['Authorization'] = authorization
                connection.request(method, '/api/v1/platform', headers=headers)
                response = connection.getresponse(); response.read()
                return response.status
            finally:
                connection.close()
        assert guard_status('GET', client.authorization) == 200
        assert guard_status('GET') == 401
        assert guard_status('GET', 'Basic '+base64.b64encode((user+':wrong').encode()).decode()) == 401
        assert guard_status('GET', 'Basic '+base64.b64encode(('unknown:'+password).encode()).decode()) == 401
        assert guard_status('POST', client.authorization) == 403
        report['authenticationGuards'] = {'correctBasic': True, 'cookieAloneDenied': True,
            'wrongPasswordDenied': True, 'unknownUserDenied': True, 'csrfRequired': True}
        devices = []
        audit_expected = 5  # Profile publication, three read denials, one CSRF denial.
        def register(index):
            d = client.expect('POST', 'devices', {'key': 'load-'+identity+'-'+str(index).zfill(4), 'displayName': 'Synthetic management load',
                'profileVersionId': profile['id'], 'sourceMode': 'SYNTHETIC'}, 201)
            session = client.expect('POST', 'devices/'+d['id']+'/sessions', {'bootId': str(uuid.uuid4())}, 201)
            body = observation(session['id'], 0)
            client.expect('POST', 'devices/'+d['id']+'/observations', body, 201)
            return {'id': d['id'], 'session': session, 'sequence': 0, 'lastBody': body, 'observationCount': 1}
        for count in a.counts:
            began = time.monotonic()
            added_devices = count-len(devices)
            with ThreadPoolExecutor(max_workers=a.workers) as executor:
                devices.extend(executor.map(register, range(len(devices), count)))
            setup_seconds = time.monotonic()-began
            print('MEASURING: '+str(count)+' synthetic devices; observations '+str(count/a.interval)+' RPS plus detail reads', flush=True)
            stage_start = time.monotonic()-monitor_start
            stage = run_window(client, devices, a.seconds, a.interval, a.workers)
            stage['startedAfterMonitorSeconds'] = stage_start
            usage = [s for s in report['apiResourceSamples'] if s['seconds'] >= stage_start]
            assert len(usage) >= 2, 'At least two API resource samples required per load window'
            stage['apiResources'] = {'peakRssBytes': max(s['rssBytes'] for s in usage),
                'cpuCoreEquivalent': (usage[-1]['cpuSeconds']-usage[0]['cpuSeconds'])/(usage[-1]['seconds']-usage[0]['seconds'])}
            stage['setupAndWarmupSeconds'] = setup_seconds
            report['stages'].append(stage); save()
            summary = stage['summary']
            # Keep one read-only snapshot before a failed window aborts further probes.
            # Counts are observations at this instant, not a full integrity/acceptance verdict:
            # an HTTP response can precede its independently committed audit outcome.
            try:
                stage['postWindowDatabase'] = json.loads(sql("""
                    SELECT json_build_object(
                        'observedAt', clock_timestamp(),
                        'devices', (SELECT count(*) FROM edgeai.device),
                        'sessions', (SELECT count(*) FROM edgeai.device_session),
                        'observations', (SELECT count(*) FROM edgeai.device_observation),
                        'audit', (SELECT json_build_object(
                            'requests', count(*), 'outcomes', count(o.request_id),
                            'invalidActors', count(*) FILTER (WHERE o.actor_type='LOCAL_BASIC'
                                AND (o.actor_subject<>'load-test' OR o.subject_format<>'NAME')),
                            'handlerFailures', count(*) FILTER (WHERE o.disposition='HANDLER_FAILED'))
                            FROM edgeai.management_audit_request r
                            LEFT JOIN edgeai.management_audit_outcome o ON o.request_id=r.id),
                        'auditTransactions', (SELECT count(DISTINCT transaction_id) FROM (
                            SELECT xmin::text AS transaction_id FROM edgeai.management_audit_request
                            UNION ALL SELECT xmin::text FROM edgeai.management_audit_outcome) committed),
                        'bytes', pg_database_size(current_database()), 'connections', numbackends,
                        'deadlocks', deadlocks, 'commits', xact_commit, 'rollbacks', xact_rollback)
                    FROM pg_stat_database WHERE datname=current_database()
                """))
            except Exception as error:
                stage['postWindowDatabaseError'] = type(error).__name__
            save()
            assert summary['unexpected'] == 0 and summary['dropped'] == 0, 'Load request failures/drops; partial metrics retained'
            assert 'postWindowDatabaseError' not in stage, 'Post-window database evidence unavailable'
            listed, offset = [], 0
            while offset is not None:
                page = client.expect('GET', 'devices?limit=100&offset='+str(offset))
                listed.extend(d['id'] for d in page['items']); offset = page['nextOffset']
            assert len(listed) == count and set(listed) == {d['id'] for d in devices}, 'Device pagination differs'
            probes = devices[::10]
            for d in probes:
                path = 'devices/'+d['id']
                replay = client.expect('POST', path+'/observations', d['lastBody'], 200)
                assert replay['sequence'] == d['sequence']
                same = client.expect('POST', path+'/sessions', {'bootId': d['session']['bootId']}, 200)
                assert same['id'] == d['session']['id']
                session = client.expect('POST', path+'/sessions', {'bootId': str(uuid.uuid4())}, 201)
                assert session['epoch'] == d['session']['epoch']+1
                client.expect('POST', path+'/observations', d['lastBody'], 409)
                d['session'], d['sequence'] = session, 0
                d['lastBody'] = observation(session['id'], 0)
                client.expect('POST', path+'/observations', d['lastBody'], 201); d['observationCount'] += 1
            expected = {d['id']: [d['session']['id'], d['sequence'], d['observationCount']] for d in devices}
            actual = json.loads(sql("SELECT json_object_agg(d.id,json_build_array(s.id,s.last_sequence,(SELECT count(*) FROM edgeai.device_observation o WHERE o.device_id=d.id))) FROM edgeai.device d JOIN edgeai.device_session s ON s.device_id=d.id AND s.closed_at IS NULL"))
            assert actual == expected, 'Committed observations/sessions differ from acknowledged requests'
            assert sql("SELECT count(*) FROM edgeai.device_observation WHERE attributes->>'source'<>'management-load' OR (attributes->>'sequence')::bigint<>sequence") == '0'
            stage['integrity'] = {'devices': count, 'latestSessionsAndSequences': True, 'exactObservationCounts': True,
                                  'observations': sum(d['observationCount'] for d in devices), 'reconnectAndReplayProbes': len(probes)}
            audit_expected += 3*added_devices + count*(a.seconds//a.interval) + 5*len(probes)
            audit_deadline = time.monotonic()+5
            while True:
                audit = json.loads(sql("SELECT json_build_object('requests',count(*),'outcomes',count(o.request_id),'unauthenticated',count(*) FILTER (WHERE o.actor_type='UNAUTHENTICATED'),'invalidActors',count(*) FILTER (WHERE o.actor_type='LOCAL_BASIC' AND (o.actor_subject<>'load-test' OR o.subject_format<>'NAME')),'handlerFailures',count(*) FILTER (WHERE o.disposition='HANDLER_FAILED')) FROM edgeai.management_audit_request r LEFT JOIN edgeai.management_audit_outcome o ON o.request_id=r.id"))
                if audit['outcomes'] == audit_expected or time.monotonic() >= audit_deadline:
                    break
                time.sleep(.05)
            assert audit == {'requests': audit_expected, 'outcomes': audit_expected, 'unauthenticated': 4,
                             'invalidActors': 0, 'handlerFailures': 0}, 'Durable HTTP audit differs from acknowledged writes and denials'
            stage['integrity']['audit'] = audit
            stage['database'] = json.loads(sql("SELECT json_build_object('bytes',pg_database_size(current_database()),'connections',numbackends,'deadlocks',deadlocks,'commits',xact_commit,'rollbacks',xact_rollback) FROM pg_stat_database WHERE datname=current_database()"))
            stage['database']['auditTransactions'] = int(sql("SELECT count(DISTINCT transaction_id) FROM (SELECT xmin::text AS transaction_id FROM edgeai.management_audit_request UNION ALL SELECT xmin::text FROM edgeai.management_audit_outcome) committed"))
            save()
            print('PASS: '+str(count)+' device correctness; scheduled p95='+str(round(summary['scheduledLatencyMs']['p95'], 2))+'ms; no request errors or drops', flush=True)
        assert report['apiResourceSamples'] and not report.get('apiResourceSamplingError'), 'API resource measurements unavailable'
        if a.max_p95_ms is not None:
            accepted = all(s['summary']['scheduledLatencyMs']['p95'] <= a.max_p95_ms for s in report['stages'])
            report['performanceAcceptance'] = 'PASSED_CLI_BUDGET' if accepted else 'FAILED_CLI_BUDGET'
            assert accepted, 'Observed scheduled p95 exceeds the supplied budget'
            report['status'], code = ('PASS' if report['fullScaleSequence'] else 'PARTIAL_SCALE'), (0 if report['fullScaleSequence'] else 2)
        elif a.measure_only:
            report['status'], code = 'MEASURED', 0
        else:
            report['status'], code = 'PENDING_ACCEPTANCE', 2
    except Exception as error:
        report['status'] = 'FAIL'
        import traceback
        report['failure'] = {'type': type(error).__name__, 'locations': ','.join(Path(f.filename).name+':'+str(f.lineno) for f in traceback.extract_tb(error.__traceback__))}
        print('FAIL: device load; sanitized report and private API log retained', flush=True)
    finally:
        stopped.set()
        if monitor: monitor.join(5)
        if client: client.close()
        try:
            if api:
                api.terminate()
                try: api.wait(15)
                except subprocess.TimeoutExpired: api.kill(); api.wait(5)
                report['ownedApiStopped'] = True
            if created:
                assert db_oid is not None and sql("SELECT oid FROM pg_database WHERE datname='"+database+"'", 'postgres') == db_oid
                sql('DROP DATABASE '+database+' WITH (FORCE)', 'postgres')
                report['ownedDatabaseRemoved'] = True
        except Exception as error:
            report['status'], code = 'FAIL', 1
            report['cleanupFailure'] = type(error).__name__
            print('FAIL: owned resource cleanup; sanitized report retained', flush=True)
        save()
    print(report['status']+': device management load; performance acceptance '+report['performanceAcceptance'], flush=True)
    return code


if __name__ == '__main__':
    raise SystemExit(main())
