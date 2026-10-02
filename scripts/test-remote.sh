#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
command -v python3 >/dev/null || blocked 'Python 3 is required for the reference Remote provider.'
backend/gradlew -p backend :app:remoteIntegrationTest --rerun-tasks --console=plain
