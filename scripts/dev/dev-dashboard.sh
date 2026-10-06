#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
pnpm_cmd --filter @edgeai/dashboard exec next dev --hostname 127.0.0.1 --port "$EDGEAI_DASHBOARD_PORT"
