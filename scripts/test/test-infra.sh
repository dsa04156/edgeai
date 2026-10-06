#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
load_env
require_docker
compose exec -T postgres pg_isready -U "$EDGEAI_DB_USER" -d "$EDGEAI_DB_NAME"
compose exec -T mqtt sh -ec '
  topic="edgeai/probe/$$"
  mosquitto_sub -h 127.0.0.1 -t "$topic" -C 1 -W 5 > /tmp/edgeai-probe-$$ &
  subscriber=$!
  trap '\''rm -f /tmp/edgeai-probe-$$'\'' EXIT
  sleep 1
  mosquitto_pub -h 127.0.0.1 -t "$topic" -m ready
  wait "$subscriber"
  test "$(cat /tmp/edgeai-probe-$$)" = ready
'
