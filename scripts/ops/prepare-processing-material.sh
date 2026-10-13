#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
[[ $# == 1 ]] || blocked 'Usage: prepare-processing-material.sh /absolute/new/private/directory'
command -v openssl >/dev/null || blocked 'OpenSSL is required.'
umask 077
exec python3 scripts/internal/processing_material.py "$1"
