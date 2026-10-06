#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
exec backend/gradlew -p backend :app:bootRun --console=plain
