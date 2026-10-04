#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
exec python3 scripts/private_material_backup.py backup "$@"
