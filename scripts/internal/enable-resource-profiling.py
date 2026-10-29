#!/usr/bin/env python3
"""Enable the 3-3 resource-profiling component in overlays/dev with a verified KRR bridge digest.

Usage: python3 scripts/internal/enable-resource-profiling.py --digest sha256:<64 hex>
         [--registry 10.254.192.47:5000/edgeai/krr-bridge] [--overlay PATH] [--check]
Without --registry the image is ghcr.io/dsa04156/edgeai-krr-bridge (.github/workflows/krr-bridge.yml); with it the
overlay rewrites the name like the live API/console images (local registry). Only the overlay file is edited; it
never talks to a cluster. --check exits 1 unless the overlay is already enabled with exactly this image and digest.
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IMAGE = "ghcr.io/dsa04156/edgeai-krr-bridge"
COMPONENT = "../../components/resource-profiling"


def enable(text: str, digest: str, registry: str | None) -> str:
    if COMPONENT not in text:
        anchor = "components:\n"
        if anchor not in text:
            raise SystemExit("overlay has no components list")
        text = text.replace(anchor, f"{anchor}  - {COMPONENT}\n", 1)
    entry = re.compile(rf"  - name: {re.escape(IMAGE)}\n(    newName: \S+\n)?    digest: sha256:[0-9a-f]{{64}}\n")
    pinned = f"  - name: {IMAGE}\n" + (f"    newName: {registry}\n" if registry else "") + f"    digest: {digest}\n"
    if entry.search(text):
        return entry.sub(lambda _: pinned, text, count=1)
    if "\nimages:\n" not in text:
        raise SystemExit("overlay has no images list")
    return text.replace("\nimages:\n", f"\nimages:\n  # KRR bridge (profiling-bridge/), verified digest; see docs/guides/resource-profiling.md\n{pinned}", 1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--digest", required=True)
    parser.add_argument("--registry", help="image name in another registry, e.g. 10.254.192.47:5000/edgeai/krr-bridge")
    parser.add_argument("--overlay", default=str(ROOT / "deploy/kubernetes/overlays/dev/kustomization.yaml"))
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", args.digest) or set(args.digest[7:]) == {"0"}:
        raise SystemExit("--digest must be the published sha256 digest (placeholders are refused)")
    if args.registry and not re.fullmatch(r"[a-z0-9.-]+(:[0-9]+)?(/[a-z0-9._-]+)+", args.registry):
        raise SystemExit("--registry must be an image name without tag or digest")
    path = Path(args.overlay)
    current = path.read_text()
    updated = enable(current, args.digest, args.registry)
    if args.check:
        return 0 if updated == current else 1
    path.write_text(updated)
    print(f"enabled {COMPONENT} with {args.registry or IMAGE}@{args.digest} in {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
