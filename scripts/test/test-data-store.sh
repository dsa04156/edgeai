#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
[[ $# == 0 ]] || blocked 'Usage: test-data-store.sh'
source scripts/test/isolated-db.sh
isolated_test_db
data_store_started=0
if ! python3 scripts/internal/influx_local.py status --test; then data_store_started=1; fi
data_store_cleanup() {
  local result=$?
  if [[ "$data_store_started" == 1 ]]; then python3 scripts/internal/influx_local.py stop --test || result=1; fi
  return "$result"
}
trap 'data_store_cleanup; test_db_cleanup' EXIT
python3 scripts/internal/influx_local.py start --test
export EDGEAI_INFLUX_URL=http://127.0.0.1:19086
export EDGEAI_INFLUX_ORG=edgeai-test
export EDGEAI_INFLUX_TOKEN_FILE="$EDGEAI_ROOT/output/sensor-storage/influx-test/api-token"
backend/gradlew -p backend :app:dataStoreIntegrationTest --console=plain
