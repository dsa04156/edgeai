#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
[[ "${1:-unit}" == unit && $# -le 1 ]] || blocked 'Use test-mapper.sh [unit].'
command -v go >/dev/null || blocked 'Go 1.24.2 is required.'
(cd mappers && GOTOOLCHAIN=go1.24.2 go test -race ./...)
