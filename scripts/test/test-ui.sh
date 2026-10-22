#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
# Kestra-native console: API boundary unit tests, Vue lint/typecheck and the release build.
pnpm_cmd --filter @edgeai/dashboard test
pnpm_cmd --filter @edgeai/dashboard lint
pnpm_cmd --filter @edgeai/dashboard typecheck
release="$(mktemp -d "${TMPDIR:-/tmp}/edgeai-console-check.XXXXXX")"
trap 'rm -rf "$release"' EXIT
bash scripts/ops/build-kestra-console.sh "$release/release"
[[ -f "$release/release/dist/edgeai-shell.json" && -f "$release/release/server.mjs" ]] || { printf 'FAIL: console release is incomplete\n' >&2; exit 1; }
