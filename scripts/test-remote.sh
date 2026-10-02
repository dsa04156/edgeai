#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
command -v python3 >/dev/null || blocked 'Python 3 is required for the reference Remote provider.'
command -v openssl >/dev/null || blocked 'OpenSSL is required for the isolated TLS provider fixture.'
python3 -m unittest discover -s simulator/tests -v
backend/gradlew -p backend :app:remoteIntegrationTest --rerun-tasks --console=plain
