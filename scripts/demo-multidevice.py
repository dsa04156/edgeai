"""Run synthetic AUTO/NODE/cancel STREAM→BATCH DAGs against the existing TLS deployment.

Creates only a labelled source-driver Pod/ConfigMap and public API demo records. It
never restarts shared API/broker/storage services. Read-only DB observations use
preselected idempotency UUIDs to recover only this invocation's Runs after errors.
"""
import argparse
import base64
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from image_identity import verify_image_id,manifest,platforms

ROOT = Path(__file__).resolve().parents[1]


def driver_failure(value):
    """Retain failure location, never exception messages, HTTP bodies or credentials."""
    if not isinstance(value,dict): return {'type':'InvalidFailureReport'}
    projected={}
    for field,pattern in [('type',r'[A-Za-z_][A-Za-z0-9_]{0,79}'),('phase',r'[a-z0-9_-]{1,128}')]:
        item=value.get(field)
        if isinstance(item,str) and re.fullmatch(pattern,item): projected[field]=item
    locations=value.get('locations')
    if isinstance(locations,str) and len(locations)<=4096:
        projected['locations']=','.join(location for location in locations.split(',')
            if re.fullmatch(r'[A-Za-z0-9_.-]{1,120}:[0-9]{1,7}',location))
    try: projected['runId']=str(uuid.UUID(value['runId']))
    except (KeyError,TypeError,ValueError,AttributeError): pass
    return projected


def wait(predicate, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(.3)
    raise AssertionError('Deployment stream demo boundary timed out')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context', required=True)
    parser.add_argument('--architecture',choices=('amd64','arm64'),default='amd64')
    parser.add_argument('--node',help='Ready node name for the explicit NODE case')
    parser.add_argument('--edge-only',action='store_true',help='Constrain all Runner and driver Pods to KubeEdge nodes')
    parser.add_argument('--runner-image', help='Explicit CI-tested project Runner digest')
    parser.add_argument('--runner-source', help='Full source commit paired with the explicit Runner digest')
    parser.add_argument('--report', type=Path, default=ROOT / '.tools/multidevice-demo.json')
    args = parser.parse_args()
    assert bool(args.runner_image) == bool(args.runner_source)
    if args.runner_image:
        assert re.fullmatch(r'ghcr\.io/dsa04156/edgeai-runner@sha256:[0-9a-f]{64}', args.runner_image)
        assert re.fullmatch(r'[0-9a-f]{40}', args.runner_source)
    k = ['kubectl', '--context', args.context, '--request-timeout=20s']
    def call(arguments, value=None):
        p = subprocess.run(k + arguments, input=None if value is None else json.dumps(value).encode(), capture_output=True, timeout=40)
        assert p.returncode == 0, 'Deployment demo Kubernetes operation failed; private output suppressed'
        return p.stdout
    def read(arguments):
        return json.loads(call(arguments))
    for namespace in ('edgeai', 'edgeai-runtimes'):
        ns = read(['get', 'namespace', namespace, '-o', 'json'])
        assert ns['metadata']['labels']['app.kubernetes.io/part-of'] == 'edgeai'
        assert ns['metadata']['labels']['app.kubernetes.io/managed-by'] == 'edgeai-bootstrap' and not ns['metadata'].get('deletionTimestamp')
    deployment = read(['-n', 'edgeai', 'get', 'deployment', 'edgeai-api', '-o', 'json'])
    assert any(e.get('configMapRef', {}).get('name') == 'edgeai-stream-config-v1' for e in deployment['spec']['template']['spec']['containers'][0]['envFrom']), 'Deployment STREAM component is not active'
    settings = read(['-n', 'edgeai', 'get', 'configmap', 'edgeai-stream-config-v1', '-o', 'json'])['data']
    assert all(settings[name] == 'true' for name in ('EDGEAI_STREAM_ENABLED', 'EDGEAI_STREAM_BINDINGS_ENABLED', 'EDGEAI_STREAM_RUNS_ENABLED', 'EDGEAI_API_TLS_ENABLED'))
    pin = json.loads((ROOT / 'deploy/kubernetes/overlays/dev/release.json').read_bytes())
    image = args.runner_image or 'ghcr.io/dsa04156/edgeai-runner@' + pin['runnerDigest']
    if args.architecture=='arm64':
        assert 'linux/arm64' in platforms(manifest(image)), 'ARM demo requires a verified multi-platform Runner index'
    runner_digest = image.split('@', 1)[1]
    root = 'edgeai-multidevice-' + uuid.uuid4().hex[:12]
    labels = {'app.kubernetes.io/part-of': 'edgeai', 'app.kubernetes.io/managed-by': 'edgeai-stream-demo', 'edgeai.io/test-id': root}
    keys = {name: str(uuid.uuid4()) for name in ('auto', 'node', 'cancel')}
    records, forwards, seen, run_ids = [], [], {}, set()
    report = {'scope': 'existing-deployment-stream-dag', 'testId': root, 'runnerImage': image, 'runnerSourceRevision': args.runner_source or pin['sourceRevision'],
              'architecture':args.architecture,'edgeOnly':args.edge_only,
              'apiImage': deployment['spec']['template']['spec']['containers'][0]['image'],
              'checkpointBarriers': [], 'observedPods': seen}
    def query(sql):
        return call(['-n', 'edgeai', 'exec', 'edgeai-postgres-0', '--', 'env', 'PGOPTIONS=-c default_transaction_read_only=on',
                     'psql', '-U', 'edgeai', '-d', 'edgeai', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', sql]).decode().strip()
    def own_runs():
        rows = query('SELECT id FROM edgeai.workflow_run WHERE idempotency_key IN(' + ','.join("'" + key + "'" for key in keys.values()) + ')')
        return {str(uuid.UUID(row)) for row in rows.splitlines()}
    def resources(run):
        assert str(uuid.UUID(run)) in run_ids
        return read(['-n', 'edgeai-runtimes', 'get', 'pods,jobs,secrets', '-l', 'edgeai.io/run-id=' + run, '-o', 'json'])['items']
    def observe(run):
        for pod in resources(run):
            if pod['kind'] != 'Pod' or not pod['spec'].get('nodeName'):
                continue
            meta, spec = pod['metadata'], pod['spec']
            assert meta['labels']['app.kubernetes.io/managed-by'] == 'edgeai-runtime-controller'
            statuses = pod.get('status', {}).get('containerStatuses', [])
            if not statuses or not statuses[0].get('imageID'):
                continue
            verify_image_id(image,statuses[0]['imageID'],'linux/'+args.architecture)
            assert spec['serviceAccountName'] == 'edgeai-runner' and spec['automountServiceAccountToken'] is False
            assert next(v for v in spec['volumes'] if v['name'] == 'edgeai-trust')['configMap']['name'] == settings['EDGEAI_RUNTIME_CA_CONFIG_MAP']
            if meta['uid'] not in seen:
                node = read(['get', 'node', spec['nodeName'], '-o', 'json'])
                assert node['status']['nodeInfo']['architecture']==args.architecture
                if args.edge_only:assert 'node-role.kubernetes.io/edge' in node['metadata']['labels']
                seen[meta['uid']] = {'runId': run, 'attemptId': meta['labels']['edgeai.io/attempt-id'], 'nodeUid': node['metadata']['uid'],
                    'nodeName':node['metadata']['name'],'architecture':args.architecture,'imageID': statuses[0]['imageID']}
    def create(kind, **fields):
        obj = {'apiVersion': 'v1', 'kind': kind, 'metadata': {'name': root, 'namespace': 'edgeai', 'labels': labels}, **fields}
        value = read_create(obj)
        records.append((kind.lower(), value['metadata']['uid']))
        return value
    def read_create(obj):
        return json.loads(call(['-n', 'edgeai', 'create', '-f', '-', '-o', 'json'], obj))
    public = None
    with tempfile.TemporaryDirectory(prefix='multidevice-trust-', dir=ROOT / '.tools') as work:
        ca_path = Path(work) / 'ca.crt'
        ca_path.write_text(read(['-n', 'edgeai', 'get', 'configmap', 'edgeai-runtime-ca-v1', '-o', 'json'])['data']['ca.crt'])
        tls = ssl.create_default_context(cafile=str(ca_path))
        def forward(service, port, health_path):
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0));local = sock.getsockname()[1]
            process = subprocess.Popen(k + ['-n', 'edgeai', 'port-forward', '--address', '127.0.0.1', 'svc/' + service, str(local) + ':' + str(port)],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            forwards.append(process)
            origin = 'https://localhost:' + str(local)
            def ready():
                assert process.poll() is None, 'Demo TLS port-forward stopped'
                try:
                    with urllib.request.urlopen(origin + health_path, context=tls, timeout=3) as response:
                        return response.status == 200
                except OSError:
                    return False
            wait(ready, 30)
            return origin
        try:
            origin = forward('edgeai-api', 18443, '/actuator/health/readiness')
            storage_origin = forward('edgeai-minio', 9000, '/minio/health/ready')
            base = read(['-n', 'edgeai', 'get', 'configmap', 'edgeai-config', '-o', 'json'])['data']
            secret = read(['-n', 'edgeai', 'get', 'secret', 'edgeai-runtime', '-o', 'json'])['data']
            password = base64.b64decode(secret['EDGEAI_API_PASSWORD']).decode()
            authorization = 'Basic ' + base64.b64encode((base['EDGEAI_API_USER'] + ':' + password).encode()).decode()
            client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), urllib.request.HTTPSHandler(context=tls))
            def public(path, cancel=False):
                headers = {'Authorization': authorization}
                if cancel:
                    headers.update({'X-CSRF-TOKEN': public('csrf')['token'], 'Content-Type': 'application/json'})
                req = urllib.request.Request(origin + '/api/v1/' + path, headers=headers, data=b'{}' if cancel else None, method='POST' if cancel else 'GET')
                try:
                    response = client.open(req, timeout=10)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    assert response.status in ((200, 409) if cancel else (200,)), 'Demo management request failed'
                    return json.loads(response.read(1048576))
            public('csrf')
            nodes = read(['get', 'nodes', '-o', 'json'])['items']
            node = next(n for n in nodes if n['status']['nodeInfo']['architecture'] == args.architecture and not n['spec'].get('unschedulable')
                        and (not args.node or n['metadata']['name']==args.node)
                        and (not args.edge_only or 'node-role.kubernetes.io/edge' in n['metadata']['labels'])
                        and not any(t['effect'] in ('NoSchedule', 'NoExecute') for t in n['spec'].get('taints', []))
                        and all(any(c['type']==kind and c['status']==status for c in n['status']['conditions'])
                            for kind,status in [('Ready','True'),('DiskPressure','False'),('MemoryPressure','False'),('PIDPressure','False')]))
            selector={'kubernetes.io/arch':args.architecture}
            if args.edge_only:selector['node-role.kubernetes.io/edge']=''
            config = {'origin': settings['EDGEAI_RUNTIME_CONTROL_PLANE_URL'], 'runnerImage': image, 'nodeId': node['metadata']['uid'],
                      'architecture':args.architecture,'runnerNodeSelector':selector,
                      'cases': list(keys), 'runKeys': keys, 'resourcePrefix': root,
                      'streamSpec': json.loads((ROOT / 'contracts/profiles/service-stream.example.json').read_bytes()),
                      'batchSpec': json.loads((ROOT / 'contracts/profiles/service-execution.example.json').read_bytes()),
                      'reportCommand': 'import time; time.sleep(2)\n' + (ROOT / 'runner/examples/stream_report.py').read_text()}
            create('ConfigMap', immutable=True, data={'driver.py': (ROOT / 'scripts/stream_acceptance.py').read_text(), 'config.json': json.dumps(config), 'ca.crt': ca_path.read_text()})
            pod = create('Pod', spec={'restartPolicy': 'Never', 'automountServiceAccountToken': False, 'enableServiceLinks': False,
                'nodeSelector': selector, 'securityContext': {'runAsNonRoot': True, 'runAsUser': 10001, 'runAsGroup': 10001, 'fsGroup': 10001, 'seccompProfile': {'type': 'RuntimeDefault'}},
                'containers': [{'name': 'source-driver', 'image': image, 'command': ['python3', '-B', '/scenario/driver.py'],
                    'env': [{'name': 'EDGEAI_API_USER', 'value': base['EDGEAI_API_USER']},
                            {'name': 'EDGEAI_API_PASSWORD', 'valueFrom': {'secretKeyRef': {'name': 'edgeai-runtime', 'key': 'EDGEAI_API_PASSWORD'}}},
                            {'name': 'SSL_CERT_FILE', 'value': '/scenario/ca.crt'}],
                    'securityContext': {'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True, 'capabilities': {'drop': ['ALL']}},
                    'resources': {'requests': {'cpu': '100m', 'memory': '128Mi'}, 'limits': {'cpu': '1', 'memory': '512Mi'}},
                    'volumeMounts': [{'name': 'scenario', 'mountPath': '/scenario', 'readOnly': True}, {'name': 'work', 'mountPath': '/work'}]}],
                'volumes': [{'name': 'scenario', 'configMap': {'name': root}}, {'name': 'work', 'emptyDir': {}}]})
            def started():
                value = read(['-n', 'edgeai', 'get', 'pod', root, '-o', 'json'])
                assert value['metadata']['uid'] == pod['metadata']['uid'] and value['status'].get('phase') not in ('Failed', 'Succeeded')
                return any(s.get('state', {}).get('running') for s in value['status'].get('containerStatuses', []))
            wait(started)
            machine=call(['-n','edgeai','exec',root,'--','python3','-c','import platform;print(platform.machine())']).decode().strip()
            assert machine=={'amd64':'x86_64','arm64':'aarch64'}[args.architecture]
            report['driverMachine']=machine
            completed = set()
            deadline = time.monotonic() + 600
            while time.monotonic() < deadline:
                expression = "import json;from pathlib import Path;print(json.dumps({n:json.loads(Path('/work',n+'.json').read_text()) for n in ('phase','failure','done') if Path('/work',n+'.json').exists()}))"
                state = read(['-n', 'edgeai', 'exec', root, '--', 'python3', '-c', expression])
                if 'failure' in state:
                    report['driverFailure']=driver_failure(state['failure'])
                    print('Deployment driver failure: '+json.dumps(report['driverFailure']),flush=True)
                    raise AssertionError('Deployment stream driver failed; projected evidence retained')
                current = state.get('phase')
                if current:
                    report['phase'] = current['phase']
                    run = str(uuid.UUID(current['runId']))
                    assert run in own_runs(), 'Driver reported a Run outside this invocation'
                    run_ids.add(run);observe(run)
                    phase = current['phase']
                    if phase not in completed and ('expectedStates' in current or phase.endswith('-done')):
                        if 'expectedStates' in current:
                            tasks = {name: str(uuid.UUID(current['tasks'][name])) for name in current['expectedStates']}
                            rows = query("SELECT DISTINCT ON(task_id) task_id,summary_json->>'stateSha256' FROM edgeai.stream_checkpoint WHERE run_id='" + run + "' AND task_id IN(" + ','.join("'" + t + "'" for t in tasks.values()) + ') ORDER BY task_id,serial DESC')
                            actual = dict(row.split('|') for row in rows.splitlines())
                            expected = {tasks[name]: hashlib.sha256(str(number).encode()).hexdigest() for name, number in current['expectedStates'].items()}
                            if actual != expected:
                                time.sleep(.3);continue
                            report['checkpointBarriers'].append({'phase': phase, 'runId': run, 'states': current['expectedStates'], 'sha256': actual})
                        else:
                            wait(lambda: not resources(run), 90)
                        call(['-n', 'edgeai', 'exec', root, '--', 'touch', '/work/' + phase + '.continue'])
                        completed.add(phase)
                        print('PASS: deployment stream boundary ' + phase, flush=True)
                if 'done' in state:
                    report['cases'] = state['done']['cases']
                    assert {case['case'] for case in report['cases']} == set(keys)
                    artifacts = []
                    for case in report['cases']:
                        for row in case['results']:
                            value = row['result'];observed = seen[value['producerPodUid']]
                            assert observed['runId'] == case['runId'] and observed['attemptId'] == value['attemptId']
                            if case['placement']['mode'] == 'NODE':
                                assert observed['nodeUid'] == case['placement']['nodeId']
                            artifacts.append({'artifact': value['artifacts'][0], 'expected': row['expected']})
                    assert len(artifacts) == 6 and len(seen) == 8
                    storage = read(['-n', 'edgeai', 'get', 'secret', 'edgeai-artifact-storage', '-o', 'json'])['data']
                    env = {**os.environ, 'EDGEAI_STORAGE_URL': storage_origin, 'NODE_EXTRA_CA_CERTS': str(ca_path),
                           **{name: base64.b64decode(storage[name]).decode() for name in ('EDGEAI_MINIO_USER', 'EDGEAI_MINIO_PASSWORD')}}
                    verified = subprocess.run(['node', 'scripts/verify-runtime-artifacts.mjs'], input=json.dumps(artifacts).encode(), env=env, capture_output=True, timeout=60)
                    assert verified.returncode == 0, 'Deployment TLS S3 results did not match; private details suppressed'
                    print(verified.stdout.decode().strip(), flush=True)
                    report['verifiedArtifacts'] = len(artifacts)
                    break
                time.sleep(.3)
            else:
                raise AssertionError('Deployment stream demo deadline exceeded')
        except BaseException as error:
            report['failureType'] = type(error).__name__
            report['status'] = 'FAIL'
            # CI uploads this exact path, including on failure. The prior random failure
            # filename was not included in the artifact and lost the driver boundary.
            args.report.parent.mkdir(parents=True,exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2) + '\n')
            (ROOT / '.tools' / (root + '-failure.json')).write_text(json.dumps(report, indent=2) + '\n')
            print('Deployment demo failure evidence saved: ' + root + '-failure.json', flush=True)
            raise
        finally:
            try:
                if public:
                    run_ids.update(own_runs())
                    for run in run_ids:
                        if public('workflow-runs/' + run)['run']['state'] not in ('SUCCEEDED', 'FAILED', 'CANCELLED'):
                            public('workflow-runs/' + run + '/cancel', cancel=True)
                        wait(lambda: not resources(run), 90)
                        wait(lambda: public('workflow-runs/' + run)['run']['state'] in ('SUCCEEDED', 'FAILED', 'CANCELLED') and
                             all(route['generation'] is None or route['generation']['closedAt'] for route in public('workflow-runs/' + run + '/streams')['items']), 90)
            finally:
                for proc in forwards:
                    proc.terminate();proc.wait(timeout=10)
                for kind, uid in reversed(records):
                    value = json.loads(call(['-n', 'edgeai', 'get', kind, root, '--ignore-not-found', '-o', 'json']) or b'null')
                    if value is None:
                        continue
                    assert value['metadata']['uid'] == uid and value['metadata']['labels']['edgeai.io/test-id'] == root
                    plural = {'pod': 'pods', 'configmap': 'configmaps'}[kind]
                    call(['delete', '--raw', '/api/v1/namespaces/edgeai/' + plural + '/' + root, '-f', '-'], {'apiVersion': 'v1', 'kind': 'DeleteOptions', 'preconditions': {'uid': uid}})
                    wait(lambda: not call(['-n', 'edgeai', 'get', kind, root, '--ignore-not-found', '-o', 'name']).strip(), 90)
    report['fixtureResourcesRemoved'] = True
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print('PASS: existing TLS deployment AUTO/NODE/cancel;8 actual Runner Pods,6 fixed S3 results; own transient resources reclaimed; demo history retained', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        import traceback
        locations = ','.join(Path(f.filename).name + ':' + str(f.lineno) for f in traceback.extract_tb(error.__traceback__))
        print('FAIL: deployment stream demo ' + type(error).__name__ + ' ' + locations, flush=True)
        raise SystemExit(1)
