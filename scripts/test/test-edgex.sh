#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
python3 scripts/internal/test_edgex_manifests.py
bash -n scripts/ops/edgex.sh
