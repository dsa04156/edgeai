#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
load_env
backend/gradlew -p backend :app:bootJar --console=plain
[[ -f dashboard/.next/BUILD_ID ]] || blocked 'Build Dashboard before this test.'
python3 - <<'PY'
import socket,os
for key in ['EDGEAI_API_PORT','EDGEAI_DASHBOARD_PORT']:
    with socket.socket() as s:
        try: s.bind(('127.0.0.1',int(os.environ[key])))
        except OSError: print(f'BLOCKED: {key} occupied; stop this project\'s dev process before testing.');raise SystemExit(2)
PY
mkdir -p .tools
java -jar backend/app/build/libs/edgeai-control-plane.jar > .tools/api-smoke.log 2>&1 &
api_pid=$!
node dashboard/node_modules/next/dist/bin/next start dashboard --hostname 127.0.0.1 --port "$EDGEAI_DASHBOARD_PORT" > .tools/ui-smoke.log 2>&1 &
ui_pid=$!
cleanup() {
  kill "$api_pid" "$ui_pid" 2>/dev/null || true
  wait "$api_pid" "$ui_pid" 2>/dev/null || true
}
trap cleanup EXIT
for attempt in {1..60}; do
  kill -0 "$api_pid" "$ui_pid" 2>/dev/null || { printf 'FAIL: owned service process exited. See .tools/*-smoke.log\n' >&2; exit 1; }
  if curl -fsS --max-time 3 "http://127.0.0.1:$EDGEAI_DASHBOARD_PORT/api/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
bash scripts/test-health.sh
