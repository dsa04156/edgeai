"""Bootstrap runtime-only RBAC in an explicitly selected cluster; never adopt unowned resources."""
import argparse
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--context', required=True)
args = parser.parse_args()
kubectl = ['kubectl', '--context', args.context, '--request-timeout=15s']

def call(arguments, value=None):
    result = subprocess.run(kubectl + arguments, input=None if value is None else json.dumps(value),
                            text=True, capture_output=True)
    if result.returncode:
        raise SystemExit('Runtime bootstrap Kubernetes operation failed; no upstream payload was printed.')
    return result.stdout

def owned(value):
    labels = value['metadata'].get('labels', {})
    return labels.get('app.kubernetes.io/part-of') == 'edgeai' and labels.get('app.kubernetes.io/managed-by') == 'edgeai-bootstrap'

control_namespace = json.loads(call(['get', 'namespace', 'edgeai', '-o', 'json']))
if not owned(control_namespace):
    raise SystemExit('Refusing an unowned control plane namespace.')
call(['-n', 'edgeai', 'get', 'serviceaccount', 'edgeai-control-plane', '-o', 'name'])
resources = json.loads(Path('deploy/kubernetes/bootstrap/runtime-rbac.json').read_text())['items']
for resource in resources:
    meta = resource['metadata']
    scope = ['-n', meta['namespace']] if 'namespace' in meta else []
    current = call(scope + ['get', resource['kind'], meta['name'], '--ignore-not-found', '-o', 'json'])
    if current and not owned(json.loads(current)):
        raise SystemExit('Refusing to modify an unowned runtime bootstrap resource.')
for resource in resources:
    call(['apply', '--dry-run=server', '-f', '-'], resource)
    call(['apply', '-f', '-'], resource)
print('Runtime namespace/RBAC ready. Runner has no resource permissions; control plane access is namespace-scoped except TokenReview and existing Node reads.')
