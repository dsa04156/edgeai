#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
backend/gradlew -p backend :app:bootJar --console=plain
exec "${EDGEAI_STREAM_PYTHON:-python3}" scripts/internal/test-recovery-device-journal.py "$@"
