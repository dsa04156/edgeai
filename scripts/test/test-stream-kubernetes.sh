#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
[[ $# -eq 1 ]] || blocked 'Usage: test-stream-kubernetes.sh <explicit Kubernetes context>'
backend/gradlew -p backend :app:bootJar --console=plain
exec python3 scripts/internal/test-stream-kubernetes.py --context "$1"
