#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
backend/gradlew -p backend test --console=plain
python3 - <<'PY'
import json
from pathlib import Path
import xml.etree.ElementTree as ET

# The following contract task replaces app:test XML with its filtered suite.
# Capture counts now, while the full test task's results are still present.
counts = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
for path in Path('backend').glob('*/build/test-results/test/TEST-*.xml'):
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.get(key, '0'))
assert counts['tests'] > 0 and counts['failures'] == counts['errors'] == counts['skipped'] == 0
print('UNIT_TEST_COUNTS ' + json.dumps(counts))
PY
