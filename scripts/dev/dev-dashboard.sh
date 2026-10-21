#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
# The console is the Kestra-native UI (Vite dev server on 13083); releases serve 13080 via server.mjs.
exec bash scripts/dev/dev-kestra-native.sh
