#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
require_docker
compose up -d --wait --wait-timeout 120
printf 'PostgreSQL and MQTT ready. For optional object storage: bash scripts/dev/dev-storage.sh\n'
