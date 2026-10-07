#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
platform_python="${PLATFORM_SERVICE_PYTHON:-$EDGEAI_ROOT/.tools/platform-service-venv/bin/python}"
[[ -x "$platform_python" ]] || blocked 'Create .tools/platform-service-venv and install platform-service/requirements.txt first.'
[[ -n "${EDGEAI_PLATFORM_SERVICE_TOKEN:-}" ]] || blocked 'Set EDGEAI_PLATFORM_SERVICE_TOKEN in .env.'
# One worker owns the build/deployment lock. Starting this API does not build or deploy anything.
exec "$platform_python" -m uvicorn workflow_api:app --app-dir "$EDGEAI_ROOT/platform-service/argocdAPI" --host 127.0.0.1 --port 18081 --workers 1 --no-access-log
