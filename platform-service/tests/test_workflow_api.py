import base64
import importlib.util
import json
import os
import subprocess
import tempfile
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient

module_path = Path(__file__).parents[1] / "argocdAPI/workflow_api.py"
spec = importlib.util.spec_from_file_location("workflow_api", module_path)
api = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = api
spec.loader.exec_module(api)

class Response:
    def __init__(self, status=200, data=None):
        self.status_code, self.data = status, data or {}
    def json(self): return self.data

class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.context = self.root / "source"
        self.context.mkdir()
        (self.context / "Dockerfile").write_text("FROM scratch")
        self.env = patch.dict(os.environ, {
            "EDGEAI_PLATFORM_SERVICE_TOKEN": "test-integration-secret",
            "PLATFORM_GITEA_URL": "http://git.test", "PLATFORM_GITEA_TOKEN": "git-secret",
            "PLATFORM_GITEA_OWNER": "owner", "PLATFORM_GITEA_REPO": "workflows", "PLATFORM_GITEA_BRANCH": "main",
            "PLATFORM_ARGOCD_URL": "http://argo.test", "PLATFORM_ARGOCD_TOKEN": "argo-secret",
            "PLATFORM_ARGOCD_NAMESPACE": "default", "PLATFORM_ARGOCD_PROJECT": "default",
            "PLATFORM_WORKSPACE": str(self.root / "workspace"), "PLATFORM_BUILD_CONTEXT": str(self.context),
            "PLATFORM_IMAGE_REPOSITORY": "registry.test/unified", "PLATFORM_BUILD_PLATFORMS": "linux/arm64,linux/amd64",
        }, clear=True)
        self.env.start()
        self.client = TestClient(api.app)
        self.headers = {"X-Platform-Token": "test-integration-secret"}
        self.manifest = "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: demo-sensor\nspec: {}\n"
        self.payload = {"content": self.manifest, "local_directory": "./workspace/demo", "filename": "deployment.yaml", "repo_path": "demo/deployment.yaml", "commit_message": "Deploy demo"}
        self.application = {"metadata": {"name": "demo"}, "spec": {"project": "default", "source": {"repoURL": "http://git.test/owner/workflows.git", "path": "demo", "targetRevision": "main"}, "destination": {"server": "https://kubernetes.default.svc", "namespace": "default"}}, "status": {"sync": {"status": "OutOfSync"}, "health": {"status": "Progressing"}}}

    def tearDown(self):
        self.client.close(); self.env.stop(); self.temp.cleanup()

    def test_token_and_sanitized_config(self):
        self.assertEqual(self.client.get("/config").status_code, 403)
        result = self.client.get("/config", headers=self.headers)
        self.assertTrue(result.json()["configured"])
        self.assertNotIn("secret", result.text)
        self.assertNotIn(str(self.root), result.text)

    def test_original_save_git_create_refresh_sequence(self):
        calls = []
        def request(method, url, **kwargs):
            calls.append((method, url, kwargs))
            if url == "http://argo.test/api/v1/applications/demo" and not kwargs.get("params"): return Response(404)
            if method == "GET" and "/contents/" in url: return Response(404)
            if method == "POST" and "/contents/" in url:
                self.assertEqual(base64.b64decode(kwargs["json"]["content"]).decode(), self.manifest)
                self.assertTrue((self.root / "workspace/demo/deployment.yaml").exists())
                return Response(201, {"commit": {"sha": "commit123"}})
            if method == "POST" and url.endswith("/applications"):
                self.assertEqual(kwargs["json"]["spec"]["source"]["path"], "demo")
                return Response(201)
            return Response(data=self.application)
        with patch.object(api.requests, "request", side_effect=request):
            response = self.client.post("/workflow/save-and-push", headers=self.headers, json=self.payload)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual([c[0] for c in calls], ["GET", "GET", "POST", "POST", "GET"])
        self.assertEqual(response.json()["git_result"]["commit"], "commit123")
        self.assertEqual(response.json()["argocd_result"]["health"], "Progressing")
        self.assertNotIn("Healthy", response.text)

    def test_update_uses_sha_and_does_not_recreate_app(self):
        def request(method, url, **kwargs):
            if "/applications" in url:
                self.assertEqual(method, "GET")
                return Response(data=self.application)
            if method == "GET": return Response(data={"sha": "old"})
            self.assertEqual(method, "PUT"); self.assertEqual(kwargs["json"]["sha"], "old")
            return Response(data={"commit": {"sha": "updated"}})
        with patch.object(api.requests, "request", side_effect=request):
            r = self.client.post("/workflow/save-and-push", headers=self.headers, json=self.payload)
        self.assertEqual(r.json()["argocd_result"]["action"], "existed")

    def test_argo_failure_after_git_is_partial_failure(self):
        results = [Response(404), Response(404), Response(201, {"commit": {"sha": "saved"}}), Response(403)]
        with patch.object(api.requests, "request", side_effect=results):
            r = self.client.post("/workflow/save-and-push", headers=self.headers, json=self.payload)
        self.assertEqual(r.json()["git_result"]["status"], "success")
        self.assertEqual(r.json()["argocd_result"]["status"], "failed")
        self.assertNotIn("secret", r.text)

    def test_git_failure_does_not_create_or_refresh_argo(self):
        with patch.object(api.requests, "request", side_effect=[Response(404), Response(500)]) as request:
            r = self.client.post("/workflow/save-and-push", headers=self.headers, json=self.payload)
        self.assertEqual(r.status_code, 502)
        self.assertEqual(request.call_count, 2)

    def test_existing_foreign_app_and_auth_failure_are_not_overwritten(self):
        foreign = {**self.application, "spec": {**self.application["spec"], "source": {"repoURL": "other", "path": "demo"}}}
        for external, expected in [(Response(data=foreign), 409), (Response(403), 502)]:
            with patch.object(api.requests, "request", return_value=external) as request:
                r = self.client.post("/workflow/save-and-push", headers=self.headers, json=self.payload)
            self.assertEqual(r.status_code, expected)
            self.assertEqual(request.call_count, 1)
        self.assertFalse((self.root / "workspace/demo/deployment.yaml").exists())

    def test_paths_and_manifests_rejected_before_external_writes(self):
        bad = [{**self.payload, "local_directory": "/tmp"}, {**self.payload, "repo_path": "../deployment.yaml"},
               {**self.payload, "content": "apiVersion: v1\nkind: Secret\nmetadata: {name: demo-secret}"},
               {**self.payload, "content": self.manifest.replace("demo-sensor", "other-sensor")},
               {**self.payload, "content": self.manifest + "---\n" + self.manifest}]
        with patch.object(api.requests, "request") as request:
            for value in bad:
                self.assertEqual(self.client.post("/workflow/save-and-push", headers=self.headers, json=value).status_code, 400)
            request.assert_not_called()

    def test_buildx_uses_fixed_context_push_and_digest(self):
        commands = []
        class Process:
            def __init__(self, command, **kwargs):
                commands.append(command)
                Path(command[command.index("--metadata-file")+1]).write_text(json.dumps({"containerimage.digest": "sha256:"+"a"*64}))
            def wait(self, timeout): return 0
        with patch.object(api.subprocess, "Popen", Process), patch.object(api.requests, "request") as network:
            r = self.client.post("/workflow/build-image", headers=self.headers, json={"tag": "test-1"})
            network.assert_not_called()
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(r.json()["image"], "registry.test/unified@sha256:"+"a"*64)
        self.assertIn("--push", commands[0]); self.assertEqual(commands[0][-1], str(self.context))
        self.assertEqual(self.client.post("/workflow/build-image", headers=self.headers, json={"tag": "x;rm"}).status_code, 422)

    def test_build_failure_and_lock(self):
        with patch.object(api.subprocess, "Popen") as process:
            process.return_value.wait.return_value = 1
            self.assertEqual(self.client.post("/workflow/build-image", headers=self.headers, json={"tag": "test"}).status_code, 502)
        with api.mutation():
            self.assertEqual(self.client.post("/workflow/build-image", headers=self.headers, json={"tag": "test"}).status_code, 409)

    def test_build_timeout_kills_group(self):
        with patch.object(api.subprocess, "Popen") as process, patch.object(api.os, "killpg") as kill:
            process.return_value.pid = 123
            process.return_value.wait.side_effect = [subprocess.TimeoutExpired("docker", 900), -9]
            r = self.client.post("/workflow/build-image", headers=self.headers, json={"tag": "test"})
            self.assertEqual(r.status_code, 504); kill.assert_called_once()

    def test_delete_retains_original_argo_git_local_order(self):
        path = self.root / "workspace/demo/deployment.yaml"; path.parent.mkdir(parents=True); path.write_text(self.manifest)
        calls=[]
        def request(method, url, **kwargs):
            calls.append((method, url))
            if method=="GET" and "/applications/" in url: return Response(data=self.application)
            if method=="GET": return Response(data={"sha":"old"})
            return Response()
        payload = {k:v for k,v in self.payload.items() if k!='content'}
        with patch.object(api.requests, "request", side_effect=request):
            r=self.client.request("DELETE", "/workflow/delete", headers=self.headers, json=payload)
        self.assertEqual(r.status_code,200,r.text)
        self.assertEqual(r.json()["argocd"]["status"], "deletion_requested")
        self.assertIn("/applications/", calls[1][1]); self.assertEqual(calls[1][0],"DELETE")
        self.assertFalse(path.exists())

if __name__ == "__main__": unittest.main()
