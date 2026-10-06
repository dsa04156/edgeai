"""Real Kubernetes identity replay/recovery and local conflict guards; no Secret rotation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context', required=True)
    parser.add_argument('--state-dir', type=Path, default=ROOT / '.tools/kubernetes/stream-v1')
    args = parser.parse_args()
    k = ['kubectl', '--context', args.context, '--request-timeout=20s']
    def snapshot():
        result = {}
        for namespace, kind, names in [
            ('edgeai', 'secret', ['edgeai-stream-ca-v1', 'edgeai-api-stream-identity-v1', 'edgeai-mqtt-identity-v1', 'edgeai-minio-identity-v1']),
            ('edgeai', 'configmap', ['edgeai-runtime-ca-v1', 'edgeai-stream-config-v1']),
            ('edgeai-runtimes', 'configmap', ['edgeai-runtime-ca-v1']),
        ]:
            for name in names:
                raw = subprocess.run(k + ['-n', namespace, 'get', kind, name, '-o', 'json'], capture_output=True, check=True).stdout
                value = json.loads(raw)
                assert value.get('immutable') is True
                assert value['metadata']['labels']['app.kubernetes.io/managed-by'] == 'edgeai-bootstrap'
                result[(namespace, kind, name)] = (value['metadata']['uid'], value['metadata']['resourceVersion'],
                    hashlib.sha256(json.dumps({key: value.get(key) for key in ('data', 'binaryData')}, sort_keys=True).encode()).hexdigest())
        return result
    def bootstrap(directory, success):
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/internal/bootstrap-stream-secrets.py'), '--context', args.context,
                                 '--state-dir', str(directory)], capture_output=True, timeout=90)
        assert (result.returncode == 0) == success, 'Identity replay/recovery guard returned the wrong outcome; private output suppressed'
        assert snapshot() == initial, 'Existing Kubernetes identities/configuration changed'
    initial = snapshot()
    bootstrap(args.state_dir, True)
    original = json.loads((args.state_dir / 'recovery.json').read_bytes())
    with tempfile.TemporaryDirectory(prefix='stream-recovery-test-', dir=ROOT / '.tools') as work:
        directory = Path(work)
        bootstrap(directory, True)
        path = directory / 'recovery.json'
        assert json.loads(path.read_bytes()) == original, 'Kubernetes recovery changed identity material'
        assert path.stat().st_mode & 0o777 == 0o600
        corrupt = json.loads(path.read_bytes())
        corrupt['secrets']['edgeai-api-stream-identity-v1']['principal.key'] = 'd3Jvbmc='
        path.write_text(json.dumps(corrupt))
        bootstrap(directory, False)
        foreign = json.loads(json.dumps(original))
        foreign['namespaceUids']['edgeai'] = 'different-installation'
        path.write_text(json.dumps(foreign))
        bootstrap(directory, False)
        path.write_text(json.dumps(original))
        path.chmod(0o644)
        bootstrap(directory, False)
        path.chmod(0o600)
        retained = directory / 'retained.json'
        path.rename(retained)
        path.symlink_to(retained)
        bootstrap(directory, False)
    print('PASS: 6 real-cluster bootstrap checks: replay, recovered bundle, changed key, foreign installation, exposed file, symlink; all7 resource UIDs/versions/data unchanged')


if __name__ == '__main__':
    main()
