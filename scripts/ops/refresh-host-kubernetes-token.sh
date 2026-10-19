#!/usr/bin/env bash
# Renew the existing host API identity. Keep its RBAC and previous token on failure.
set +x
set -euo pipefail
if [[ $# != 5 ]]; then
  echo 'Usage: refresh-host-kubernetes-token.sh CONTEXT KUBECONFIG NAMESPACE SERVICE_ACCOUNT TOKEN_FILE' >&2
  exit 1
fi
token_destination="$5"
if [[ ! -f "$token_destination" || -L "$token_destination" || ! -O "$token_destination" ]]; then
  echo 'Token destination must be an existing regular file owned by this user.' >&2
  exit 1
fi
umask 077
exec 9>"${token_destination}.renew.lock"
flock -n 9 || exit 0
token_temporary="$(mktemp "${token_destination}.renew.XXXXXX")"
trap 'rm -f -- "$token_temporary"' EXIT
replacement_token="$("${KUBECTL_BIN:-kubectl}" --context "$1" --kubeconfig "$2" -n "$3" create token "$4" --duration=24h)"
[[ -n "$replacement_token" ]] || { echo 'Token request returned no credential.' >&2; exit 1; }
printf '%s\n' "$replacement_token" > "$token_temporary"
unset replacement_token
chmod 600 "$token_temporary"
mv -f -- "$token_temporary" "$token_destination"
echo 'Host API service-account token renewed.'
