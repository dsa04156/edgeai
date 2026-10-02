#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
python3 -m unittest discover -s runner/tests -v
