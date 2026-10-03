"""Exercise the current API JAR in isolated real Kubernetes API/DB Pods.

The immutable deployed API image supplies the JRE; the exact local JAR is streamed into a temporary
volume and checksum verified. This is not a test of a newly built API container image (kind covers that).
Only resources created by this invocation are deleted. The existing deployment/DB/storage stay intact.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.request
import uuid
from vd_acceptance import VDScenario, wait, ROOT


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--context', required=True)
    parser.add_argument('--mixed', action='store_true', help='Also verify a chain across two distinct VDs and a real Node Job')
    args = parser.parse_args()
    k = ['kubectl', '--context', args.context, '--request-timeout=20s']
    def call(arguments, value=None, raw=None, timeout=40):
        data = raw if raw is not None else None if value is None else json.dumps(value).encode()
        r = subprocess.run(k + arguments, input=data, capture_output=True, timeout=timeout)
        assert r.returncode == 0, 'Isolated VD Kubernetes operation failed; private output suppressed'
        return r.stdout
    def read(arguments): return json.loads(call(arguments))
    for namespace in ('edgeai', 'edgeai-runtimes'):
        meta = read(['get', 'namespace', namespace, '-o', 'json'])['metadata']; labels = meta.get('labels', {})
        assert labels.get('app.kubernetes.io/part-of') == 'edgeai' and labels.get('app.kubernetes.io/managed-by') == 'edgeai-bootstrap' and 'deletionTimestamp' not in meta
    root = 'edgeai-vd-check-' + uuid.uuid4().hex[:12]
    labels = {'app.kubernetes.io/part-of': 'edgeai', 'app.kubernetes.io/managed-by': 'edgeai-vd-test', 'edgeai.io/test-id': root}
    records = []; forwards = []; scenario = None
    def metadata(name): return {'name': name, 'namespace': 'edgeai', 'labels': {**labels, 'edgeai.io/test-resource': name}}
    def create(kind, name, **fields):
        obj = {'apiVersion': 'v1', 'kind': kind, 'metadata': metadata(name), **fields}
        created = read_create(obj); records.append((kind.lower(), name, created['metadata']['uid'])); return created
    def read_create(obj): return json.loads(call(['create', '-f', '-', '-o', 'json'], obj))
    def remove(kind, name, uid):
        current = json.loads(call(['-n', 'edgeai', 'get', kind, name, '--ignore-not-found', '-o', 'json']) or b'null')
        if current is None: return
        assert current['metadata']['uid'] == uid and current['metadata']['labels'].get('edgeai.io/test-id') == root, 'Refusing resource not owned by this invocation'
        plural = {'pod': 'pods', 'secret': 'secrets', 'service': 'services'}[kind]
        call(['delete', '--raw', '/api/v1/namespaces/edgeai/' + plural + '/' + name, '-f', '-'],
             {'apiVersion': 'v1', 'kind': 'DeleteOptions', 'preconditions': {'uid': uid}})
        def absent(): return not call(['-n', 'edgeai', 'get', kind, name, '--ignore-not-found', '-o', 'name']).strip()
        wait(absent, 80, 'Owned test resource did not terminate')
    def is_running(name):
        pod = read(['-n', 'edgeai', 'get', 'pod', name, '-o', 'json'])
        assert pod.get('status', {}).get('phase') not in ('Failed', 'Succeeded'), 'Owned test Pod exited before readiness: ' + name
        return pod if any(c.get('state', {}).get('running') for c in pod.get('status', {}).get('containerStatuses', [])) else None
    def is_ready(name):
        pod = read(['-n', 'edgeai', 'get', 'pod', name, '-o', 'json'])
        assert pod.get('status', {}).get('phase') not in ('Failed', 'Succeeded'), 'Owned test Pod exited before readiness: ' + name
        return pod if any(c.get('type') == 'Ready' and c.get('status') == 'True' for c in pod.get('status', {}).get('conditions', [])) else None
    pin = json.loads((ROOT / 'deploy/kubernetes/overlays/dev/release.json').read_text())
    jar = (ROOT / 'backend/app/build/libs/edgeai-control-plane.jar').read_bytes(); jar_hash = hashlib.sha256(jar).hexdigest()
    api_name, db_name, storage_name = root + '-api', root + '-db', root + '-storage'
    api_image = 'ghcr.io/dsa04156/edgeai-api@' + pin['apiDigest']
    postgres = 'public.ecr.aws/docker/library/postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24'
    auth = {'EDGEAI_API_USER': 'vd-test', 'EDGEAI_API_PASSWORD': secrets.token_hex(24)}
    credentials = {**auth, 'EDGEAI_DB_PASSWORD': secrets.token_hex(24), 'EDGEAI_MINIO_USER': 'vd-test', 'EDGEAI_MINIO_PASSWORD': secrets.token_hex(24), 'signing.key': secrets.token_hex(32)}
    def secret_env(name, key): return {'name': name, 'valueFrom': {'secretKeyRef': {'name': root, 'key': key}}}
    def env(name, value): return {'name': name, 'value': value}
    try:
        create('Secret', root, stringData=credentials)
        for name, port in ((db_name, 5432), (api_name, 18080), (storage_name, 9000)):
            create('Service', name, spec={'selector': {'edgeai.io/test-resource': name}, 'ports': [{'name': 'service', 'port': port, 'targetPort': port}]})
        create('Pod', db_name, spec={'restartPolicy': 'Never', 'automountServiceAccountToken': False, 'nodeSelector': {'kubernetes.io/arch': 'amd64'},
            'securityContext': {'runAsNonRoot': True, 'runAsUser': 70, 'runAsGroup': 70, 'fsGroup': 70, 'seccompProfile': {'type': 'RuntimeDefault'}},
            'containers': [{'name': 'postgres', 'image': postgres,
                'env': [env('POSTGRES_DB', 'edgeai'), env('POSTGRES_USER', 'edgeai'), env('PGDATA', '/var/lib/postgresql/data/pgdata'), secret_env('POSTGRES_PASSWORD', 'EDGEAI_DB_PASSWORD')],
                'securityContext': {'allowPrivilegeEscalation': False, 'capabilities': {'drop': ['ALL']}},
                'resources': {'requests': {'cpu': '100m', 'memory': '128Mi'}, 'limits': {'cpu': '1', 'memory': '512Mi'}},
                'readinessProbe': {'exec': {'command': ['pg_isready', '-U', 'edgeai', '-d', 'edgeai']}, 'periodSeconds': 2},
                'volumeMounts': [{'name': 'data', 'mountPath': '/var/lib/postgresql/data'}]}], 'volumes': [{'name': 'data', 'emptyDir': {}}]})
        wait(lambda: is_ready(db_name), 120, 'Isolated test PostgreSQL not ready')
        create('Pod', storage_name, spec={'restartPolicy': 'Never', 'automountServiceAccountToken': False, 'nodeSelector': {'kubernetes.io/arch': 'amd64'},
            'securityContext': {'runAsNonRoot': True, 'runAsUser': 10001, 'runAsGroup': 10001, 'fsGroup': 10001, 'seccompProfile': {'type': 'RuntimeDefault'}},
            'containers': [{'name': 'minio', 'image': 'ghcr.io/dsa04156/edgeai-minio@' + pin['minioDigest'], 'args': ['server', '/data', '--console-address', ':9001'],
                'env': [secret_env('MINIO_ROOT_USER', 'EDGEAI_MINIO_USER'), secret_env('MINIO_ROOT_PASSWORD', 'EDGEAI_MINIO_PASSWORD')],
                'securityContext': {'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True, 'capabilities': {'drop': ['ALL']}},
                'resources': {'requests': {'cpu': '100m', 'memory': '256Mi'}, 'limits': {'cpu': '1', 'memory': '1Gi'}},
                'readinessProbe': {'httpGet': {'path': '/minio/health/ready', 'port': 9000}, 'periodSeconds': 2},
                'volumeMounts': [{'name': 'data', 'mountPath': '/data'}, {'name': 'tmp', 'mountPath': '/tmp'}]}],
            'volumes': [{'name': 'data', 'emptyDir': {}}, {'name': 'tmp', 'emptyDir': {}}]})
        wait(lambda: is_ready(storage_name), 120, 'Isolated versioned storage not ready')
        api_spec = {'restartPolicy': 'Never', 'serviceAccountName': 'edgeai-control-plane', 'automountServiceAccountToken': True, 'nodeSelector': {'kubernetes.io/arch': 'amd64'},
            'securityContext': {'runAsNonRoot': True, 'runAsUser': 10001, 'runAsGroup': 10001, 'fsGroup': 10001, 'seccompProfile': {'type': 'RuntimeDefault'}},
            'containers': [{'name': 'api', 'image': api_image, 'command': ['sh', '-c', 'while [ ! -f /tmp/start ]; do sleep 0.2; done; exec java -XX:MaxRAMPercentage=75.0 -jar /tmp/current-api.jar'],
                'env': [env('EDGEAI_BIND_ADDRESS', '0.0.0.0'), env('EDGEAI_API_PORT', '18080'), env('EDGEAI_DB_HOST', db_name + '.edgeai.svc'), env('EDGEAI_DB_PORT', '5432'),
                    env('EDGEAI_KUBE_ENABLED', 'true'), env('EDGEAI_RUNTIME_ENABLED', 'true'), env('EDGEAI_VD_ENABLED', 'true'), env('EDGEAI_VD_LEASE_SECONDS', '60'),
                    env('EDGEAI_RUNTIME_NAMESPACE', 'edgeai-runtimes'), env('EDGEAI_RUNTIME_CONTROL_PLANE_URL', 'http://' + api_name + '.edgeai.svc:18080'),
                    env('EDGEAI_KUBE_API_URL', 'https://kubernetes.default.svc'), env('EDGEAI_KUBE_TOKEN_FILE', '/var/run/secrets/kubernetes.io/serviceaccount/token'), env('EDGEAI_KUBE_CA_FILE', '/var/run/secrets/kubernetes.io/serviceaccount/ca.crt'),
                    env('EDGEAI_RUNNER_KEY_FILE', '/var/run/edgeai-test/signing.key'), env('EDGEAI_STORAGE_URL', 'http://' + storage_name + '.edgeai.svc:9000'),
                    env('EDGEAI_STORAGE_RUNNER_URL', 'http://' + storage_name + '.edgeai.svc:9000')]
                    + [secret_env(name, name) for name in credentials if name != 'signing.key'],
                'securityContext': {'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True, 'capabilities': {'drop': ['ALL']}},
                'resources': {'requests': {'cpu': '250m', 'memory': '512Mi'}, 'limits': {'cpu': '2', 'memory': '1Gi'}},
                'readinessProbe': {'httpGet': {'path': '/actuator/health/readiness', 'port': 18080}, 'periodSeconds': 2},
                'volumeMounts': [{'name': 'work', 'mountPath': '/tmp'}, {'name': 'identity', 'mountPath': '/var/run/edgeai-test', 'readOnly': True}]}],
            'volumes': [{'name': 'work', 'emptyDir': {}}, {'name': 'identity', 'secret': {'secretName': root, 'defaultMode': 288, 'items': [{'key': 'signing.key', 'path': 'signing.key'}]}}]}
        def start_api():
            pod = create('Pod', api_name, spec=api_spec)
            wait(lambda: is_running(api_name), 120, 'Isolated API container not running')
            call(['-n', 'edgeai', 'exec', '-i', api_name, '--', 'sh', '-c', 'cat > /tmp/current-api.jar'], raw=jar, timeout=90)
            digest = call(['-n', 'edgeai', 'exec', api_name, '--', 'sha256sum', '/tmp/current-api.jar']).decode().split()[0]
            assert digest == jar_hash, 'Transferred API JAR checksum mismatch'
            call(['-n', 'edgeai', 'exec', api_name, '--', 'touch', '/tmp/start'])
            wait(lambda: is_ready(api_name), 120, 'Isolated API not ready')
            return pod['metadata']['uid']
        def forward(name=api_name, target_port=18080, health_path='/actuator/health/readiness'):
            with socket.socket() as sock: sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
            process = subprocess.Popen(k + ['-n', 'edgeai', 'port-forward', '--address', '127.0.0.1', 'svc/' + name, f'{port}:{target_port}'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            forwards.append(process); url = 'http://127.0.0.1:' + str(port)
            def health():
                assert process.poll() is None, 'Owned API forward exited'
                try:
                    with urllib.request.urlopen(url + health_path, timeout=2) as r: return r.status == 200
                except OSError: return False
            wait(health, 30, 'Owned API forward not ready'); return url
        storage_url = forward(storage_name, 9000, '/minio/health/ready')
        test_env = {**os.environ, **{k: v for k, v in credentials.items() if k != 'signing.key'}, 'EDGEAI_STORAGE_URL': storage_url, 'EDGEAI_ARTIFACT_BUCKET': 'edgeai-artifacts'}
        subprocess.run(['node', 'scripts/bootstrap-artifact-bucket.mjs'], env=test_env, check=True, timeout=30)
        current_api_uid = start_api()
        scenario = VDScenario(args.context, '.tools/vd-kubernetes.json', {**test_env, 'EDGEAI_SMOKE_API_URL': forward(), 'EDGEAI_SMOKE_PROXY_URL': ''})
        def restart():
            nonlocal current_api_uid
            started = time.monotonic(); old_uid = current_api_uid; remove('pod', api_name, old_uid)
            records.remove(('pod', api_name, old_uid))
            fresh_uid = start_api(); assert fresh_uid != old_uid; current_api_uid = fresh_uid
            scenario.origin = forward()
            return {'kind': 'actual-kubernetes-api-pod', 'oldUid': old_uid, 'newUid': fresh_uid, 'jarSha256': jar_hash, 'databasePodPreserved': True, 'elapsedSeconds': round(time.monotonic() - started, 3)}
        scenario.run(restart, tasks=True, mixed=args.mixed)
    except BaseException:
        for kind, name, uid in records:
            if kind != 'pod': continue
            try:
                current = read(['-n', 'edgeai', 'get', 'pod', name, '-o', 'json'])
                if current['metadata']['uid'] != uid or current['metadata']['labels'].get('edgeai.io/test-id') != root: continue
                log = call(['-n', 'edgeai', 'logs', name, '--tail=200']).decode(errors='replace')
                for value in credentials.values(): log = log.replace(value, '[REDACTED]')
                path = ROOT / '.tools' / (name + '.log')
                with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as target: target.write(log)
                print('Owned test Pod diagnostic saved privately: ' + str(path.relative_to(ROOT)), flush=True)
                for line in log.splitlines():
                    if line.startswith('Caused by:'): print(line[:500], flush=True)
            except (AssertionError, OSError, subprocess.TimeoutExpired):
                print('Owned test Pod diagnostic unavailable: ' + name, flush=True)
        raise
    finally:
        for process in forwards:
            process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        for kind, name, uid in reversed(records): remove(kind, name, uid)
    print('PASS: isolated API/DB/services/Secret removed with UID ownership checks; deployed platform unchanged', flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
