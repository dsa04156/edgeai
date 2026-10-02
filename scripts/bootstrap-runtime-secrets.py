"""Create/recover dedicated runtime secrets without rotating existing signing keys or storage credentials."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--context', required=True)
args = parser.parse_args()
kubectl = ['kubectl', '--context', args.context, '--request-timeout=15s']
labels = {'app.kubernetes.io/part-of':'edgeai','app.kubernetes.io/managed-by':'edgeai-bootstrap'}

def call(arguments, document=None):
    result = subprocess.run(kubectl + arguments, input=None if document is None else json.dumps(document),
                            capture_output=True, text=True)
    if result.returncode:
        raise SystemExit('Runtime secret operation failed; credential-bearing output suppressed.')
    return json.loads(result.stdout) if result.stdout.strip() else None

def owned(value):
    actual = value['metadata'].get('labels', {})
    return all(actual.get(key) == expected for key, expected in labels.items())

namespace = call(['get','namespace','edgeai','-o','json'])
if not owned(namespace) or 'deletionTimestamp' in namespace['metadata']:
    raise SystemExit('Refusing an unowned or terminating control plane namespace.')
directory = Path('.tools/kubernetes')
directory.mkdir(parents=True, exist_ok=True)

def private_file(path, content):
    if path.exists():
        if path.is_symlink() or path.read_text() != content:
            raise SystemExit('Local private file differs from the retained Kubernetes secret; nothing was overwritten.')
        if path.stat().st_mode & 0o077:
            raise SystemExit('Local private file must have mode 600.')
    else:
        with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as file:
            file.write(content)

def ensure(name, path, generate, serialize, deserialize):
    current = call(['-n','edgeai','get','secret',name,'--ignore-not-found','-o','json'])
    if current:
        if not owned(current):
            raise SystemExit('Refusing an unowned runtime secret.')
        values = {k:base64.b64decode(v, validate=True).decode() for k,v in current.get('data',{}).items()}
    elif path.exists():
        if path.is_symlink():
            raise SystemExit('Private credential files cannot be symlinks.')
        values = deserialize(path.read_text())
    else:
        values = generate()
    content = serialize(values)  # Validates the exact key/value contract before any write.
    private_file(path,content)
    if current is None:
        call(['create','-f','-','-o','json'],{'apiVersion':'v1','kind':'Secret','type':'Opaque',
             'metadata':{'name':name,'namespace':'edgeai','labels':labels},'stringData':values})

def signing(values):
    if set(values) != {'key'} or not re.fullmatch('[0-9a-f]{64}',values['key']):
        raise SystemExit('Signing secret must contain exactly one 32-byte hex key.')
    return values['key']+'\n'

def storage(values):
    if set(values) != {'EDGEAI_MINIO_USER','EDGEAI_MINIO_PASSWORD'} or values['EDGEAI_MINIO_USER'] != 'edgeai-store' or not re.fullmatch('[0-9a-f]{64}',values['EDGEAI_MINIO_PASSWORD']):
        raise SystemExit('Storage credential contract differs; existing values were retained.')
    return ''.join(f'{key}={values[key]}\n' for key in ['EDGEAI_MINIO_USER','EDGEAI_MINIO_PASSWORD'])

ensure('edgeai-runner-signing', directory/'runner-signing.key', lambda:{'key':secrets.token_hex(32)},
       signing, lambda content:{'key':content.strip()})
ensure('edgeai-artifact-storage', directory/'edgeai-storage.env',
       lambda:{'EDGEAI_MINIO_USER':'edgeai-store','EDGEAI_MINIO_PASSWORD':secrets.token_hex(32)},
       storage, lambda content:dict(line.split('=',1) for line in content.splitlines()))
print('Dedicated signing/storage Secrets ready; private mode600 recovery files retained, no existing values rotated.')
