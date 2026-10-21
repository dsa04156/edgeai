#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
platform_python="${PLATFORM_SERVICE_PYTHON:-$EDGEAI_ROOT/.tools/platform-service-venv/bin/python}"
[[ -x "$platform_python" ]] || blocked 'Install platform-service/requirements-test.txt in .tools/platform-service-venv first.'
"$platform_python" scripts/internal/export-platform-service-contract.py --check
"$platform_python" -m unittest discover -s platform-service/tests -v
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
pnpm_cmd exec openapi-typescript contracts/openapi/platform-service-api.json -o "$tmp"
cmp dashboard/lib/platform-service-schema.d.ts "$tmp" || { printf 'Platform-Service types differ. Run corepack pnpm contract:generate\n' >&2; exit 1; }
# The console proxy routes for this API are covered by the native console boundary tests.
node --experimental-strip-types --disable-warning=ExperimentalWarning --test dashboard/tests/kestra-console.unit.mjs
