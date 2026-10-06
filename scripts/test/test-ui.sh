#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
pnpm_cmd --filter @edgeai/dashboard lint
pnpm_cmd --filter @edgeai/dashboard typecheck
pnpm_cmd --filter @edgeai/dashboard build
pnpm_cmd --filter @edgeai/dashboard test:e2e
