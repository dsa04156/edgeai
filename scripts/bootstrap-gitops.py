"""Create an isolated namespace and initial secret; never rotate an existing database password."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--context', required=True)
args = parser.parse_args()
kubectl = ['kubectl', '--context', args.context, '--request-timeout=15s']

def read(kind, name, namespace=None):
    command = kubectl + (['-n', namespace] if namespace else [])
    result = subprocess.run(command + ['get', kind, name, '--ignore-not-found', '-o', 'json'],
                            check=True, capture_output=True, text=True)
    return json.loads(result.stdout) if result.stdout.strip() else None

def create(value):
    # Credentials go only to kubectl stdin, never command arguments or console output.
    subprocess.run(kubectl + ['create', '-f', '-'], input=json.dumps(value),
                   check=True, text=True, stdout=subprocess.DEVNULL)

if not read('deployment', 'argocd-server', 'argocd'):
    raise SystemExit('ArgoCD must already exist in namespace argocd')
namespace = read('namespace', 'edgeai')
if namespace:
    if namespace['metadata'].get('labels', {}).get('app.kubernetes.io/part-of') != 'edgeai':
        raise SystemExit('Refusing to reuse an unowned edgeai namespace')
else:
    create({'apiVersion':'v1','kind':'Namespace','metadata':{'name':'edgeai','labels':{
        'app.kubernetes.io/part-of':'edgeai','app.kubernetes.io/managed-by':'edgeai-bootstrap'}}})

existing = read('secret', 'edgeai-runtime', 'edgeai')
if existing:
    required = {'EDGEAI_API_PASSWORD', 'EDGEAI_DB_PASSWORD'}
    if not required.issubset(existing.get('data', {})):
        raise SystemExit('Existing secret is incomplete; it was not changed')
    print('Existing edgeai-runtime retained; no credentials rotated.')
else:
    values = {'EDGEAI_API_USER':'edgeai','EDGEAI_API_PASSWORD':secrets.token_hex(24),
              'EDGEAI_DB_PASSWORD':secrets.token_hex(24)}
    location = Path('.tools/kubernetes/edgeai-runtime.env')
    location.parent.mkdir(parents=True, exist_ok=True)
    # An old credential file needs reconciliation, not a silent overwrite.
    fd = os.open(location, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as file:
        file.write(''.join(f'{key}={value}\n' for key,value in values.items()))
    create({'apiVersion':'v1','kind':'Secret','type':'Opaque',
            'metadata':{'name':'edgeai-runtime','namespace':'edgeai','labels':{'app.kubernetes.io/part-of':'edgeai'}},
            'stringData':{key:value for key,value in values.items() if key.endswith('_PASSWORD')}})
    print('Created edgeai-runtime; credentials saved locally with mode 600 at .tools/kubernetes/edgeai-runtime.env')
print('Bootstrap ready. Apply deploy/argocd only after CI has published image digests.')
