#!/usr/bin/env bash
# Build one scenario runtime image from its pinned base (no model/fixture blobs in this repository).
#   bash scenario-runtime/build.sh gpu|npu                -> output/scenario-runtime/<target>.oci.tar (local only)
#   bash scenario-runtime/build.sh gpu|npu --push REF     -> push REF and print its digest
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(dirname "$here")"
target="${1:-}"
case "$target" in
  gpu) platform=linux/amd64 ;;
  npu) platform=linux/arm64 ;;
  *) printf 'usage: %s gpu|npu [--push REF]\n' "$0" >&2; exit 2 ;;
esac
out="$root/output/scenario-runtime"
mkdir -p "$out"
# Reproducible context: the Dockerfile and the runtime package only.
context="$(mktemp -d)"
trap 'rm -rf "$context"' EXIT
cp "$here/Dockerfile.$target" "$context/Dockerfile"
cp -r "$here/edgeai_scenario_runtime" "$context/"
find "$context" -name __pycache__ -prune -exec rm -rf {} +
(cd "$context" && find . -type f -print0 | sort -z | xargs -0 sha256sum) > "$out/$target-context.sha256"
args=(buildx build --platform "$platform" --provenance=false --file "$context/Dockerfile")
if [[ "${2:-}" == "--push" ]]; then
  [[ -n "${3:-}" ]] || { printf 'missing image reference after --push\n' >&2; exit 2; }
  docker "${args[@]}" --tag "$3" --output type=registry,registry.insecure=true --metadata-file "$out/$target-metadata.json" "$context"
  python3 -I -c 'import json,sys;print(json.load(open(sys.argv[1]))["containerimage.digest"])' "$out/$target-metadata.json"
else
  docker "${args[@]}" --output "type=oci,dest=$out/$target.oci.tar" "$context"
  printf 'built %s\n' "$out/$target.oci.tar"
fi
