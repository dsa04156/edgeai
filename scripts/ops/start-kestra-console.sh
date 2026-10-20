#!/usr/bin/env bash
# Run a built console release; 13080 BFF, 18080 management and 18082 workflow APIs stay separate.
set -euo pipefail
if [[ $# != 1 ]]; then
  echo "사용법: $0 RELEASE_DIR" >&2
  exit 2
fi
RELEASE_DIR="$(cd "$1" && pwd)"
export EDGEAI_KESTRA_DIST="$RELEASE_DIR/dist"
exec node --experimental-strip-types --disable-warning=ExperimentalWarning "$RELEASE_DIR/server.mjs"
