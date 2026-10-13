#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
[[ $# == 0 ]] || blocked 'Usage: build-processing-agent.sh'
mkdir -p output/continuous-vd/bin
(cd mappers && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 GOTOOLCHAIN=go1.24.2 go build -trimpath -ldflags '-s -w' -o ../output/continuous-vd/bin/processing-agent ./cmd/processing-agent)
printf 'AMD64 processing-agent binary built for mappers/processing-agent.Dockerfile.\n'
