#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
source "$EDGEAI_ROOT/scripts/test/isolated-db.sh"
isolated_test_db
export EDGEAI_INFRASTRUCTURE_SOURCE=kubectl
export EDGEAI_INFRASTRUCTURE_CONTEXT="${EDGEAI_INFRASTRUCTURE_CONTEXT:-$(kubectl config current-context)}"
export EDGEAI_STORAGE_URL="${EDGEAI_STORAGE_URL:-http://127.0.0.1:$EDGEAI_MINIO_PORT}"
backend/gradlew -p backend :app:vdSensorAcceptanceTest --rerun-tasks --console=plain
python3 - <<'PY'
import xml.etree.ElementTree as E
from pathlib import Path
reports = list(Path('backend/app/build/test-results/vdSensorAcceptanceTest').glob('TEST-*.xml'))
assert reports, 'Sense HAT acceptance report is missing'
for path in reports:
    root = E.parse(path).getroot()
    assert int(root.get('tests', '0')) > 0
    assert all(int(root.get(key, '0')) == 0 for key in ('failures', 'errors', 'skipped'))
    for line in (root.findtext('system-out') or '').splitlines():
        if line.startswith('VD_SENSOR_ACCEPTANCE '):
            print(line)
PY
