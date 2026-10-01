#!/usr/bin/env bash
# Ubuntu 24.04 fallback: private PostgreSQL 16.15 binaries/data, no system install.
source "$(dirname "$0")/lib.sh"
load_env
action="${1:-start}"
[[ "$action" == start || "$action" == stop ]] || blocked 'Usage: dev-postgres-local.sh start|stop'
pg_root="$EDGEAI_ROOT/.tools/postgres"
pg_bin="$pg_root/usr/lib/postgresql/16/bin"
pg_data="$EDGEAI_ROOT/.tools/pgdata"
if [[ "$action" == stop ]]; then
  [[ -f "$pg_data/.edgeai-owned" ]] || blocked 'No project-owned PostgreSQL data directory.'
  exec "$pg_bin/pg_ctl" -D "$pg_data" -m fast stop
fi
if [[ ! -x "$pg_bin/postgres" ]]; then
  source /etc/os-release
  [[ "$ID" == ubuntu && "$VERSION_ID" == 24.04 && "$(uname -m)" == x86_64 ]] || blocked 'This optional fallback supports Ubuntu 24.04 x86_64 only. Use Compose elsewhere.'
  mkdir -p .tools/postgres-packages "$pg_root"
  (
    cd .tools/postgres-packages
    apt download postgresql-16=16.15-0ubuntu0.24.04.1 postgresql-client-16=16.15-0ubuntu0.24.04.1 libpq5=16.15-0ubuntu0.24.04.1
    for file in ./*.deb; do dpkg-deb -x "$file" "$pg_root"; done
  )
fi
export LD_LIBRARY_PATH="$pg_root/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
if ldd "$pg_bin/postgres" | grep -q 'not found'; then blocked 'PostgreSQL shared libraries missing; use a Docker-enabled environment.'; fi
if [[ ! -f "$pg_data/PG_VERSION" ]]; then
  umask 077
  password_file="$(mktemp "$EDGEAI_ROOT/.tools/pg-password.XXXXXX")"
  trap 'rm -f "$password_file"' EXIT
  printf '%s\n' "$EDGEAI_DB_PASSWORD" > "$password_file"
  "$pg_bin/initdb" -D "$pg_data" -L "$pg_root/usr/share/postgresql/16" -U "$EDGEAI_DB_USER" \
    --auth-host=scram-sha-256 --auth-local=scram-sha-256 --pwfile="$password_file" --encoding=UTF8 --locale=C
  touch "$pg_data/.edgeai-owned"
fi
[[ -f "$pg_data/.edgeai-owned" ]] || blocked 'Refusing to operate on unowned PostgreSQL data.'
if ! "$pg_bin/pg_ctl" -D "$pg_data" status >/dev/null 2>&1; then
  "$pg_bin/pg_ctl" -D "$pg_data" -l "$EDGEAI_ROOT/.tools/postgres.log" \
    -o "-p $EDGEAI_DB_PORT -h 127.0.0.1 -k $EDGEAI_ROOT/.tools" -w start
fi
export PGPASSWORD="$EDGEAI_DB_PASSWORD"
if ! "$pg_bin/psql" -h 127.0.0.1 -p "$EDGEAI_DB_PORT" -U "$EDGEAI_DB_USER" -d "$EDGEAI_DB_NAME" -Atc 'select 1' >/dev/null 2>&1; then
  "$pg_bin/createdb" -h 127.0.0.1 -p "$EDGEAI_DB_PORT" -U "$EDGEAI_DB_USER" "$EDGEAI_DB_NAME"
fi
printf 'Project-local PostgreSQL ready on 127.0.0.1:%s\n' "$EDGEAI_DB_PORT"
