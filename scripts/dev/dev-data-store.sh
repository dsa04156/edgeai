#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
[[ $# == 1 && ( "$1" == start || "$1" == stop ) ]] || blocked 'Usage: dev-data-store.sh start|stop'
python3 scripts/internal/influx_local.py "$1"
