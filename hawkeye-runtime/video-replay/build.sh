#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="$root/output/hawkeye-video-replay"
mkdir -p "$out"
# The cached clip and the build context are named after the source file, so an earlier clip is never reused.
clip="$out/3_te1.mp4"
context="$out/context-3_te1"
if [[ ! -f "$clip" ]]; then
  rtk proxy curl --fail --location --output "$clip" 'https://huggingface.co/datasets/Voxel51/Safe_and_Unsafe_Behaviours/resolve/7369ff1830b5f6c00f7d7383eeeaa90704bfc5b5/data/3_te1.mp4'
fi
printf '%s  %s\n' 79c298ce0cb0eaa9a920285dce299dadc25ebfc737bf1f058c981f6bc2e23517 "$clip" | rtk proxy sha256sum --check
mkdir -p "$context"
rtk proxy cp "$clip" "$context/forklift.mp4"
rtk proxy cp "$here/Dockerfile" "$here/mediamtx.yml" "$here/NOTICE.md" "$context/"
rtk proxy docker buildx build --builder edgeai-kube-arm64-rootless --platform linux/amd64 --provenance=false \
  --tag "${1:?usage: build.sh registry/image:tag}" --output type=registry,registry.insecure=true \
  --metadata-file "$out/image-metadata.json" "$context"
