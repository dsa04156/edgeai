#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
node scripts/internal/check-docs.mjs "$@"
