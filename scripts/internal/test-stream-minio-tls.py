"""Verify the actual STREAM component's MinIO TLS mounts with isolated data and credentials."""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]


def wait(predicate, seconds=150):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(.5)
    raise AssertionError('Isolated MinIO TLS check timed out')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context', required=True)
    parser.add_argument('--minio-image')
    parser.add_argument('--report', type=Path, default=ROOT / '.tools/stream-minio-tls.json')
    args = parser.parse_args()
    k = ['kubectl', '--context', args.context, '--request-timeout=20s']
    def call(arguments, value=None):
        p = subprocess.run(k + arguments, input=None if value is None else json.dumps(value).encode(), capture_output=True, timeout=40)
        assert p.returncode == 0, 'Isolated MinIO Kubernetes operation failed; private details suppressed'
        return p.stdout
    ns = json.loads(call(['get', 'namespace', 'edgeai', '-o', 'json']))
    assert ns['metadata']['labels']['app.kubernetes.io/managed-by'] == 'edgeai-bootstrap'
    assert ns['metadata']['labels']['app.kubernetes.io/part-of'] == 'edgeai'
    # The exact component template, not a separate hand-written approximation.
    with tempfile.TemporaryDirectory(prefix='.minio-tls-', dir=ROOT / 'deploy/kubernetes/overlays') as overlay:
        path = Path(overlay)
        pinned = json.loads((ROOT / 'deploy/kubernetes/overlays/dev/release.json').read_bytes())['minioDigest']
        (path / 'kustomization.yaml').write_text(json.dumps({'apiVersion': 'kustomize.config.k8s.io/v1beta1', 'kind': 'Kustomization',
            'namespace': 'edgeai', 'resources': ['../../base'], 'components': ['../../components/stream'],
            'images': [{'name': 'ghcr.io/dsa04156/edgeai-minio', 'digest': pinned}]}))
        rendered = subprocess.run(['kubectl', 'kustomize', str(path)], capture_output=True, check=True).stdout
        raw = subprocess.run(k + ['-n', 'edgeai', 'create', '--dry-run=client', '-f', '-', '-o', 'json'],
                             input=rendered, capture_output=True, check=True).stdout.decode()
        documents = []
        while raw.strip():
            value, end = json.JSONDecoder().raw_decode(raw.lstrip())
            documents.append(value)
            raw = raw.lstrip()[end:]
    sts = next(d for d in documents if d['kind'] == 'StatefulSet' and d['metadata']['name'] == 'edgeai-minio')
    assert sts['metadata']['namespace'] == 'edgeai'
    spec = sts['spec']['template']['spec']
    spec['restartPolicy'] = 'Never'
    container = spec['containers'][0]
    if args.minio_image:
        import re
        assert re.fullmatch(r'ghcr\.io/dsa04156/edgeai-minio@sha256:[0-9a-f]{64}', args.minio_image)
        container['image'] = args.minio_image
    assert '@sha256:' in container['image']
    root = 'edgeai-minio-tls-check-' + uuid.uuid4().hex[:12]
    labels = {'app.kubernetes.io/part-of': 'edgeai', 'app.kubernetes.io/managed-by': 'edgeai-stream-test', 'edgeai.io/test-id': root}
    user, password = 'edgeai-probe', secrets.token_hex(32)
    container['env'] = [{'name': name, 'valueFrom': {'secretKeyRef': {'name': root, 'key': name}}} for name in ('MINIO_ROOT_USER', 'MINIO_ROOT_PASSWORD')]
    spec['volumes'].append({'name': 'data', 'emptyDir': {}})
    assert not any('persistentVolumeClaim' in v for v in spec['volumes'])
    records, forward = [], None
    def create(kind, **content):
        doc = {'apiVersion': 'v1', 'kind': kind, 'metadata': {'name': root, 'namespace': 'edgeai', 'labels': labels}, **content}
        result = json.loads(call(['-n', 'edgeai', 'create', '-f', '-', '-o', 'json'], doc))
        records.append((kind.lower(), result['metadata']['uid']))
        return result
    try:
        create('Secret', stringData={'MINIO_ROOT_USER': user, 'MINIO_ROOT_PASSWORD': password})
        pod = create('Pod', spec=spec)
        def ready():
            current = json.loads(call(['-n', 'edgeai', 'get', 'pod', root, '-o', 'json']))
            assert current['metadata']['uid'] == pod['metadata']['uid']
            assert current['status'].get('phase') not in ('Failed', 'Succeeded'), 'Isolated TLS MinIO exited'
            return current if any(c['type'] == 'Ready' and c['status'] == 'True' for c in current['status'].get('conditions', [])) else None
        current = wait(ready)
        image_id = current['status']['containerStatuses'][0]['imageID']
        assert image_id.endswith('@' + container['image'].split('@', 1)[1])
        with tempfile.TemporaryDirectory(prefix='minio-ca-', dir=ROOT / '.tools') as work:
            ca = json.loads(call(['-n', 'edgeai', 'get', 'configmap', 'edgeai-runtime-ca-v1', '-o', 'json']))['data']['ca.crt']
            ca_path = Path(work) / 'ca.crt'
            ca_path.write_text(ca)
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0));port = sock.getsockname()[1]
            forward = subprocess.Popen(k + ['-n', 'edgeai', 'port-forward', '--address', '127.0.0.1', 'pod/' + root, str(port) + ':9000'],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            origin = 'https://localhost:' + str(port)
            tls = ssl.create_default_context(cafile=str(ca_path))
            def health():
                assert forward.poll() is None
                try:
                    with urllib.request.urlopen(origin + '/minio/health/ready', context=tls, timeout=3) as r:
                        return r.status == 200
                except OSError:
                    return False
            wait(health, 30)
            env = {**os.environ, 'EDGEAI_STORAGE_PROBE_URL': origin, 'EDGEAI_MINIO_USER': user,
                   'EDGEAI_MINIO_PASSWORD': password, 'NODE_EXTRA_CA_CERTS': str(ca_path)}
            result = subprocess.run(['node', 'scripts/internal/storage-probe.mjs'], capture_output=True, env=env, timeout=60)
            assert result.returncode == 0, 'TLS storage byte roundtrip or anonymous rejection failed; private details suppressed'
            print(result.stdout.decode().strip(), flush=True)
            report = {'scope': 'rendered-stream-minio-tls', 'podUid': pod['metadata']['uid'], 'imageId': image_id,
                      'readOnlySecretMount': True, 'isolatedData': True, 'tlsHealth': True, 'bytesRoundtrip': 262144, 'anonymousStatus': 403}
            args.report.write_text(json.dumps(report, indent=2) + '\n')
    finally:
        if forward:
            forward.terminate();forward.wait(timeout=10)
        for kind, uid in reversed(records):
            current = json.loads(call(['-n', 'edgeai', 'get', kind, root, '--ignore-not-found', '-o', 'json']) or b'null')
            if not current:
                continue
            assert current['metadata']['uid'] == uid and current['metadata']['labels']['edgeai.io/test-id'] == root
            plural = {'pod': 'pods', 'secret': 'secrets'}[kind]
            call(['delete', '--raw', '/api/v1/namespaces/edgeai/' + plural + '/' + root, '-f', '-'],
                 {'apiVersion': 'v1', 'kind': 'DeleteOptions', 'preconditions': {'uid': uid}})
            wait(lambda: not call(['-n', 'edgeai', 'get', kind, root, '--ignore-not-found', '-o', 'name']).strip(), 90)
    print('PASS: exact rendered MinIO TLS component with pinned image and isolated data; all owned fixtures removed')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        import traceback
        locations = ','.join(Path(f.filename).name + ':' + str(f.lineno) for f in traceback.extract_tb(error.__traceback__))
        print('FAIL: MinIO TLS component ' + type(error).__name__ + ' ' + locations, flush=True)
        raise SystemExit(1)
