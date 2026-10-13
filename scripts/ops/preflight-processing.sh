#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
[[ $# == 2 && -n "$1" && "$2" != -* ]] || blocked 'Usage: preflight-processing.sh explicit-kube-context configured-CG002-ssh-alias'
command -v kubectl >/dev/null || blocked 'kubectl required.'
command -v ssh >/dev/null || blocked 'ssh required for actual local-path disk-free measurement.'
python3 scripts/internal/processing_preflight.py "$1" "$2" || { code=$?; [[ $code == 1 ]] && exit 2; exit "$code"; }
