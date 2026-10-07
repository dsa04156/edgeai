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
if [[ -z "${EDGEAI_EDGEX_METADATA_URL:-}" && "${EDGEAI_INFRASTRUCTURE_SOURCE:-}" == kubectl && -n "${EDGEAI_INFRASTRUCTURE_CONTEXT:-}" ]]; then
  edgex_metadata_ip=$(kubectl --context "$EDGEAI_INFRASTRUCTURE_CONTEXT" --request-timeout=3s get service edgex-core-metadata -n edgex-system -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)
  if [[ "$edgex_metadata_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    export EDGEAI_EDGEX_METADATA_URL="http://${edgex_metadata_ip}:59881"
  fi
fi
if [[ -n "${EDGEAI_EDGEX_METADATA_URL:-}" ]]; then export EDGEAI_EDGEX_ENABLED="${EDGEAI_EDGEX_ENABLED:-true}"; fi
if [[ "${EDGEAI_INFRASTRUCTURE_SOURCE:-}" == kubectl && -n "${EDGEAI_INFRASTRUCTURE_CONTEXT:-}" ]]; then
  for edgex_kind in DATA COMMAND; do
    edgex_variable="EDGEAI_EDGEX_${edgex_kind}_URL"
    if [[ -z "${!edgex_variable:-}" ]]; then
      edgex_service="edgex-core-${edgex_kind,,}"
      edgex_ip=$(kubectl --context "$EDGEAI_INFRASTRUCTURE_CONTEXT" --request-timeout=3s get service "$edgex_service" -n edgex-system -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)
      edgex_port=59880
      if [[ "$edgex_kind" == COMMAND ]]; then edgex_port=59882; fi
      if [[ "$edgex_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then export "$edgex_variable=http://${edgex_ip}:${edgex_port}"; fi
    fi
  done
fi
exec backend/gradlew -p backend :app:bootRun --console=plain
