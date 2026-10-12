#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
# Read-only inventory uses the developer's selected cluster, fixed for this process.
# No proxy, observer, workload, or other server is started here.
if [[ -z "${EDGEAI_INFRASTRUCTURE_SOURCE:-}" ]]; then
  if [[ "${EDGEAI_KUBE_ENABLED:-false}" == true ]]; then
    export EDGEAI_INFRASTRUCTURE_SOURCE=api
  elif command -v kubectl >/dev/null 2>&1; then
    export EDGEAI_INFRASTRUCTURE_CONTEXT="${EDGEAI_INFRASTRUCTURE_CONTEXT:-$(kubectl config current-context 2>/dev/null || true)}"
    if [[ -n "$EDGEAI_INFRASTRUCTURE_CONTEXT" ]]; then export EDGEAI_INFRASTRUCTURE_SOURCE=kubectl; fi
  fi
fi
exec backend/gradlew -p backend :app:bootRun --console=plain
