#!/usr/bin/env bash
# Build a self-contained console release: static bundle plus the node-only server modules.
set -euo pipefail
EDGEAI_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ $# -gt 1 ]]; then
  echo "사용법: $0 [RELEASE_DIR]" >&2
  exit 2
fi
# Without a directory, build a timestamped release and point output/kestra-native/current at it
# so start-kestra-console.sh can run it without arguments.
CURRENT_LINK=""
if [[ $# == 0 ]]; then
  CURRENT_LINK="$EDGEAI_ROOT/output/kestra-native/current"
  set -- "$EDGEAI_ROOT/output/kestra-native/release-$(date +%Y%m%d%H%M%S)"
fi
# Resolve now: the build later runs from the (possibly symlinked) upstream checkout.
RELEASE_DIR="$(realpath -m -- "$1")"
if [ -e "$RELEASE_DIR" ]; then
  echo "이미 존재하는 release는 덮어쓰지 않습니다: $RELEASE_DIR" >&2
  exit 1
fi
bash "$EDGEAI_ROOT/scripts/dev/prepare-kestra-native.sh"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/edgeai-console.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
cd "$EDGEAI_ROOT/output/kestra-native/upstream/ui"
# Same packaging as the dev server: no federation/PWA service worker.
STORYBOOK=1 EDGEAI_KESTRA_OUT_DIR="$STAGE/dist" \
  npx --yes --package=node@24 --package=npm@11.16.0 -- npm exec -- vite build --config "$EDGEAI_ROOT/dashboard/kestra-native/vite.config.mjs"
for file in server.mjs shell-api.ts overview.ts device-registration.ts; do
  cp "$EDGEAI_ROOT/dashboard/kestra-native/$file" "$STAGE/$file"
done
echo '{"type":"module"}' > "$STAGE/package.json"
git -C "$EDGEAI_ROOT" rev-parse HEAD > "$STAGE/REVISION"
if ! git -C "$EDGEAI_ROOT" diff --quiet HEAD -- dashboard/kestra-native; then
  echo "dirty" >> "$STAGE/REVISION"
fi
mkdir -p "$(dirname "$RELEASE_DIR")"
mv "$STAGE" "$RELEASE_DIR"
trap - EXIT
if [[ -n "$CURRENT_LINK" ]]; then
  ln -sfn "$(basename "$RELEASE_DIR")" "$CURRENT_LINK"
fi
echo "release: $RELEASE_DIR ($(head -1 "$RELEASE_DIR/REVISION"))"
