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
pnpm_cmd --filter @edgeai/dashboard exec tsc tests/platform-service-proxy.unit.ts --outDir ../.tools/platform-proxy-tests --module commonjs --moduleResolution node --target ES2022 --esModuleInterop --skipLibCheck --strict
NODE_PATH="$EDGEAI_ROOT/dashboard/node_modules" node --test .tools/platform-proxy-tests/tests/platform-service-proxy.unit.js
