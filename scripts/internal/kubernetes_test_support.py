"""Shared helpers for isolated Kubernetes acceptance tests."""
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[2]


def wait(predicate, seconds, message):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.2)
    raise AssertionError(message)
