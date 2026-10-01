#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
load_env
require_docker
# Project-scoped stop; named data volumes are deliberately retained.
compose --profile storage down
