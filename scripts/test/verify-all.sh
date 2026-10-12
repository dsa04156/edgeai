#!/usr/bin/env bash
source "$(dirname "$0")/../lib.sh"
mode="${1:-scaffold}"
case "$mode" in
  scaffold) checks=(test-unit test-contract test-runner test-remote test-ui) ;;
  local) checks=(test-unit test-contract test-runner test-remote test-runtime-storage test-runtime-results test-integration test-ui test-health test-kind) ;;
  full) checks=(test-unit test-contract test-runner test-remote test-runtime-storage test-runtime-results test-integration test-ui test-health test-kind test-load) ;;
  *) blocked 'Usage: verify-all.sh scaffold|local|full' ;;
esac
result=0
for check in "${checks[@]}"; do
  if bash scripts/collect-evidence.sh "$check" bash "scripts/test/$check.sh"; then
    printf 'PASS %s\n' "$check"
  else
    code=$?
    printf 'INCOMPLETE %s (exit %s)\n' "$check" "$code"
    if [[ "$code" != 2 ]]; then result=1;
    elif [[ "$result" == 0 ]]; then result=2; fi
  fi
done
if [[ "$mode" != scaffold ]]; then
  printf '%s\n' 'BLOCKED: M9 platform-wide fault acceptance is not implemented; component checks do not replace it.' >&2
  if [[ "$result" == 0 ]]; then result=2; fi
fi
if [[ "$mode" == full ]]; then
  printf '%s\n' 'BLOCKED: M10 hardware/remote-contract/performance acceptance is not implemented.' >&2
fi
if [[ "$result" == 0 && "$mode" == scaffold ]]; then printf 'SCAFFOLD_VERIFIED only; domain and hardware acceptance remain pending.\n'; fi
exit "$result"
