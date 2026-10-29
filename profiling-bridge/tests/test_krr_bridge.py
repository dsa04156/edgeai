import hashlib
import http.client
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import krr_bridge  # noqa: E402

REPORT = HERE.parents[1] / "backend/app/src/test/resources/resource-profiling/krr-v1.31.1-edgeai-report.json"


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.argv = os.path.join(self.tmp.name, "argv.json")
        os.environ.update(FAKE_KRR_ARGV=self.argv, FAKE_KRR_REPORT=str(REPORT), FAKE_KRR_MODE="ok")
        self.settings = krr_bridge.Settings({
            "KRR_PROMETHEUS_URL": "http://prometheus.example:9090/", "KRR_COMMAND": f"{sys.executable} {HERE / 'fake_krr.py'}",
            "KRR_ALLOWED_NAMESPACES": "edgeai,lab", "KRR_TIMEOUT_SECONDS": "10", "KRR_VERSION": "v1.31.1"})
        krr_bridge.Handler.settings = self.settings
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), krr_bridge.Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def post(self, body, content_type="application/json"):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=30)
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        connection.request("POST", "/v1/recommendations", data, {"Content-Type": content_type})
        response = connection.getresponse()
        payload = json.loads(response.read())
        connection.close()
        return response.status, payload

    request = {"namespace": "edgeai", "kind": "Deployment", "name": "edgeai-api", "container": "api", "historyHours": 24}

    def test_completed_run_returns_raw_report_with_provenance_and_fixed_arguments(self):
        status, body = self.post(self.request)
        self.assertEqual(200, status)
        self.assertEqual("COMPLETED", body["status"])
        self.assertEqual("v1.31.1", body["krrVersion"])
        self.assertEqual(hashlib.sha256(REPORT.read_bytes()).hexdigest(), body["reportSha256"])
        self.assertEqual("simple", body["report"]["strategy"]["name"])
        argv = json.loads(Path(self.argv).read_text())
        self.assertEqual(["simple", "--prometheus-url", "http://prometheus.example:9090", "--namespace", "edgeai", "--resource",
                          "Deployment", "--formatter", "json", "--fileoutput"], argv[:10])
        self.assertEqual(["--history_duration", "24", "--logtostderr", "--quiet"], argv[11:])
        self.assertNotIn("http://prometheus.example:9090", json.dumps(body["arguments"]))

    def test_requests_cannot_choose_urls_commands_or_unbounded_scope(self):
        invalid = [
            {**self.request, "namespace": "kube-system"},
            {**self.request, "kind": "Pod"},
            {**self.request, "name": "api; rm -rf /"},
            {**self.request, "name": "x\"} or vector(1)"},
            {**self.request, "container": "API"},
            {**self.request, "historyHours": 0},
            {**self.request, "historyHours": 337},
            {**self.request, "historyHours": True},
            {**self.request, "historyHours": 1.5},
            {**self.request, "prometheusUrl": "http://attacker"},
            {k: v for k, v in self.request.items() if k != "container"},
        ]
        for body in invalid:
            with self.subTest(body=body):
                self.assertEqual(400, self.post(body)[0])
        self.assertEqual(400, self.post(b"{not json")[0])
        self.assertEqual(415, self.post(self.request, "text/plain")[0])
        self.assertEqual(413, self.post(b"{" + b" " * 5000 + b"}")[0])
        self.assertFalse(Path(self.argv).exists(), "KRR must not run for rejected requests")

    def test_failures_are_reported_without_a_report(self):
        for mode, expected in (("fail", "FAILED"), ("noreport", "FAILED")):
            os.environ["FAKE_KRR_MODE"] = mode
            status, body = self.post(self.request)
            self.assertEqual(200, status)
            self.assertEqual(expected, body["status"])
            self.assertIsNone(body["report"])
        os.environ["FAKE_KRR_MODE"] = "fail"
        self.assertEqual(3, self.post(self.request)[1]["exitCode"])

    def test_timeout_kills_krr(self):
        krr_bridge.Handler.settings = krr_bridge.Settings({"KRR_PROMETHEUS_URL": "http://p:9090", "KRR_COMMAND": f"{sys.executable} {HERE / 'fake_krr.py'}",
                                                          "KRR_TIMEOUT_SECONDS": "10"})
        os.environ["FAKE_KRR_MODE"] = "sleep"
        status, body = self.post(self.request)
        self.assertEqual("TIMEOUT", body["status"])
        self.assertIsNone(body["report"])

    def test_configuration_is_validated(self):
        for env in ({"KRR_PROMETHEUS_URL": "http://user:pw@p:9090"}, {"KRR_PROMETHEUS_URL": "http://p:9090/api?x"},
                    {"KRR_PROMETHEUS_URL": "file:///etc/passwd"}, {"KRR_PROMETHEUS_URL": "http://p:9090", "KRR_ALLOWED_NAMESPACES": "Bad NS"},
                    {"KRR_PROMETHEUS_URL": "http://p:9090", "KRR_TIMEOUT_SECONDS": "5"}):
            with self.subTest(env=env):
                with self.assertRaises(ValueError):
                    krr_bridge.Settings(env)


if __name__ == "__main__":
    unittest.main()
