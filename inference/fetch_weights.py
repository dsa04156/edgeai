"""Build-time download of the pinned COCO_V1 weights, verified by full SHA-256."""

import hashlib
import os
import sys
import urllib.request

URL = "https://download.pytorch.org/models/fasterrcnn_resnet50_fpn_v2_coco-dd69338a.pth"
SHA256 = sys.argv[1]
target = os.path.join("/opt/models", os.path.basename(URL))

os.makedirs("/opt/models", exist_ok=True)
digest = hashlib.sha256()
with urllib.request.urlopen(URL, timeout=120) as response, open(target, "wb") as out:
    for chunk in iter(lambda: response.read(1 << 20), b""):
        digest.update(chunk)
        out.write(chunk)
if digest.hexdigest() != SHA256:
    os.remove(target)
    sys.exit(f"sha256 mismatch: {digest.hexdigest()} != {SHA256}")
print(target, SHA256)
