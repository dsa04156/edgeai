#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
load_env
backend/gradlew -p backend :app:bootJar --console=plain
exec python3 scripts/test-device-load.py "$@"
