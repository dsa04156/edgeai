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


def main():
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        print('BLOCKED: this kind acceptance environment currently requires Linux amd64')
        return 2
    for executable in ['docker', 'kubectl', 'node']:
        if not shutil.which(executable):
            print('BLOCKED: required executable missing: ' + executable)
            return 2
    if subprocess.run(['docker', 'info'], capture_output=True).returncode:
        print('BLOCKED: Docker daemon unavailable; no permissions changed')
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
            # Kustomize resources stay under this checkout; the overlay contains no credentials.
            with tempfile.TemporaryDirectory(prefix='.run-', dir=ROOT / 'deploy/kind') as overlay:
                pins = []
                for component in ['api', 'dashboard', 'minio']:
                    ref = images[component]
                    pins.append({'name': 'ghcr.io/dsa04156/edgeai-' + component,
                                 **({'digest': ref.split('@')[1]} if '@' in ref else {'newTag': ref.rsplit(':', 1)[1]})})
                (Path(overlay) / 'kustomization.yaml').write_text(json.dumps({'apiVersion': 'kustomize.config.k8s.io/v1beta1', 'kind': 'Kustomization',
                    'namespace': 'edgeai', 'resources': ['../../kubernetes/base'], 'images': pins}))
                kcall(['apply', '-k', overlay])
            for resource in ['statefulset/edgeai-postgres', 'statefulset/edgeai-minio', 'deployment/edgeai-api', 'deployment/edgeai-dashboard']:
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
            result = subprocess.run(['python3', 'scripts/smoke-runtime.py', '--context', context, '--faults', '--report', '.tools/kind-runtime.json'], env=env, timeout=900)
            if result.returncode:
                raise RuntimeError('kind runtime acceptance failed')
            print('PASS: isolated kind runtime acceptance', flush=True)
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
                             'containers': [{'name': c['name'], 'ready': c['ready'], 'restartCount': c['restartCount'],
                                             'waitingReason': c.get('state', {}).get('waiting', {}).get('reason')} for c in p['status'].get('containerStatuses', [])]} for p in status['items']]
                    print('kind final Pod status: ' + json.dumps(safe), flush=True)
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
