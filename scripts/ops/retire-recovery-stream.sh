#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
exec "${EDGEAI_STREAM_PYTHON:-python3}" scripts/internal/recovery_stream_retire.py "$@"
