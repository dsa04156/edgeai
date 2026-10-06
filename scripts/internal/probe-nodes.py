"""Compare real Kubernetes nodes to the API cache; only synthetic application data is written."""
import base64
import http.cookiejar
import json
import os
import subprocess
import time
import urllib.request
import uuid

context = os.environ["EDGEAI_NODE_CONTEXT"]
baseline = json.loads(subprocess.check_output([
    "kubectl", "--context", context, "--request-timeout=15s", "get", "nodes", "-o", "json",
], text=True))["items"]
expected = {node["metadata"]["uid"]: node for node in baseline}
if not expected:
    raise SystemExit("BLOCKED: a real cluster with at least one node is required")
origin = os.environ.get("EDGEAI_NODE_API_URL", "http://127.0.0.1:" + os.environ["EDGEAI_API_PORT"])
auth = "Basic " + base64.b64encode((os.environ["EDGEAI_API_USER"] + ":" + os.environ["EDGEAI_API_PASSWORD"]).encode()).decode()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf = None

def request(path, method="GET", data=None):
    headers = {"Authorization": auth}
    if csrf:
        headers["X-CSRF-TOKEN"] = csrf
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(origin + "/api/v1/" + path, method=method, headers=headers,
                                 data=json.dumps(data).encode() if data is not None else None)
    with client.open(req, timeout=10) as response:
        return response.status, json.load(response)

for attempt in range(20):
    nodes = []
    offset = 0
    while offset is not None:
        _, result = request(f"nodes?limit=100&offset={offset}")
        nodes.extend(result["items"])
        offset = result["nextOffset"]
    observed = {node["id"]: node for node in nodes if node["status"] not in ("REMOVED", "STALE")}
    if observed.keys() == expected.keys():
        break
    time.sleep(2)
else:
    raise SystemExit("FAIL: cached fresh node UIDs differ from the real Kubernetes list")

for node_id, node in expected.items():
    actual = observed[node_id]
    assert actual["name"] == node["metadata"]["name"]
    assert actual["architecture"] == node["status"]["nodeInfo"]["architecture"]
    assert actual["operatingSystem"] == node["status"]["nodeInfo"]["operatingSystem"]
    assert actual["cpu"] == node["status"]["allocatable"]["cpu"]
    assert actual["memory"] == node["status"]["allocatable"]["memory"]
    assert actual["labels"] == node["metadata"].get("labels", {})
    assert request("nodes/" + node_id)[1]["id"] == node_id

ready = next((node for node in observed.values() if node["status"] == "READY"), None)
if ready is None:
    raise SystemExit("BLOCKED: no fresh Ready node for attachment verification")
csrf = request("csrf")[1]["token"]
key = "node-probe-" + uuid.uuid4().hex
_, profile = request("profiles/DEVICE", "POST", {"key": key, "version": "1.0.0", "spec": {"source": "synthetic-probe"}})
_, device = request("devices", "POST", {"key": key, "displayName": "Synthetic node attachment probe",
                                        "profileVersionId": profile["id"], "sourceMode": "SYNTHETIC"})
path = "devices/" + device["id"]
try:
    _, first = request(path + "/attachments/" + ready["id"], "PUT", {"port": "synthetic-probe"})
    _, replay = request(path + "/attachments/" + ready["id"], "PUT", {"port": "synthetic-probe"})
    assert first == replay
    _, detail = request(path)
    assert len(detail["attachments"]) == 1 and detail["attachments"][0]["nodeId"] == ready["id"]
finally:
    request(path, "DELETE")
assert request(path)[1]["attachments"][0]["detachedAt"] is not None
print(f"PASS: {len(expected)} real Kubernetes node UIDs and metadata match; synthetic attachment replay and release preserve history")
