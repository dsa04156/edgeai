#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="$root/output/hawkeye-video-replay"
mkdir -p "$out"
clip="$out/forklift.mp4"
if [[ ! -f "$clip" ]]; then
  rtk proxy curl --fail --location --output "$clip" 'https://huggingface.co/datasets/Voxel51/Safe_and_Unsafe_Behaviours/resolve/7369ff1830b5f6c00f7d7383eeeaa90704bfc5b5/data/3_te2.mp4'
fi
printf '%s  %s\n' 99d7ad69638f0b4ebbf3b966f279386ceeadd4521f16ce81cc9b551ab0293c36 "$clip" | rtk proxy sha256sum --check
rtk proxy cp "$here/Dockerfile" "$here/mediamtx.yml" "$here/NOTICE.md" "$out/"
rtk proxy docker buildx build --builder edgeai-kube-arm64-rootless --platform linux/amd64 --provenance=false \
  --tag "${1:?usage: build.sh registry/image:tag}" --output type=registry,registry.insecure=true \
  --metadata-file "$out/image-metadata.json" "$out"
