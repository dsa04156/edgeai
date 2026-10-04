#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
load_env
backend/gradlew -p backend :app:bootJar --console=plain
python3 - <<'PY'
import json
from datetime import datetime, timezone
from pathlib import Path
import subprocess
import sys
import uuid

directory = Path('docs/evidence/runs') / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-load-acceptance-' + uuid.uuid4().hex[:8])
directory.mkdir(mode=0o700, parents=True)
cases = [
    ('measurement', ['--measure-only'], 0, 'MEASURED', 'UNSET'),
    ('no-budget', [], 2, 'PENDING_ACCEPTANCE', 'UNSET'),
    ('rejected-budget', ['--max-p95-ms', '0.000001'], 1, 'FAIL', 'FAILED_CLI_BUDGET'),
    ('partial-scale', ['--max-p95-ms', '59999'], 2, 'PARTIAL_SCALE', 'PASSED_CLI_BUDGET'),
]
for name, options, code, status, acceptance in cases:
    path = directory / (name + '.json')
    result = subprocess.run([sys.executable, 'scripts/test-device-load.py', '--counts', '10',
        '--seconds', '2', '--interval', '1', '--report', str(path), *options], timeout=180)
    assert result.returncode == code, name + ': unexpected exit code'
    report = json.loads(path.read_text())
    assert report['status'] == status and report['performanceAcceptance'] == acceptance, name
    assert not report['fullScaleSequence']
    assert report['ownedApiStopped'] and report['ownedDatabaseRemoved'], name + ': cleanup'
    assert len(report['authenticationGuards']) == 5 and all(report['authenticationGuards'].values()), name + ': authentication'
    stage, = report['stages']
    assert stage['summary']['offered'] == stage['summary']['succeeded'] == 22
    assert stage['summary']['unexpected'] == stage['summary']['dropped'] == 0
    assert stage['integrity']['observations'] == 31 and stage['integrity']['exactObservationCounts']
    print('PASS: ' + name + ' expected exit=' + str(code) + ' status=' + status + '; actual API/DB correctness and cleanup', flush=True)
print('PASS: all four real API/DB acceptance classifications; reduced-scale harness regression only')
print('Acceptance reports: ' + str(directory))
PY
