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
parser.add_argument('--influxdb', required=True)
parser.add_argument('--revision', required=True)
parser.add_argument('--runner-source')
args = parser.parse_args()
if not re.fullmatch(r'[0-9a-f]{40}', args.revision):
    parser.error('revision must be a full Git SHA')
runner_source=args.runner_source or args.revision
if not re.fullmatch(r'[0-9a-f]{40}',runner_source):
    parser.error('runner-source must be a full Git SHA')
for value in [args.api, args.dashboard, args.runner, args.minio, args.influxdb]:
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', value):
        parser.error('images must be immutable SHA-256 digests')
runner_platforms=json.loads(args.runner_platform_digests)
if (set(runner_platforms)!={'linux/amd64','linux/arm64'} or len(set(runner_platforms.values()))!=2 or
        any(not re.fullmatch(r'sha256:[0-9a-f]{64}',value) for value in runner_platforms.values())):
    parser.error('the two independently tested native Runner digests are required')
path = Path('deploy/kubernetes/overlays/dev/kustomization.yaml')
text = path.read_text()
# An operator-built cluster-registry copy is replaced by the exact image this pipeline tested and published to GHCR.
CLUSTER_REGISTRY = '10.254.192.47:5000'
# The unpatched official image stays pinned until the first tested patched InfluxDB digest replaces it.
UPSTREAM = {'influxdb': 'public.ecr.aws/docker/library/influxdb'}
for name, digest in [('api', args.api), ('dashboard', args.dashboard), ('minio', args.minio), ('influxdb', args.influxdb)]:
    pattern = rf'(- name: ghcr.io/dsa04156/edgeai-{name}\n)(?:    newName: ([^\n]+)\n)?    (?:newTag|digest): [^\n]+'
    matches = list(re.finditer(pattern, text))
    if len(matches) != 1:
        raise SystemExit(f'Expected one {name} image pin')
    if matches[0].group(2) not in (None, f'{CLUSTER_REGISTRY}/edgeai-{name}', UPSTREAM.get(name)):
        raise SystemExit(f'Unexpected {name} image registry; deployment pins were left unchanged')
    text = text[:matches[0].start()] + matches[0].group(1) + f'    digest: {digest}' + text[matches[0].end():]
path.write_text(text)
Path('deploy/kubernetes/overlays/dev/release.json').write_text(
    json.dumps({'sourceRevision': args.revision, 'runnerSourceRevision':runner_source, 'apiDigest': args.api,
                            'dashboardDigest': args.dashboard, 'runnerDigest': args.runner,
                            'minioDigest': args.minio, 'influxdbDigest': args.influxdb, 'runtimeImagePlatforms': sorted(runner_platforms),
                            'runnerPlatformDigests':runner_platforms}, indent=2) + '\n')
