"""Record bounded test evidence, masking local secrets before console/file output."""
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

test_id, *command = sys.argv[1:]
if not re.fullmatch(r"[a-zA-Z0-9_-]+", test_id):
    raise SystemExit("Invalid test ID")
secrets = []
if Path(".env").exists():
    for line in Path(".env").read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep and re.search(r"PASSWORD|SECRET|TOKEN|KEY", key) and value:
            secrets.append(value)
secrets += [v for k, v in os.environ.items() if re.search(r"PASSWORD|SECRET|TOKEN", k) and v]

def redact(text):
    for value in sorted(set(secrets), key=len, reverse=True):
        text = text.replace(value, "[REDACTED]")
    return re.sub(r"(?i)(authorization[:=]\s*).+", r"\1[REDACTED]", text)

run_id = datetime.datetime.now(datetime.UTC).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
directory = Path("docs/evidence/runs") / run_id
directory.mkdir(parents=True)
with (directory / "output.log").open("w") as log:
    try:
        child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in child.stdout:
            safe = redact(line)
            log.write(safe)
            print(safe, end="", flush=True)
        code = child.wait()
    except OSError as error:
        code = 2
        log.write(redact(str(error)))
status = "PASS" if code == 0 else "BLOCKED" if code == 2 else "FAIL"
metadata = {"testRunId": run_id, "testId": test_id, "command": [redact(x) for x in command], "exitCode": code, "status": status}
(directory / "result.json").write_text(json.dumps(metadata, indent=2) + "\n")
print(f"Evidence: {directory} ({status})")
raise SystemExit(code if code >= 0 else 1)
