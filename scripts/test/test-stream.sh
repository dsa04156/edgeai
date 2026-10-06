#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
stream_python="${EDGEAI_STREAM_PYTHON:-python3}"
"$stream_python" -c 'import paho.mqtt' || blocked 'Install the pinned runner/requirements-stream.txt in a virtual environment and set EDGEAI_STREAM_PYTHON.'
"$stream_python" -W error::ResourceWarning -m unittest discover -s runner/integration -p 'test_stream_*.py' -v
