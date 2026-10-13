#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
[[ $# == 0 ]] || blocked 'Usage: test-processing.sh'
engine_home="${PROCESSING_TEST_EKUIPER_HOME:-$EDGEAI_ROOT/.tools/ekuiper-test-2.4.2-full/kuiper-2.4.2-linux-amd64}"
[[ -x "$engine_home/bin/kuiperd" ]] || blocked 'Verified full eKuiper 2.4.2 test distribution required; see docs/guides/vd-processing.md.'
[[ -x "${MAPPER_TEST_MOSQUITTO:-$EDGEAI_ROOT/.tools/mosquitto/usr/sbin/mosquitto}" ]] || blocked 'Real Mosquitto test broker required.'
export PROCESSING_TEST_EKUIPER_HOME="$engine_home"
(cd mappers && GOTOOLCHAIN=go1.24.2 go test -race ./internal/processing ./cmd/processing-agent)
pnpm_cmd --filter @edgeai/dashboard exec tsc tests/continuous-proxy.unit.ts --outDir ../.tools/continuous-proxy-tests --module commonjs --moduleResolution node --target ES2022 --esModuleInterop --skipLibCheck --strict
NODE_PATH="$EDGEAI_ROOT/dashboard/node_modules" node --test .tools/continuous-proxy-tests/tests/continuous-proxy.unit.js
