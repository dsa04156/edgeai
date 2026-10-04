#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
exec python3 scripts/recovery_remote_storage.py "$@"
