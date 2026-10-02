#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
export EDGEAI_PROFILE_E2E=1
bash scripts/test-health-stack.sh "${1:-none}"
