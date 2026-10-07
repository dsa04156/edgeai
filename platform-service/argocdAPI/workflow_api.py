"""Platform-Service: YAML -> local workspace -> Gitea -> Argo CD.
The original endpoint vocabulary and service boundaries are retained; Buildx is an explicit preceding step.
"""
from __future__ import annotations
import base64
import hmac
import json
import os
import re
import signal
import subprocess
import threading
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import quote
from uuid import uuid4

import requests
import yaml
from fastapi import Depends, FastAPI, HTTPException
from fastapi.security import APIKeyHeader
from pydantic import BaseModel, Field

NAME = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
DIGEST = re.compile(r"sha256:[a-f0-9]{64}")
LOCK = threading.Lock()


def setting(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


def required(name: str) -> str:
    value = setting(name)
    if not value:
        raise HTTPException(503, f"Missing server setting: {name}")
    return value


def workspace() -> Path:
    return Path(required("PLATFORM_WORKSPACE")).resolve()


token_header = APIKeyHeader(name="X-Platform-Token", auto_error=False)


def verify_token(x_platform_token: str | None = Depends(token_header)):
    if not hmac.compare_digest(x_platform_token or "", required("EDGEAI_PLATFORM_SERVICE_TOKEN")):
        raise HTTPException(403, "Invalid integration token")


@contextmanager
def mutation():
    if not LOCK.acquire(blocking=False):
        raise HTTPException(409, "Another build or deployment is in progress")
    try:
        yield
    finally:
        LOCK.release()


class WorkflowRequest(BaseModel):
    content: str = Field(min_length=1, max_length=1_000_000)
    local_directory: str = Field(max_length=160)
    filename: str = "deployment.yaml"
    overwrite_local: bool = True
    repo_path: str = Field(max_length=160)
    commit_message: str = Field(min_length=1, max_length=200)


class WorkflowDeleteRequest(BaseModel):
    local_directory: str = Field(max_length=160)
    filename: str = "deployment.yaml"
    repo_path: str = Field(max_length=160)
    commit_message: str = Field(min_length=1, max_length=200)


class BuildRequest(BaseModel):
    tag: str = Field(pattern=r"^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,99}$")


class IntegrationConfig(BaseModel):
    configured: bool
    missing: list[str]
    imageRepository: str
    platforms: list[str]
    buildConfigured: bool
    repository: str
    branch: str


class BuildResponse(BaseModel):
    status: str
    image: str
    tag: str
    platforms: list[str]


class StepResult(BaseModel):
    status: str
    action: str | None = None
    path: str | None = None
    commit: str | None = None
    name: str | None = None
    sync: str | None = None
    health: str | None = None
    revision: str | None = None
    operation: str | None = None
    deleting: bool | None = None
    error: str | None = None


class DeploymentResponse(BaseModel):
    local_result: StepResult
    git_result: StepResult
    argocd_result: StepResult


class DeleteResponse(BaseModel):
    argocd: StepResult
    git: StepResult
    local: StepResult


def target(req: WorkflowRequest | WorkflowDeleteRequest) -> tuple[str, Path]:
    pieces = req.repo_path.split("/")
    if len(pieces) != 2 or not NAME.fullmatch(pieces[0]) or pieces[1] != "deployment.yaml" or req.filename != "deployment.yaml":
        raise HTTPException(400, "Expected <app-name>/deployment.yaml")
    name = pieces[0]
    if req.local_directory not in (f"./workspace/{name}", f"workspace/{name}"):
        raise HTTPException(400, "Local directory must match the application")
    root = workspace()
    result = (root / name / req.filename).resolve()
    if not result.is_relative_to(root) or result.parent.name != name:
        raise HTTPException(400, "Invalid workspace path")
    return name, result


def validate_yaml(content: str, app_name: str):
    try:
        docs = list(yaml.safe_load_all(content))
        if not docs or len(docs) > 128:
            raise ValueError()
        identities = set()
        for doc in docs:
            if not isinstance(doc, dict) or (doc.get("apiVersion"), doc.get("kind")) not in [("apps/v1", "Deployment"), ("v1", "Service")]:
                raise ValueError()
            meta = doc.get("metadata", {})
            name = meta.get("name", "")
            if not NAME.fullmatch(name) or not name.startswith(app_name + "-"):
                raise ValueError()
            namespace = setting("PLATFORM_ARGOCD_NAMESPACE", "default")
            if meta.get("namespace", namespace) != namespace:
                raise ValueError()
            identity = (doc["kind"], name)
            if identity in identities:
                raise ValueError()
            identities.add(identity)
        return docs
    except (yaml.YAMLError, ValueError, TypeError, AttributeError, RecursionError):
        raise HTTPException(400, "Invalid workflow YAML, duplicate names or application prefix") from None


class LocalFileManager:
    @staticmethod
    def save_yaml(path: Path, content: str, overwrite: bool):
        if path.exists() and not overwrite:
            raise HTTPException(409, "Local manifest already exists")
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(content)
        temporary.chmod(0o600)
        temporary.replace(path)

    @staticmethod
    def delete_file_safely(path: Path):
        path.unlink(missing_ok=True)
        try:
            path.parent.rmdir()
        except OSError:
            pass


class GiteaService:
    def __init__(self):
        self.base_url = required("PLATFORM_GITEA_URL").rstrip("/").removesuffix("/api/v1") + "/api/v1"
        self.owner = required("PLATFORM_GITEA_OWNER")
        self.repo = required("PLATFORM_GITEA_REPO")
        self.branch = setting("PLATFORM_GITEA_BRANCH", "main")
        self.token = required("PLATFORM_GITEA_TOKEN")
        self.repo_url = self.base_url.removesuffix("/api/v1") + f"/{quote(self.owner, safe='')}/{quote(self.repo, safe='')}.git"

    def request(self, method, path, **kwargs):
        try:
            response = requests.request(method, self.base_url + path,
                headers={"Authorization": f"token {self.token}"}, timeout=(5, 30),
                verify=setting("PLATFORM_CA_FILE") or True, allow_redirects=False, **kwargs)
        except requests.RequestException:
            raise HTTPException(502, "Gitea connection failed") from None
        if response.status_code not in (200, 201, 204, 404):
            raise HTTPException(502, f"Gitea request failed (HTTP {response.status_code})")
        return response

    def file_url(self, path):
        return f"/repos/{quote(self.owner, safe='')}/{quote(self.repo, safe='')}/contents/{quote(path, safe='/')}"

    def push_file_default(self, path, content, message):
        endpoint = self.file_url(path)
        found = self.request("GET", endpoint, params={"ref": self.branch})
        payload = {"content": base64.b64encode(content.encode()).decode(), "message": message, "branch": self.branch}
        if found.status_code == 200:
            payload["sha"] = found.json()["sha"]
        response = self.request("PUT" if "sha" in payload else "POST", endpoint, json=payload)
        if response.status_code not in (200, 201):
            raise HTTPException(502, "Gitea did not save the manifest")
        return {"status": "success", "action": "updated" if "sha" in payload else "created", "path": path,
                "commit": response.json().get("commit", {}).get("sha")}

    def delete_file_default(self, path, message):
        endpoint = self.file_url(path)
        found = self.request("GET", endpoint, params={"ref": self.branch})
        if found.status_code == 404:
            return {"status": "absent"}
        response = self.request("DELETE", endpoint, json={"sha": found.json()["sha"], "branch": self.branch, "message": message})
        if response.status_code not in (200, 204):
            raise HTTPException(502, "Gitea deletion failed")
        return {"status": "deleted"}


class ArgoCDService:
    def __init__(self):
        self.base_url = required("PLATFORM_ARGOCD_URL").rstrip("/").removesuffix("/api/v1") + "/api/v1"
        self.token = setting("PLATFORM_ARGOCD_TOKEN")

    def login(self):
        try:
            response = requests.post(self.base_url + "/session", json={"username": required("PLATFORM_ARGOCD_USER"), "password": required("PLATFORM_ARGOCD_PASSWORD")},
                                     timeout=(5, 15), verify=setting("PLATFORM_CA_FILE") or True, allow_redirects=False)
            response.raise_for_status()
            self.token = response.json()["token"]
        except (requests.RequestException, KeyError, ValueError):
            raise HTTPException(502, "Argo CD authentication failed") from None

    def request(self, method, path, **kwargs):
        if not self.token:
            self.login()
        for attempt in range(2):
            try:
                response = requests.request(method, self.base_url + path, headers={"Authorization": f"Bearer {self.token}"},
                                            timeout=(5, 30), verify=setting("PLATFORM_CA_FILE") or True, allow_redirects=False, **kwargs)
            except requests.RequestException:
                raise HTTPException(502, "Argo CD connection failed") from None
            if response.status_code == 401 and not setting("PLATFORM_ARGOCD_TOKEN") and attempt == 0:
                self.login()
                continue
            if response.status_code not in (200, 201, 204, 404):
                raise HTTPException(502, f"Argo CD request failed (HTTP {response.status_code})")
            return response
        raise HTTPException(502, "Argo CD authentication failed")

    def checked_app(self, name, repo_url):
        response = self.request("GET", f"/applications/{name}")
        if response.status_code == 404:
            return None
        app = response.json()
        spec = app.get("spec", {})
        source, destination = spec.get("source", {}), spec.get("destination", {})
        if (source.get("repoURL", "").removesuffix(".git") != repo_url.removesuffix(".git") or source.get("path") != name
            or source.get("targetRevision") not in (setting("PLATFORM_GITEA_BRANCH", "main"), "HEAD")
            or destination.get("namespace") != setting("PLATFORM_ARGOCD_NAMESPACE", "default")
            or destination.get("server") != "https://kubernetes.default.svc"
            or spec.get("project") != setting("PLATFORM_ARGOCD_PROJECT", "default")):
            raise HTTPException(409, "Argo CD application belongs to a different deployment target")
        return app

    def create_app_simple(self, name, repo_url):
        manifest = {"metadata": {"name": name}, "spec": {
            "project": setting("PLATFORM_ARGOCD_PROJECT", "default"),
            "source": {"repoURL": repo_url, "path": name, "targetRevision": setting("PLATFORM_GITEA_BRANCH", "main")},
            "destination": {"server": "https://kubernetes.default.svc", "namespace": setting("PLATFORM_ARGOCD_NAMESPACE", "default")},
            "syncPolicy": {"automated": {"prune": True, "selfHeal": True}}}}
        response = self.request("POST", "/applications", json=manifest)
        if response.status_code not in (200, 201):
            raise HTTPException(502, "Argo CD application creation failed")

    def refresh_app(self, name):
        response = self.request("GET", f"/applications/{name}", params={"refresh": "hard"})
        if response.status_code == 404:
            raise HTTPException(502, "Argo CD application missing after deployment")
        return public_status(response.json())

    def delete_app(self, name):
        response = self.request("DELETE", f"/applications/{name}", params={"cascade": "true"})
        if response.status_code not in (200, 204, 404):
            raise HTTPException(502, "Argo CD deletion failed")
        return {"status": "absent" if response.status_code == 404 else "deletion_requested"}


def public_status(app):
    status = app.get("status", {})
    return {"name": app.get("metadata", {}).get("name"), "status": "observed",
            "sync": status.get("sync", {}).get("status", "Unknown"), "health": status.get("health", {}).get("status", "Unknown"),
            "revision": status.get("sync", {}).get("revision"), "operation": status.get("operationState", {}).get("phase"),
            "deleting": bool(app.get("metadata", {}).get("deletionTimestamp"))}


class BuildxService:
    def build(self, tag):
        context = Path(required("PLATFORM_BUILD_CONTEXT")).resolve()
        if not (context / "Dockerfile").is_file():
            raise HTTPException(503, "Configured build context has no Dockerfile")
        repository = required("PLATFORM_IMAGE_REPOSITORY")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9./:_-]+", repository) or "@" in repository:
            raise HTTPException(503, "Invalid configured image repository")
        platforms = setting("PLATFORM_BUILD_PLATFORMS", "linux/arm64").split(",")
        if not platforms or any(p not in ("linux/amd64", "linux/arm64") for p in platforms):
            raise HTTPException(503, "Invalid configured build platforms")
        directory = workspace() / "builds" / str(uuid4())
        directory.mkdir(parents=True, mode=0o700)
        metadata = directory / "metadata.json"
        command = ["docker", "buildx", "build", "--platform", ",".join(platforms), "--tag", f"{repository}:{tag}",
                   "--file", str(context / "Dockerfile"), "--metadata-file", str(metadata), "--push"]
        if setting("PLATFORM_BUILDX_BUILDER"):
            command += ["--builder", setting("PLATFORM_BUILDX_BUILDER")]
        command.append(str(context))
        try:
            with (directory / "build.log").open("wb") as log:
                os.chmod(directory / "build.log", 0o600)
                process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                try:
                    code = process.wait(timeout=900)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                    raise HTTPException(504, "Buildx timed out; deployment was not started") from None
            if code != 0:
                raise HTTPException(502, "Buildx failed; check the server build log. Deployment was not started")
            digest = json.loads(metadata.read_text()).get("containerimage.digest", "")
            if not DIGEST.fullmatch(digest):
                raise HTTPException(502, "Buildx did not return an image digest")
        except (OSError, ValueError):
            raise HTTPException(502, "Buildx or its metadata is unavailable; deployment was not started") from None
        return {"status": "success", "image": f"{repository}@{digest}", "tag": f"{repository}:{tag}", "platforms": platforms}


app = FastAPI(title="Platform-Service workflow integration", version="1.0.0", dependencies=[Depends(verify_token)])


@app.get("/config", response_model=IntegrationConfig)
def config():
    missing = [k for k in ["PLATFORM_GITEA_URL", "PLATFORM_GITEA_TOKEN", "PLATFORM_GITEA_OWNER", "PLATFORM_GITEA_REPO", "PLATFORM_ARGOCD_URL", "PLATFORM_WORKSPACE"] if not setting(k)]
    if not setting("PLATFORM_ARGOCD_TOKEN") and not (setting("PLATFORM_ARGOCD_USER") and setting("PLATFORM_ARGOCD_PASSWORD")):
        missing.append("PLATFORM_ARGOCD_TOKEN or credentials")
    return {"configured": not missing, "missing": missing, "imageRepository": setting("PLATFORM_IMAGE_REPOSITORY"),
            "platforms": setting("PLATFORM_BUILD_PLATFORMS", "linux/arm64").split(","),
            "buildConfigured": bool(setting("PLATFORM_BUILD_CONTEXT") and setting("PLATFORM_IMAGE_REPOSITORY")),
            "repository": f"{setting('PLATFORM_GITEA_OWNER')}/{setting('PLATFORM_GITEA_REPO')}", "branch": setting("PLATFORM_GITEA_BRANCH", "main")}


@app.post("/workflow/build-image", response_model=BuildResponse)
def build_image(req: BuildRequest):
    with mutation():
        return BuildxService().build(req.tag)


@app.post("/workflow/save-and-push", response_model=DeploymentResponse, response_model_exclude_none=True)
def workflow_save_push(req: WorkflowRequest):
    name, path = target(req)
    validate_yaml(req.content, name)
    with mutation():
        gitea, argo = GiteaService(), ArgoCDService()
        existing = argo.checked_app(name, gitea.repo_url)
        if existing and existing.get("metadata", {}).get("deletionTimestamp"):
            raise HTTPException(409, "Application deletion is still in progress")
        LocalFileManager.save_yaml(path, req.content, req.overwrite_local)
        pushed = gitea.push_file_default(req.repo_path, req.content, req.commit_message)
        try:
            if existing is None:
                argo.create_app_simple(name, gitea.repo_url)
            observed = argo.refresh_app(name)
            observed["action"] = "created" if existing is None else "existed"
        except HTTPException as exc:
            # A committed manifest can already be seen by Argo; never claim rollback.
            observed = {"status": "failed", "error": str(exc.detail)}
        return {"local_result": {"status": "success"}, "git_result": pushed, "argocd_result": observed}


@app.get("/workflow/status/{name}", response_model=StepResult, response_model_exclude_none=True)
def workflow_status(name: str):
    if not NAME.fullmatch(name):
        raise HTTPException(400, "Invalid application name")
    gitea, argo = GiteaService(), ArgoCDService()
    value = argo.checked_app(name, gitea.repo_url)
    return public_status(value) if value else {"name": name, "status": "absent"}


@app.delete("/workflow/delete", response_model=DeleteResponse, response_model_exclude_none=True)
def workflow_delete(req: WorkflowDeleteRequest):
    name, path = target(req)
    with mutation():
        gitea, argo = GiteaService(), ArgoCDService()
        existing = argo.checked_app(name, gitea.repo_url)
        argo_result = argo.delete_app(name) if existing else {"status": "absent"}
        git_result = gitea.delete_file_default(req.repo_path, req.commit_message)
        LocalFileManager.delete_file_safely(path)
        return {"argocd": argo_result, "git": git_result, "local": {"status": "deleted"}}
