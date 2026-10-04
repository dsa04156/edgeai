"""Packaged API image or current JAR, real TLS services and actual Kubernetes Runner DAG.

Creates only uniquely labelled, UID-owned temporary resources in the previously
bootstrapped project namespaces. Existing deployment, database and storage remain.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.request
import uuid
from vd_acceptance import ROOT, wait
from vd_stream_acceptance import CASES as VD_CASES
from vd_stream_kubernetes import VDStreamObserver
from image_identity import verify_image_id

CASES = ('auto', 'node', 'recover', 'finalizer', 'cancel', 'offload', 'offload-cancel', 'offload-automatic', 'offload-automatic-cancel', 'placement', 'placement-recover')


def driver_failure_evidence(value):
    """Keep only driver-generated phase/type/code locations, never exception text or HTTP bodies."""
    patterns = {'phase': r'[a-zA-Z0-9-]{1,128}', 'type': r'[a-zA-Z][a-zA-Z0-9]{0,79}',
                'locations': r'[a-zA-Z0-9_.-]+:[0-9]+(?:,[a-zA-Z0-9_.-]+:[0-9]+)*',
                'runId': r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}'}
    value = value if isinstance(value, dict) else {}
    result = {key: field if isinstance(field := value.get(key), str) and len(field) <= 2000 and re.fullmatch(pattern, field)
              else None for key, pattern in patterns.items()}
    transition = value.get('attemptTransition')
    if isinstance(transition, dict) and transition.get('task') in ('root', 'sink') and transition.get('expectedOldState') in ('FAILED', 'OFFLOADED'):
        attempts = transition.get('attempts')
        if isinstance(attempts, list) and len(attempts) <= 4 and all(isinstance(a, dict) for a in attempts):
            result['attemptTransition'] = {'task': transition['task'], 'expectedOldState': transition['expectedOldState'],
                'attempts': [{'id': a.get('id') if isinstance(a.get('id'), str) and re.fullmatch(patterns['runId'], a['id']) else None,
                    'state': a.get('state') if a.get('state') in ('QUEUED', 'DISPATCHING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'OFFLOADED') else None,
                    **{k: a[k] if type(a.get(k)) is int and 0 < a[k] <= 9223372036854775807 else None for k in ('number', 'epoch')}}
                    for a in attempts]}
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--context', required=True)
    parser.add_argument('--runner-image', help='Explicit CI-tested ghcr.io/dsa04156/edgeai-runner@sha256 digest')
    parser.add_argument('--runner-source', help='Full source commit for the explicit CI-tested Runner image')
    parser.add_argument('--api-image', help='Built project API commit tag or published digest; uses its packaged JAR')
    parser.add_argument('--api-source', help='Full source commit for the explicit API image')
    parser.add_argument('--minio-image', help='Explicit CI-tested project MinIO digest')
    parser.add_argument('--report', type=Path, default=ROOT / '.tools/stream-kubernetes.json')
    parser.add_argument('--cases', nargs='+', choices=CASES + VD_CASES, default=list(CASES + VD_CASES), help='Explicit subset for diagnosis; CI defaults to every case')
    args = parser.parse_args()
    if len(args.cases) != len(set(args.cases)):
        parser.error('Duplicate acceptance cases')
    if bool(args.runner_image) != bool(args.runner_source):
        parser.error('--runner-image and --runner-source must be supplied together')
    if args.runner_image and (not re.fullmatch(r'ghcr\.io/dsa04156/edgeai-runner@sha256:[0-9a-f]{64}', args.runner_image)
            or not re.fullmatch(r'[0-9a-f]{40}', args.runner_source)):
        parser.error('Runner override requires the project image digest and full source commit')
    if bool(args.api_image) != bool(args.api_source):
        parser.error('--api-image and --api-source must be supplied together')
    if args.api_image and (not re.fullmatch(r'ghcr\.io/dsa04156/edgeai-api(?:@sha256:[0-9a-f]{64}|:sha-[0-9a-f]{40})', args.api_image)
            or not re.fullmatch(r'[0-9a-f]{40}', args.api_source)
            or ':sha-' in args.api_image and not args.api_image.endswith(':sha-' + args.api_source)):
        parser.error('API override requires a project image pinned to its full source commit or digest')
    if args.minio_image and not re.fullmatch(r'ghcr\.io/dsa04156/edgeai-minio@sha256:[0-9a-f]{64}', args.minio_image):
        parser.error('MinIO override requires the project image digest')
    k = ['kubectl', '--context', args.context, '--request-timeout=20s']
    records, forwards = [], []
    root = 'edgeai-stream-check-' + uuid.uuid4().hex[:12]
    labels = {'app.kubernetes.io/part-of': 'edgeai', 'app.kubernetes.io/managed-by': 'edgeai-stream-test', 'edgeai.io/test-id': root}
    api, db, storage, broker, driver = [root + '-' + name for name in ('api', 'db', 'storage', 'broker', 'driver')]
    run_ids = set()
    vd_ids = set()
    seen = {}
    held_jobs, private_producers = {}, {}
    succeeded = False
    db_ready = False
    snapshot = {'scope': 'real-kubernetes-stream-dag', 'testId': root, 'cases': [], 'checkpointBarriers': [], 'observedPods': seen}
    owner_path = ROOT / '.tools' / (root + '-owner.json')

    def save_owner():
        owner_path.write_text(json.dumps({'context': args.context, 'testId': root, 'resources': records,
            'heldJobs': [{'name': name, 'uid': uid, 'runId': run} for (name, uid), run in held_jobs.items()]}) + '\n')
        owner_path.chmod(0o600)

    def call(arguments, value=None, raw=None, timeout=40):
        data = raw if raw is not None else None if value is None else json.dumps(value).encode()
        result = subprocess.run(k + arguments, input=data, capture_output=True, timeout=timeout)
        assert result.returncode == 0, 'Owned stream Kubernetes operation failed; private output suppressed'
        return result.stdout

    def read(arguments):
        return json.loads(call(arguments))

    for namespace in ('edgeai', 'edgeai-runtimes'):
        meta = read(['get', 'namespace', namespace, '-o', 'json'])['metadata']
        assert meta.get('labels', {}).get('app.kubernetes.io/part-of') == 'edgeai'
        assert meta.get('labels', {}).get('app.kubernetes.io/managed-by') == 'edgeai-bootstrap' and not meta.get('deletionTimestamp')

    def create(kind, name, namespace='edgeai', **fields):
        obj = {'apiVersion': 'v1', 'kind': kind, 'metadata': {'name': name, 'namespace': namespace,
               'labels': {**labels, 'edgeai.io/test-resource': name}}, **fields}
        value = read_create(obj)
        records.append((kind.lower(), namespace, name, value['metadata']['uid']))
        save_owner()
        return value

    def read_create(value):
        return json.loads(call(['create', '-f', '-', '-o', 'json'], value))

    def remove(kind, namespace, name, uid, grace_seconds=None):
        current = json.loads(call(['-n', namespace, 'get', kind, name, '--ignore-not-found', '-o', 'json']) or b'null')
        if current is None:
            return
        assert current['metadata']['uid'] == uid and current['metadata'].get('labels', {}).get('edgeai.io/test-id') == root
        plural = {'pod': 'pods', 'secret': 'secrets', 'service': 'services', 'configmap': 'configmaps'}[kind]
        options = {'apiVersion': 'v1', 'kind': 'DeleteOptions', 'preconditions': {'uid': uid}}
        if grace_seconds is not None:
            options['gracePeriodSeconds'] = grace_seconds
        call(['delete', '--raw', '/api/v1/namespaces/' + namespace + '/' + plural + '/' + name, '-f', '-'], options)
        wait(lambda: not call(['-n', namespace, 'get', kind, name, '--ignore-not-found', '-o', 'name']).strip(), 90, 'Owned fixture resource did not terminate')

    def hold_job(name, uid, run, enabled):
        # Only Jobs created by this invocation's isolated API; never alter a shared node.
        assert run in run_ids
        finalizer = 'edgeai.io/stream-acceptance-hold'
        for _ in range(6):
            obj = json.loads(call(['-n', 'edgeai-runtimes', 'get', 'job', name, '--ignore-not-found', '-o', 'json']) or b'null')
            if obj is None:
                assert not enabled, 'Owned source Job vanished before installing the barrier'
                held_jobs.pop((name, uid), None);save_owner();return
            meta = obj['metadata']
            assert meta['uid'] == uid and meta['labels']['edgeai.io/run-id'] == run
            assert meta['labels']['app.kubernetes.io/managed-by'] == 'edgeai-runtime-controller'
            before = meta.get('finalizers', [])
            if enabled:
                assert not meta.get('deletionTimestamp') and finalizer not in before
                after = before + [finalizer]
            else:
                after = [value for value in before if value != finalizer]
            patch = [{'op': 'test', 'path': '/metadata/uid', 'value': uid},
                     {'op': 'test', 'path': '/metadata/resourceVersion', 'value': meta['resourceVersion']},
                     {'op': 'add', 'path': '/metadata/finalizers', 'value': after}]
            result = subprocess.run(k + ['-n', 'edgeai-runtimes', 'patch', 'job', name, '--type=json', '--patch-file=/dev/stdin'],
                input=json.dumps(patch).encode(), capture_output=True, timeout=30)
            if result.returncode == 0:
                if enabled:
                    held_jobs[(name, uid)] = run
                else:
                    held_jobs.pop((name, uid), None)
                save_owner();return
        raise AssertionError('Owned source Job barrier changed concurrently')

    def capture_producer(pod):
        code = "import sys;sys.path.insert(0,'/opt/edgeai');from edgeai_runner.main import Runner;import json;r=Runner();print(json.dumps({'attemptId':r.attempt,'podUid':r.pod,'epoch':r.epoch,'claim':r.token,'podToken':r.pod_token_file.read_text().strip()}))"
        return json.loads(call(['-n', 'edgeai-runtimes', 'exec', pod['metadata']['name'], '--', 'python3', '-c', code]))

    def check_old_producer(value, origin):
        # Credentials stay in private pipes/memory. Only the HTTP status is returned.
        code = """import json,sys,urllib.request,urllib.error
v=json.load(sys.stdin);c=v['credentials']
r=urllib.request.Request(v['origin']+'/internal/v1/attempts/'+c['attemptId']+'/commit',method='POST',
 data=json.dumps({'epoch':c['epoch'],'podUid':c['podUid'],'outputs':[]}).encode(),
 headers={'Authorization':'Bearer '+c['claim'],'X-EdgeAI-Pod-Token':c['podToken'],'Content-Type':'application/json'})
try: response=urllib.request.urlopen(r,timeout=10)
except urllib.error.HTTPError as error: response=error
with response: print(response.status)
"""
        status = int(call(['-n', 'edgeai', 'exec', '-i', driver, '--', 'python3', '-c', code], {'origin': origin, 'credentials': value}))
        assert status in (401, 409), 'Previous producer was not fenced'
        return status

    def running(name, ready=False):
        pod = read(['-n', 'edgeai', 'get', 'pod', name, '-o', 'json'])
        assert pod['status'].get('phase') not in ('Failed', 'Succeeded'), 'Owned fixture Pod exited early: ' + name
        if ready:
            return any(c.get('type') == 'Ready' and c.get('status') == 'True' for c in pod['status'].get('conditions', []))
        return any(c.get('state', {}).get('running') for c in pod['status'].get('containerStatuses', []))

    def env(name, value):
        return {'name': name, 'value': value}

    def secret_env(name, key=None):
        return {'name': name, 'valueFrom': {'secretKeyRef': {'name': root, 'key': key or name}}}

    def pod_spec(containers, volumes, uid=10001, account=None):
        spec = {'restartPolicy': 'Never', 'automountServiceAccountToken': account is not None, 'enableServiceLinks': False,
                'nodeSelector': {'kubernetes.io/arch': 'amd64'},
                'securityContext': {'runAsNonRoot': True, 'runAsUser': uid, 'runAsGroup': uid, 'fsGroup': uid, 'seccompProfile': {'type': 'RuntimeDefault'}},
                'containers': containers, 'volumes': volumes}
        if account:
            spec['serviceAccountName'] = account
        return spec

    security = {'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True, 'capabilities': {'drop': ['ALL']}}
    credentials = {'EDGEAI_API_USER': 'stream-test', 'EDGEAI_API_PASSWORD': secrets.token_hex(24), 'EDGEAI_DB_PASSWORD': secrets.token_hex(24),
                   'EDGEAI_MINIO_USER': 'stream-test', 'EDGEAI_MINIO_PASSWORD': secrets.token_hex(24),
                   'runner.key': secrets.token_hex(32), 'device.key': secrets.token_hex(32), 'principal.key': secrets.token_hex(32), 'admin.password': secrets.token_hex(24)}
    pin = json.loads((ROOT / 'deploy/kubernetes/overlays/dev/release.json').read_bytes())
    runner_image = args.runner_image or 'ghcr.io/dsa04156/edgeai-runner@' + pin['runnerDigest']
    runner_digest = runner_image.split('@', 1)[1]
    jar = None if args.api_image else (ROOT / 'backend/app/build/libs/edgeai-control-plane.jar').read_bytes()
    api_image = args.api_image or 'ghcr.io/dsa04156/edgeai-api@' + pin['apiDigest']
    minio_image = args.minio_image or 'ghcr.io/dsa04156/edgeai-minio@' + pin['minioDigest']
    snapshot.update(apiJarSha256=None if jar is None else hashlib.sha256(jar).hexdigest(),
                    apiArtifactMode='packaged-image' if jar is None else 'local-jar', apiImage=api_image,
                    imageSourceRevision=args.api_source or pin['sourceRevision'], minioImage=minio_image,
                    runnerSourceRevision=args.runner_source or pin['sourceRevision'], runnerImage=runner_image)
    report_path = args.report

    with tempfile.TemporaryDirectory(prefix='stream-kube-', dir=ROOT / '.tools') as temporary:
        temp = Path(temporary)
        temp.chmod(0o700)
        def local(command, data=None):
            result = subprocess.run(command, input=data, capture_output=True, timeout=60)
            assert result.returncode == 0, 'Private TLS fixture preparation failed; details suppressed'
            return result.stdout
        sans = ['DNS:localhost'] + ['DNS:' + name + '.edgeai.svc' for name in (api, storage, broker)]
        local(['openssl', 'req', '-x509', '-nodes', '-newkey', 'rsa:2048', '-days', '1', '-subj', '/CN=edgeai-stream-test',
               '-addext', 'subjectAltName=' + ','.join(sans), '-keyout', str(temp / 'server.key'), '-out', str(temp / 'server.crt')])
        (temp / 'server.key').chmod(0o600)
        keytool = str(ROOT / '.tools/jdk/bin/keytool') if (ROOT / '.tools/jdk/bin/keytool').exists() else 'keytool'
        local([keytool, '-importcert', '-noprompt', '-alias', 'edgeai-test', '-file', str(temp / 'server.crt'),
               '-keystore', str(temp / 'trust.p12'), '-storetype', 'PKCS12', '-storepass', 'changeit'])
        control = os.environ.get('EDGEAI_MOSQUITTO_CTRL_BINARY') or shutil.which('mosquitto_ctrl') or str(ROOT / '.tools/mosquitto/usr/bin/mosquitto_ctrl')
        local([control, 'dynsec', 'init', str(temp / 'dynamic-security.json'), 'edgeai-admin'], (credentials['admin.password'] + '\n' + credentials['admin.password'] + '\n').encode())
        dynamic = json.loads((temp / 'dynamic-security.json').read_bytes())
        dynamic['defaultACLAccess'] = dict.fromkeys(('publishClientSend', 'publishClientReceive', 'subscribe', 'unsubscribe'), False)
        credentials.update({'server.crt': (temp / 'server.crt').read_text(), 'server.key': (temp / 'server.key').read_text(), 'dynamic-security.json': json.dumps(dynamic)})
        ca = str(temp / 'server.crt')
        tls = ssl.create_default_context(cafile=ca)
        bootstrap = {'name': 'bootstrap', 'secret': {'secretName': root, 'defaultMode': 288}}
        def secret_volume(keys):
            return {**bootstrap, 'secret': {**bootstrap['secret'], 'items': [{'key': key, 'path': key} for key in keys]}}
        mounts = [{'name': 'tmp', 'mountPath': '/tmp'}, {'name': 'bootstrap', 'mountPath': '/bootstrap', 'readOnly': True}]

        def forward(name, port, path):
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0))
                local_port = sock.getsockname()[1]
            process = subprocess.Popen(k + ['-n', 'edgeai', 'port-forward', '--address', '127.0.0.1', 'svc/' + name, f'{local_port}:{port}'],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            forwards.append(process)
            origin = 'https://localhost:' + str(local_port)
            def ready():
                assert process.poll() is None, 'Owned TLS port-forward stopped'
                try:
                    with urllib.request.urlopen(origin + path, context=tls, timeout=2) as response:
                        return response.status == 200
                except OSError:
                    return False
            wait(ready, 40, 'Owned TLS service was not ready')
            return origin

        def query(sql):
            return call(['-n', 'edgeai', 'exec', db, '--', 'psql', '-U', 'edgeai', '-d', 'edgeai', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', sql]).decode().strip()

        held_vds = {}
        def hold_vd(identity, enabled):
            identity = str(uuid.UUID(identity))
            application = 'edgeai-vd-hold-' + identity
            if enabled:
                assert identity not in held_vds and identity in vd_ids
                process = subprocess.Popen(k + ['-n', 'edgeai', 'exec', '-i', db, '--', 'psql', '-U', 'edgeai', '-d', 'edgeai', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1'],
                    stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                held_vds[identity] = process
                # Permit the offload member's VD foreign-key check while blocking the poll row mutex.
                process.stdin.write(("SET application_name='" + application + "'; BEGIN; SELECT id FROM edgeai.virtual_device WHERE id='" + identity + "' FOR NO KEY UPDATE;\n").encode())
                process.stdin.flush()
                def locked():
                    assert process.poll() is None, 'Owned VD barrier session exited'
                    return query("SELECT count(*) FROM pg_stat_activity WHERE application_name='" + application + "' AND state='idle in transaction'") == '1'
                wait(locked, 15, 'Owned VD poll receipt barrier did not acquire')
            else:
                process = held_vds[identity]
                if process.poll() is None:
                    process.communicate(b'ROLLBACK;\n', timeout=15)
                assert process.returncode == 0, 'Owned VD barrier did not release'
                held_vds.pop(identity)

        def resources(run):
            uuid.UUID(run)
            return read(['-n', 'edgeai-runtimes', 'get', 'pods,jobs,secrets', '-l', 'edgeai.io/run-id=' + run, '-o', 'json'])['items']

        def vd_resources(vd):
            uuid.UUID(vd)
            return read(['-n', 'edgeai-runtimes', 'get', 'pods,jobs,secrets', '-l', 'edgeai.io/vd-id=' + vd, '-o', 'json'])['items']

        def observe(run):
            for pod in resources(run):
                if pod['kind'] != 'Pod' or not pod['spec'].get('nodeName'):
                    continue
                meta, spec = pod['metadata'], pod['spec']
                statuses = pod['status'].get('containerStatuses', [])
                if not statuses or not statuses[0].get('imageID'):
                    continue
                verify_image_id(runner_image,statuses[0]['imageID'])
                assert spec['serviceAccountName'] == 'edgeai-runner' and spec['automountServiceAccountToken'] is False
                container = spec['containers'][0]
                assert {'name': 'SSL_CERT_FILE', 'value': '/var/run/edgeai-trust/ca.crt'} in container['env']
                trust = next(v for v in spec['volumes'] if v['name'] == 'edgeai-trust')['configMap']
                assert trust['name'] == root + '-ca' and trust['items'] == [{'key': 'ca.crt', 'path': 'ca.crt'}]
                assert container['securityContext']['readOnlyRootFilesystem'] is True
                if meta['uid'] not in seen:
                    node = read(['get', 'node', spec['nodeName'], '-o', 'json'])
                    seen[meta['uid']] = {'runId': run, 'attemptId': meta['labels']['edgeai.io/attempt-id'], 'taskId': meta['labels']['edgeai.io/task-id'],
                                         'nodeName': spec['nodeName'], 'nodeUid': node['metadata']['uid'], 'imageID': statuses[0]['imageID'], 'runnerEvents': []}
                logs = subprocess.run(k + ['-n', 'edgeai-runtimes', 'logs', meta['name'], '--tail=10'], capture_output=True, text=True, timeout=25)
                if logs.returncode == 0:
                    seen[meta['uid']]['runnerEvents'] = sorted(set(seen[meta['uid']]['runnerEvents']) | {line for line in logs.stdout.splitlines() if re.fullmatch(r'RUNNER_(WORKLOAD_START|RESULT_COMMITTED|FAILED [A-Z_]+)', line)})

        try:
            create('Secret', root, stringData=credentials, data={'trust.p12': base64.b64encode((temp / 'trust.p12').read_bytes()).decode()})
            create('ConfigMap', root + '-ca', namespace='edgeai-runtimes', immutable=True, data={'ca.crt': credentials['server.crt']})
            for name, port in ((api, 18443), (db, 5432), (storage, 9000), (broker, 8883)):
                create('Service', name, spec={'selector': {'edgeai.io/test-resource': name}, 'ports': [{'port': port, 'targetPort': port}]})
            create('Pod', db, spec=pod_spec([{'name': 'postgres', 'image': 'public.ecr.aws/docker/library/postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24',
                'env': [env('POSTGRES_DB', 'edgeai'), env('POSTGRES_USER', 'edgeai'), env('PGDATA', '/var/lib/postgresql/data/pgdata'), secret_env('POSTGRES_PASSWORD', 'EDGEAI_DB_PASSWORD')],
                'securityContext': {**security, 'readOnlyRootFilesystem': False}, 'resources': {'requests': {'cpu': '100m', 'memory': '128Mi'}, 'limits': {'cpu': '1', 'memory': '512Mi'}},
                'readinessProbe': {'exec': {'command': ['pg_isready', '-U', 'edgeai', '-d', 'edgeai']}, 'periodSeconds': 2},
                'volumeMounts': [{'name': 'data', 'mountPath': '/var/lib/postgresql/data'}]}], [{'name': 'data', 'emptyDir': {}}], uid=70))
            wait(lambda: running(db, True), 150, 'Owned PostgreSQL was not ready')
            db_ready = True
            create('Pod', storage, spec=pod_spec([{'name': 'minio', 'image': minio_image,
                'command': ['sh', '-c', 'umask 077; mkdir -p /tmp/certs; cp /bootstrap/server.crt /tmp/certs/public.crt; cp /bootstrap/server.key /tmp/certs/private.key; exec minio server /data --certs-dir /tmp/certs --console-address :9001'],
                'env': [secret_env('MINIO_ROOT_USER', 'EDGEAI_MINIO_USER'), secret_env('MINIO_ROOT_PASSWORD', 'EDGEAI_MINIO_PASSWORD')], 'securityContext': security,
                'resources': {'requests': {'cpu': '100m', 'memory': '256Mi'}, 'limits': {'cpu': '1', 'memory': '1Gi'}},
                'readinessProbe': {'httpGet': {'path': '/minio/health/ready', 'port': 9000, 'scheme': 'HTTPS'}, 'periodSeconds': 2},
                'volumeMounts': mounts + [{'name': 'data', 'mountPath': '/data'}]}], [secret_volume(('server.crt', 'server.key')), {'name': 'tmp', 'emptyDir': {}}, {'name': 'data', 'emptyDir': {}}]))
            wait(lambda: running(storage, True), 150, 'Owned native TLS MinIO was not ready')
            broker_config = 'listener 8883 0.0.0.0\nallow_anonymous false\nplugin /usr/lib/mosquitto_dynamic_security.so\nplugin_opt_config_file /tmp/broker/dynamic-security.json\ncertfile /tmp/broker/server.crt\nkeyfile /tmp/broker/server.key\npersistence false\nmax_packet_size 1048576\nmax_queued_messages 128\nmax_queued_bytes 16777216\nmax_inflight_messages 32\nlog_dest stderr\n'
            create('ConfigMap', root + '-broker', immutable=True, data={'mosquitto.conf': broker_config})
            create('Pod', broker, spec=pod_spec([{'name': 'mosquitto', 'image': 'eclipse-mosquitto:2@sha256:38c0da4f2ef84284d47b3b3eeea1cb3bdeabe81ee10caf0cd5c5ff61ee3ea408',
                'command': ['sh', '-c', 'umask 077; mkdir -p /tmp/broker; cp /bootstrap/dynamic-security.json /bootstrap/server.crt /bootstrap/server.key /tmp/broker/; exec /usr/sbin/mosquitto -c /config/mosquitto.conf'],
                'securityContext': security, 'resources': {'requests': {'cpu': '100m', 'memory': '64Mi'}, 'limits': {'cpu': '1', 'memory': '256Mi'}},
                'readinessProbe': {'tcpSocket': {'port': 8883}, 'periodSeconds': 2}, 'volumeMounts': mounts + [{'name': 'config', 'mountPath': '/config', 'readOnly': True}]}],
                [secret_volume(('server.crt', 'server.key', 'dynamic-security.json')), {'name': 'tmp', 'emptyDir': {}}, {'name': 'config', 'configMap': {'name': root + '-broker'}}], uid=1883))
            wait(lambda: running(broker, True), 150, 'Owned TLS broker was not ready')
            storage_url = forward(storage, 9000, '/minio/health/ready')
            verify_env = {**os.environ, 'EDGEAI_STORAGE_URL': storage_url, 'EDGEAI_MINIO_USER': credentials['EDGEAI_MINIO_USER'],
                          'EDGEAI_MINIO_PASSWORD': credentials['EDGEAI_MINIO_PASSWORD'], 'NODE_EXTRA_CA_CERTS': ca}
            result = subprocess.run(['node', 'scripts/bootstrap-artifact-bucket.mjs'], env=verify_env, capture_output=True, timeout=40)
            assert result.returncode == 0, 'TLS artifact bucket bootstrap failed; private output suppressed'
            api_origin = 'https://' + api + '.edgeai.svc:18443'
            api_env = [env('EDGEAI_BIND_ADDRESS', '0.0.0.0'), env('EDGEAI_API_PORT', '18080'), env('EDGEAI_DB_HOST', db + '.edgeai.svc'), env('EDGEAI_DB_PORT', '5432'),
                env('EDGEAI_API_TLS_ENABLED', 'true'), env('EDGEAI_API_TLS_PORT', '18443'),
                env('EDGEAI_API_TLS_CERTIFICATE_FILE', '/tmp/identity/server.crt'), env('EDGEAI_API_TLS_PRIVATE_KEY_FILE', '/tmp/identity/server.key'),
                env('EDGEAI_KUBE_ENABLED', 'true'), env('EDGEAI_RUNTIME_ENABLED', 'true'), env('EDGEAI_RUNTIME_NAMESPACE', 'edgeai-runtimes'), env('EDGEAI_RUNTIME_CONTROL_PLANE_URL', api_origin),
                env('EDGEAI_RUNTIME_CA_CONFIG_MAP', root + '-ca'), env('EDGEAI_KUBE_API_URL', 'https://kubernetes.default.svc'),
                env('EDGEAI_KUBE_TOKEN_FILE', '/var/run/secrets/kubernetes.io/serviceaccount/token'), env('EDGEAI_KUBE_CA_FILE', '/var/run/secrets/kubernetes.io/serviceaccount/ca.crt'),
                env('EDGEAI_RUNNER_KEY_FILE', '/tmp/identity/runner.key'), env('EDGEAI_STORAGE_URL', 'https://' + storage + '.edgeai.svc:9000'), env('EDGEAI_STORAGE_RUNNER_URL', 'https://' + storage + '.edgeai.svc:9000'),
                env('EDGEAI_STREAM_ENABLED', 'true'), env('EDGEAI_STREAM_BINDINGS_ENABLED', 'true'), env('EDGEAI_STREAM_RUNS_ENABLED', 'true'), env('EDGEAI_STREAM_LEASE_SECONDS', '120'),
                env('EDGEAI_STREAM_BROKER_URL', 'ssl://' + broker + '.edgeai.svc:8883'), env('EDGEAI_STREAM_BROKER_DIGEST', 'sha256:' + hashlib.sha256(credentials['server.crt'].encode()).hexdigest()),
                env('EDGEAI_STREAM_CA_FILE', '/tmp/identity/server.crt'), env('EDGEAI_STREAM_ADMIN_PASSWORD_FILE', '/tmp/identity/admin.password'),
                env('EDGEAI_STREAM_PRINCIPAL_KEY_FILE', '/tmp/identity/principal.key'), env('EDGEAI_STREAM_DEVICE_KEY_FILE', '/tmp/identity/device.key')]
            api_env += [secret_env(name) for name in ('EDGEAI_API_USER', 'EDGEAI_API_PASSWORD', 'EDGEAI_DB_PASSWORD', 'EDGEAI_MINIO_USER', 'EDGEAI_MINIO_PASSWORD')]
            if any(name in VD_CASES for name in args.cases):
                api_env += [env('EDGEAI_VD_ENABLED', 'true'), env('EDGEAI_VD_LEASE_SECONDS', '60')]
            if any(name in ('vd-distinct-offload', 'vd-shared-offload-pending-cancel') for name in args.cases):
                # The injected row lock deliberately holds several poll/child requests; reserve
                # connections for the public command and readiness while those transactions wait.
                api_env += [env('SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE', '20')]
                snapshot['vdOffloadBarrierPoolSize'] = 20
            jar_path = '/app/app.jar' if jar is None else '/tmp/current-api.jar'
            api_command = 'umask 077; mkdir -p /tmp/identity; cp /bootstrap/* /tmp/identity/; chmod 600 /tmp/identity/*; '
            if jar is not None:
                api_command += 'while [ ! -f /tmp/start ]; do sleep 0.2; done; '
            api_command += 'exec java -XX:MaxRAMPercentage=75.0 -Djavax.net.ssl.trustStore=/tmp/identity/trust.p12 -Djavax.net.ssl.trustStorePassword=changeit -jar ' + jar_path
            api_spec = pod_spec([{'name': 'api', 'image': api_image,
                'command': ['sh', '-c', api_command],
                'env': api_env, 'securityContext': security, 'resources': {'requests': {'cpu': '250m', 'memory': '512Mi'}, 'limits': {'cpu': '2', 'memory': '1Gi'}},
                'readinessProbe': {'httpGet': {'path': '/actuator/health/readiness', 'port': 18443, 'scheme': 'HTTPS'}, 'periodSeconds': 2}, 'volumeMounts': mounts}],
                [secret_volume(('server.crt', 'server.key', 'runner.key', 'device.key', 'principal.key', 'admin.password', 'trust.p12')), {'name': 'tmp', 'emptyDir': {}}], account='edgeai-control-plane')

            def start_api():
                pod = create('Pod', api, spec=api_spec)
                wait(lambda: running(api), 150, 'Owned API container was not running')
                if jar is not None:
                    call(['-n', 'edgeai', 'exec', '-i', api, '--', 'sh', '-c', 'cat > /tmp/current-api.jar'], raw=jar, timeout=100)
                actual_hash = call(['-n', 'edgeai', 'exec', api, '--', 'sha256sum', jar_path]).decode().split()[0]
                assert re.fullmatch(r'[0-9a-f]{64}', actual_hash)
                if snapshot['apiJarSha256'] is None:
                    snapshot['apiJarSha256'] = actual_hash
                assert actual_hash == snapshot['apiJarSha256'], 'API JAR changed during the scenario'
                if jar is not None:
                    call(['-n', 'edgeai', 'exec', api, '--', 'touch', '/tmp/start'])
                wait(lambda: running(api, True), 150, 'Owned native TLS API was not ready')
                actual = read(['-n', 'edgeai', 'get', 'pod', api, '-o', 'json'])
                image_id = actual['status']['containerStatuses'][0]['imageID']
                assert actual['spec']['containers'][0]['image'] == api_image and image_id
                if '@' in api_image:
                    assert image_id.endswith('@' + api_image.split('@', 1)[1]), 'Actual API image differs from pinned digest'
                snapshot.setdefault('apiPods', []).append({'uid': actual['metadata']['uid'], 'imageID': image_id, 'jarSha256': actual_hash})
                snapshot['apiTlsMode'] = 'additional-native-connector'
                return pod['metadata']['uid']

            api_uid = start_api()
            def restart_vd_api(abrupt=False):
                nonlocal api_uid
                old = api_uid
                remove('pod', 'edgeai', api, old, grace_seconds=0 if abrupt else None); records.remove(('pod', 'edgeai', api, old))
                api_uid = start_api()
                assert api_uid != old
                return {'oldUid': old, 'newUid': api_uid, 'kind': 'actual-kubernetes-api-pod', 'abrupt': abrupt}

            vd_observer = VDStreamObserver(read, call, query, snapshot, vd_resources, runner_digest, root + '-ca', restart_vd_api, hold_vd)
            forward(api, 18443, '/actuator/health/readiness')
            nodes = read(['get', 'nodes', '-o', 'json'])['items']
            eligible = [n for n in nodes if n['status']['nodeInfo']['architecture'] == 'amd64' and not n['spec'].get('unschedulable')
                        and any(c['type'] == 'Ready' and c['status'] == 'True' for c in n['status']['conditions'])
                        and not any(t['effect'] in ('NoSchedule', 'NoExecute') for t in n['spec'].get('taints', []))]
            assert len(eligible) >= (2 if any(name.startswith(('offload', 'placement')) or '-offload' in name for name in args.cases) else 1), 'Two eligible nodes are required for actual stream transfer or task placement'
            node = eligible[0]
            config = {'origin': api_origin, 'runnerImage': snapshot['runnerImage'], 'nodeId': node['metadata']['uid'],
                      'nodeName': node['metadata']['name'],
                      'targetNodeId': eligible[-1]['metadata']['uid'], 'cases': args.cases,
                      'streamSpec': json.loads((ROOT / 'contracts/profiles/service-stream.example.json').read_bytes()),
                      'batchSpec': json.loads((ROOT / 'contracts/profiles/service-execution.example.json').read_bytes()),
                      # A test workload barrier after the real server grants completion, before file output.
                      'finalizerCommand': "import os,time\nfrom pathlib import Path\nwork=Path(os.environ['EDGEAI_OUTPUT_DIR']).parent\n(work/'finalizer-entered').touch()\nwhile not (work/'finalizer-release').exists(): time.sleep(.05)\n" + (ROOT / 'runner/examples/stream_result.py').read_text(),
                      # Allocate real memory in the model's container only after the first checkpoint.
                      # No synthetic telemetry, cgroup overrides, or shared node mutations.
                      'memoryPressureCommand': "import runpy,threading,time\nfrom pathlib import Path\ndef pressure():\n global allocation\n while not Path('/work/automatic-pressure').exists(): time.sleep(.05)\n allocation=bytearray(b'x')*(320*1024*1024)\nthreading.Thread(target=pressure,daemon=True).start()\nrunpy.run_path('/opt/edgeai/examples/stream_sum.py',run_name='__main__')\n",
                      'reportCommand': 'import time; time.sleep(2)\n' + (ROOT / 'runner/examples/stream_report.py').read_text()}
            create('ConfigMap', root + '-scenario', immutable=True, data={'driver.py': (ROOT / 'scripts/stream_acceptance.py').read_text(),
                'vd_stream_acceptance.py': (ROOT / 'scripts/vd_stream_acceptance.py').read_text(), 'config.json': json.dumps(config), 'ca.crt': credentials['server.crt']})
            create('Pod', driver, spec=pod_spec([{'name': 'source-driver', 'image': snapshot['runnerImage'], 'command': ['python3', '-B', '/scenario/driver.py'],
                'env': [secret_env('EDGEAI_API_USER'), secret_env('EDGEAI_API_PASSWORD'), env('SSL_CERT_FILE', '/scenario/ca.crt')], 'securityContext': security,
                'resources': {'requests': {'cpu': '100m', 'memory': '128Mi'}, 'limits': {'cpu': '1', 'memory': '512Mi'}},
                'volumeMounts': [{'name': 'work', 'mountPath': '/work'}, {'name': 'scenario', 'mountPath': '/scenario', 'readOnly': True}]}],
                [{'name': 'work', 'emptyDir': {}}, {'name': 'scenario', 'configMap': {'name': root + '-scenario'}}]))
            wait(lambda: running(driver), 150, 'Owned source driver did not start')
            completed = set()
            deadline = time.monotonic() + 1500
            restarted = False
            while time.monotonic() < deadline:
                running(driver)
                script = "import json;from pathlib import Path;print(json.dumps({n:json.loads(Path('/work',n+'.json').read_text()) for n in ('phase','failure','done','report') if Path('/work',n+'.json').exists()}))"
                state = json.loads(call(['-n', 'edgeai', 'exec', driver, '--', 'python3', '-c', script]))
                if 'failure' in state:
                    snapshot['driverFailure'] = driver_failure_evidence(state['failure'])
                    print('Owned source failure: ' + json.dumps(snapshot['driverFailure']), flush=True)
                    raise AssertionError('Actual source driver failed; sanitized evidence retained')
                current = state.get('phase')
                if current:
                    run = current['runId'];uuid.UUID(run);run_ids.add(run);observe(run)
                    if current['case'] in VD_CASES:
                        vd_ids.update(current['vdTargets'])
                        vd_observer.observe(current)
                    phase = current['phase']
                    if phase not in completed and ('expectedStates' in current or phase.endswith(('-done', '-children-exited', '-finalizer-granted', '-finalizer-restoring', '-draining', '-releasing', '-cancelling'))):
                        if 'expectedStates' in current:
                            task_ids = [str(uuid.UUID(current['tasks'][n])) for n in current['expectedStates']]
                            attempt_filter = ''
                            if 'expectedAttempts' in current:
                                attempt_filter = ' AND attempt_id IN(' + ','.join("'" + str(uuid.UUID(a)) + "'" for a in current['expectedAttempts'].values()) + ')'
                            rows = query("SELECT DISTINCT ON(task_id) task_id,summary_json->>'stateSha256' FROM edgeai.stream_checkpoint WHERE task_id IN(" + ','.join("'" + t + "'" for t in task_ids) + ')' + attempt_filter + ' ORDER BY task_id,serial DESC')
                            actual = dict(line.split('|') for line in rows.splitlines())
                            expected = {current['tasks'][n]: hashlib.sha256(str(v).encode()).hexdigest() for n, v in current['expectedStates'].items()}
                            if actual != expected:
                                time.sleep(.3)
                                continue
                            snapshot['checkpointBarriers'].append({'phase': phase, 'runId': run, 'states': current['expectedStates'], 'sha256': actual})
                            if current['case'].startswith('placement') and phase.endswith(('-first', '-recovered')):
                                pods = [p for p in resources(run) if p['kind'] == 'Pod']
                                assert len(pods) == 2 and all(p['metadata']['uid'] in seen for p in pods)
                                observed_nodes = {}
                                for name in ('root', 'sink'):
                                    pod = next(p for p in pods if p['metadata']['labels']['edgeai.io/task-id'] == current['tasks'][name])
                                    observed_nodes[name] = seen[pod['metadata']['uid']]['nodeUid']
                                    assert observed_nodes[name] == current['taskExecutions'][name]['nodeId']
                                assert observed_nodes['root'] != observed_nodes['sink']
                                for name, target in current['taskExecutions'].items():
                                    assert query("SELECT initial_mode,initial_node_id FROM edgeai.task WHERE id='" + str(uuid.UUID(current['tasks'][name])) + "'") == 'NODE|' + target['nodeId']
                                snapshot.setdefault('taskPlacementBarriers', []).append({'phase': phase, 'runId': run, 'observedNodeUids': observed_nodes, 'initialTaskTargetsPreserved': True})
                            if current['case'].startswith('offload') and phase.endswith('-first'):
                                owned = resources(run);before = [p for p in owned if p['kind'] == 'Pod']
                                assert len(before) == 2 and all(p['metadata']['uid'] in seen for p in before)
                                assert all(seen[p['metadata']['uid']]['nodeUid'] == config['nodeId'] for p in before)
                                private_producers[current['case']] = [capture_producer(p) for p in before]
                                jobs = [p for p in owned if p['kind'] == 'Job'];assert len(jobs) == 2
                                for job in jobs:
                                    hold_job(job['metadata']['name'], job['metadata']['uid'], run, True)
                                snapshot.setdefault('offloads', {})[current['case']] = {
                                    'runId': run, 'oldPodUids': sorted(p['metadata']['uid'] for p in before),
                                    'oldAttemptIds': sorted(p['metadata']['labels']['edgeai.io/attempt-id'] for p in before),
                                    'oldGenerationIds': sorted(r['generation']['id'] for r in current['routes']),
                                    'sourceNodeId': config['nodeId'], 'targetNodeId': None if current['case'].startswith('offload-automatic') else config['targetNodeId'],
                                    'heldJobUids': sorted(p['metadata']['uid'] for p in jobs)}
                                if current['case'].startswith('offload-automatic'):
                                    root_pod = next(p for p in before if p['metadata']['labels']['edgeai.io/task-id'] == current['tasks']['root'])
                                    assert root_pod['spec']['containers'][0]['resources']['limits']['memory'] == '512Mi'
                                    measurement = json.loads(call(['-n', 'edgeai-runtimes', 'exec', root_pod['metadata']['name'], '--', 'python3', '-c',
                                        "import sys,json;sys.path.insert(0,'/opt/edgeai');from edgeai_runner.telemetry import own_cgroup;p=own_cgroup();assert p;print(json.dumps({'memoryBytes':int((p/'memory.current').read_text()),'memoryLimitBytes':int((p/'memory.max').read_text())}))"]))
                                    assert measurement['memoryLimitBytes'] == 512 * 1024 * 1024 and measurement['memoryBytes'] * 2 < measurement['memoryLimitBytes']
                                    assert query("SELECT count(*) FROM edgeai.task_offload WHERE run_id='" + run + "'") == '0'
                                    snapshot['offloads'][current['case']]['memoryPressure'] = {'before': measurement, 'allocatedBytes': 320 * 1024 * 1024,
                                        'producerPodUid': root_pod['metadata']['uid'], 'source': 'real-model-process-memory'}
                                    call(['-n', 'edgeai-runtimes', 'exec', root_pod['metadata']['name'], '--', 'touch', '/work/automatic-pressure'])
                            if phase == 'auto-first' and not restarted:
                                before = {p['metadata']['uid'] for p in resources(run) if p['kind'] == 'Pod'}
                                assert len(before) == 2
                                started = time.monotonic();old = api_uid
                                remove('pod', 'edgeai', api, api_uid);records.remove(('pod', 'edgeai', api, api_uid))
                                api_uid = start_api();assert api_uid != old
                                after = {p['metadata']['uid'] for p in resources(run) if p['kind'] == 'Pod'}
                                assert before == after, 'API restart replaced a live stream Pod'
                                snapshot['apiRestart'] = {'oldUid': old, 'newUid': api_uid, 'preservedRunnerPodUids': sorted(before), 'elapsedSeconds': round(time.monotonic() - started, 3)}
                                restarted = True
                            if phase in ('recover-first', 'placement-recover-first'):
                                owned = resources(run)
                                before = [p for p in owned if p['kind'] == 'Pod']
                                assert len(before) == 2 and all(p['metadata']['uid'] in seen for p in before)
                                job = next(p for p in owned if p['kind'] == 'Job' and p['metadata']['labels']['edgeai.io/task-id'] == current['tasks']['sink'])
                                meta = job['metadata']
                                assert meta['labels']['edgeai.io/run-id'] == run and meta['labels']['app.kubernetes.io/managed-by'] == 'edgeai-runtime-controller'
                                snapshot['placementGroupFault' if current['case'] == 'placement-recover' else 'groupFault'] = {'runId': run, 'deletedJobUid': meta['uid'], 'oldPodUids': sorted(p['metadata']['uid'] for p in before),
                                    'oldAttemptIds': sorted(p['metadata']['labels']['edgeai.io/attempt-id'] for p in before),
                                    'oldGenerationIds': sorted(r['generation']['id'] for r in current['routes'])}
                                # Foreground removal stops the owned Pod; the real controller observes the missing Job.
                                call(['delete', '--raw', '/apis/batch/v1/namespaces/edgeai-runtimes/jobs/' + meta['name'], '-f', '-'],
                                     {'apiVersion': 'v1', 'kind': 'DeleteOptions', 'propagationPolicy': 'Foreground', 'preconditions': {'uid': meta['uid']}})
                            if phase in ('recover-recovered', 'placement-recover-recovered'):
                                fault = snapshot['placementGroupFault' if current['case'] == 'placement-recover' else 'groupFault']
                                after = {p['metadata']['uid'] for p in resources(run) if p['kind'] == 'Pod'}
                                assert len(after) == 2 and not after.intersection(fault['oldPodUids'])
                                old_attempts = ','.join("'" + str(uuid.UUID(a)) + "'" for a in fault['oldAttemptIds'])
                                old_generations = ','.join("'" + str(uuid.UUID(g)) + "'" for g in fault['oldGenerationIds'])
                                rows = query('SELECT desired_state,observed_state FROM edgeai.runtime_instance WHERE attempt_id IN(' + old_attempts + ')').splitlines()
                                assert len(rows) == 2 and all(row == 'STOPPED|TERMINATED' for row in rows)
                                assert query('SELECT count(*) FROM edgeai.route_generation WHERE id IN(' + old_generations + ') AND closed_at IS NOT NULL') == '3'
                                fault.update(newPodUids=sorted(after), oldRuntimesStopped=True, oldGenerationsClosed=True,
                                             restoredAttemptIds=current['expectedAttempts'])
                            if current['case'].startswith('offload') and phase.endswith('-recovered'):
                                proof = snapshot['offloads'][current['case']];operation = str(uuid.UUID(proof['operationId']))
                                after = [p for p in resources(run) if p['kind'] == 'Pod']
                                assert len(after) == 2 and not {p['metadata']['uid'] for p in after}.intersection(proof['oldPodUids'])
                                for p in after:
                                    meta = p['metadata'];observed = seen[meta['uid']]
                                    if current['case'].startswith('offload-automatic') and observed['taskId'] == current['tasks']['root']:
                                        assert observed['nodeUid'] != config['nodeId']
                                        proof['selectedTargetNodeId'] = observed['nodeUid']
                                    else:
                                        expected_node = config['targetNodeId'] if observed['taskId'] == current['tasks']['root'] else config['nodeId']
                                        assert observed['nodeUid'] == expected_node
                                old_attempts = ','.join("'" + str(uuid.UUID(a)) + "'" for a in proof['oldAttemptIds'])
                                old_generations = ','.join("'" + str(uuid.UUID(g)) + "'" for g in proof['oldGenerationIds'])
                                assert query('SELECT desired_state,observed_state FROM edgeai.runtime_instance WHERE attempt_id IN(' + old_attempts + ')').splitlines() == ['STOPPED|TERMINATED'] * 2
                                assert query('SELECT count(*) FROM edgeai.route_generation WHERE id IN(' + old_generations + ') AND closed_at IS NOT NULL') == '3'
                                assert query("SELECT count(*) FROM edgeai.stream_checkpoint c JOIN edgeai.task_offload_member m ON c.handover_from_id=m.checkpoint_id AND c.attempt_id=m.target_attempt_id JOIN edgeai.stream_checkpoint old ON old.id=m.checkpoint_id WHERE m.operation_id='" + operation + "' AND c.serial=old.serial+1 AND c.state_revision=old.state_revision AND c.summary_json-'manifest'=old.summary_json-'manifest'") == '2'
                                assert query("SELECT state FROM edgeai.task_offload WHERE id='" + operation + "'") == 'SUCCEEDED'
                                proof.update(newPodUids=sorted(p['metadata']['uid'] for p in after), oldRuntimesStopped=True,
                                    oldGenerationsClosed=True, immutableCheckpointHandovers=2, distinctNodeMove=True,
                                    peerNodePreserved=True, restoredAttemptIds=current['expectedAttempts'])
                            if current['case'] == 'offload-automatic' and phase.endswith('-second'):
                                proof = snapshot['offloads'][current['case']]
                                after = [p for p in resources(run) if p['kind'] == 'Pod']
                                replacement = next(p for p in after if p['metadata']['labels']['edgeai.io/task-id'] == current['tasks']['sink'])
                                # The peer has visited only its original node; the selected task's
                                # actual Ready destination remains a compatible unvisited choice.
                                destination = read(['get', 'node', next(seen[p['metadata']['uid']]['nodeName'] for p in after
                                    if p['metadata']['labels']['edgeai.io/task-id'] == current['tasks']['root']), '-o', 'json'])
                                assert destination['metadata']['uid'] != config['nodeId'] and not destination['spec'].get('unschedulable')
                                assert any(c['type'] == 'Ready' and c['status'] == 'True' for c in destination['status']['conditions'])
                                proof['budgetAlternativeNodeId'] = destination['metadata']['uid']
                                call(['-n', 'edgeai-runtimes', 'exec', replacement['metadata']['name'], '--', 'python3', '-c',
                                    "from pathlib import Path;p=Path('/work/automatic-pressure');assert not p.exists();p.touch()"])
                        elif phase.endswith(('-draining', '-releasing', '-cancelling')) and current['case'] not in VD_CASES:
                            assert current['case'].startswith('offload')
                            proof = snapshot['offloads'][current['case']]
                            operation = str(uuid.UUID(current['offload']['id']))
                            expected_state = 'CANCELLING' if phase.endswith('-cancelling') else 'DRAINING'
                            assert query("SELECT state FROM edgeai.task_offload WHERE id='" + operation + "' AND run_id='" + run + "'") == expected_state
                            assert query("SELECT count(*) FROM edgeai.task_offload_member WHERE operation_id='" + operation + "' AND target_attempt_id IS NULL") == '2'
                            assert query("SELECT count(*) FROM edgeai.task_attempt a JOIN edgeai.task t ON a.task_id=t.id WHERE t.run_id='" + run + "'") == '2'
                            assert query("SELECT count(*) FROM edgeai.runtime_instance WHERE run_id='" + run + "' AND desired_state='STOPPED'") == '2'
                            if phase.endswith('-draining'):
                                pinned = query("SELECT source_attempt_id,checkpoint_id FROM edgeai.task_offload_member WHERE operation_id='" + operation + "' ORDER BY source_attempt_id")
                                assert pinned.splitlines() == sorted(m['sourceAttemptId'] + '|' + m['checkpointId'] for m in current['offload']['members'])
                                proof.update(operationId=operation, pinnedCheckpoints=pinned.splitlines(), drainingObserved=True)
                                if current['case'].startswith('offload-automatic'):
                                    assert current['offload']['trigger'] == 'MEMORY'
                                    assert query("SELECT count(*) FROM edgeai.task_offload WHERE run_id='" + run + "'") == '1'
                                    assert query("SELECT count(*) FROM edgeai.task_offload o CROSS JOIN LATERAL jsonb_array_elements(o.decision->'samples') s JOIN edgeai.runtime_telemetry t ON t.attempt_id=(s->>'attemptId')::uuid AND t.sequence=(s->>'sequence')::bigint WHERE o.id='" + operation + "' AND t.memory_bytes=(s->>'memoryBytes')::bigint AND t.memory_limit_bytes=(s->>'memoryLimitBytes')::bigint AND t.observed_at=(s->>'observedAt')::timestamptz AND t.received_at=(s->>'receivedAt')::timestamptz") == '2'
                                    proof['automaticDecision'] = current['offload']['decision']
                                    proof['decisionSamplesMatchReceivedTelemetry'] = True
                                if not current['case'].endswith('-cancel'):
                                    started = time.monotonic();old = api_uid
                                    remove('pod', 'edgeai', api, api_uid);records.remove(('pod', 'edgeai', api, api_uid))
                                    api_uid = start_api();assert api_uid != old
                                    assert query("SELECT state FROM edgeai.task_offload WHERE id='" + operation + "'") == 'DRAINING'
                                    assert query("SELECT source_attempt_id,checkpoint_id FROM edgeai.task_offload_member WHERE operation_id='" + operation + "' ORDER BY source_attempt_id") == pinned
                                    proof['apiRestart'] = {'oldUid': old, 'newUid': api_uid, 'elapsedSeconds': round(time.monotonic() - started, 3), 'pendingOperationPreserved': True}
                                proof['lateProducerStatuses'] = [check_old_producer(v, api_origin) for v in private_producers.pop(current['case'])]
                            else:
                                if current['case'].startswith('offload-automatic'):
                                    assert current['offloadDecisionPreserved']
                                    proof['publicDecisionPreserved'] = True
                                else:
                                    assert current['offloadReplayPreserved']
                                    proof['publicReplayPreserved'] = True
                                if phase.endswith('-cancelling'):
                                    proof['cancelledBeforeTargetCreation'] = True
                                for (name, uid), held_run in list(held_jobs.items()):
                                    if held_run == run:
                                        hold_job(name, uid, run, False)
                        elif phase.endswith('-finalizer-granted') and current['case'] not in VD_CASES:
                            sink = str(uuid.UUID(current['tasks']['sink']))
                            owned = resources(run)
                            pod = next(p for p in owned if p['kind'] == 'Pod' and p['metadata']['labels']['edgeai.io/task-id'] == sink)
                            meta = pod['metadata'];old_attempt = str(uuid.UUID(meta['labels']['edgeai.io/attempt-id']))
                            entered = call(['-n', 'edgeai-runtimes', 'exec', meta['name'], '--', 'python3', '-c',
                                "from pathlib import Path;print(int(Path('/work/finalizer-entered').exists()))"]).decode().strip()
                            if entered != '1':
                                time.sleep(.3)
                                continue
                            grant = query("SELECT checkpoint_id,granted_at FROM edgeai.stream_task_completion WHERE attempt_id='" + old_attempt + "' AND granted_at IS NOT NULL")
                            assert grant and query("SELECT count(*) FROM edgeai.task_result WHERE task_id='" + sink + "'") == '0'
                            job = next(p for p in owned if p['kind'] == 'Job' and p['metadata']['labels']['edgeai.io/attempt-id'] == old_attempt)
                            jm = job['metadata']
                            assert jm['labels']['edgeai.io/run-id'] == run and jm['labels']['app.kubernetes.io/managed-by'] == 'edgeai-runtime-controller'
                            snapshot['finalizerFault'] = {'runId': run, 'oldPodUid': meta['uid'], 'oldAttemptId': old_attempt, 'deletedJobUid': jm['uid'],
                                'grant': grant, 'preservedResultId': current['preservedResult']['id'],
                                'checkpointIds': query("SELECT id FROM edgeai.stream_checkpoint WHERE run_id='" + run + "' ORDER BY id").splitlines()}
                            call(['delete', '--raw', '/apis/batch/v1/namespaces/edgeai-runtimes/jobs/' + jm['name'], '-f', '-'],
                                {'apiVersion': 'v1', 'kind': 'DeleteOptions', 'propagationPolicy': 'Foreground', 'preconditions': {'uid': jm['uid']}})
                        elif phase.endswith('-finalizer-restoring') and current['case'] not in VD_CASES:
                            fault = snapshot['finalizerFault'];old_attempt = fault['oldAttemptId']
                            sink = str(uuid.UUID(current['tasks']['sink']))
                            candidates = [p for p in resources(run) if p['kind'] == 'Pod' and p['metadata']['labels']['edgeai.io/task-id'] == sink
                                and p['metadata']['uid'] != fault['oldPodUid'] and any(s.get('state', {}).get('running') for s in p['status'].get('containerStatuses', []))]
                            if not candidates:
                                time.sleep(.3)
                                continue
                            assert len(candidates) == 1
                            pod = candidates[0];meta = pod['metadata'];new_attempt = str(uuid.UUID(meta['labels']['edgeai.io/attempt-id']))
                            flags = call(['-n', 'edgeai-runtimes', 'exec', meta['name'], '--', 'python3', '-c',
                                "from pathlib import Path;print(int(Path('/work/finalizer-entered').exists()),int(Path('/work/stream').exists()))"]).decode().strip()
                            if flags.startswith('0 '):
                                time.sleep(.3)
                                continue
                            assert flags == '1 0', 'Finalizer retry unexpectedly opened a stream computation directory'
                            assert fault['oldPodUid'] not in {p['metadata']['uid'] for p in resources(run) if p['kind'] == 'Pod'}
                            assert query("SELECT desired_state,observed_state FROM edgeai.runtime_instance WHERE attempt_id='" + old_attempt + "'") == 'STOPPED|TERMINATED'
                            assert query("SELECT predecessor_attempt_id,granted_attempt_id FROM edgeai.stream_finalization_recovery WHERE attempt_id='" + new_attempt + "'") == old_attempt + '|' + old_attempt
                            assert query("SELECT checkpoint_id,granted_at FROM edgeai.stream_task_completion WHERE attempt_id='" + old_attempt + "'") == fault['grant']
                            assert query("SELECT count(*) FROM edgeai.stream_task_completion WHERE attempt_id='" + new_attempt + "'") == '0'
                            assert query("SELECT id FROM edgeai.stream_checkpoint WHERE run_id='" + run + "' ORDER BY id").splitlines() == fault['checkpointIds']
                            assert query("SELECT count(*) FROM edgeai.route_generation WHERE run_id='" + run + "'") == '3'
                            assert query("SELECT count(*) FROM edgeai.route_generation WHERE run_id='" + run + "' AND generation=1 AND closed_at IS NOT NULL") == '3'
                            fault.update(newPodUid=meta['uid'], newAttemptId=new_attempt, oldRuntimeStopped=True,
                                originalGrantPreserved=True, checkpointHistoryPreserved=True, newComputationOpened=False)
                            call(['-n', 'edgeai-runtimes', 'exec', meta['name'], '--', 'touch', '/work/finalizer-release'])
                        else:
                            wait(lambda: not resources(run), 90, 'Actual stream runtime resources were not reclaimed')
                            if current['case'].startswith('offload') and current['case'].endswith('-cancel'):
                                proof = snapshot['offloads'][current['case']]
                                assert query("SELECT state FROM edgeai.task_offload WHERE id='" + str(uuid.UUID(proof['operationId'])) + "'") == 'CANCELLED'
                                assert query("SELECT count(*) FROM edgeai.task_attempt a JOIN edgeai.task t ON a.task_id=t.id WHERE t.run_id='" + run + "'") == '2'
                                proof['cancelledWithoutNewAttempt'] = True
                        if current['case'] in VD_CASES:
                            vd_observer.boundary(current)
                        call(['-n', 'edgeai', 'exec', driver, '--', 'touch', '/work/' + phase + '.continue'])
                        completed.add(phase)
                        print('PASS: actual Kubernetes stream boundary ' + phase, flush=True)
                if 'done' in state:
                    snapshot['cases'] = state['done']['cases']
                    assert {case['case'] for case in snapshot['cases']} == set(args.cases)
                    if 'auto' in args.cases:
                        assert restarted
                    if 'recover' in args.cases:
                        assert snapshot['groupFault']['oldRuntimesStopped'] and snapshot['groupFault']['oldGenerationsClosed']
                    if 'placement-recover' in args.cases:
                        assert snapshot['placementGroupFault']['oldRuntimesStopped'] and snapshot['placementGroupFault']['oldGenerationsClosed']
                    if 'finalizer' in args.cases:
                        assert snapshot['finalizerFault']['originalGrantPreserved'] and snapshot['finalizerFault']['checkpointHistoryPreserved']
                    for name in args.cases:
                        if name.startswith('offload'):
                            proof = snapshot['offloads'][name]
                            if name.endswith('-cancel'):
                                assert proof['cancelledWithoutNewAttempt']
                            else:
                                assert proof['apiRestart']['pendingOperationPreserved'] and proof['immutableCheckpointHandovers'] == 2
                                if name.startswith('offload-automatic'):
                                    case = next(v for v in snapshot['cases'] if v['case'] == name)
                                    assert len(case['automaticBudget']['freshPressureSamples']) >= 3
                                    proof['automaticBudgetPreserved'] = True
                    assert not held_jobs
                    artifacts = []
                    for case in snapshot['cases']:
                        for row in case['results']:
                            result = row['result']
                            if case['case'] in VD_CASES and case['taskExecutions'][row['task']]['mode'] == 'VD':
                                vd_observer.result(case, row)
                                artifacts.append({'artifact': result['artifacts'][0], 'expected': row['expected']})
                                continue
                            observed = seen[result['producerPodUid']]
                            assert observed['runId'] == case['runId'] and observed['attemptId'] == result['attemptId']
                            if case['case'] in VD_CASES:
                                assert observed['nodeUid'] == case['taskExecutions'][row['task']]['nodeId']
                            if case['case'].startswith('placement'):
                                assert observed['nodeUid'] == case['taskExecutions'][row['task']]['nodeId']
                            if case['placement']['mode'] == 'NODE':
                                if case['case'] == 'offload-automatic' and row['task'] == 'root':
                                    assert observed['nodeUid'] == snapshot['offloads'][case['case']]['selectedTargetNodeId'] != case['placement']['nodeId']
                                else:
                                    expected_node = case['offload']['targetNodeId'] if case['case'] == 'offload' and row['task'] == 'root' else case['placement']['nodeId']
                                    assert observed['nodeUid'] == expected_node
                            artifacts.append({'artifact': result['artifacts'][0], 'expected': row['expected']})
                    assert len(artifacts) == sum(3 for case in args.cases if case != 'cancel' and not case.endswith('-cancel'))
                    if artifacts:
                        result = subprocess.run(['node', 'scripts/verify-runtime-artifacts.mjs'], input=json.dumps(artifacts).encode(), env=verify_env, capture_output=True, timeout=60)
                        assert result.returncode == 0, 'Actual fixed-version TLS S3 results differed; private output suppressed'
                        print(result.stdout.decode().strip(), flush=True)
                    else:
                        assert all(case['cancelled'] and not case['results'] for case in snapshot['cases'])
                        assert query('SELECT count(*) FROM edgeai.task_result') == '0'
                        print('PASS: cancellation-only selection has no committed Result; no artifact download claimed', flush=True)
                    snapshot['verifiedArtifacts'] = len(artifacts)
                    starts = json.loads(query("""
                        SELECT COALESCE(jsonb_agg(jsonb_build_object(
                            'runtimeId',r.id,'runId',r.run_id,'taskId',r.task_id,'attemptId',r.attempt_id,'epoch',r.epoch,
                            'namespace',r.namespace,'jobName',r.job_name,'jobUid',r.job_uid,'podUid',r.producer_pod_uid,
                            'nodeUid',r.node_uid,'nodeName',r.node_name,'expiresAt',r.expires_at,'createdAt',r.created_at,
                            'offloadId',o.id,'startDeadline',o.start_deadline)), '[]'::jsonb)
                        FROM edgeai.runtime_instance r LEFT JOIN edgeai.task_offload o ON
                            (o.target_attempt_id=r.attempt_id OR EXISTS(SELECT 1 FROM edgeai.task_offload_member m
                             WHERE m.operation_id=o.id AND m.target_attempt_id=r.attempt_id))
                        WHERE r.remote_allocation_id IS NULL AND r.vd_id IS NULL AND r.producer_pod_uid IS NOT NULL
                        """))
                    for receipt in starts:
                        observed = seen[receipt['podUid']]
                        assert all(receipt[field] == observed[field] for field in ('runId', 'taskId', 'attemptId', 'nodeUid', 'nodeName'))
                    result = subprocess.run(['node', 'scripts/verify-runtime-start-journals.mjs'], input=json.dumps(starts).encode(), env=verify_env, capture_output=True, timeout=60)
                    assert result.returncode == 0, 'Actual Kubernetes start records differed; private output suppressed'
                    print(result.stdout.decode().strip(), flush=True)
                    snapshot['verifiedStartJournals'] = len(starts)
                    wait(lambda: query("SELECT count(*) FROM edgeai.runtime_result_publication WHERE NOT completed") == '0',
                        60, 'Committed Result publication queue did not drain')
                    results = json.loads(query("""
                        SELECT COALESCE(jsonb_agg(jsonb_build_object(
                            'apiVersion','edgeai.runtime.result/v1','resultId',s.id,'runtimeId',r.id,'runId',r.run_id,
                            'taskId',r.task_id,'attemptId',r.attempt_id,'epoch',r.epoch,'namespace',r.namespace,
                            'jobName',r.job_name,'jobUid',r.job_uid,'podUid',r.producer_pod_uid,'nodeUid',r.node_uid,'nodeName',r.node_name,
                            'startKey','authority/runtime-start/'||r.id||'.json','manifestDigest',s.manifest_digest,'committedAt',s.created_at,
                            'outputs',(SELECT jsonb_agg(jsonb_build_object('port',a.port,'bucket',a.bucket,'objectKey',a.object_key,
                                'versionId',a.object_version,'bytes',a.bytes,'sha256',a.sha256,'mediaType',a.media_type) ORDER BY a.port)
                                FROM edgeai.result_artifact a WHERE a.result_id=s.id))), '[]'::jsonb)
                        FROM edgeai.task_result s JOIN edgeai.runtime_instance r ON r.id=s.runtime_id
                        WHERE s.committed AND s.remote_allocation_id IS NULL AND s.vd_runtime_id IS NULL
                        """))
                    result = subprocess.run(['node', 'scripts/verify-runtime-result-journals.mjs'], input=json.dumps(results).encode(), env=verify_env, capture_output=True, timeout=60)
                    assert result.returncode == 0, 'Actual Kubernetes committed Result records differed; private output suppressed'
                    print(result.stdout.decode().strip(), flush=True)
                    snapshot['verifiedResultJournals'] = len(results)
                    snapshot['pendingResultPublications'] = 0
                    report_path.write_text(json.dumps(snapshot, indent=2) + '\n')
                    succeeded = True
                    break
                time.sleep(.3)
            else:
                raise AssertionError('Actual Kubernetes stream scenario deadline exceeded')
        except BaseException:
            snapshot['status'] = 'FAIL'
            report_path.write_text(json.dumps(snapshot, indent=2) + '\n')
            if db_ready:
                try:
                    run_ids.update(str(uuid.UUID(row)) for row in query('SELECT id FROM edgeai.workflow_run').splitlines())
                    for run in run_ids:
                        observe(run)
                    snapshot['runtimeStates'] = query('SELECT d.task_key,t.state,a.state,r.observed_state,r.failure_reason FROM edgeai.task t JOIN edgeai.task_definition d ON d.id=t.definition_id LEFT JOIN edgeai.task_attempt a ON a.task_id=t.id LEFT JOIN edgeai.runtime_instance r ON r.attempt_id=a.id ORDER BY d.task_key').splitlines()
                    failure_path = ROOT / '.tools' / (root + '-failure.json')
                    failure_path.write_text(json.dumps(snapshot, indent=2) + '\n')
                    print('Owned runtime failure evidence saved: ' + failure_path.name, flush=True)
                except (AssertionError, OSError, subprocess.TimeoutExpired):
                    (ROOT / '.tools' / (root + '-partial-failure.json')).write_text(json.dumps(snapshot, indent=2) + '\n')
                    print('Owned runtime failure evidence unavailable', flush=True)
            for kind, namespace, name, uid in records:
                if kind != 'pod':
                    continue
                current = json.loads(call(['-n', namespace, 'get', 'pod', name, '--ignore-not-found', '-o', 'json']) or b'null')
                if current is None or current['metadata']['uid'] != uid or current['metadata'].get('labels', {}).get('edgeai.io/test-id') != root:
                    continue
                result = subprocess.run(k + ['-n', namespace, 'logs', name, '--tail=150'], capture_output=True, timeout=25)
                if result.returncode == 0:
                    path = ROOT / '.tools' / (name + '.log')
                    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'wb') as output:
                        output.write(result.stdout)
                    print('Owned Pod diagnostic saved privately: ' + path.name, flush=True)
            # CI already archives this explicit report path. Private Pod logs remain local.
            report_path.write_text(json.dumps(snapshot, indent=2) + '\n')
            raise
        finally:
            if db_ready:
                try:
                    rows = query('SELECT id FROM edgeai.workflow_run')
                    run_ids.update(str(uuid.UUID(row)) for row in rows.splitlines())
                    rows = query('SELECT id FROM edgeai.virtual_device')
                    vd_ids.update(str(uuid.UUID(row)) for row in rows.splitlines())
                except (AssertionError, OSError, subprocess.TimeoutExpired):
                    pass
            try:
                if not succeeded:
                    # Stop this isolated controller before releasing deletion barriers on a failed run.
                    for record in records:
                        if record[0] == 'pod' and record[2] == api:
                            remove(*record)
                for (name, uid), run in list(held_jobs.items()):
                    hold_job(name, uid, run, False)
                for identity in list(held_vds):
                    hold_vd(identity, False)
                owned = [('edgeai.io/run-id', run, resources, 'edgeai-runtime-controller') for run in run_ids]
                owned += [('edgeai.io/vd-id', vd, vd_resources, 'edgeai-vd-controller') for vd in vd_ids]
                for label, identity, lookup, manager in owned:
                    for item in lookup(identity):
                        meta = item['metadata'];assert meta['labels'][label] == identity
                        assert meta['labels'].get('app.kubernetes.io/managed-by') == manager
                        plural = {'Pod': 'pods', 'Job': 'jobs', 'Secret': 'secrets'}[item['kind']]
                        path = ('/apis/batch/v1/namespaces/edgeai-runtimes/jobs/' if plural == 'jobs' else '/api/v1/namespaces/edgeai-runtimes/' + plural + '/') + meta['name']
                        # The IDs came from this invocation's isolated DB, never another API's Runs.
                        result = subprocess.run(k + ['delete', '--raw', path, '-f', '-'],
                            input=json.dumps({'apiVersion': 'v1', 'kind': 'DeleteOptions', 'preconditions': {'uid': meta['uid']}}).encode(), capture_output=True, timeout=30)
                        # A concurrent real controller may already have removed the same object.
                        if result.returncode:
                            assert not call(['-n', 'edgeai-runtimes', 'get', plural, meta['name'], '--ignore-not-found', '-o', 'name']).strip()
                    wait(lambda: not lookup(identity), 90, 'Owned runtime cleanup incomplete')
            finally:
                for process in forwards:
                    process.terminate()
                    try:
                        process.wait(5)
                    except subprocess.TimeoutExpired:
                        process.kill();process.wait(5)
                for record in reversed(records):
                    remove(*record)
    print('PASS: actual TLS Kubernetes stream cases ' + ','.join(args.cases) + '; all owned resources and Job barriers removed', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        import traceback
        frames = ','.join(Path(f.filename).name + ':' + str(f.lineno) for f in traceback.extract_tb(error.__traceback__))
        print('FAIL: stream Kubernetes ' + type(error).__name__ + ' ' + frames, flush=True)
        raise SystemExit(1)
