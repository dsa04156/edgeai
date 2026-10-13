#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
[[ $# == 0 ]] || blocked 'Usage: processing-post-renderer.sh (Helm post-renderer; stdin is the pinned chart manifest)'
exec python3 scripts/internal/processing_post_renderer.py
