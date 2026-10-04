"""Create, verify and delete one isolated kind cluster. Never read the user's kubeconfig.

API/dashboard are the locally built images; Runner/MinIO are immutable CI-tested digests.
Only explicitly projected nonsecret status is retained on failure, never logs or Secrets.
"""
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid
from vd_acceptance import VDScenario, wait as vd_wait
from mixed_remote_acceptance import run_mixed_remote

ROOT = Path(__file__).resolve().parent.parent
KIND_VERSION = 'v0.33.0'
KIND_SHA = 'aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d'
NODE_IMAGE = 'kindest/node:v1.35.8@sha256:07b2536e30b803ed61d1677a79df6115f798ce64c80f9e22f6ed45afd09323c0'
LABELS = {'app.kubernetes.io/part-of': 'edgeai', 'app.kubernetes.io/managed-by': 'edgeai-bootstrap'}


class GateFailure(Exception):
    """Only fixed, credential-free diagnostics may be passed to this exception."""


def call(command, *, data=None, env=None, timeout=120, label='Test operation'):
    result = subprocess.run(command, input=data, text=True, capture_output=True, env=env, timeout=timeout, cwd=ROOT)
    if result.returncode:
        raise GateFailure(label + ' failed; upstream output suppressed')
    return result.stdout


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def remote_fixture(state,runner_image,cluster):
    documents=[]
    # Independent, persistent TLS provider. API replacement must not replace the provider or its data.
    cert, private_key = state / 'remote.crt', state / 'remote.key'
    call(['openssl', 'req', '-x509', '-nodes', '-newkey', 'rsa:2048', '-days', '1',
          '-subj', '/CN=edgeai-remote.edgeai.svc', '-addext', 'subjectAltName=DNS:edgeai-remote.edgeai.svc',
          '-keyout', str(private_key), '-out', str(cert)], label='Test provider certificate generation')
    private_key.chmod(0o600)
    credential = secrets.token_urlsafe(32)
    for secret_name, values in [('edgeai-remote-server', {'tls.crt': cert.read_text(), 'tls.key': private_key.read_text(), 'token': credential}),
                                ('edgeai-remote-client', {'ca.crt': cert.read_text(), 'token': credential})]:
        documents.append({'apiVersion': 'v1', 'kind': 'Secret',
            'metadata': {'name': secret_name, 'namespace': 'edgeai', 'labels': LABELS}, 'stringData': values})
    del credential, values
    documents.append({'apiVersion': 'v1', 'kind': 'ConfigMap',
        'metadata': {'name': 'edgeai-remote-code', 'namespace': 'edgeai', 'labels': LABELS},
        'data': {'remote_server.py': (ROOT / 'simulator/remote_server.py').read_text(),
                 'remote-provider.py': (ROOT / 'deploy/kind/remote-provider.py').read_text()}})
    provider_labels = {**LABELS, 'app': 'edgeai-remote', 'edgeai.io/test-cluster': cluster}
    documents.append({'apiVersion': 'v1', 'kind': 'List', 'items': [
        {'apiVersion': 'v1', 'kind': 'PersistentVolumeClaim', 'metadata': {'name': 'edgeai-remote-data', 'namespace': 'edgeai', 'labels': provider_labels},
         'spec': {'accessModes': ['ReadWriteOnce'], 'resources': {'requests': {'storage': '1Gi'}}}},
        {'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 'edgeai-remote', 'namespace': 'edgeai', 'labels': provider_labels},
         'spec': {'selector': {'app': 'edgeai-remote'}, 'ports': [{'name': 'https', 'port': 8443, 'targetPort': 8443}]}},
        {'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': {'name': 'edgeai-remote', 'namespace': 'edgeai', 'labels': provider_labels},
         'spec': {'replicas': 1, 'strategy': {'type': 'Recreate'}, 'selector': {'matchLabels': {'app': 'edgeai-remote'}},
             'template': {'metadata': {'labels': provider_labels}, 'spec': {'automountServiceAccountToken': False,
                 'securityContext': {'runAsNonRoot': True, 'runAsUser': 10001, 'runAsGroup': 10001, 'fsGroup': 10001, 'seccompProfile': {'type': 'RuntimeDefault'}},
                 'containers': [{'name': 'provider', 'image': runner_image, 'command': ['python3', '/opt/probe/remote-provider.py'],
                     'args': ['--state-dir', '/data/provider', '--token-file', '/var/run/remote/token', '--cert-file', '/var/run/remote/tls.crt', '--key-file', '/var/run/remote/tls.key'],
                     'securityContext': {'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True, 'capabilities': {'drop': ['ALL']}},
                     'resources': {'requests': {'cpu': '50m', 'memory': '64Mi'}, 'limits': {'cpu': '1', 'memory': '256Mi'}},
                     'readinessProbe': {'tcpSocket': {'port': 8443}, 'periodSeconds': 2},
                     'volumeMounts': [{'name': 'code', 'mountPath': '/opt/probe', 'readOnly': True}, {'name': 'identity', 'mountPath': '/var/run/remote', 'readOnly': True}, {'name': 'data', 'mountPath': '/data'}]}],
                 'volumes': [{'name': 'code', 'configMap': {'name': 'edgeai-remote-code'}},
                             {'name': 'identity', 'secret': {'secretName': 'edgeai-remote-server', 'defaultMode': 288}},
                             {'name': 'data', 'persistentVolumeClaim': {'claimName': 'edgeai-remote-data'}}]}}}}
    ]})
    return documents


def remote_api_patch():
    return {'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': {'name': 'edgeai-api'},
    'spec': {'template': {'spec': {'containers': [{'name': 'api', 'env': [
        {'name': 'EDGEAI_VD_ENABLED', 'value': 'true'}, {'name': 'EDGEAI_VD_LEASE_SECONDS', 'value': '60'},
        {'name': 'EDGEAI_REMOTE_ENABLED', 'value': 'true'}, {'name': 'EDGEAI_REMOTE_URL', 'value': 'https://edgeai-remote.edgeai.svc:8443'},
        {'name': 'EDGEAI_REMOTE_TOKEN_FILE', 'value': '/var/run/edgeai-remote/token'}, {'name': 'EDGEAI_REMOTE_CA_FILE', 'value': '/var/run/edgeai-remote/ca.crt'}],
        'volumeMounts': [{'name': 'remote-client', 'mountPath': '/var/run/edgeai-remote', 'readOnly': True}]}],
        'volumes': [{'name': 'remote-client', 'secret': {'secretName': 'edgeai-remote-client', 'defaultMode': 288}}]}}}}


def main():
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        print('BLOCKED: this kind acceptance environment currently requires Linux amd64')
        return 2
    for executable in ['docker', 'kubectl', 'node', 'openssl', 'keytool', 'mosquitto_ctrl']:
        if not shutil.which(executable):
            print('BLOCKED: required executable missing: ' + executable)
            return 2
    if subprocess.run(['docker', 'info'], capture_output=True).returncode:
        print('BLOCKED: Docker daemon unavailable; no permissions changed')
        return 2
    if not (ROOT / '.env').is_file() or not (ROOT / 'backend/app/build/libs/edgeai-control-plane.jar').is_file():
        print('BLOCKED: prepare .env and the API JAR from the exact built image for restored-database recovery')
        return 2
    recovery_database = subprocess.run(['docker', 'compose', '--project-name', 'edgeai-dev', '--env-file', str(ROOT / '.env'),
        '-f', str(ROOT / 'deploy/compose/compose.yaml'), 'exec', '-T', 'postgres', 'pg_isready', '-q'],
        capture_output=True, timeout=30)
    if recovery_database.returncode:
        print('BLOCKED: the separate Compose PostgreSQL recovery fixture must be ready before kind acceptance')
        return 2
    images = {}
    for name in ['API', 'DASHBOARD']:
        image = os.environ.get('EDGEAI_' + name + '_IMAGE', '')
        if not re.fullmatch(r'ghcr\.io/dsa04156/edgeai-[a-z]+:sha-[a-f0-9]{40}', image):
            print('BLOCKED: set the exact built EDGEAI_' + name + '_IMAGE commit tag')
            return 2
        call(['docker', 'image', 'inspect', image], label='Built image lookup')
        images[name.lower()] = image
    for name in ['RUNNER', 'MINIO']:
        digest = os.environ.get('EDGEAI_' + name + '_DIGEST', '')
        if not re.fullmatch(r'sha256:[a-f0-9]{64}', digest):
            print('BLOCKED: set the tested EDGEAI_' + name + '_DIGEST')
            return 2
        images[name.lower()] = 'ghcr.io/dsa04156/edgeai-' + name.lower() + '@' + digest

    tools = ROOT / '.tools'
    tools.mkdir(exist_ok=True)
    binary = tools / ('kind-' + KIND_VERSION)
    if not binary.exists():
        payload = urllib.request.urlopen('https://github.com/kubernetes-sigs/kind/releases/download/' + KIND_VERSION + '/kind-linux-amd64', timeout=60).read(32 * 1024 * 1024)
        if hashlib.sha256(payload).hexdigest() != KIND_SHA:
            raise RuntimeError('kind release checksum mismatch')
        with binary.open('xb') as file:
            file.write(payload)
        binary.chmod(0o700)
    if binary.is_symlink() or hashlib.sha256(binary.read_bytes()).hexdigest() != KIND_SHA:
        raise RuntimeError('Refusing modified kind binary')

    name = 'edgeai-ci-' + uuid.uuid4().hex[:12]
    context = 'kind-' + name
    forwards = []
    created = False
    with tempfile.TemporaryDirectory(prefix='kind-', dir=tools) as directory:
        state = Path(directory)
        env = {**os.environ, 'KUBECONFIG': str(state / 'kubeconfig'), 'KIND_EXPERIMENTAL_PROVIDER': 'docker'}
        kube = ['kubectl', '--context', context, '--request-timeout=20s']
        def kcall(arguments, value=None, timeout=120):
            return call(kube + arguments, data=None if value is None else json.dumps(value), env=env, timeout=timeout, label='Isolated Kubernetes operation')

        def forward(service, remote_port, health):
            port = free_port()
            process = subprocess.Popen(kube + ['-n', 'edgeai', 'port-forward', '--address', '127.0.0.1', 'svc/' + service, f'{port}:{remote_port}'],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
            forwards.append(process)
            url = 'http://127.0.0.1:' + str(port)
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline and process.poll() is None:
                try:
                    with urllib.request.urlopen(url + health, timeout=3) as response:
                        if response.status == 200:
                            return url
                except OSError:
                    time.sleep(0.5)
            raise RuntimeError('Isolated service port-forward not ready: ' + service)

        try:
            if name in call([str(binary), 'get', 'clusters'], env=env).splitlines():
                raise RuntimeError('Refusing pre-existing kind cluster')
            created = True  # Also clean up a partially created cluster if create fails.
            print('Creating isolated kind cluster ' + name, flush=True)
            call([str(binary), 'create', 'cluster', '--name', name, '--kubeconfig', env['KUBECONFIG'], '--image', NODE_IMAGE,
                  '--config', 'deploy/kind/cluster.json', '--wait', '180s'], env=env, timeout=300, label='kind cluster creation')
            nodes = json.loads(kcall(['get', 'nodes', '-o', 'json']))['items']
            assert len(nodes) == 3 and all(n['metadata']['name'].startswith(name + '-') for n in nodes)
            call([str(binary), 'load', 'docker-image', images['api'], images['dashboard'], '--name', name], env=env, timeout=240, label='Built image load')
            kcall(['create', '-f', '-'], {'apiVersion': 'v1', 'kind': 'Namespace', 'metadata': {'name': 'edgeai', 'labels': {**LABELS, 'edgeai.io/test-cluster': name}}})
            kcall(['-n', 'edgeai', 'apply', '-f', 'deploy/kubernetes/base/serviceaccount.yaml'])
            kcall(['apply', '-f', 'deploy/kubernetes/bootstrap/node-reader.json'])
            call(['python3', 'scripts/bootstrap-runtime.py', '--context', context], env=env)
            call(['python3', 'scripts/bootstrap-runtime-secrets.py', '--context', context, '--state-dir', str(state / 'secrets')], env=env)
            env.update(EDGEAI_API_USER='edgeai', EDGEAI_API_PASSWORD=secrets.token_hex(24), EDGEAI_DB_PASSWORD=secrets.token_hex(24))
            kcall(['create', '-f', '-'], {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'edgeai-runtime', 'namespace': 'edgeai', 'labels': LABELS},
                   'stringData': {key: env[key] for key in ['EDGEAI_API_PASSWORD', 'EDGEAI_DB_PASSWORD']}})
            for line in (state / 'secrets/edgeai-storage.env').read_text().splitlines():
                key, value = line.split('=', 1)
                env[key] = value
            for document in remote_fixture(state,images['runner'],name):
                kcall(['create', '-f', '-'], document)
            # Kustomize resources stay under this checkout; the overlay contains no credentials.
            with tempfile.TemporaryDirectory(prefix='.run-', dir=ROOT / 'deploy/kind') as overlay:
                pins = []
                for component in ['api', 'dashboard', 'minio']:
                    ref = images[component]
                    pins.append({'name': 'ghcr.io/dsa04156/edgeai-' + component,
                                 **({'digest': ref.split('@')[1]} if '@' in ref else {'newTag': ref.rsplit(':', 1)[1]})})
                (Path(overlay) / 'remote-api.json').write_text(json.dumps(remote_api_patch()))
                (Path(overlay) / 'kustomization.yaml').write_text(json.dumps({'apiVersion': 'kustomize.config.k8s.io/v1beta1', 'kind': 'Kustomization',
                    'namespace': 'edgeai', 'resources': ['../../kubernetes/base'], 'images': pins, 'patches': [{'path': 'remote-api.json'}]}))
                kcall(['apply', '-k', overlay])
            for resource in ['statefulset/edgeai-postgres', 'statefulset/edgeai-minio', 'deployment/edgeai-remote', 'deployment/edgeai-api', 'deployment/edgeai-dashboard']:
                print('Waiting for ' + resource, flush=True)
                kcall(['-n', 'edgeai', 'rollout', 'status', resource, '--timeout=240s'], timeout=250)
            env['EDGEAI_STORAGE_URL'] = forward('edgeai-minio', 9000, '/minio/health/ready')
            call(['node', 'scripts/bootstrap-artifact-bucket.mjs'], env=env)
            env['EDGEAI_SMOKE_API_URL'] = forward('edgeai-api', 18080, '/actuator/health/readiness')
            env['EDGEAI_SMOKE_UI_URL'] = forward('edgeai-dashboard', 13080, '/api/health')
            env['EDGEAI_SMOKE_RUNTIME_ENABLED'] = 'true'
            # The UI proxy survives API Pod replacement; direct kubectl port-forward to the old Pod does not.
            env['EDGEAI_SMOKE_PROXY_URL'] = env['EDGEAI_SMOKE_UI_URL']
            print('Running real scheduler, BATCH, artifacts, cancellation and runtime fault acceptance', flush=True)
            result = subprocess.run(['python3', 'scripts/smoke-runtime.py', '--context', context, '--faults', '--remote', '--report', '.tools/kind-runtime.json'], env=env, timeout=1500)
            if result.returncode:
                raise RuntimeError('kind runtime acceptance failed')
            scenario = VDScenario(context, '.tools/kind-vd.json', env)
            def restart_vd_api():
                meta = json.loads(kcall(['get', 'namespace', 'edgeai', '-o', 'json']))['metadata']
                assert meta['labels'].get('edgeai.io/test-cluster') == name and context == 'kind-' + name
                before = {p['metadata']['uid'] for p in json.loads(kcall(['-n', 'edgeai', 'get', 'pods', '-l', 'app=edgeai-api', '-o', 'json']))['items']}
                started = time.monotonic()
                kcall(['-n', 'edgeai', 'rollout', 'restart', 'deployment/edgeai-api'])
                kcall(['-n', 'edgeai', 'rollout', 'status', 'deployment/edgeai-api', '--timeout=180s'], timeout=190)
                vd_wait(lambda: before.isdisjoint({p['metadata']['uid'] for p in json.loads(kcall(['-n', 'edgeai', 'get', 'pods', '-l', 'app=edgeai-api', '-o', 'json']))['items']}), 60, 'Old API Pod did not terminate')
                scenario.origin = forward('edgeai-api', 18080, '/actuator/health/readiness')
                return {'kind': 'actual-kubernetes-api-pod', 'replaced': True, 'elapsedSeconds': round(time.monotonic() - started, 3)}
            scenario.run(restart_vd_api, tasks=True, mixed=True)
            scenario = VDScenario(context, '.tools/kind-mixed-remote.json', env)
            def mixed_provider_pod():
                pods = json.loads(kcall(['-n', 'edgeai', 'get', 'pods', '-l', 'app=edgeai-remote', '-o', 'json']))['items']
                assert len(pods) == 1 and pods[0]['metadata']['labels'].get('edgeai.io/test-cluster') == name
                return pods[0]
            run_mixed_remote(scenario, mixed_provider_pod, restart_vd_api)
            print('Running actual TLS multi-device STREAM/BATCH, group retry, API restart and cancellation acceptance', flush=True)
            source_revision = images['api'].rsplit(':sha-', 1)[1]
            result = subprocess.run(['python3', 'scripts/test-stream-kubernetes.py', '--context', context,
                '--api-image', images['api'], '--api-source', source_revision,
                '--runner-image', images['runner'], '--runner-source', source_revision,
                '--minio-image', images['minio'], '--report', '.tools/kind-stream.json'], env=env, timeout=1950)
            if result.returncode:
                raise RuntimeError('kind multi-device stream acceptance failed')
            print('Verifying retained stream identities and persistent TLS broker replacement', flush=True)
            stream_state = str(state / 'stream-identities')
            call(['python3', 'scripts/bootstrap-stream-secrets.py', '--context', context, '--state-dir', stream_state], env=env)
            call(['python3', 'scripts/test-stream-bootstrap.py', '--context', context, '--state-dir', stream_state], env=env)
            broker_config = (ROOT / 'deploy/kubernetes/components/stream/mosquitto.conf').read_text()
            kcall(['-n', 'edgeai', 'create', '-f', '-'], {'apiVersion': 'v1', 'kind': 'ConfigMap',
                'metadata': {'name': 'edgeai-mqtt-config-v1', 'namespace': 'edgeai', 'labels': LABELS},
                'immutable': True, 'data': {'mosquitto.conf': broker_config}})
            kcall(['-n', 'edgeai', 'create', '-f', 'deploy/kubernetes/components/stream/broker.yaml'])
            output = call([str(ROOT / '.tools/stream-venv/bin/python'), 'scripts/test-stream-platform-broker.py',
                '--context', context, '--report', '.tools/kind-stream-broker.json'], env=env, timeout=300, label='Persistent TLS broker acceptance')
            print(output.strip(), flush=True)
            print(call(['python3', 'scripts/test-stream-minio-tls.py', '--context', context, '--minio-image', images['minio'],
                '--report', '.tools/kind-stream-minio-tls.json'], env=env, timeout=300).strip(), flush=True)
            result = subprocess.run(['python3', 'scripts/test-recovery-stop-live.py', '--context', context,
                '--runner-image', images['runner'], '--runner-source', source_revision,
                '--report', '.tools/kind-recovery-stop.json'], env=env, timeout=480)
            if result.returncode:
                raise RuntimeError('kind recovery producer termination acceptance failed')
            result = subprocess.run(['bash', 'scripts/collect-evidence.sh', 'recovery-kubernetes-retire',
                'bash', 'scripts/test-recovery-kubernetes-retire.sh', '--context', context, '--transport', 'compose',
                '--vd-tasks', '--workflows',
                '--runner-image', images['runner'], '--runner-source', source_revision,
                '--report', '.tools/kind-recovery-kubernetes-retire.json'], env=env, timeout=600)
            if result.returncode:
                raise RuntimeError('kind restored Kubernetes runtime retirement acceptance failed')
            # Exercise the real component on the original persistent API/DB/storage deployment.
            with tempfile.TemporaryDirectory(prefix='.stream-', dir=ROOT / 'deploy/kind') as overlay:
                (Path(overlay) / 'remote-api.json').write_text(json.dumps(remote_api_patch()))
                (Path(overlay) / 'kustomization.yaml').write_text(json.dumps({'apiVersion': 'kustomize.config.k8s.io/v1beta1', 'kind': 'Kustomization',
                    'namespace': 'edgeai', 'resources': ['../../kubernetes/base'], 'images': pins,
                    'components': ['../../kubernetes/components/stream'], 'patches': [{'path': 'remote-api.json'}]}))
                kcall(['-n', 'edgeai', 'apply', '-k', overlay])
            for resource in ['statefulset/edgeai-minio', 'deployment/edgeai-api']:
                kcall(['-n', 'edgeai', 'rollout', 'status', resource, '--timeout=240s'], timeout=250)
            result = subprocess.run(['python3', 'scripts/demo-multidevice.py', '--context', context,
                '--runner-image', images['runner'], '--runner-source', source_revision, '--report', '.tools/kind-multidevice-demo.json'], env=env, timeout=900)
            if result.returncode:
                raise RuntimeError('kind persistent-deployment stream demo failed')
            print('PASS: isolated kind runtime, VD, multi-device STREAM, persistent TLS broker and deployment demo acceptance', flush=True)
            return 0
        finally:
            for process in forwards:
                process.terminate()
            for process in forwards:
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            if created:
                try:
                    status = json.loads(kcall(['-n', 'edgeai', 'get', 'pods', '-o', 'json']))
                    safe = [{'name': p['metadata']['name'], 'phase': p['status'].get('phase'),
                             'conditions': [{'type': c['type'], 'status': c['status'], 'reason': c.get('reason')}
                                            for c in p['status'].get('conditions', [])],
                             'containers': [{'name': c['name'], 'ready': c['ready'], 'restartCount': c['restartCount'],
                                             'waitingReason': c.get('state', {}).get('waiting', {}).get('reason')} for c in p['status'].get('containerStatuses', [])]} for p in status['items']]
                    print('kind final Pod status: ' + json.dumps(safe), flush=True)
                    claims = json.loads(kcall(['-n', 'edgeai', 'get', 'pvc', '-o', 'json']))['items']
                    print('kind final PVC status: ' + json.dumps([{'name': c['metadata']['name'],
                        'phase': c.get('status', {}).get('phase'), 'storageClass': c['spec'].get('storageClassName'),
                        'conditions': [{'type': v['type'], 'status': v['status'], 'reason': v.get('reason')}
                                       for v in c.get('status', {}).get('conditions', [])]} for c in claims]), flush=True)
                except Exception:
                    print('kind status unavailable; no sensitive diagnostics collected', flush=True)
                call([str(binary), 'delete', 'cluster', '--name', name, '--kubeconfig', env['KUBECONFIG']], env=env, timeout=120, label='Owned kind cluster cleanup')
                print('Deleted only the created kind cluster ' + name, flush=True)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        # Exceptions from Kubernetes/storage/network libraries can contain credentials.
        print('FAIL: '+(str(error) if isinstance(error,GateFailure) else 'kind acceptance ('+type(error).__name__+'); upstream details suppressed'),flush=True)
        raise SystemExit(1)
