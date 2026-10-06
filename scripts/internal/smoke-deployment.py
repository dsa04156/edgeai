"""Exercise the deployed HTTP path without printing credentials or session tokens."""
import argparse
import base64
import http.cookiejar
import json
import os
import re
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from datetime import datetime, timezone

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--through", choices=["device", "workflow"], default="workflow", help="Last milestone exercised; workflow is the complete current smoke")
args = parser.parse_args()

ui = os.environ.get("EDGEAI_SMOKE_UI_URL", "http://127.0.0.1:13080").rstrip("/")
api = os.environ.get("EDGEAI_SMOKE_API_URL", "http://127.0.0.1:18080").rstrip("/")
auth = "Basic " + base64.b64encode(
    (os.environ["EDGEAI_API_USER"] + ":" + os.environ["EDGEAI_API_PASSWORD"]).encode()
).decode()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def request(url, method="GET", data=None, authenticated=False, csrf=None, idempotency=None):
    headers = {}
    if authenticated:
        headers["Authorization"] = auth
    if csrf:
        headers["X-CSRF-TOKEN"] = csrf
    if idempotency:
        headers["Idempotency-Key"] = idempotency
    if data is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(data).encode()
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        response = client.open(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read()

for attempt in range(90):
    try:
        status, body = request(ui + "/api/health")
        if status == 200 and json.loads(body)["status"] == "UP":
            break
    except (OSError, ValueError):
        pass
    time.sleep(2)
else:
    raise SystemExit("FAIL: deployment did not become ready in 180 seconds")

assert request(ui + "/api/live")[0] == 200
status, html = request(ui + "/")
assert status == 200
assets = re.findall(r'href="([^" ]+\.css(?:\?[^" ]*)?)"', html.decode())
assert assets, "Dashboard must serve its built CSS"
assert request(ui + assets[0])[0] == 200
assert request(api + "/swagger-ui/index.html")[0] == 401
assert request(api + "/swagger-ui/index.html", authenticated=True)[0] == 200
status, contract = request(api + "/openapi.yaml", authenticated=True)
assert status == 200 and b"publishProfile" in contract
url = ui + "/api/control-plane/profiles/DEVICE"
assert request(url)[0] == 401
status, body = request(ui + "/api/control-plane/csrf", authenticated=True)
assert status == 200
csrf = json.loads(body)["token"]
payload = {"key": "deploy-" + uuid.uuid4().hex, "version": "1.0.0", "spec": {"protocol": "mqtt"}}
missing_csrf_status = request(url, "POST", payload, authenticated=True)[0]
assert missing_csrf_status == 403, f"Expected CSRF denial 403, got {missing_csrf_status}"
invalid_csrf_status = request(url, "POST", payload, authenticated=True, csrf="invalid-token")[0]
assert invalid_csrf_status == 403, f"Expected invalid CSRF denial 403, got {invalid_csrf_status}"
status, body = request(url, "POST", payload, authenticated=True, csrf=csrf)
assert status == 201
version = json.loads(body)
assert request(url, "POST", payload, authenticated=True, csrf=csrf)[0] == 200
payload["spec"] = {"protocol": "http"}
assert request(url, "POST", payload, authenticated=True, csrf=csrf)[0] == 409
status, body = request(url + "/" + payload["key"] + "/versions/1.0.0", authenticated=True)
assert status == 200 and json.loads(body)["id"] == version["id"]
print("PASS: deployed UI/assets, API service connection, Swagger, authentication, CSRF, Profile 201/200/409/read")

assert request(ui + "/devices")[0] == 200
devices_url = ui + "/api/control-plane/devices"
assert request(devices_url)[0] == 401
device_input = {"key": "deploy-device-" + uuid.uuid4().hex, "displayName": "Deployment synthetic probe",
                "profileVersionId": version["id"], "sourceMode": "SYNTHETIC"}
status, body = request(devices_url, "POST", device_input, authenticated=True, csrf=csrf)
assert status == 201
device = json.loads(body)
device_url = devices_url + "/" + device["id"]
try:
    assert request(devices_url, "POST", device_input, authenticated=True, csrf=csrf)[0] == 200
    assert request(devices_url, "POST", {**device_input, "displayName": "conflict"}, authenticated=True, csrf=csrf)[0] == 409
    patch = {"revision": device["revision"], "displayName": "Renamed synthetic probe"}
    for method, url, body in [("PATCH", device_url, patch), ("DELETE", device_url, None),
                              ("PUT", device_url + "/attachments/" + str(uuid.uuid4()), {"port": "synthetic"})]:
        assert request(url, method, body, authenticated=True)[0] == 403
    assert request(device_url, "PATCH", patch, authenticated=True, csrf=csrf)[0] == 200
    assert request(device_url, "PATCH", patch, authenticated=True, csrf=csrf)[0] == 409
    boot = {"bootId": str(uuid.uuid4())}
    status, body = request(device_url + "/sessions", "POST", boot, authenticated=True, csrf=csrf)
    assert status == 201
    session = json.loads(body)
    assert request(device_url + "/sessions", "POST", boot, authenticated=True, csrf=csrf)[0] == 200
    observation = {"sessionId": session["id"], "sequence": 0, "observedAt": datetime.now(timezone.utc).isoformat(),
                   "status": "ONLINE", "attributes": {"source": "deployment-smoke", "serial": 9007199254740993}}
    assert request(device_url + "/observations", "POST", observation, authenticated=True, csrf=csrf)[0] == 201
    assert request(device_url + "/observations", "POST", observation, authenticated=True, csrf=csrf)[0] == 200
    status, body = request(device_url, authenticated=True)
    detail = json.loads(body)
    assert status == 200 and detail["device"]["connectionStatus"] == "ONLINE"
    assert detail["observations"][0]["attributes"]["serial"] == 9007199254740993
    assert request(device_url + "/sessions", "POST", {"bootId": str(uuid.uuid4())}, authenticated=True, csrf=csrf)[0] == 201
    assert request(device_url + "/observations", "POST", observation, authenticated=True, csrf=csrf)[0] == 409
    status, body = request(ui + "/api/control-plane/nodes", authenticated=True)
    assert status == 200 and isinstance(json.loads(body)["items"], list)
finally:
    assert request(device_url, "DELETE", authenticated=True, csrf=csrf)[0] == 200
status, body = request(device_url, authenticated=True)
assert status == 200 and json.loads(body)["device"]["connectionStatus"] == "RELEASED"
assert request(device_url + "/sessions", "POST", {"bootId": str(uuid.uuid4())}, authenticated=True, csrf=csrf)[0] == 409
print("PASS: deployed Device lifecycle, CSRF on every mutation, revision conflicts, session fencing, lossless observations and Node reads")

if args.through == "device":
    raise SystemExit(0)

status, body = request(api + "/api/v1/platform", authenticated=True)
assert status == 200
if "workflows" not in json.loads(body)["capabilities"]:
    assert request(ui + "/workflows")[0] == 404
    for page in ("/", "/profiles", "/devices", "/virtual-devices", "/audit"):
        status, html = request(ui + page)
        assert status == 200 and b'href="/workflows"' not in html
    for path in ("workflows", "workflow-runs"):
        for origin in (api + "/api/v1/", ui + "/api/control-plane/"):
            assert request(origin + path, authenticated=True)[0] == 404
            assert request(origin + path, "POST", {}, authenticated=True, csrf=csrf, idempotency=str(uuid.uuid4()))[0] == 404
    status, contract = request(api + "/openapi.yaml", authenticated=True)
    assert status == 200 and b"/api/v1/workflows" not in contract and b"/api/v1/workflow-runs" not in contract
    print("PASS: deferred workflow UI, direct URL, public API, proxy and Swagger endpoints are unavailable; other management pages remain available")
    raise SystemExit(0)

assert request(ui + "/workflows")[0] == 200
workflow_url = ui + "/api/control-plane/workflows"
runs_url = ui + "/api/control-plane/workflow-runs"
assert request(workflow_url)[0] == 401
service_body = {"key": "workflow-probe-" + uuid.uuid4().hex, "version": "1.0.0", "spec": {"source": "synthetic-deployment-test"}}
runtime_enabled = os.environ.get("EDGEAI_SMOKE_RUNTIME_ENABLED") == "true"
if runtime_enabled:
    spec = json.loads(Path('contracts/profiles/service-execution.example.json').read_text())
    release = json.loads(Path('deploy/kubernetes/overlays/dev/release.json').read_text())
    spec['image'] = 'ghcr.io/dsa04156/edgeai-runner@' + release['runnerDigest']
    spec['platform'] = {'os': 'linux', 'architectures': ['amd64']}
    spec['inputs'] = {'input': {'mediaType': 'application/json', 'maxBytes': 1048576, 'required': False}}
    # This CRUD probe deliberately remains unscheduled so its cancellation assertions cannot race a successful workload.
    spec['nodeSelector'] = {'edgeai.io/crud-probe': uuid.uuid4().hex}
    service_body['spec'] = spec
status, body = request(ui + "/api/control-plane/profiles/SERVICE", "POST", service_body, authenticated=True, csrf=csrf)
assert status == 201
service = json.loads(body)
workflow_body = {"key": service_body["key"], "displayName": "Synthetic DAG deployment probe"}
assert request(workflow_url, "POST", workflow_body, authenticated=True)[0] == 403
status, body = request(workflow_url, "POST", workflow_body, authenticated=True, csrf=csrf)
assert status == 201
workflow = json.loads(body)
assert request(workflow_url, "POST", workflow_body, authenticated=True, csrf=csrf)[0] == 200
version_url = workflow_url + "/" + workflow["id"] + "/versions"
dag = {"version": "1.0.0", "tasks": [
    {"key": key, "serviceProfileVersionId": service["id"], "parameters": {"serial": 9007199254740993}}
    for key in ["root", "child", "independent"]],
    "dependencies": [{"fromTask": "root", "toTask": "child", "fromPort": "output", "toPort": "input", "mode": "BATCH"}]}
assert request(version_url, "POST", dag, authenticated=True)[0] == 403
status, body = request(version_url, "POST", dag, authenticated=True, csrf=csrf)
assert status == 201
version = json.loads(body)
assert request(version_url, "POST", {**dag, "tasks": list(reversed(dag["tasks"]))}, authenticated=True, csrf=csrf)[0] == 200
assert request(version_url, "POST", {**dag, "dependencies": []}, authenticated=True, csrf=csrf)[0] == 409
run_key = str(uuid.uuid4())
run_body = {"workflowVersionId": version["id"], "execution": {"mode": "AUTO"}, "parameters": {"serial": 9007199254740993}}
assert request(runs_url, "POST", run_body, authenticated=True)[0] == 403
assert request(runs_url, "POST", run_body, authenticated=True, csrf=csrf)[0] == 400
status, body = request(runs_url, "POST", run_body, authenticated=True, csrf=csrf, idempotency=run_key)
assert status == 201
run = json.loads(body)
run_url = runs_url + "/" + run["id"]
try:
    assert run["state"] == ("RUNNING" if runtime_enabled else "PENDING") and run["parameters"]["serial"] == 9007199254740993
    assert request(runs_url, "POST", run_body, authenticated=True, csrf=csrf, idempotency=run_key)[0] == 200
    assert request(runs_url, "POST", {**run_body, "parameters": {}}, authenticated=True, csrf=csrf, idempotency=run_key)[0] == 409
    status, body = request(run_url, authenticated=True)
    tasks = {task["key"]: task for task in json.loads(body)["tasks"]}
    assert status == 200 and len(tasks) == 3
    assert tasks["root"]["state"] == ("RUNNING" if runtime_enabled else "READY") and tasks["child"]["state"] == "WAITING"
    task_url = ui + "/api/control-plane/tasks/" + tasks["root"]["id"]
    status, body = request(task_url, authenticated=True)
    assert status == 200 and json.loads(body)["attempts"][0]["state"] == ("DISPATCHING" if runtime_enabled else "QUEUED")
    assert request(task_url + "/cancel", "POST", {}, authenticated=True)[0] == 403
    assert request(task_url + "/cancel", "POST", {}, authenticated=True, csrf=csrf)[0] == 200
    if runtime_enabled:
        for _ in range(120):
            _, value = request(task_url, authenticated=True)
            if json.loads(value)['task']['state'] == 'CANCELLED': break
            time.sleep(0.5)
        else: raise AssertionError('Runtime did not confirm Task termination in 60 seconds')
    status, body = request(run_url, authenticated=True)
    state = json.loads(body)
    assert status == 200 and state["run"]["state"] == ("RUNNING" if runtime_enabled else "PENDING")
    assert {task["key"]: task["state"] for task in state["tasks"]} == {"root": "CANCELLED", "child": "SKIPPED", "independent": "RUNNING" if runtime_enabled else "READY"}
    assert request(run_url + "/cancel", "POST", {}, authenticated=True)[0] == 403
finally:
    assert request(run_url + "/cancel", "POST", {}, authenticated=True, csrf=csrf)[0] == 200
if runtime_enabled:
    for _ in range(120):
        _, value = request(run_url, authenticated=True)
        if json.loads(value)['run']['state'] == 'CANCELLED': break
        time.sleep(0.5)
    else: raise AssertionError('Runtime did not confirm Run termination in 60 seconds')
status, body = request(runs_url, "POST", run_body, authenticated=True, csrf=csrf, idempotency=run_key)
assert status == 200 and json.loads(body)["id"] == run["id"] and json.loads(body)["state"] == "CANCELLED"
print("PASS: deployed immutable DAG, idempotent Run/Task/Attempt initialization, branch cancellation and replay after cancellation; no runtime execution is claimed")
