#!/usr/bin/env bash
set -euo pipefail
EDGEAI_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
bash "$EDGEAI_ROOT/scripts/dev/prepare-kestra-native.sh"
cd "$EDGEAI_ROOT/output/kestra-native/upstream/ui"
# Upstream's Storybook flag disables federation/PWA development packaging only.
export STORYBOOK=1
export VITE_PROXY_URL="http://127.0.0.1:1"
exec npx --yes --package=node@24 --package=npm@11.16.0 -- npm exec -- vite --config "$EDGEAI_ROOT/dashboard/kestra-native/vite.config.mjs" --host 0.0.0.0 --port 13083 --strictPort
