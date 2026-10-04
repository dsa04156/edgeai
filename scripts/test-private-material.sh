#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
exec python3 scripts/test-private-material.py "$@"
