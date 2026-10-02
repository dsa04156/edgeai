"""Real child processes and HTTP transfers against an isolated protocol fixture, not Kubernetes."""
import hashlib
import http.server
import json
import os
from pathlib import Path
import signal
import selectors
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]


class Fixture:
    def __init__(self, command, parameters=None, inputs=None, timeout=10, retry_commit=False, fenced=False, pending_claims=0, telemetry_status=None, telemetry_error=None):
        self.image = os.environ.get("EDGEAI_RUNNER_IMAGE")
        if self.image:
            command = ["python3" if value == sys.executable else value.replace(str(ROOT / "runner/examples"),"/opt/edgeai/examples") for value in command]
        self.attempt, self.task, self.run, self.pod = [str(uuid.uuid4()) for _ in range(4)]
        self.token = "test-only-" + uuid.uuid4().hex
        self.pod_token = "pod-test-only-" + uuid.uuid4().hex
        self.outputs, self.manifests, self.commits, self.failures = {}, {}, [], []
        self.commit_calls = 0
        self.claim_calls = 0
        self.telemetry = []
        self.telemetry_status = telemetry_status
        self.assignment = {"runId": self.run, "taskId": self.task, "attemptId": self.attempt, "epoch": 1,
            "command": command, "args": [], "parameters": parameters or {}, "inputs": inputs or {},
            "outputs": {"output": {"mediaType": "application/json", "maxBytes": 1048576}}, "timeoutSeconds": timeout}
        if telemetry_status is not None:
            self.assignment['telemetry'] = {'intervalSeconds': 1}
        fixture = self
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def reply(self, status, value):
                # Literal high-precision input exercises transport without a Python float roundtrip.
                body = json.dumps(value).replace('"PRECISE_NUMBER"', '0.12345678901234567890123456789').encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            def do_POST(self):
                if self.headers.get("Authorization") != "Bearer " + fixture.token or self.headers.get("X-EdgeAI-Pod-Token") != fixture.pod_token:
                    return self.reply(401,{})
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if body.get("epoch") != 1 or body.get("podUid") != fixture.pod:
                    return self.reply(409,{})
                operation = self.path.rsplit("/",1)[1]
                if operation == "claim":
                    fixture.claim_calls += 1
                    if fixture.claim_calls <= pending_claims:
                        return self.reply(503,{})
                    return self.reply(409 if fenced else 200, fixture.assignment)
                if operation == "uploads":
                    grants = []
                    for output in body["outputs"]:
                        fixture.manifests[output["port"]] = output
                        grants.append({"port": output["port"], "url": fixture.origin + "/objects/" + output["port"],
                            "headers": {"Content-Type": output["mediaType"]}, "expiresAt": "2099-01-01T00:00:00Z"})
                    return self.reply(200,{"outputs":grants})
                if operation == "commit":
                    fixture.commit_calls += 1
                    if body not in fixture.commits:
                        fixture.commits.append(body)
                    if retry_commit and fixture.commit_calls == 1:
                        return self.reply(503,{})
                    return self.reply(201 if fixture.commit_calls == 1 else 200,{"resultId":str(uuid.uuid4()),"taskId":fixture.task,"attemptId":fixture.attempt,"state":"SUCCEEDED"})
                if operation == "fail":
                    fixture.failures.append(body["reason"])
                    return self.reply(200,{"attemptId":fixture.attempt,"state":"FAILED"})
                if operation == 'telemetry':
                    fixture.telemetry.append(body)
                    if telemetry_error:
                        return self.reply(telemetry_status, {'code': telemetry_error})
                    return self.reply(telemetry_status, {'attemptId': fixture.attempt, 'sequence': body['sequence'], 'receivedAt': body['observedAt']})
                return self.reply(404,{})
            def do_PUT(self):
                if self.headers.get("Authorization"):
                    return self.reply(403,{})
                name = self.path.rsplit("/",1)[1]
                data = self.rfile.read(int(self.headers["Content-Length"]))
                fixture.outputs[name] = data
                self.send_response(200)
                self.send_header("x-amz-version-id",str(uuid.uuid4()))
                self.send_header("Content-Length","0")
                self.end_headers()
            def do_GET(self):
                data = b'{"features":[2,3]}'
                self.send_response(200)
                self.send_header("Content-Length",str(len(data)))
                self.end_headers()
                self.wfile.write(data)
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1",0),Handler)
        self.origin = "http://127.0.0.1:" + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever,daemon=True)

    def __enter__(self):
        self.thread.start()
        self.temp = tempfile.TemporaryDirectory(prefix="edgeai-runner-test-")
        self.path = Path(self.temp.name)
        (self.path / "token").write_text(self.token)
        (self.path / "pod-token").write_text(self.pod_token)
        (self.path / "work").mkdir()
        return self

    def __exit__(self,*_):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def start(self):
        env = {**os.environ, "EDGEAI_ATTEMPT_ID":self.attempt,"EDGEAI_POD_UID":self.pod,"EDGEAI_ATTEMPT_EPOCH":"1",
            "EDGEAI_CONTROL_PLANE_URL":self.origin,"EDGEAI_CLAIM_FILE":str(self.path / "token"),"EDGEAI_POD_TOKEN_FILE":str(self.path / "pod-token"),"EDGEAI_WORK_DIR":str(self.path / "work")}
        command = [sys.executable,str(ROOT / "runner" / "runner.py")]
        if self.image:
            # Same protocol scenarios run in the exact image on Linux CI. No cluster privileges.
            command = ["docker","run","--rm","--network=host","--read-only","--cap-drop=ALL",
                "--security-opt=no-new-privileges","--tmpfs","/work:rw,uid=10001,gid=10001,size=64m",
                "--mount",f"type=bind,source={self.path / 'token'},target=/var/run/edgeai/token,readonly",
                "--mount",f"type=bind,source={self.path / 'pod-token'},target=/var/run/edgeai-identity/token,readonly"]
            env["EDGEAI_CLAIM_FILE"] = "/var/run/edgeai/token"
            env["EDGEAI_POD_TOKEN_FILE"] = "/var/run/edgeai-identity/token"
            env["EDGEAI_WORK_DIR"] = "/work"
            for key in ("EDGEAI_ATTEMPT_ID","EDGEAI_POD_UID","EDGEAI_ATTEMPT_EPOCH","EDGEAI_CONTROL_PLANE_URL","EDGEAI_CLAIM_FILE","EDGEAI_POD_TOKEN_FILE","EDGEAI_WORK_DIR"):
                command.extend(["--env",key])
            if self.telemetry_status is not None:
                command.extend(['--cpus=0.5', '--memory=128m'])
            command.append(self.image)
        return subprocess.Popen(command,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)

    def finish(self, process):
        try:
            out, err = process.communicate(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate()
            raise AssertionError("Runner exceeded test deadline") from None
        if self.token in out + err or self.pod_token in out + err or self.origin in out + err:
            raise AssertionError("Runner leaked credentials or transfer URL")
        if err:
            raise AssertionError("Runner emitted unstructured stderr")
        return process.returncode, out


class RunnerTest(unittest.TestCase):
    def python(self, body):
        return [sys.executable,"-c",body]

    def test_real_reference_inference_upload_and_idempotent_commit_retry(self):
        with Fixture([sys.executable,str(ROOT / "runner/examples/linear.py")],
                {"features":[2,3],"weights":[4,-1],"bias":-2},retry_commit=True) as f:
            code,out = f.finish(f.start())
            self.assertEqual(0,code)
            self.assertIn("RUNNER_RESULT_COMMITTED",out)
            self.assertEqual(3,json.loads(f.outputs["output"])["score"])
            self.assertEqual(hashlib.sha256(f.outputs["output"]).hexdigest(),f.manifests["output"]["sha256"])
            self.assertEqual(2,f.commit_calls)
            self.assertEqual(1,len(f.commits))
            self.assertFalse(f.failures)

    def test_parameters_preserve_big_integer_and_decimal_bytes(self):
        command = self.python("import os,pathlib; pathlib.Path(os.environ['EDGEAI_OUTPUT_DIR'],'output').write_bytes(pathlib.Path(os.environ['EDGEAI_PARAMETERS_FILE']).read_bytes())")
        with Fixture(command,{"serial":9007199254740993,"decimal":"PRECISE_NUMBER"}) as f:
            self.assertEqual(0,f.finish(f.start())[0])
            self.assertIn(b"9007199254740993",f.outputs["output"])
            self.assertIn(b"0.12345678901234567890123456789",f.outputs["output"])

    def test_startup_status_lag_retries_claim_without_restarting_workload(self):
        with Fixture([sys.executable,str(ROOT / "runner/examples/linear.py")],
                {"features":[2,3],"weights":[4,-1],"bias":-2},pending_claims=4) as f:
            code,out = f.finish(f.start())
            self.assertEqual(0,code)
            self.assertEqual(5,f.claim_calls)
            self.assertEqual(1,out.count("RUNNER_WORKLOAD_START"))
            self.assertEqual(1,len(f.commits))

    def test_missing_output_and_symlink_cannot_be_committed(self):
        for body in ["pass", "import os; os.symlink('/etc/hostname',os.path.join(os.environ['EDGEAI_OUTPUT_DIR'],'output'))"]:
            with self.subTest(body=body), Fixture(self.python(body)) as f:
                self.assertNotEqual(0,f.finish(f.start())[0])
                self.assertEqual(["OUTPUT_INVALID"],f.failures)
                self.assertFalse(f.commits)

    def test_input_hash_is_verified_before_workload(self):
        with Fixture([sys.executable,str(ROOT / "runner/examples/linear.py")],{"weights":[4,-1],"bias":-2}) as f:
            f.assignment["inputs"] = {"input":{"url":f.origin+"/input","bytes":18,"sha256":"0"*64,"mediaType":"application/json"}}
            code,out = f.finish(f.start())
            self.assertNotEqual(0,code)
            self.assertNotIn("RUNNER_WORKLOAD_START",out)
            self.assertEqual(["INPUT_INVALID"],f.failures)
            self.assertFalse(f.commits)

    def test_timeout_stops_the_actual_workload_process(self):
        with Fixture(self.python("import time; time.sleep(10)"),timeout=1) as f:
            started = time.monotonic()
            self.assertNotEqual(0,f.finish(f.start())[0])
            self.assertLess(time.monotonic()-started,5)
            self.assertEqual(["TIMEOUT"],f.failures)
            self.assertFalse(f.commits)

    def test_sigterm_cancels_the_actual_workload(self):
        with Fixture(self.python("import time; time.sleep(10)")) as f:
            process = f.start()
            # A fixed Runner phase confirms the child execution has begun.
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout,selectors.EVENT_READ)
                if not selector.select(timeout=8):
                    process.kill(); process.communicate()
                    self.fail("Runner did not start within the test deadline")
                line = process.stdout.readline()
            self.assertEqual("RUNNER_WORKLOAD_START\n",line)
            process.send_signal(signal.SIGTERM)
            self.assertNotEqual(0,f.finish(process)[0])
            self.assertEqual(["CANCELLED"],f.failures)
            self.assertFalse(f.commits)

    def test_fenced_claim_never_executes_or_reports_failure(self):
        with Fixture(self.python("raise RuntimeError('must not execute')"),fenced=True) as f:
            code,out = f.finish(f.start())
            self.assertNotEqual(0,code)
            self.assertIn("FENCED",out)
            self.assertNotIn("RUNNER_WORKLOAD_START",out)
            self.assertFalse(f.failures)
            self.assertFalse(f.commits)

    def test_actual_workload_latency_and_optional_resource_measurements(self):
        body = """import datetime,json,os,pathlib,time
path=pathlib.Path(os.environ['EDGEAI_TELEMETRY_FILE'])
for sequence in range(1,14):
    begin=time.perf_counter_ns();time.sleep(.25);latency=(time.perf_counter_ns()-begin)//1000
    temporary=path.with_suffix('.tmp')
    temporary.write_text(json.dumps({'sequence':sequence,'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'latencyMicros':latency}))
    temporary.replace(path)
pathlib.Path(os.environ['EDGEAI_OUTPUT_DIR'],'output').write_text('{}')
"""
        for status, error in [(200, None), (503, None), (409, None), (409, 'TELEMETRY_EXPIRED')]:
            with self.subTest(status=status, error=error), Fixture(self.python(body), telemetry_status=status, telemetry_error=error) as f:
                code, out = f.finish(f.start())
                self.assertTrue(f.telemetry)
                self.assertGreater(f.telemetry[0]['latencyMicros'], 0)
                if f.image:
                    self.assertIsNotNone(f.telemetry[0]['cpuUsageMicros'])
                    self.assertEqual(500, f.telemetry[0]['cpuLimitMillicores'])
                    self.assertGreater(f.telemetry[0]['memoryBytes'], 0)
                    self.assertEqual(128 * 1024 * 1024, f.telemetry[0]['memoryLimitBytes'])
                if status == 409 and error is None:
                    self.assertNotEqual(0, code); self.assertIn('FENCED', out)
                    self.assertFalse(f.commits); self.assertFalse(f.failures)
                else:
                    self.assertEqual(0, code); self.assertEqual(1, len(f.commits))
                    self.assertGreaterEqual(len(f.telemetry), 2)
                    self.assertEqual(sorted({s['sequence'] for s in f.telemetry}), [s['sequence'] for s in f.telemetry])


if __name__ == "__main__":
    unittest.main()
