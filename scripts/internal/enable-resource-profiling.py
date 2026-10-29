#!/usr/bin/env python3
"""Enable the 3-3 resource-profiling component in overlays/dev with a CI-published KRR bridge digest.

Usage: python3 scripts/internal/enable-resource-profiling.py --digest sha256:<64 hex> [--overlay PATH] [--check]
Only edits the overlay file (adds the component and pins ghcr.io/dsa04156/edgeai-krr-bridge@digest); it never
talks to a cluster. --check verifies the overlay is already enabled with that digest and exits 1 otherwise.
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IMAGE = "ghcr.io/dsa04156/edgeai-krr-bridge"
COMPONENT = "../../components/resource-profiling"


def enable(text: str, digest: str) -> str:
    if COMPONENT not in text:
        anchor = "components:\n"
        if anchor not in text:
            raise SystemExit("overlay has no components list")
        text = text.replace(anchor, f"{anchor}  - {COMPONENT}\n", 1)
    entry = re.compile(rf"  - name: {re.escape(IMAGE)}\n    digest: sha256:[0-9a-f]{{64}}\n")
    pinned = f"  - name: {IMAGE}\n    digest: {digest}\n"
    if entry.search(text):
        return entry.sub(pinned, text, count=1)
    if "\nimages:\n" not in text:
        raise SystemExit("overlay has no images list")
    return text.replace("\nimages:\n", f"\nimages:\n  # KRR bridge built and smoke-tested by .github/workflows/krr-bridge.yml\n{pinned}", 1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--digest", required=True)
    parser.add_argument("--overlay", default=str(ROOT / "deploy/kubernetes/overlays/dev/kustomization.yaml"))
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", args.digest) or set(args.digest[7:]) == {"0"}:
        raise SystemExit("--digest must be the published sha256 index digest (placeholders are refused)")
    path = Path(args.overlay)
    current = path.read_text()
    updated = enable(current, args.digest)
    if args.check:
        return 0 if updated == current else 1
    path.write_text(updated)
    print(f"enabled {COMPONENT} with {IMAGE}@{args.digest} in {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
