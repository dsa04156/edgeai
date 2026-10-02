#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
load_env
require_docker
: "${EDGEAI_API_IMAGE:?Set EDGEAI_API_IMAGE}"
: "${EDGEAI_DASHBOARD_IMAGE:?Set EDGEAI_DASHBOARD_IMAGE}"
image_compose() { docker compose --project-name edgeai-image-test --env-file .env -f deploy/compose/image-test.yaml "$@"; }
# This isolated test project has a tmpfs database; no development DB or volume is touched.
trap 'image_compose down' EXIT
image_compose up -d
python3 scripts/smoke-deployment.py
