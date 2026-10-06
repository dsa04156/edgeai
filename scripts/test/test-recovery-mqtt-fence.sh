#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
command -v "${EDGEAI_MOSQUITTO_BINARY:-mosquitto}" >/dev/null || blocked 'Set EDGEAI_MOSQUITTO_BINARY or install Mosquitto.'
command -v "${EDGEAI_MOSQUITTO_CTRL_BINARY:-mosquitto_ctrl}" >/dev/null || blocked 'Set EDGEAI_MOSQUITTO_CTRL_BINARY or install mosquitto_ctrl.'
[[ -r "${EDGEAI_MOSQUITTO_DYNAMIC_SECURITY_PLUGIN:-/usr/lib/x86_64-linux-gnu/mosquitto_dynamic_security.so}" ]] || blocked 'Set EDGEAI_MOSQUITTO_DYNAMIC_SECURITY_PLUGIN.'
stream_python="${EDGEAI_STREAM_PYTHON:-python3}"
"$stream_python" -c 'import paho.mqtt' || blocked 'Install pinned runner/requirements-stream.txt and set EDGEAI_STREAM_PYTHON.'
exec "$stream_python" scripts/internal/test-recovery-mqtt-fence.py "$@"
