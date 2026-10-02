"""Real persistent supervisor/Runner processes against HTTP fixtures; not platform acceptance."""
import contextlib
import http.server
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import uuid

from test_runner import Fixture, ROOT


class VDHost:
    def __init__(self, tasks, capacity=1, lease=5, drain=5, handler=None, attempt_limit=None):
        self.tasks = tasks
        self.capacity, self.lease, self.drain = capacity, lease, drain
        self.handler = handler
        self.attempt_limit = attempt_limit
        self.runtime, self.vd, self.pod = [str(uuid.uuid4()) for _ in range(3)]
        self.token, self.pod_token = "vd-test-"+uuid.uuid4().hex, "pod-test-"+uuid.uuid4().hex
        self.requests, self.completed, self.issued = [], {}, set()
        self.image = os.environ.get("EDGEAI_RUNNER_IMAGE")
        self.container = "edgeai-vd-test-"+uuid.uuid4().hex[:12]
        self.process = None
        self.faults = []
        host = self
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def reply(self, status, value):
                data = json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass
            def do_POST(self):
                data = self.rfile.read(int(self.headers["Content-Length"]))
                if self.path.startswith("/internal/v1/attempts/"):
                    attempt = self.path.split("/")[4]
                    task = next((task for task in host.tasks if task.attempt == attempt), None)
                    if task is None:
                        return self.reply(404, {})
                    request = urllib.request.Request(task.origin+self.path, data=data, method="POST", headers={
                        "Content-Type": "application/json", "Authorization": self.headers.get("Authorization", ""),
                        "X-EdgeAI-Pod-Token": self.headers.get("X-EdgeAI-Pod-Token", "")})
                    try:
                        with urllib.request.urlopen(request, timeout=5) as response:
                            return self.reply(response.status, json.load(response))
                    except urllib.error.HTTPError as error:
                        status, value = error.code, json.load(error); error.close()
                        return self.reply(status, value)
                if self.path != f"/internal/v1/vd-runtimes/{host.runtime}/poll":
                    return self.reply(404, {})
                if self.headers.get("Authorization") != "Bearer "+host.token or self.headers.get("X-EdgeAI-Pod-Token") != host.pod_token:
                    return self.reply(401, {})
                body = json.loads(data)
                host.requests.append(body)
                reply = {key: body[key] for key in ["runtimeId", "generation", "podUid", "sessionId", "sequence"]}
                reply.update(command="RUN", leaseSeconds=host.lease, assignments=[], cancelAttempts=[], acknowledgedAttempts=[])
                for completion in body["completed"]:
                    host.completed[completion["attemptId"]] = completion
                    reply["acknowledgedAttempts"].append(completion["attemptId"])
                for task in host.tasks:
                    if task.attempt in host.completed:
                        continue
                    if len(reply["assignments"]) == host.capacity:
                        break
                    reply["assignments"].append({"attemptId": task.attempt, "epoch": 1, "claimToken": task.token})
                    host.issued.add(task.attempt)
                if len(host.completed) == len(host.tasks):
                    reply["command"] = "STOP"
                status = 200
                if host.handler is not None:
                    try:
                        status = host.handler(host, body, reply)
                    except Exception as error:
                        host.faults.append(type(error).__name__)
                        return self.reply(500, {})
                return self.reply(status, reply)
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.origin = "http://127.0.0.1:"+str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.stack = contextlib.ExitStack()
        for task in self.tasks:
            self.stack.enter_context(task)
            task.pod, task.pod_token = self.pod, self.pod_token
        self.temp = tempfile.TemporaryDirectory(prefix="edgeai-vd-test-")
        self.path = Path(self.temp.name)
        self.work = self.path / "work"; self.work.mkdir(mode=0o777); self.work.chmod(0o777)
        (self.path / "token").write_text(self.token)
        (self.path / "pod-token").write_text(self.pod_token)
        self.thread.start()
        return self

    def __exit__(self, *_):
        if self.process is not None and self.process.poll() is None:
            self.terminate()
            try:
                self.process.communicate(timeout=8)
            except subprocess.TimeoutExpired:
                self.process.kill(); self.process.communicate()
        if self.image:
            subprocess.run(["docker", "rm", "-f", self.container], capture_output=True, timeout=15)
            # The production image owns private directories as UID 10001. The CI host user
            # must neither chmod those directories nor run the supervisor as a different UID.
            self.work_command("import pathlib,shutil;[(shutil.rmtree(p) if p.is_dir() and not p.is_symlink() else p.unlink()) for p in pathlib.Path('/work').iterdir()]")
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=2)
        self.stack.close()
        self.temp.cleanup()

    def work_command(self, script):
        command = ["docker", "run", "--rm", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
            "--mount", f"type=bind,source={self.work},target=/work", "--entrypoint", "python3", self.image, "-c", script]
        result = subprocess.run(command, capture_output=True, timeout=20)
        if result.returncode or result.stdout or result.stderr:
            raise AssertionError("Private fixture volume verification/cleanup failed")

    def assert_empty_attempts(self):
        if self.image:
            self.work_command("import pathlib;assert not list(pathlib.Path('/work/attempts').iterdir())")
        elif list((self.work / "attempts").iterdir()):
            raise AssertionError("Attempt work directories were not removed")

    def start(self):
        env = {**os.environ, "EDGEAI_VD_ID": self.vd, "EDGEAI_VD_RUNTIME_ID": self.runtime, "EDGEAI_VD_GENERATION": "2",
            "EDGEAI_VD_MAX_CONCURRENT_TASKS": str(self.capacity), "EDGEAI_VD_STARTUP_SECONDS": "5", "EDGEAI_VD_DRAIN_SECONDS": str(self.drain),
            "EDGEAI_POD_UID": self.pod, "EDGEAI_VD_CLAIM_FILE": str(self.path / "token"), "EDGEAI_POD_TOKEN_FILE": str(self.path / "pod-token"),
            "EDGEAI_WORK_DIR": str(self.work), "EDGEAI_CONTROL_PLANE_URL": self.origin}
        package = "/opt/edgeai" if self.image else str(ROOT / "runner")
        entrypoint = [package + "/vd.py"]
        if self.attempt_limit is not None:
            entrypoint = ["-c", "import sys;sys.path.insert(0,"+repr(package)+");from edgeai_runner import virtual_device as vd;vd.MAX_ATTEMPTS="+str(self.attempt_limit)+";sys.exit(vd.main())"]
        command = [sys.executable] + entrypoint
        if self.image:
            env.update(EDGEAI_VD_CLAIM_FILE="/var/run/vd/token", EDGEAI_POD_TOKEN_FILE="/var/run/vd/pod-token", EDGEAI_WORK_DIR="/work")
            command = ["docker", "run", "--rm", "--name", self.container, "--network=host", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                "--mount", f"type=bind,source={self.work},target=/work",
                "--mount", f"type=bind,source={self.path / 'token'},target=/var/run/vd/token,readonly",
                "--mount", f"type=bind,source={self.path / 'pod-token'},target=/var/run/vd/pod-token,readonly", "--entrypoint", "python3"]
            for key in env:
                if key.startswith("EDGEAI_") and key != "EDGEAI_RUNNER_IMAGE":
                    command.extend(["--env", key])
            command.extend([self.image] + entrypoint)
        self.process = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.launch_env = env
        return self.process

    def probe(self):
        command = [sys.executable, str(ROOT / "runner/vd.py"), "--ready"]
        if self.image:
            command = ["docker", "exec", self.container, "python3", "/opt/edgeai/vd.py", "--ready"]
        result = subprocess.run(command, env=self.launch_env, capture_output=True, timeout=5)
        if result.stdout or result.stderr:
            raise AssertionError("Readiness probe emitted diagnostics")
        return result.returncode

    def process_exists(self, pid):
        command = [sys.executable, "-c", "import pathlib,sys;sys.exit(0 if pathlib.Path('/proc/'+sys.argv[1]).exists() else 1)", str(pid)]
        if self.image:
            command = ["docker", "exec", self.container, "python3"] + command[1:]
        result = subprocess.run(command, capture_output=True, timeout=5)
        if result.returncode not in (0, 1) or result.stdout or result.stderr:
            raise AssertionError("Child process observation unavailable")
        return result.returncode == 0

    def terminate(self):
        if self.image:
            subprocess.run(["docker", "kill", "--signal=TERM", self.container], capture_output=True, timeout=10, check=True)
        else:
            self.process.send_signal(signal.SIGTERM)

    def finish(self):
        out, error = self.process.communicate(timeout=15)
        if error or any(value in out for value in [self.token, self.pod_token, self.origin]+[task.token for task in self.tasks]):
            raise AssertionError("Supervisor leaked private data or unstructured diagnostics")
        if self.faults:
            raise AssertionError("HTTP fixture failed: "+str(self.faults))
        return self.process.returncode, out

    def wait(self, predicate):
        deadline = time.monotonic()+8
        while not predicate():
            if time.monotonic() >= deadline:
                raise AssertionError("VD process condition was not observed")
            if self.process.poll() is not None:
                raise AssertionError("VD supervisor exited before the expected condition")
            time.sleep(.02)


class VirtualDeviceTest(unittest.TestCase):
    def inference(self, delay=0):
        return Fixture([sys.executable, str(ROOT / "runner/examples/linear.py")],
            {"features": [2, 3], "weights": [4, -1], "bias": -2, "simulationDelayMillis": delay}, timeout=10)

    def test_two_real_tasks_share_supervisor_identity_with_no_duplicate_execution(self):
        a, b = self.inference(700), self.inference(700)
        with VDHost([a, b]) as host:
            process = host.start(); pid = process.pid
            host.wait(lambda: len(host.completed) == 1)
            self.assertEqual(pid, process.pid); self.assertIsNone(process.poll())
            self.assertEqual(0, host.finish()[0])
            for task in [a, b]:
                self.assertEqual(1, task.claim_calls); self.assertEqual(1, len(task.commits))
                self.assertEqual(3, json.loads(task.outputs["output"])["score"])
                self.assertEqual(0, host.completed[task.attempt]["exitCode"])
            self.assertEqual(1, len({request["sessionId"] for request in host.requests}))
            self.assertFalse((host.work / ".vd-ready").exists())
            host.assert_empty_attempts()

    def test_drain_waits_for_actual_workload_and_completion_acknowledgement(self):
        task = self.inference(1200)
        def drain(host, body, reply):
            if task.claim_calls and task.attempt not in host.completed:
                reply.update(command="DRAIN", assignments=[])
            return 200
        with VDHost([task], handler=drain) as host:
            host.start(); self.assertEqual(0, host.finish()[0])
            self.assertEqual(1, len(task.commits)); self.assertIn(task.attempt, host.completed)
            self.assertTrue(any(request["state"] == "DRAINING" for request in host.requests))

    def test_cancel_one_concurrent_task_keeps_other_task_running(self):
        a, b = self.inference(6000), self.inference(1200)
        def cancel(host, body, reply):
            if a.claim_calls and b.claim_calls and a.attempt not in host.completed:
                reply["assignments"] = [item for item in reply["assignments"] if item["attemptId"] != a.attempt]
                reply["cancelAttempts"] = [a.attempt]
            return 200
        with VDHost([a, b], capacity=2, handler=cancel) as host:
            host.start(); self.assertEqual(0, host.finish()[0])
            self.assertFalse(a.commits); self.assertEqual(["CANCELLED"], a.failures)
            self.assertEqual(1, len(b.commits)); self.assertNotEqual(0, host.completed[a.attempt]["exitCode"])
            self.assertEqual(0, host.completed[b.attempt]["exitCode"])

    def test_poll_retry_keeps_sequence_and_bytes_and_does_not_repeat_work(self):
        task = self.inference(700)
        def transient(host, body, reply):
            return 503 if len(host.requests) in (2, 3) else 200
        with VDHost([task], handler=transient) as host:
            host.start(); self.assertEqual(0, host.finish()[0])
            self.assertEqual(host.requests[1], host.requests[2]); self.assertEqual(host.requests[2], host.requests[3])
            self.assertEqual(1, task.claim_calls); self.assertEqual(1, len(task.commits))

    def test_runtime_token_rotation_is_read_from_file_without_restart(self):
        task = self.inference(800)
        def rotate(host, body, reply):
            if len(host.requests) == 2:
                host.token = "rotated-test-"+uuid.uuid4().hex
                (host.path / "token").write_text(host.token)
            return 200
        with VDHost([task], handler=rotate) as host:
            host.start(); self.assertEqual(0, host.finish()[0]); self.assertEqual(1, len(task.commits))

    def test_lease_expiry_and_auth_rejection_stop_live_workload(self):
        for rejection in [503, 401]:
            task = self.inference(6000)
            def reject(host, body, reply):
                return rejection if task.claim_calls else 200
            with self.subTest(status=rejection), VDHost([task], lease=1, handler=reject) as host:
                host.start(); started = time.monotonic(); code, out = host.finish()
                self.assertNotEqual(0, code); self.assertLess(time.monotonic()-started, 6)
                self.assertIn("VD_LEASE_EXPIRED" if rejection == 503 else "VD_FENCED", out)
                self.assertFalse(task.commits); self.assertEqual(["CANCELLED"], task.failures)
                self.assertFalse((host.work / ".vd-ready").exists())

    def test_wrong_identity_duplicate_assignment_or_oversubscription_never_launches_work(self):
        for mutation in ["identity", "duplicate", "capacity", "ack"]:
            a, b = self.inference(), self.inference()
            def corrupt(host, body, reply):
                if mutation == "identity": reply["podUid"] = str(uuid.uuid4())
                elif mutation == "duplicate": reply["assignments"] *= 2
                elif mutation == "capacity": reply["assignments"].append({"attemptId": b.attempt, "epoch": 1, "claimToken": b.token})
                else: reply["acknowledgedAttempts"] = [str(uuid.uuid4())]
                return 200
            with self.subTest(mutation=mutation), VDHost([a, b], handler=corrupt) as host:
                host.start(); self.assertNotEqual(0, host.finish()[0])
                self.assertEqual(0, a.claim_calls); self.assertEqual(0, b.claim_calls)

    def test_drain_timeout_and_sigterm_cleanup_actual_children(self):
        task = self.inference(6000)
        def drain(host, body, reply):
            if task.claim_calls: reply.update(command="DRAIN", assignments=[])
            return 200
        with VDHost([task], drain=1, handler=drain) as host:
            host.start(); code, out = host.finish(); self.assertNotEqual(0, code); self.assertIn("VD_DRAIN_TIMEOUT", out)
            self.assertFalse(task.commits); self.assertEqual(["CANCELLED"], task.failures)
        task = self.inference(6000)
        with VDHost([task]) as host:
            host.start(); host.wait(lambda: task.claim_calls > 0); host.terminate()
            self.assertEqual(0, host.finish()[0]); self.assertFalse(task.commits)

    def test_process_restart_cannot_reuse_runtime_volume_and_forget_completed_attempts(self):
        task = self.inference()
        with VDHost([task]) as host:
            host.start(); self.assertEqual(0, host.finish()[0]); self.assertEqual(1, task.claim_calls)
            host.start(); code, out = host.finish()
            self.assertNotEqual(0, code); self.assertIn("VD_INVALID_CONFIGURATION", out); self.assertEqual(1, task.claim_calls)

    def test_probe_revokes_readiness_on_outage_and_drain_and_recovers_with_lease(self):
        mode = ["RUN"]
        task = self.inference(6000)
        def control(host, body, reply):
            if mode[0] == "OUTAGE": return 503
            if mode[0] == "DRAIN": reply.update(command="DRAIN", assignments=[])
            return 200
        with VDHost([task], handler=control) as host:
            host.start(); host.wait(lambda: (host.work / ".vd-ready").exists())
            self.assertEqual(0, host.probe())
            mode[0] = "OUTAGE"; host.wait(lambda: not (host.work / ".vd-ready").exists())
            self.assertEqual(1, host.probe())
            mode[0] = "RUN"; host.wait(lambda: (host.work / ".vd-ready").exists())
            self.assertEqual(0, host.probe())
            mode[0] = "DRAIN"; host.wait(lambda: not (host.work / ".vd-ready").exists())
            self.assertEqual(1, host.probe()); host.terminate(); self.assertEqual(0, host.finish()[0])

    def test_crashed_runner_orphan_is_killed_and_reaped_without_harming_other_task(self):
        orphan = "import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(60)"
        command = "\n".join([
            "import os,pathlib,signal,subprocess,sys,time",
            "child=subprocess.Popen([sys.executable,'-c',"+repr(orphan)+"],process_group=0)",
            "pathlib.Path.cwd().parents[1].joinpath('orphan-pid').write_text(str(child.pid))",
            "os.kill(os.getppid(),signal.SIGKILL)",
            "time.sleep(60)"])
        a, b = Fixture([sys.executable, "-c", command]), self.inference(4000)
        with VDHost([a, b], capacity=2) as host:
            host.start(); marker = host.work / "orphan-pid"
            host.wait(lambda: marker.exists() and a.attempt in host.completed and b.claim_calls > 0)
            pid = int(marker.read_text()); host.wait(lambda: not host.process_exists(pid))
            self.assertIsNone(host.process.poll()); self.assertFalse(a.commits)
            self.assertEqual(-signal.SIGKILL, host.completed[a.attempt]["exitCode"])
            self.assertEqual(0, host.finish()[0]); self.assertEqual(1, len(b.commits))

    def test_lease_cannot_expire_during_cancel_and_then_launch_a_new_task(self):
        command = "import pathlib,signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);pathlib.Path.cwd().parents[1].joinpath('stubborn').touch();time.sleep(60)"
        a, b = Fixture([sys.executable, "-c", command]), self.inference()
        def cancel(host, body, reply):
            if (host.work / "stubborn").exists():
                reply["assignments"] = [{"attemptId": b.attempt, "epoch": 1, "claimToken": b.token}]
                reply["cancelAttempts"] = [a.attempt]
            else:
                reply["assignments"] = [{"attemptId": a.attempt, "epoch": 1, "claimToken": a.token}]
            return 200
        with VDHost([a, b], capacity=2, lease=1, handler=cancel) as host:
            host.start(); code, out = host.finish()
            self.assertNotEqual(0, code); self.assertIn("VD_LEASE_EXPIRED", out)
            self.assertEqual(0, b.claim_calls); self.assertFalse(b.commits)

    def test_attempt_retention_limit_drains_after_last_accepted_work_without_new_assignment(self):
        a, b, c = [self.inference(600) for _ in range(3)]
        def honor_drain(host, body, reply):
            if body["state"] == "DRAINING": reply.update(command="DRAIN" if body["active"] else "STOP", assignments=[])
            return 200
        with VDHost([a, b, c], handler=honor_drain, attempt_limit=2) as host:
            host.start(); self.assertEqual(0, host.finish()[0])
            self.assertEqual(1, len(a.commits)); self.assertEqual(1, len(b.commits)); self.assertEqual(0, c.claim_calls)
            self.assertTrue(any(body["state"] == "DRAINING" and body["active"] for body in host.requests))

    def test_idle_drain_keeps_polling_until_server_confirms_stop(self):
        def drain(host, body, reply):
            reply.update(command="DRAIN" if body["sequence"] < 2 else "STOP", assignments=[])
            return 200
        with VDHost([], handler=drain) as host:
            host.start(); self.assertEqual(0, host.finish()[0])
            self.assertEqual([0, 1, 2], [body["sequence"] for body in host.requests])


if __name__ == "__main__":
    unittest.main()
