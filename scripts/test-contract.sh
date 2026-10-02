#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
pnpm_cmd exec openapi-typescript contracts/openapi/platform-api.yaml -o "$tmp"
cmp dashboard/lib/api-schema.d.ts "$tmp" || { printf 'Generated contract differs. Run corepack pnpm contract:generate\n' >&2; exit 1; }
backend/gradlew -p backend :app:test --tests io.edgeai.app.PlatformControllerTest --tests io.edgeai.app.profile.ProfileControllerTest --tests io.edgeai.app.SwaggerUiTest --console=plain
cmp contracts/openapi/platform-api.yaml backend/app/build/resources/main/static/openapi.yaml || { printf 'Packaged Swagger contract differs from source\n' >&2; exit 1; }
