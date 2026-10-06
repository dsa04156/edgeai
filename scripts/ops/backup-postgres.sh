#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
exec python3 scripts/internal/postgres_backup.py backup "$@"
