#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
load_env
backend/gradlew -p backend :app:streamBrokerIntegrationTest --console=plain --rerun-tasks
