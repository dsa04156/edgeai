#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
[[ $# -eq 1 ]] || blocked 'Usage: demo-vd.sh <explicit Kubernetes context>; set API origin and credentials in the environment.'
: "${EDGEAI_SMOKE_API_URL:?Set the reachable API origin with VD execution enabled}"
exec python3 scripts/vd_acceptance.py --context "$1"
