#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
load_env
node scripts/storage-probe.mjs
