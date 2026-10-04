#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
python3 - <<'PY'
import hashlib
import os
from pathlib import Path
import platform
import sys
import tempfile
import urllib.request

if platform.system() != 'Linux' or platform.machine() != 'x86_64':
    print('BLOCKED: this pinned MinIO client installer supports Linux x86_64 only')
    sys.exit(2)
path = Path('.tools/mc')
checksum = '01f866e9c5f9b87c2b09116fa5d7c06695b106242d829a8bb32990c00312e891'
url = 'https://github.com/minio/mc/releases/download/RELEASE.2025-08-13T08-35-41Z/mc.linux-amd64.RELEASE.2025-08-13T08-35-41Z'
path.parent.mkdir(mode=0o700, exist_ok=True)
if path.exists():
    assert hashlib.sha256(path.read_bytes()).hexdigest() == checksum, 'Existing MinIO client checksum differs; file was not replaced'
else:
    descriptor, temporary = tempfile.mkstemp(prefix='mc-download-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as output, urllib.request.urlopen(url, timeout=30) as source:
            while chunk := source.read(1024 * 1024):
                output.write(chunk)
        temporary = Path(temporary)
        assert hashlib.sha256(temporary.read_bytes()).hexdigest() == checksum, 'MinIO client checksum mismatch'
        temporary.chmod(0o700)
        os.link(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)
print('PASS: pinned MinIO client RELEASE.2025-08-13T08-35-41Z and SHA-256 verified')
PY
