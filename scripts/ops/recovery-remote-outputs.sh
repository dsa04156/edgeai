#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
if [[ "${1:-}" == recover ]]; then load_env; fi
exec python3 scripts/internal/recovery_remote_outputs.py "$@"
