#!/usr/bin/env bash
set -euo pipefail
EDGEAI_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$EDGEAI_ROOT"
export NEXT_TELEMETRY_DISABLED=1
if [[ -x "$EDGEAI_ROOT/.tools/jdk/bin/javac" && -z "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$EDGEAI_ROOT/.tools/jdk"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
blocked() { printf 'BLOCKED: %s\n' "$*" >&2; exit 2; }
load_env() {
  [[ -f "$EDGEAI_ROOT/.env" ]] || blocked 'Run bash scripts/bootstrap.sh first.'
  set -a
  source "$EDGEAI_ROOT/.env"
  set +a
}
pnpm_cmd() {
  if command -v corepack >/dev/null; then corepack pnpm "$@";
  elif command -v pnpm >/dev/null; then pnpm "$@";
  else blocked 'Install Corepack or pnpm 10.34.6.'; fi
}
compose() { docker compose --project-name edgeai-dev --env-file "$EDGEAI_ROOT/.env" -f "$EDGEAI_ROOT/deploy/compose/compose.yaml" "$@"; }
require_docker() { docker info >/dev/null 2>&1 || blocked 'Docker daemon unavailable or socket permission denied. No permissions are modified automatically.'; }
