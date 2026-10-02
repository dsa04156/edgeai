"""Exercise the deployed HTTP path without printing credentials or session tokens."""
import base64
import http.cookiejar
import json
import os
import re
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

ui = os.environ.get("EDGEAI_SMOKE_UI_URL", "http://127.0.0.1:13080").rstrip("/")
api = os.environ.get("EDGEAI_SMOKE_API_URL", "http://127.0.0.1:18080").rstrip("/")
auth = "Basic " + base64.b64encode(
    (os.environ["EDGEAI_API_USER"] + ":" + os.environ["EDGEAI_API_PASSWORD"]).encode()
).decode()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def request(url, method="GET", data=None, authenticated=False, csrf=None):
    headers = {}
    if authenticated:
        headers["Authorization"] = auth
    if csrf:
        headers["X-CSRF-TOKEN"] = csrf
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
