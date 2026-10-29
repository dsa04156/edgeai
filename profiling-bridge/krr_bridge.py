"""Recommendation-only KRR bridge for 3-3 resource profiling.

Runs the pinned open-source KRR (robusta-dev/krr) for one validated workload identity and returns its raw JSON
report with provenance. The Prometheus URL, KRR command, namespace allowlist and timeout are server configuration;
requests can only name a namespace/kind/workload/container and a bounded history. No shell is used, no request
value becomes a URL, command or PromQL, and KRR never writes to the cluster (it only reads workloads/Pods).
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import shlex
import signal
import subprocess
import sys
import tempfile
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

BRIDGE_VERSION = "1"
KINDS = ("Deployment", "StatefulSet", "DaemonSet", "Job", "CronJob")
LABEL = re.compile(r"^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")
SUBDOMAIN = re.compile(r"^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$")
MAX_REQUEST_BYTES = 4096
MAX_REPORT_BYTES = 12 * 1024 * 1024
LOG = logging.getLogger("krr-bridge")


class Invalid(ValueError):
    pass


class Settings:
    def __init__(self, env: dict[str, str]):
        self.prometheus_url = origin(env.get("KRR_PROMETHEUS_URL", ""))
        self.command = shlex.split(env.get("KRR_COMMAND", "python /app/krr.py"))
        if not self.command:
            raise ValueError("KRR_COMMAND is required")
        self.version = env.get("KRR_VERSION", "v1.31.1")
        self.namespaces = tuple(n.strip() for n in env.get("KRR_ALLOWED_NAMESPACES", "edgeai").split(",") if n.strip())
        if not self.namespaces or not all(LABEL.match(n) and len(n) <= 63 for n in self.namespaces):
            raise ValueError("KRR_ALLOWED_NAMESPACES must list Kubernetes namespaces")
        self.timeout = int(env.get("KRR_TIMEOUT_SECONDS", "120"))
        if not 10 <= self.timeout <= 600:
            raise ValueError("KRR_TIMEOUT_SECONDS must be 10-600")


def origin(value: str) -> str:
    parts = urlsplit(value.rstrip("/"))
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password \
            or parts.path or parts.query or parts.fragment:
        raise ValueError("KRR_PROMETHEUS_URL must be an HTTP(S) origin without credentials")
    return f"{parts.scheme}://{parts.netloc}"


def validate(request: object, settings: Settings) -> dict:
    if not isinstance(request, dict) or set(request) != {"namespace", "kind", "name", "container", "historyHours"}:
        raise Invalid("request must contain exactly namespace, kind, name, container, historyHours")
    namespace, kind, name, container, hours = (request[k] for k in ("namespace", "kind", "name", "container", "historyHours"))
    if not isinstance(namespace, str) or namespace not in settings.namespaces:
        raise Invalid("namespace is not allowed")
    if kind not in KINDS:
        raise Invalid("kind must be a KRR-supported controller")
    if not isinstance(name, str) or len(name) > 253 or not SUBDOMAIN.match(name):
        raise Invalid("name must be a Kubernetes name")
    if not isinstance(container, str) or len(container) > 63 or not LABEL.match(container):
        raise Invalid("container must be a Kubernetes name")
    if not isinstance(hours, int) or isinstance(hours, bool) or not 1 <= hours <= 336:
        raise Invalid("historyHours must be an integer 1-336")
    return {"namespace": namespace, "kind": kind, "name": name, "container": container, "historyHours": hours}


def arguments(request: dict, settings: Settings, output: str) -> list[str]:
    """KRR CLI of the pinned version; KRR has no name filter, so the caller matches the exact scan identity."""
    return ["simple", "--prometheus-url", settings.prometheus_url, "--namespace", request["namespace"],
            "--resource", request["kind"], "--formatter", "json", "--fileoutput", output,
            "--history_duration", str(request["historyHours"]), "--logtostderr", "--quiet"]


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def run(request: dict, settings: Settings) -> dict:
    started = now()
    with tempfile.TemporaryDirectory(prefix="krr-") as directory:
        output = os.path.join(directory, "report.json")
        args = arguments(request, settings, output)
        public = [a if a != settings.prometheus_url else "<configured-prometheus>" for a in args]
        public[public.index("--fileoutput") + 1] = "<report.json>"
        result = {"bridgeVersion": BRIDGE_VERSION, "krrVersion": settings.version, "startedAt": started,
                  "arguments": public, "request": request, "exitCode": None, "report": None, "reportSha256": None}
        process = subprocess.Popen(settings.command + args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.PIPE, cwd=directory, start_new_session=True)
        try:
            _, stderr = process.communicate(timeout=settings.timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate()
            return {**result, "status": "TIMEOUT", "finishedAt": now()}
        result["exitCode"] = process.returncode
        result["finishedAt"] = now()
        if process.returncode != 0:
            LOG.warning("krr exited %s: %s", process.returncode, stderr[-2000:].decode("utf-8", "replace"))
            return {**result, "status": "FAILED"}
        try:
            size = os.path.getsize(output)
            if size > MAX_REPORT_BYTES:
                return {**result, "status": "FAILED", "error": "report too large"}
            with open(output, "rb") as handle:
                raw = handle.read()
            report = json.loads(raw)
        except (OSError, ValueError):
            return {**result, "status": "FAILED", "error": "KRR did not write a JSON report"}
        return {**result, "status": "COMPLETED", "report": report, "reportSha256": hashlib.sha256(raw).hexdigest()}


class Handler(BaseHTTPRequestHandler):
    settings: Settings
    lock = threading.BoundedSemaphore(1)
    server_version = "edgeai-krr-bridge/" + BRIDGE_VERSION

    def log_message(self, format: str, *args) -> None:  # noqa: A002 - BaseHTTPRequestHandler signature
        LOG.info("%s %s", self.address_string(), format % args)

    def reply(self, status: int, body: dict) -> None:
        data = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/healthz":
            self.reply(200, {"status": "ok", "krrVersion": self.settings.version, "bridgeVersion": BRIDGE_VERSION})
        else:
            self.reply(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/recommendations":
            self.reply(404, {"error": "not found"})
            return
        if self.headers.get_content_type() != "application/json":
            self.reply(415, {"error": "application/json required"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if not 0 < length <= MAX_REQUEST_BYTES:
            self.reply(413 if length > MAX_REQUEST_BYTES else 400, {"error": "invalid request size"})
            return
        try:
            request = validate(json.loads(self.rfile.read(length)), self.settings)
        except (Invalid, ValueError) as error:
            self.reply(400, {"error": str(error) if isinstance(error, Invalid) else "invalid JSON"})
            return
        if not self.lock.acquire(blocking=False):
            self.reply(429, {"error": "another KRR run is in progress"})
            return
        try:
            self.reply(200, run(request, self.settings))
        finally:
            self.lock.release()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    Handler.settings = Settings(dict(os.environ))
    port = int(os.environ.get("KRR_BRIDGE_PORT", "8080"))
    server = ThreadingHTTPServer((os.environ.get("KRR_BRIDGE_BIND", "0.0.0.0"), port), Handler)
    LOG.info("KRR bridge %s listening on %s (KRR %s)", BRIDGE_VERSION, port, Handler.settings.version)
    server.serve_forever()


if __name__ == "__main__":
    sys.exit(main())
