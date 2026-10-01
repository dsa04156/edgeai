#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
load_env
python3 - <<'PY'
import socket,os
try:
    with socket.create_connection(('127.0.0.1',int(os.environ['EDGEAI_DB_PORT'])),timeout=3): pass
except OSError:
    print('BLOCKED: PostgreSQL not reachable. Run dev-up.sh.');raise SystemExit(2)
PY
backend/gradlew -p backend :app:integrationTest --console=plain --rerun-tasks
