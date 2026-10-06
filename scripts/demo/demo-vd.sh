#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
[[ $# -eq 1 ]] || blocked 'Usage: demo-vd.sh <explicit Kubernetes context>; set API origin and credentials in the environment.'
: "${EDGEAI_SMOKE_API_URL:?Set the reachable API origin with VD execution enabled}"
: "${EDGEAI_STORAGE_URL:?Set a reachable origin for the platform artifact storage}"
: "${EDGEAI_MINIO_USER:?Set the artifact storage user}"
: "${EDGEAI_MINIO_PASSWORD:?Set the artifact storage password}"
exec python3 scripts/internal/vd_acceptance.py --context "$1" --tasks
