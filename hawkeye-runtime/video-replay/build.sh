#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="$root/output/hawkeye-video-replay"
mkdir -p "$out"
# The cached clip and the build context are named after the source files, so an earlier clip is never reused.
clip="$out/3_te1.mp4"
context="$out/context-3_te1-tYYNdqPooMI-lSGMB3h41ug"
# The two YouTube clips are prepared locally (NOTICE.md has the conversion) and are not downloaded here.
youtube="$root/output/hawkeye-youtube"
turn="$youtube/tYYNdqPooMI-24fps.mp4"
accidents="$youtube/lSGMB3h41ug-cctv-24fps.mp4"
for prepared in "$turn" "$accidents"; do
  [[ -f "$prepared" ]] || { echo "prepared replay clip missing: $prepared (see NOTICE.md)" >&2; exit 1; }
done
if [[ ! -f "$clip" ]]; then
  rtk proxy curl --fail --location --output "$clip" 'https://huggingface.co/datasets/Voxel51/Safe_and_Unsafe_Behaviours/resolve/7369ff1830b5f6c00f7d7383eeeaa90704bfc5b5/data/3_te1.mp4'
fi
printf '%s  %s\n' 79c298ce0cb0eaa9a920285dce299dadc25ebfc737bf1f058c981f6bc2e23517 "$clip" \
  8dcfd5fb65b391468d7e7434befc98429adc6970a081cbe1ef9e3c642315c516 "$turn" \
  de12a1229946289c7a389a0067cf4e7f01898e71f7855f293d489bdec1cf7102 "$accidents" | rtk proxy sha256sum --check
mkdir -p "$context"
rtk proxy cp "$clip" "$context/forklift.mp4"
rtk proxy cp "$turn" "$context/forklift-turn.mp4"
rtk proxy cp "$accidents" "$context/forklift-accidents.mp4"
rtk proxy cp "$here/Dockerfile" "$here/mediamtx.yml" "$here/NOTICE.md" "$context/"
rtk proxy docker buildx build --builder edgeai-kube-arm64-rootless --platform linux/amd64 --provenance=false \
  --tag "${1:?usage: build.sh registry/image:tag}" --output type=registry,registry.insecure=true \
  --metadata-file "$out/image-metadata.json" "$context"
