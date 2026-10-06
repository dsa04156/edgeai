#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
exec python3 scripts/internal/device_journal_backup.py restore "$@"
