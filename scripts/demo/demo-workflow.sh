#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
[[ $# -eq 1 ]] || blocked 'Usage: demo-workflow.sh <explicit Kubernetes context>; set API and storage credentials in the environment.'
: "${EDGEAI_SMOKE_API_URL:?Set the deployed API origin}"
: "${EDGEAI_STORAGE_URL:?Set the reachable artifact storage origin}"
exec python3 scripts/internal/smoke-runtime.py --context "$1"
