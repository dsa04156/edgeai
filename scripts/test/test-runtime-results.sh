#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
export EDGEAI_STORAGE_URL="${EDGEAI_STORAGE_URL:-http://127.0.0.1:$EDGEAI_MINIO_PORT}"
test_status=0
test_started=$(date +%s)
backend/gradlew -p backend :app:runtimeArtifactIntegrationTest --rerun-tasks --console=plain || test_status=$?
# Preserve failure locations/explicit sanitized diagnostics without archiving arbitrary test payloads.
python3 - "$test_started" <<'PY'
import json,re,sys,xml.etree.ElementTree as E
from pathlib import Path
totals=dict(tests=0,failures=0,errors=0,skipped=0)
for path in Path('backend/app/build/test-results/runtimeArtifactIntegrationTest').glob('TEST-*.xml'):
    if path.stat().st_mtime < int(sys.argv[1]):continue
    root=E.parse(path).getroot()
    for key in totals:totals[key]+=int(root.get(key,'0'))
    for case in root.findall('testcase'):
        failure=case.find('failure')
        if failure is not None:
            locations=re.findall(r'io\.edgeai\.[\w.$]+\([\w.]+:\d+\)',failure.text or '')
            print('RUNTIME_TEST_FAILURE '+json.dumps({'test':case.get('name'),'type':failure.get('type'),'locations':locations}))
    for line in (root.findtext('system-out') or '').splitlines():
        if line.startswith('VD_STREAM_DIAGNOSTIC '):
            value=json.loads(line.removeprefix('VD_STREAM_DIAGNOSTIC '))
            print('VD_STREAM_DIAGNOSTIC '+json.dumps(value))
print('RUNTIME_TEST_COUNTS '+json.dumps(totals))
assert totals['tests']>0, 'Runtime storage test reports are missing'
PY
exit "$test_status"
