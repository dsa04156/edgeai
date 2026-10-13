#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
[[ $# == 0 ]] || blocked 'Usage: dev-processing-history.sh'
[[ -f "$EDGEAI_ROOT/.tools/pgdata/.edgeai-owned" ]] || blocked 'Start project-local PostgreSQL first; external databases are not created by this helper.'
pg_root="$EDGEAI_ROOT/.tools/postgres"
pg_bin="$pg_root/usr/lib/postgresql/16/bin"
export LD_LIBRARY_PATH="$pg_root/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PGPASSWORD="$EDGEAI_DB_PASSWORD"
export PGCONNECT_TIMEOUT=3
# A marker alone must not authorize creating databases on another server if
# the local environment has been changed to point at a different port.
history_data_dir="$("$pg_bin/psql" -h 127.0.0.1 -p "$EDGEAI_DB_PORT" -U "$EDGEAI_DB_USER" -d postgres -Atc 'show data_directory' 2>/dev/null)" || blocked 'Owned local PostgreSQL is not reachable.'
[[ "$history_data_dir" == "$EDGEAI_ROOT/.tools/pgdata" ]] || blocked 'History setup is restricted to the project-owned local data directory.'
for database in edgeai_history edgeai_history_test; do
  if ! "$pg_bin/psql" -h 127.0.0.1 -p "$EDGEAI_DB_PORT" -U "$EDGEAI_DB_USER" -d "$database" -Atc 'select 1' >/dev/null 2>&1; then
    "$pg_bin/createdb" -h 127.0.0.1 -p "$EDGEAI_DB_PORT" -U "$EDGEAI_DB_USER" "$database"
  fi
done
printf 'Separate development and test history databases are ready.\n'
