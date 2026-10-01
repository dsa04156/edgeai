#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
mode="${1:-local}"
[[ "$mode" == local || "$mode" == compose || "$mode" == hardware ]] || blocked 'Usage: preflight.sh local|compose|hardware'
failed=0
for tool in java javac node python3 curl git; do
  if command -v "$tool" >/dev/null; then printf 'OK tool: %s\n' "$tool"; else printf 'BLOCKED tool: %s\n' "$tool"; failed=1; fi
done
java -version 2>&1 || failed=1
javac -version || failed=1
node --version || failed=1
pnpm_cmd --version || failed=1
printf 'CPU cores: %s\n' "$(getconf _NPROCESSORS_ONLN)"
df -h .
if command -v kubectl >/dev/null; then
  printf 'Observed context (read-only): '
  kubectl config current-context || true
fi
if [[ "$mode" == compose || "$mode" == hardware ]]; then
  load_env
  docker compose version || failed=1
  if ! docker info >/dev/null 2>&1; then printf 'BLOCKED: Docker daemon or socket access\n'; failed=1; fi
  python3 - <<'PY' || failed=1
import os,socket
for key in ['EDGEAI_API_PORT','EDGEAI_DASHBOARD_PORT','EDGEAI_DB_PORT','EDGEAI_MQTT_PORT','EDGEAI_MINIO_PORT','EDGEAI_MINIO_CONSOLE_PORT']:
    with socket.socket() as s:
        try: s.bind(('127.0.0.1',int(os.environ[key])))
        except OSError: print(f'BLOCKED: {key} is in use (stop this project first if already running)');raise SystemExit(2)
print('Local development ports available')
PY
fi
if [[ "$mode" == hardware ]]; then
  printf 'BLOCKED: real device inventory, remote endpoint, and acceptance criteria are not configured.\n'
  failed=1
fi
[[ "$failed" == 0 ]] || exit 2
