#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
[[ $# == 0 ]] || blocked 'Usage: test-processing.sh'
engine_home="${PROCESSING_TEST_EKUIPER_HOME:-$EDGEAI_ROOT/.tools/ekuiper-test-2.4.2-full/kuiper-2.4.2-linux-amd64}"
[[ -x "$engine_home/bin/kuiperd" ]] || blocked 'Verified full eKuiper 2.4.2 test distribution required; see docs/guides/vd-processing.md.'
[[ -x "${MAPPER_TEST_MOSQUITTO:-$EDGEAI_ROOT/.tools/mosquitto/usr/sbin/mosquitto}" ]] || blocked 'Real Mosquitto test broker required.'
export PROCESSING_TEST_EKUIPER_HOME="$engine_home"
(cd mappers && GOTOOLCHAIN=go1.24.2 go test -race ./internal/processing ./cmd/processing-agent)
# The console proxy routes for this API are covered by the native console boundary tests.
node --experimental-strip-types --disable-warning=ExperimentalWarning --test dashboard/tests/kestra-console.unit.mjs
