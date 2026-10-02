#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
[[ $# -eq 1 ]] || blocked 'Usage: test-vd-kubernetes.sh <explicit Kubernetes context>'
backend/gradlew -p backend :app:bootJar --console=plain
exec python3 scripts/test-vd-kubernetes.py --context "$1"
