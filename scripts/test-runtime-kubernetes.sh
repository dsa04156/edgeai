#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
[[ $# == 1 ]] || blocked 'Pass the explicit Kubernetes context; bootstrap-runtime.py must have provisioned the owned namespaces/RBAC.'
python3 scripts/test-runtime-kubernetes.py --context "$1"
