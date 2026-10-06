#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
# Uses running project API/UI. Creates a unique SYNTHETIC device and releases it.
# Immutable Profile and Device histories remain in the dedicated development DB.
python3 scripts/internal/smoke-deployment.py --through device
