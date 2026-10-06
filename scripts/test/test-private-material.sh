#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
exec python3 scripts/internal/test-private-material.py "$@"
