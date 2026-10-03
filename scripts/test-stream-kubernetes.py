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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--context', required=True)
    parser.add_argument('--runner-image', help='Explicit CI-tested ghcr.io/dsa04156/edgeai-runner@sha256 digest')
    parser.add_argument('--runner-source', help='Full source commit for the explicit CI-tested Runner image')
    parser.add_argument('--api-image', help='Built project API commit tag or published digest; uses its packaged JAR')
    parser.add_argument('--api-source', help='Full source commit for the explicit API image')
    parser.add_argument('--minio-image', help='Explicit CI-tested project MinIO digest')
    parser.add_argument('--report', type=Path, default=ROOT / '.tools/stream-kubernetes.json')
    args = parser.parse_args()
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
    seen = {}
    db_ready = False
    snapshot = {'scope': 'real-kubernetes-stream-dag', 'testId': root, 'cases': [], 'checkpointBarriers': [], 'observedPods': seen}
    owner_path = ROOT / '.tools' / (root + '-owner.json')

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
        owner_path.write_text(json.dumps({'context': args.context, 'testId': root, 'resources': records}) + '\n')
        return value

    def read_create(value):
        return json.loads(call(['create', '-f', '-', '-o', 'json'], value))

    def remove(kind, namespace, name, uid):
        current = json.loads(call(['-n', namespace, 'get', kind, name, '--ignore-not-found', '-o', 'json']) or b'null')
        if current is None:
            return
        assert current['metadata']['uid'] == uid and current['metadata'].get('labels', {}).get('edgeai.io/test-id') == root
        plural = {'pod': 'pods', 'secret': 'secrets', 'service': 'services', 'configmap': 'configmaps'}[kind]
        call(['delete', '--raw', '/api/v1/namespaces/' + namespace + '/' + plural + '/' + name, '-f', '-'],
             {'apiVersion': 'v1', 'kind': 'DeleteOptions', 'preconditions': {'uid': uid}})
        wait(lambda: not call(['-n', namespace, 'get', kind, name, '--ignore-not-found', '-o', 'name']).strip(), 90, 'Owned fixture resource did not terminate')

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

        def resources(run):
            uuid.UUID(run)
            return read(['-n', 'edgeai-runtimes', 'get', 'pods,jobs,secrets', '-l', 'edgeai.io/run-id=' + run, '-o', 'json'])['items']

        def observe(run):
            for pod in resources(run):
                if pod['kind'] != 'Pod' or not pod['spec'].get('nodeName'):
                    continue
                meta, spec = pod['metadata'], pod['spec']
                statuses = pod['status'].get('containerStatuses', [])
                if not statuses or not statuses[0].get('imageID'):
                    continue
                assert statuses[0]['imageID'].endswith('@' + runner_digest), 'Actual Runner image differs from tested digest'
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
            api_env = [env('EDGEAI_BIND_ADDRESS', '0.0.0.0'), env('EDGEAI_API_PORT', '18443'), env('EDGEAI_DB_HOST', db + '.edgeai.svc'), env('EDGEAI_DB_PORT', '5432'),
                env('SERVER_SSL_ENABLED', 'true'), env('SERVER_SSL_CERTIFICATE', '/tmp/identity/server.crt'), env('SERVER_SSL_CERTIFICATE_PRIVATE_KEY', '/tmp/identity/server.key'),
                env('EDGEAI_KUBE_ENABLED', 'true'), env('EDGEAI_RUNTIME_ENABLED', 'true'), env('EDGEAI_RUNTIME_NAMESPACE', 'edgeai-runtimes'), env('EDGEAI_RUNTIME_CONTROL_PLANE_URL', api_origin),
                env('EDGEAI_RUNTIME_CA_CONFIG_MAP', root + '-ca'), env('EDGEAI_KUBE_API_URL', 'https://kubernetes.default.svc'),
                env('EDGEAI_KUBE_TOKEN_FILE', '/var/run/secrets/kubernetes.io/serviceaccount/token'), env('EDGEAI_KUBE_CA_FILE', '/var/run/secrets/kubernetes.io/serviceaccount/ca.crt'),
                env('EDGEAI_RUNNER_KEY_FILE', '/tmp/identity/runner.key'), env('EDGEAI_STORAGE_URL', 'https://' + storage + '.edgeai.svc:9000'), env('EDGEAI_STORAGE_RUNNER_URL', 'https://' + storage + '.edgeai.svc:9000'),
                env('EDGEAI_STREAM_ENABLED', 'true'), env('EDGEAI_STREAM_BINDINGS_ENABLED', 'true'), env('EDGEAI_STREAM_RUNS_ENABLED', 'true'), env('EDGEAI_STREAM_LEASE_SECONDS', '120'),
                env('EDGEAI_STREAM_BROKER_URL', 'ssl://' + broker + '.edgeai.svc:8883'), env('EDGEAI_STREAM_BROKER_DIGEST', 'sha256:' + hashlib.sha256(credentials['server.crt'].encode()).hexdigest()),
                env('EDGEAI_STREAM_CA_FILE', '/tmp/identity/server.crt'), env('EDGEAI_STREAM_ADMIN_PASSWORD_FILE', '/tmp/identity/admin.password'),
                env('EDGEAI_STREAM_PRINCIPAL_KEY_FILE', '/tmp/identity/principal.key'), env('EDGEAI_STREAM_DEVICE_KEY_FILE', '/tmp/identity/device.key')]
            api_env += [secret_env(name) for name in ('EDGEAI_API_USER', 'EDGEAI_API_PASSWORD', 'EDGEAI_DB_PASSWORD', 'EDGEAI_MINIO_USER', 'EDGEAI_MINIO_PASSWORD')]
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
                return pod['metadata']['uid']

            api_uid = start_api()
            forward(api, 18443, '/actuator/health/readiness')
            nodes = read(['get', 'nodes', '-o', 'json'])['items']
            node = next(n for n in nodes if n['status']['nodeInfo']['architecture'] == 'amd64' and not n['spec'].get('unschedulable')
                        and not any(t['effect'] in ('NoSchedule', 'NoExecute') for t in n['spec'].get('taints', [])))
            config = {'origin': api_origin, 'runnerImage': snapshot['runnerImage'], 'nodeId': node['metadata']['uid'],
                      'streamSpec': json.loads((ROOT / 'contracts/profiles/service-stream.example.json').read_bytes()),
                      'batchSpec': json.loads((ROOT / 'contracts/profiles/service-execution.example.json').read_bytes()),
                      'reportCommand': 'import time; time.sleep(2)\n' + (ROOT / 'runner/examples/stream_report.py').read_text()}
            create('ConfigMap', root + '-scenario', immutable=True, data={'driver.py': (ROOT / 'scripts/stream_acceptance.py').read_text(), 'config.json': json.dumps(config), 'ca.crt': credentials['server.crt']})
            create('Pod', driver, spec=pod_spec([{'name': 'source-driver', 'image': snapshot['runnerImage'], 'command': ['python3', '-B', '/scenario/driver.py'],
                'env': [secret_env('EDGEAI_API_USER'), secret_env('EDGEAI_API_PASSWORD'), env('SSL_CERT_FILE', '/scenario/ca.crt')], 'securityContext': security,
                'resources': {'requests': {'cpu': '100m', 'memory': '128Mi'}, 'limits': {'cpu': '1', 'memory': '512Mi'}},
                'volumeMounts': [{'name': 'work', 'mountPath': '/work'}, {'name': 'scenario', 'mountPath': '/scenario', 'readOnly': True}]}],
                [{'name': 'work', 'emptyDir': {}}, {'name': 'scenario', 'configMap': {'name': root + '-scenario'}}]))
            wait(lambda: running(driver), 150, 'Owned source driver did not start')
            completed = set()
            deadline = time.monotonic() + 1050
            restarted = False
            while time.monotonic() < deadline:
                running(driver)
                script = "import json;from pathlib import Path;print(json.dumps({n:json.loads(Path('/work',n+'.json').read_text()) for n in ('phase','failure','done','report') if Path('/work',n+'.json').exists()}))"
                state = json.loads(call(['-n', 'edgeai', 'exec', driver, '--', 'python3', '-c', script]))
                assert 'failure' not in state, 'Actual source driver failed: ' + json.dumps(state.get('failure', {}))
                current = state.get('phase')
                if current:
                    run = current['runId'];uuid.UUID(run);run_ids.add(run);observe(run)
                    phase = current['phase']
                    if phase not in completed and ('expectedStates' in current or phase.endswith('-done')):
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
                            if phase == 'recover-first':
                                owned = resources(run)
                                before = [p for p in owned if p['kind'] == 'Pod']
                                assert len(before) == 2 and all(p['metadata']['uid'] in seen for p in before)
                                job = next(p for p in owned if p['kind'] == 'Job' and p['metadata']['labels']['edgeai.io/task-id'] == current['tasks']['sink'])
                                meta = job['metadata']
                                assert meta['labels']['edgeai.io/run-id'] == run and meta['labels']['app.kubernetes.io/managed-by'] == 'edgeai-runtime-controller'
                                snapshot['groupFault'] = {'runId': run, 'deletedJobUid': meta['uid'], 'oldPodUids': sorted(p['metadata']['uid'] for p in before),
                                    'oldAttemptIds': sorted(p['metadata']['labels']['edgeai.io/attempt-id'] for p in before),
                                    'oldGenerationIds': sorted(r['generation']['id'] for r in current['routes'])}
                                # Foreground removal stops the owned Pod; the real controller observes the missing Job.
                                call(['delete', '--raw', '/apis/batch/v1/namespaces/edgeai-runtimes/jobs/' + meta['name'], '-f', '-'],
                                     {'apiVersion': 'v1', 'kind': 'DeleteOptions', 'propagationPolicy': 'Foreground', 'preconditions': {'uid': meta['uid']}})
                            if phase == 'recover-recovered':
                                fault = snapshot['groupFault']
                                after = {p['metadata']['uid'] for p in resources(run) if p['kind'] == 'Pod'}
                                assert len(after) == 2 and not after.intersection(fault['oldPodUids'])
                                old_attempts = ','.join("'" + str(uuid.UUID(a)) + "'" for a in fault['oldAttemptIds'])
                                old_generations = ','.join("'" + str(uuid.UUID(g)) + "'" for g in fault['oldGenerationIds'])
                                rows = query('SELECT desired_state,observed_state FROM edgeai.runtime_instance WHERE attempt_id IN(' + old_attempts + ')').splitlines()
                                assert len(rows) == 2 and all(row == 'STOPPED|TERMINATED' for row in rows)
                                assert query('SELECT count(*) FROM edgeai.route_generation WHERE id IN(' + old_generations + ') AND closed_at IS NOT NULL') == '3'
                                fault.update(newPodUids=sorted(after), oldRuntimesStopped=True, oldGenerationsClosed=True,
                                             restoredAttemptIds=current['expectedAttempts'])
                        else:
                            wait(lambda: not resources(run), 90, 'Actual stream runtime resources were not reclaimed')
                        call(['-n', 'edgeai', 'exec', driver, '--', 'touch', '/work/' + phase + '.continue'])
                        completed.add(phase)
                        print('PASS: actual Kubernetes stream boundary ' + phase, flush=True)
                if 'done' in state:
                    snapshot['cases'] = state['done']['cases']
                    assert {case['case'] for case in snapshot['cases']} == {'auto', 'node', 'recover', 'cancel'} and restarted
                    assert snapshot['groupFault']['oldRuntimesStopped'] and snapshot['groupFault']['oldGenerationsClosed']
                    artifacts = []
                    for case in snapshot['cases']:
                        for row in case['results']:
                            result = row['result'];observed = seen[result['producerPodUid']]
                            assert observed['runId'] == case['runId'] and observed['attemptId'] == result['attemptId']
                            if case['placement']['mode'] == 'NODE':
                                assert observed['nodeUid'] == case['placement']['nodeId']
                            artifacts.append({'artifact': result['artifacts'][0], 'expected': row['expected']})
                    assert len(artifacts) == 9
                    result = subprocess.run(['node', 'scripts/verify-runtime-artifacts.mjs'], input=json.dumps(artifacts).encode(), env=verify_env, capture_output=True, timeout=60)
                    assert result.returncode == 0, 'Actual fixed-version TLS S3 results differed; private output suppressed'
                    print(result.stdout.decode().strip(), flush=True)
                    snapshot['verifiedArtifacts'] = len(artifacts)
                    report_path.write_text(json.dumps(snapshot, indent=2) + '\n')
                    break
                time.sleep(.3)
            else:
                raise AssertionError('Actual Kubernetes stream scenario deadline exceeded')
        except BaseException:
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
            raise
        finally:
            if db_ready:
                try:
                    rows = query('SELECT id FROM edgeai.workflow_run')
                    run_ids.update(str(uuid.UUID(row)) for row in rows.splitlines())
                except (AssertionError, OSError, subprocess.TimeoutExpired):
                    pass
            try:
                for run in run_ids:
                    for item in resources(run):
                        meta = item['metadata'];assert meta['labels']['edgeai.io/run-id'] == run
                        assert meta['labels'].get('app.kubernetes.io/managed-by') == 'edgeai-runtime-controller'
                        plural = {'Pod': 'pods', 'Job': 'jobs', 'Secret': 'secrets'}[item['kind']]
                        path = ('/apis/batch/v1/namespaces/edgeai-runtimes/jobs/' if plural == 'jobs' else '/api/v1/namespaces/edgeai-runtimes/' + plural + '/') + meta['name']
                        # The IDs came from this invocation's isolated DB, never another API's Runs.
                        result = subprocess.run(k + ['delete', '--raw', path, '-f', '-'],
                            input=json.dumps({'apiVersion': 'v1', 'kind': 'DeleteOptions', 'preconditions': {'uid': meta['uid']}}).encode(), capture_output=True, timeout=30)
                        # A concurrent real controller may already have removed the same object.
                        if result.returncode:
                            assert not call(['-n', 'edgeai-runtimes', 'get', plural, meta['name'], '--ignore-not-found', '-o', 'name']).strip()
                    wait(lambda: not resources(run), 90, 'Owned Run cleanup incomplete')
            finally:
                for process in forwards:
                    process.terminate()
                    try:
                        process.wait(5)
                    except subprocess.TimeoutExpired:
                        process.kill();process.wait(5)
                for record in reversed(records):
                    remove(*record)
    print('PASS: actual TLS Kubernetes multi-device DAG, group retry, API restart and cancellation; all owned resources removed', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        import traceback
        frames = ','.join(Path(f.filename).name + ':' + str(f.lineno) for f in traceback.extract_tb(error.__traceback__))
        print('FAIL: stream Kubernetes ' + type(error).__name__ + ' ' + frames, flush=True)
        raise SystemExit(1)
