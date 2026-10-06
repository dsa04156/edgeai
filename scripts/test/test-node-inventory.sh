#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
[[ $# == 1 && -n "$1" ]] || blocked 'Usage: test-node-inventory.sh <explicit-read-only-context>'
export EDGEAI_NODE_CONTEXT="$1"
kubectl --context "$EDGEAI_NODE_CONTEXT" --request-timeout=15s get nodes -o name >/dev/null
python3 - <<'PY'
import socket
with socket.socket() as listener:
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try: listener.bind(('127.0.0.1', 18081))
    except OSError: raise SystemExit('Port 18081 is occupied; no existing process was changed.')
PY
mkdir -p .tools
kubectl --context "$EDGEAI_NODE_CONTEXT" proxy --address=127.0.0.1 --port=18081 > .tools/node-proxy.log 2>&1 &
proxy_pid=$!
cleanup() { kill "$proxy_pid" 2>/dev/null || true; wait "$proxy_pid" 2>/dev/null || true; }
trap cleanup EXIT
for attempt in {1..15}; do
  kill -0 "$proxy_pid" 2>/dev/null || blocked 'Owned Kubernetes proxy exited.'
  if curl -fsS --max-time 2 http://127.0.0.1:18081/version >/dev/null 2>&1; then break; fi
  sleep 1
done
export EDGEAI_KUBE_ENABLED=true EDGEAI_KUBE_API_URL=http://127.0.0.1:18081
export EDGEAI_KUBE_TOKEN_FILE='' EDGEAI_KUBE_CA_FILE='' EDGEAI_NODE_E2E=1
export EDGEAI_DEPLOYMENT_SMOKE=1
bash scripts/test/test-health-stack.sh none
