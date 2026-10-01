#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
[[ $# -ge 2 ]] || blocked 'Usage: collect-evidence.sh test-id command [args...]'
exec python3 scripts/evidence.py "$@"
