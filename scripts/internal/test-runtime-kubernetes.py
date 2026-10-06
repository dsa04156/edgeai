"""Run the real gateway test with a short-lived control-plane SA token, never admin API credentials."""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--context', required=True)
args = parser.parse_args()
kubectl = ['kubectl', '--context', args.context, '--request-timeout=15s']

def capture(arguments):
    result = subprocess.run(kubectl + arguments, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit('Kubernetes gateway test setup failed; credential-bearing output was suppressed.')
    return result.stdout.strip()

for namespace in ['edgeai', 'edgeai-runtimes']:
    metadata = json.loads(capture(['get', 'namespace', namespace, '-o', 'json']))['metadata']
    labels = metadata.get('labels', {})
    if labels.get('app.kubernetes.io/part-of') != 'edgeai' or labels.get('app.kubernetes.io/managed-by') != 'edgeai-bootstrap' or 'deletionTimestamp' in metadata:
        raise SystemExit('Refusing an unowned or terminating namespace.')
server = capture(['config', 'view', '--minify', '-o', 'jsonpath={.clusters[0].cluster.server}'])
ca = capture(['config', 'view', '--minify', '--raw', '-o', 'jsonpath={.clusters[0].cluster.certificate-authority-data}'])
if not server.startswith('https://') or not ca:
    raise SystemExit('A TLS API server with embedded CA is required for this test.')
with tempfile.TemporaryDirectory(prefix='edgeai-runtime-kube-') as directory:
    def private(name, content):
        path = Path(directory, name)
        with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as file:
            file.write(content)
        return str(path)
    ca_file = private('ca.crt', base64.b64decode(ca, validate=True))
    token = capture(['-n', 'edgeai', 'create', 'token', 'edgeai-control-plane', '--duration=10m'])
    token_file = private('token', token.encode())
    env = dict(os.environ, EDGEAI_RUNTIME_TEST_CONTEXT=args.context, EDGEAI_RUNTIME_TEST_URL=server,
               EDGEAI_RUNTIME_TEST_CA_FILE=ca_file, EDGEAI_RUNTIME_TEST_TOKEN_FILE=token_file)
    result = subprocess.run(['backend/gradlew', '-p', 'backend', ':app:kubernetesRuntimeIntegrationTest',
                             '--rerun-tasks', '--console=plain'], env=env)
    raise SystemExit(result.returncode)
