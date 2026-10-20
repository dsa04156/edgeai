#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
if [[ "${EDGEAI_NODE_E2E:-0}" == 1 ]]; then
  export EDGEAI_KUBE_ENABLED=true EDGEAI_KUBE_API_URL=http://127.0.0.1:18081
  export EDGEAI_KUBE_TOKEN_FILE='' EDGEAI_KUBE_CA_FILE=''
fi
restart_mode="${1:-none}"
[[ "$restart_mode" == none || "$restart_mode" == compose || "$restart_mode" == local ]] || blocked 'Usage: test-health-stack.sh [compose|local]'
backend/gradlew -p backend :app:bootJar --console=plain
# The UI under test is the console release (scripts/ops/build-kestra-console.sh), as in the dashboard image.
[[ -f "${EDGEAI_CONSOLE_RELEASE:-}/dist/edgeai-shell.json" ]] || blocked 'Set EDGEAI_CONSOLE_RELEASE to a built console release.'
python3 - <<'PY'
import socket,os
for key in ['EDGEAI_API_PORT','EDGEAI_DASHBOARD_PORT']:
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try: s.bind(('127.0.0.1',int(os.environ[key])))
        except OSError: print(f'BLOCKED: {key} occupied; stop this project\'s dev process before testing.');raise SystemExit(2)
PY
mkdir -p .tools
java -jar backend/app/build/libs/edgeai-control-plane.jar > .tools/api-smoke.log 2>&1 &
api_pid=$!
EDGEAI_CONSOLE_HOST=127.0.0.1 EDGEAI_CONSOLE_PORT="$EDGEAI_DASHBOARD_PORT" \
  EDGEAI_API_BASE_URL="http://127.0.0.1:$EDGEAI_API_PORT" EDGEAI_WORKFLOW_API_BASE_URL='' \
  bash scripts/ops/start-kestra-console.sh "$EDGEAI_CONSOLE_RELEASE" > .tools/ui-smoke.log 2>&1 &
ui_pid=$!
db_stopped=false
restore_db() {
  if [[ "$db_stopped" == true ]]; then
    if [[ "$restart_mode" == compose ]]; then compose start postgres;
    else bash scripts/dev/dev-postgres-local.sh start; fi
    db_stopped=false
  fi
}
cleanup() {
  kill "$api_pid" "$ui_pid" 2>/dev/null || true
  wait "$api_pid" "$ui_pid" 2>/dev/null || true
  restore_db
}
trap cleanup EXIT
for attempt in {1..60}; do
  for service_pid in "$api_pid" "$ui_pid"; do
    kill -0 "$service_pid" 2>/dev/null || { printf 'FAIL: owned service process exited. See .tools/*-smoke.log\n' >&2; exit 1; }
  done
  if curl -fsS --max-time 3 "http://127.0.0.1:$EDGEAI_DASHBOARD_PORT/api/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
bash scripts/test/test-health.sh
if [[ "${EDGEAI_NODE_E2E:-0}" == 1 ]]; then
  python3 scripts/internal/probe-nodes.py
fi
if [[ "${EDGEAI_DEPLOYMENT_SMOKE:-0}" == 1 ]]; then
  python3 scripts/internal/smoke-deployment.py
fi
if [[ "${EDGEAI_PROFILE_E2E:-0}" == 1 ]]; then
  pnpm_cmd --filter @edgeai/dashboard exec playwright test --config playwright.profiles.config.ts
fi
if [[ "$restart_mode" != none ]]; then
  # Only restart the project-owned DB after the baseline path has passed.
  db_stopped=true
  if [[ "$restart_mode" == compose ]]; then compose stop postgres;
  else bash scripts/dev/dev-postgres-local.sh stop; fi
  bash scripts/test/test-health.sh DOWN
  restore_db
  for attempt in {1..30}; do
    if curl -fsS --max-time 3 "http://127.0.0.1:$EDGEAI_DASHBOARD_PORT/api/health" >/dev/null 2>&1; then break; fi
    sleep 1
  done
  bash scripts/test/test-health.sh
  printf 'PASS: existing API/UI processes recover after the project database restarts\n'
fi
