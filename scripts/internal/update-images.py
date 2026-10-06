"""Update only the deployment image pins after the exact main revision passes CI."""
import argparse
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument('--api', required=True)
parser.add_argument('--dashboard', required=True)
parser.add_argument('--runner', required=True)
parser.add_argument('--runner-platform-digests', required=True)
parser.add_argument('--minio', required=True)
parser.add_argument('--revision', required=True)
parser.add_argument('--runner-source')
args = parser.parse_args()
if not re.fullmatch(r'[0-9a-f]{40}', args.revision):
    parser.error('revision must be a full Git SHA')
runner_source=args.runner_source or args.revision
if not re.fullmatch(r'[0-9a-f]{40}',runner_source):
    parser.error('runner-source must be a full Git SHA')
for value in [args.api, args.dashboard, args.runner, args.minio]:
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', value):
        parser.error('images must be immutable SHA-256 digests')
runner_platforms=json.loads(args.runner_platform_digests)
if (set(runner_platforms)!={'linux/amd64','linux/arm64'} or len(set(runner_platforms.values()))!=2 or
        any(not re.fullmatch(r'sha256:[0-9a-f]{64}',value) for value in runner_platforms.values())):
    parser.error('the two independently tested native Runner digests are required')
path = Path('deploy/kubernetes/overlays/dev/kustomization.yaml')
text = path.read_text()
for name, digest in [('api', args.api), ('dashboard', args.dashboard), ('minio', args.minio)]:
    pattern = rf'(- name: ghcr.io/dsa04156/edgeai-{name}\n)    (?:newTag|digest): [^\n]+'
    text, count = re.subn(pattern, rf'\g<1>    digest: {digest}', text)
    if count != 1:
        raise SystemExit(f'Expected one {name} image pin')
path.write_text(text)
Path('deploy/kubernetes/overlays/dev/release.json').write_text(
    json.dumps({'sourceRevision': args.revision, 'runnerSourceRevision':runner_source, 'apiDigest': args.api,
                            'dashboardDigest': args.dashboard, 'runnerDigest': args.runner,
                            'minioDigest': args.minio, 'runtimeImagePlatforms': sorted(runner_platforms),
                            'runnerPlatformDigests':runner_platforms}, indent=2) + '\n')
