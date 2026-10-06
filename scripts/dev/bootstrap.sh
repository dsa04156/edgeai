#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
for tool in python3 node curl; do command -v "$tool" >/dev/null || blocked "Missing $tool"; done
node -e 'if (Number(process.versions.node.split(".")[0]) !== 22) { console.error("Use Node 22 (see .nvmrc)"); process.exit(2); }'
python3 - <<'PY'
from pathlib import Path
import os,secrets
p=Path('.env')
if not p.exists():
    text=Path('.env.example').read_text()
    text=text.replace('EDGEAI_WORKFLOW_ENABLED=false', 'EDGEAI_WORKFLOW_ENABLED=' + ('true' if os.environ.get('EDGEAI_WORKFLOW_ENABLED') == 'true' else 'false'))
    while 'GENERATE_WITH_BOOTSTRAP' in text:
        text=text.replace('GENERATE_WITH_BOOTSTRAP',secrets.token_hex(24),1)
    fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as f: f.write(text)
    print('Created .env with random local credentials (mode 600).')
else:
    print('Preserving existing .env.')
PY
bash scripts/dev/setup-jdk.sh
pnpm_cmd install --frozen-lockfile
printf 'Bootstrap ready. Next: bash scripts/dev/preflight.sh local\n'
