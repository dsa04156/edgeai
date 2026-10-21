#!/usr/bin/env bash
# Run a built console release; the 13080 console, 18080 management and 18082 workflow APIs stay separate.
set -euo pipefail
EDGEAI_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ $# -gt 1 ]]; then
  echo "사용법: $0 [RELEASE_DIR]" >&2
  exit 2
fi
# Default: the release last built without arguments (output/kestra-native/current).
RELEASE="${1:-$EDGEAI_ROOT/output/kestra-native/current}"
if [[ ! -f "$RELEASE/server.mjs" ]]; then
  echo "console release가 없습니다: $RELEASE (먼저 corepack pnpm build)" >&2
  exit 1
fi
RELEASE_DIR="$(cd "$RELEASE" && pwd -P)"
# Production port; EDGEAI_CONSOLE_PORT (or the former EDGEAI_DASHBOARD_PORT) overrides it.
export EDGEAI_CONSOLE_PORT="${EDGEAI_CONSOLE_PORT:-${EDGEAI_DASHBOARD_PORT:-13080}}"
export EDGEAI_KESTRA_DIST="$RELEASE_DIR/dist"
exec node --experimental-strip-types --disable-warning=ExperimentalWarning "$RELEASE_DIR/server.mjs"
