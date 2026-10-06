#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
require_docker
compose --profile storage up -d --build --wait --wait-timeout 180 minio
