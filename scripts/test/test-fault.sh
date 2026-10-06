#!/usr/bin/env bash
set -euo pipefail
printf "%s\n" "BLOCKED: M9 platform-wide fault acceptance is incomplete. Existing cancellation, fencing, runtime recovery and database restore checks are listed in docs/testing/verification-matrix.md; they do not replace this gate." >&2
exit 2
