"""Stand-in for the KRR CLI: records argv and writes the recorded v1.31.1 report to --fileoutput."""
import json
import os
import sys
import time

args = sys.argv[1:]
with open(os.environ["FAKE_KRR_ARGV"], "w") as handle:
    json.dump(args, handle)
mode = os.environ.get("FAKE_KRR_MODE", "ok")
if mode == "fail":
    print("krr: prometheus unreachable", file=sys.stderr)
    sys.exit(3)
if mode == "noisy":
    block = b"x" * 1048576
    for _ in range(16):
        sys.stderr.buffer.write(block)
    sys.stderr.flush()
    sys.exit(1)
if mode == "sleep":
    time.sleep(30)
if mode == "noreport":
    sys.exit(0)
with open(os.environ["FAKE_KRR_REPORT"], "rb") as source, open(args[args.index("--fileoutput") + 1], "wb") as target:
    target.write(source.read())
