"""Check local copies of the scenario artifacts against provenance.json.

usage: python3 -I scenario-runtime/verify-provenance.py DIR [DIR ...]
Every file in the directories whose name appears in provenance.json must match its SHA-256.
"""
import hashlib
import json
import re
import sys
from pathlib import Path

expected = {}
for section in json.loads((Path(__file__).resolve().parent / "provenance.json").read_text()).values():
    expected.update({name: value for name, value in section.items() if re.fullmatch("[0-9a-f]{64}", value)})
checked = failed = 0
for directory in sys.argv[1:]:
    for path in sorted(Path(directory).iterdir()):
        if path.name in expected:
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            ok = digest == expected[path.name]
            checked += 1
            failed += not ok
            print(f"{'OK  ' if ok else 'FAIL'} {path} {digest}")
print(f"checked={checked} failed={failed}")
sys.exit(1 if failed or not checked else 0)
