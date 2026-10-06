#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
[[ $# -eq 1 ]] || blocked 'Usage: demo-multidevice.sh <explicit Kubernetes context>; requires the enabled STREAM deployment.'
exec python3 scripts/internal/demo-multidevice.py --context "$1"
