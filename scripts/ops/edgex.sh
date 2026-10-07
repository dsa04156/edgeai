#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
action="${1:-status}"
context="${2:-}"
if [[ "$action" == render ]]; then
  exec kubectl kustomize deploy/edgex
fi
if [[ -z "$context" ]]; then
  echo "Usage: $0 render | {check|apply|status} <explicit-kubernetes-context>" >&2
  exit 2
fi
k=(kubectl --context "$context" --request-timeout=30s)
case "$action" in
  check) "${k[@]}" apply --dry-run=server -k deploy/edgex ;;
  apply)
    # Secrets are provisioned separately. Never silently replace an existing database password.
    "${k[@]}" -n edgex-system get secret edgex-postgres-credentials -o name >/dev/null
    "${k[@]}" apply -k deploy/edgex
    ;;
  status)
    for ns in edgex-system edgex-edge; do
      "${k[@]}" -n "$ns" get deploy,sts,pods,svc,pvc -l app.kubernetes.io/part-of=edgeai
    done
    ;;
  *) echo "Unknown action: $action" >&2; exit 2 ;;
esac
