#!/usr/bin/env bash
# Prepare the pinned Kestra v2.0.6 UI source and its lockfile dependencies (no Kestra server/DB).
set -euo pipefail
EDGEAI_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KESTRA_SOURCE="$EDGEAI_ROOT/output/kestra-native/upstream"
KESTRA_REVISION="b59444f09be2e44a3b0738452b2e02fc4fe89505"
if [ ! -d "$KESTRA_SOURCE/.git" ]; then
  mkdir -p "$(dirname "$KESTRA_SOURCE")"
  git clone --depth 1 --branch v2.0.6 --filter=blob:none --sparse https://github.com/kestra-io/kestra.git "$KESTRA_SOURCE"
  git -C "$KESTRA_SOURCE" sparse-checkout set ui
fi
if [ "$(git -C "$KESTRA_SOURCE" rev-parse HEAD)" != "$KESTRA_REVISION" ]; then
  echo "Kestra 소스 버전이 다릅니다. 기존 checkout을 보존하고 v2.0.6을 별도로 준비하세요." >&2
  exit 1
fi
cd "$KESTRA_SOURCE/ui"
if [ ! -d node_modules ]; then
  npx --yes --package=node@24 --package=npm@11.16.0 -- npm ci --no-audit --no-fund
  node "$EDGEAI_ROOT/scripts/dev/record-kestra-lock-hash.mjs" .
fi
npx --yes --package=node@24 --package=npm@11.16.0 -- npm run predev
