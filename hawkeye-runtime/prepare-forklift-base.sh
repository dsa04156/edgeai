#!/usr/bin/env bash
# RT-DETR Warehouse TensorRT base image (Dockerfile.forklift-base). No model or wheel blobs in Git.
#   bash hawkeye-runtime/prepare-forklift-base.sh            -> output/hawkeye-runtime/forklift-base/ context + forklift-base.oci.tar
#   bash hawkeye-runtime/prepare-forklift-base.sh --push REF -> push REF and print its digest
# The ONNX is downloaded from NGC once (or reused from output/verification/forklift-motion-20261026/model) and checked by
# SHA-256; wheels come from forklift-base-requirements.txt with --require-hashes.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(dirname "$here")"
out="$root/output/hawkeye-runtime"
context="$out/forklift-base"
model=rtdetr_warehouse_v1.0.2.onnx
sha=7b57da65ea42d0ed835da7ffee0656a7ba32337402ba8bff3dd1d0dd77b17084
url=https://api.ngc.nvidia.com/v2/models/nvidia/tao/rtdetr_2d_warehouse/versions/deployable_rn50_v1.0.2/files/$model
cached="$root/output/verification/forklift-motion-20261026/model/$model"
rm -rf "$context" && mkdir -p "$context/model"
if [[ -f "$cached" ]]; then cp "$cached" "$context/model/$model"; else curl -sfL -o "$context/model/$model" "$url"; fi
[[ "$(sha256sum "$context/model/$model" | cut -d' ' -f1)" == "$sha" ]] || { printf 'SHA-256 mismatch %s\n' "$model" >&2; exit 1; }
cat >"$context/model/SOURCE.txt" <<EOF
nvidia/tao/rtdetr_2d_warehouse deployable_rn50_v1.0.2 ($model, sha256 $sha)
Source: $url
Model card and license (NVIDIA Open Model License): https://catalog.ngc.nvidia.com/orgs/nvidia/tao/models/rtdetr_2d_warehouse
EOF
uv pip install --quiet --target "$context/site-packages" --python-version 3.12 --python-platform x86_64-manylinux_2_28 \
  --only-binary :all: --no-deps --require-hashes --link-mode copy -r "$here/forklift-base-requirements.txt"
# Linux runtime only: the wheel also carries builder resources for every other architecture and Windows copies (~6 GB).
libs="$context/site-packages/tensorrt_libs"
find "$libs" -name 'libnvinfer_builder_resource_*' ! -name 'libnvinfer_builder_resource_sm120.so.*' -delete
find "$context" -name __pycache__ -prune -exec rm -rf {} +
cp "$here/forklift_detector.py" "$here/Dockerfile.forklift-base" "$context/"
(cd "$context" && find . -type f -print0 | sort -z | xargs -0 sha256sum) >"$out/forklift-base-context.sha256"
args=(buildx build --builder "${BUILDX_BUILDER:-edgeai-kube-arm64-rootless}" --platform linux/amd64 --provenance=false
  --file "$context/Dockerfile.forklift-base")
if [[ "${1:-}" == "--push" ]]; then
  [[ -n "${2:-}" ]] || { printf 'missing image reference after --push\n' >&2; exit 2; }
  docker "${args[@]}" --tag "$2" --output type=registry,registry.insecure=true --metadata-file "$out/forklift-base-metadata.json" "$context"
  python3 -I -c 'import json,sys;print(json.load(open(sys.argv[1]))["containerimage.digest"])' "$out/forklift-base-metadata.json"
else
  docker "${args[@]}" --output "type=oci,dest=$out/forklift-base.oci.tar" "$context"
  printf 'built %s\n' "$out/forklift-base.oci.tar"
fi
