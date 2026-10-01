#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
backend/gradlew -p backend test --console=plain
