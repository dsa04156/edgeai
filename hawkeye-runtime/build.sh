#!/usr/bin/env bash
# Hawkeye warehouse-safety runtime images (docs/scenarios/hawkeye-warehouse-safety.md). No model or image blobs in Git.
#   bash hawkeye-runtime/build.sh prepare                 -> output/hawkeye-runtime/{store,samples} verified against provenance.json
#   bash hawkeye-runtime/build.sh ollama|adapter|workflow  -> output/hawkeye-runtime/<target>.oci.tar (local only)
#   bash hawkeye-runtime/build.sh ollama|adapter|workflow --push REF -> push REF and print its digest
#   workflow = released Runner + capture/infer workloads of the video workflow; it needs no model or samples.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(dirname "$here")"
out="$root/output/hawkeye-runtime"
target="${1:-}"
mkdir -p "$out"
field() { python3 -I -c 'import json,sys;print(json.load(open(sys.argv[1]))[sys.argv[2]][sys.argv[3]])' "$here/provenance.json" "$1" "$2"; }
check() { # file expected-sha256
  local actual; actual="$(sha256sum "$1" | cut -d' ' -f1)"
  [[ "$actual" == "$2" ]] || { printf 'SHA-256 mismatch %s: %s != %s\n' "$1" "$actual" "$2" >&2; exit 1; }
}
verify() {
  local blobs="$out/store/blobs"
  check "$blobs/sha256-$(field model Qwen3.5-2B.Q4_K_M.gguf)" "$(field model Qwen3.5-2B.Q4_K_M.gguf)"
  check "$blobs/sha256-$(field model Qwen3.5-2B.F16-mmproj.gguf)" "$(field model Qwen3.5-2B.F16-mmproj.gguf)"
  check "$out/store/manifests/hf.co/Jsohal174/hawkeye-warehouse-safety-gguf/latest" "$(field model ollama_manifest_digest)"
  check "$here/samples.json" "$(field samples samples.json)"
  for name in forklift_0004.jpg forklift_0024.jpg; do check "$out/samples/$name" "$(field samples "$name")"; done
}
case "$target" in
  prepare)
    # Pull through an isolated Ollama (own port and store), exactly as the model card quick start.
    port=11500
    OLLAMA_HOST="127.0.0.1:$port" OLLAMA_MODELS="$out/store" ollama serve >"$out/prepare-serve.log" 2>&1 &
    serve=$!
    trap 'kill $serve' EXIT
    until curl -sf "127.0.0.1:$port/api/version" >/dev/null; do sleep 1; done
    OLLAMA_HOST="127.0.0.1:$port" ollama pull "hf.co/Jsohal174/hawkeye-warehouse-safety-gguf"
    mkdir -p "$out/samples"
    revision="$(field samples revision)"
    python3 -I -c 'import json,sys;[print(s["file"]) for s in json.load(open(sys.argv[1]))["samples"]]' "$here/samples.json" | while read -r file; do
      curl -sfL -o "$out/samples/$(basename "$file")" \
        "https://huggingface.co/datasets/Jsohal174/warehouse-safety-hazard-dataset/resolve/$revision/$file"
    done
    verify
    printf 'prepared %s\n' "$out"
    exit 0 ;;
  ollama) platform=linux/amd64 ;;
  adapter) platform=linux/amd64 ;;
  workflow) platform=linux/amd64 ;;
  *) printf 'usage: %s prepare|ollama|adapter|workflow [--push REF]\n' "$0" >&2; exit 2 ;;
esac
[[ "$target" == workflow ]] || verify
context="$(mktemp -d "$out/context.XXXXXX")" # same filesystem as the store, for hard links
trap 'rm -rf "$context"' EXIT
cp "$here/Dockerfile.$target" "$context/Dockerfile"
if [[ "$target" == ollama ]]; then
  # Only the Hawkeye manifest and the blobs it references (hard links, no 2 GB copy).
  python3 -I - "$out/store" "$context/models" <<'PY'
import json, os, sys
store, dest = sys.argv[1], sys.argv[2]
manifest = "manifests/hf.co/Jsohal174/hawkeye-warehouse-safety-gguf/latest"
document = json.load(open(os.path.join(store, manifest)))
names = [manifest] + ["blobs/" + layer["digest"].replace(":", "-") for layer in [document["config"]] + document["layers"]]
for name in names:
    os.makedirs(os.path.dirname(os.path.join(dest, name)), exist_ok=True)
    os.link(os.path.join(store, name), os.path.join(dest, name))
PY
elif [[ "$target" == workflow ]]; then
  cp "$here/workflow/hawkeye_capture.py" "$here/workflow/hawkeye_infer.py" "$context/"
else
  cp -r "$here/edgeai_hawkeye_runtime" "$context/"
  cp "$here/samples.json" "$context/"
  mkdir "$context/samples" && cp "$out"/samples/*.jpg "$context/samples/"
  find "$context" -name __pycache__ -prune -exec rm -rf {} +
fi
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
