"""Update only the deployment image pins after the exact main revision passes CI."""
import argparse
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument('--api', required=True)
parser.add_argument('--dashboard', required=True)
parser.add_argument('--revision', required=True)
args = parser.parse_args()
if not re.fullmatch(r'[0-9a-f]{40}', args.revision):
    parser.error('revision must be a full Git SHA')
for value in [args.api, args.dashboard]:
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', value):
        parser.error('images must be immutable SHA-256 digests')
path = Path('deploy/kubernetes/overlays/dev/kustomization.yaml')
text = path.read_text()
for name, digest in [('api', args.api), ('dashboard', args.dashboard)]:
    pattern = rf'(- name: ghcr.io/dsa04156/edgeai-{name}\n)    (?:newTag|digest): [^\n]+'
    text, count = re.subn(pattern, rf'\g<1>    digest: {digest}', text)
    if count != 1:
        raise SystemExit(f'Expected one {name} image pin')
path.write_text(text)
Path('deploy/kubernetes/overlays/dev/release.json').write_text(
    __import__('json').dumps({'sourceRevision': args.revision, 'apiDigest': args.api,
                            'dashboardDigest': args.dashboard}, indent=2) + '\n')
