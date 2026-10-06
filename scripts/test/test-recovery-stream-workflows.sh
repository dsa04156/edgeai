#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
exec "${EDGEAI_STREAM_PYTHON:-python3}" scripts/internal/test-recovery-stream-workflows.py "$@"
