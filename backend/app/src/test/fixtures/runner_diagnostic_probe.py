"""Bounded diagnostics for actual stream Runner subprocesses."""
import json
from pathlib import Path
import re
import sys
import traceback

REPO = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(REPO / "runner"))
from edgeai_runner import main as runner

def diagnostic(error, operation):
    frames = []
    for frame in traceback.extract_tb(error.__traceback__):
        path = Path(frame.filename).resolve()
        if REPO / "runner" in path.parents:
            frames.append(f"{path.name}:{frame.name}:{frame.lineno}")
    code = getattr(error, "code", None)
    if type(error).__name__ == "SessionError" and error.args and error.args[0] in {
            "STREAM_CANCELLED", "STREAM_SESSION_TIMEOUT", "STREAM_ASSIGNMENT_EXPIRED"}:
        code = error.args[0]
    print("RUNNER_DIAGNOSTIC " + json.dumps({
        "operation": operation, "errorType": type(error).__name__,
        "code": code if isinstance(code, str) and re.fullmatch(r"[A-Z_]{1,80}", code) else "NONE",
        "frames": frames[-12:],
    }, sort_keys=True), flush=True)


def child():
    original_run, original_api = runner.Runner.run, runner.Runner.api

    def run(self):
        try:
            return original_run(self)
        except Exception as error:
            diagnostic(error, "run")
            raise

    def api(self, operation, *args, **kwargs):
        try:
            return original_api(self, operation, *args, **kwargs)
        except Exception as error:
            diagnostic(error, operation if operation in {
                "claim", "uploads", "commit", "fail", "telemetry", "streams/complete", "streams/execution"} else "other")
            raise

    runner.Runner.run, runner.Runner.api = run, api
    return runner.main()

if __name__ == "__main__":
    sys.exit(child())
