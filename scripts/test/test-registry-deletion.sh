#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
python3 scripts/internal/test_registry_deletion.py
