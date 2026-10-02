#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
mode="${1:-scaffold}"
case "$mode" in
  scaffold) checks=(test-unit test-contract test-runner test-remote test-ui) ;;
  local) checks=(test-unit test-contract test-runner test-remote test-runtime-storage test-runtime-results test-integration test-ui test-health test-kind test-fault) ;;
  full) checks=(test-unit test-contract test-runner test-remote test-runtime-storage test-runtime-results test-integration test-ui test-health test-kind test-fault test-load test-hardware) ;;
  *) blocked 'Usage: verify-all.sh scaffold|local|full' ;;
esac
result=0
for check in "${checks[@]}"; do
  if bash scripts/collect-evidence.sh "$check" bash "scripts/$check.sh"; then
    printf 'PASS %s\n' "$check"
  else
    code=$?
    printf 'INCOMPLETE %s (exit %s)\n' "$check" "$code"
    result=1
  fi
done
if [[ "$result" == 0 && "$mode" == scaffold ]]; then printf 'SCAFFOLD_VERIFIED only; domain and hardware acceptance remain pending.\n'; fi
exit "$result"
