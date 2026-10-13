#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
[[ $# == 0 ]] || blocked 'Usage: test-data-store.sh'
source scripts/test/isolated-db.sh
isolated_test_db
python3 scripts/internal/influx_local.py start --test
export EDGEAI_INFLUX_URL=http://127.0.0.1:19086
export EDGEAI_INFLUX_ORG=edgeai-test
export EDGEAI_INFLUX_TOKEN_FILE="$EDGEAI_ROOT/output/sensor-storage/influx-test/api-token"
backend/gradlew -p backend :app:dataStoreIntegrationTest --console=plain
