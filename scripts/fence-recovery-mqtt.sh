#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
stream_python="${EDGEAI_STREAM_PYTHON:-python3}"
"$stream_python" -c 'import paho.mqtt' || blocked 'Install pinned runner/requirements-stream.txt and set EDGEAI_STREAM_PYTHON.'
exec "$stream_python" scripts/recovery_mqtt_fence.py "$@"
