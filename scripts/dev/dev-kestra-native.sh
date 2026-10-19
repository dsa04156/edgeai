#!/usr/bin/env bash
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
fi
npx --yes --package=node@24 --package=npm@11.16.0 -- npm run predev
# Upstream's Storybook flag disables federation/PWA development packaging only.
export STORYBOOK=1
export VITE_PROXY_URL="http://127.0.0.1:1"
exec npx --yes --package=node@24 --package=npm@11.16.0 -- npm exec -- vite --config "$EDGEAI_ROOT/dashboard/kestra-native/vite.config.mjs" --host 0.0.0.0 --port 13083 --strictPort
