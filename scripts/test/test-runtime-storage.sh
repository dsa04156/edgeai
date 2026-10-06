#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
export EDGEAI_STORAGE_URL="${EDGEAI_STORAGE_URL:-http://127.0.0.1:$EDGEAI_MINIO_PORT}"
backend/gradlew -p backend :app:storageIntegrationTest --rerun-tasks --console=plain
