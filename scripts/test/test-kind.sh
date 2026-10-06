#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
require_docker
exec python3 scripts/internal/test-kind.py
