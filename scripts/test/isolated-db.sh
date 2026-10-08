#!/usr/bin/env bash
# Source after lib.sh and load_env. Each invocation owns one disposable database.
isolated_test_db() {
  local pg_bin="$EDGEAI_ROOT/.tools/postgres/usr/lib/postgresql/16/bin"
  if command -v createdb >/dev/null && command -v dropdb >/dev/null; then pg_bin="";
  elif [[ ! -x "$pg_bin/createdb" || ! -x "$pg_bin/dropdb" ]]; then
    blocked 'Install PostgreSQL createdb/dropdb to run isolated integration tests.'
  fi
  export LD_LIBRARY_PATH="$EDGEAI_ROOT/.tools/postgres/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
  export EDGEAI_DB_NAME="edgeai_test_$(date +%s)_${BASHPID}_${RANDOM}"
  export PGPASSWORD="$EDGEAI_DB_PASSWORD"
  EDGEAI_TEST_DROPDB="${pg_bin:+$pg_bin/}dropdb"
  "${pg_bin:+$pg_bin/}createdb" -h "${EDGEAI_DB_HOST:-127.0.0.1}" -p "$EDGEAI_DB_PORT" -U "$EDGEAI_DB_USER" "$EDGEAI_DB_NAME"
  trap 'test_db_cleanup' EXIT
  printf 'Isolated PostgreSQL database: %s\n' "$EDGEAI_DB_NAME"
}
test_db_cleanup() {
  local result=$?
  "$EDGEAI_TEST_DROPDB" --force -h "${EDGEAI_DB_HOST:-127.0.0.1}" -p "$EDGEAI_DB_PORT" -U "$EDGEAI_DB_USER" "$EDGEAI_DB_NAME" || result=1
  exit "$result"
}
