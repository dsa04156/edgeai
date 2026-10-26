"""Publish the hawkeye-video-safety SERVICE profiles, workflow and DAG version through the public API.

    EDGEAI_API_URL=http://<api> EDGEAI_API_USER=... EDGEAI_API_PASSWORD=... python3 -I hawkeye-runtime/workflow/publish.py [definition.json]

The default definition is hawkeye-video-safety.json (v1 capture → infer); hawkeye-video-safety-v2.json publishes the
continuous-observation snapshot version. A profile image must be pinned by digest before publishing.

Every call is replay-safe: an existing identical profile/workflow/version returns 200, a different one 409 (stop and
publish a new version instead). Prints the published IDs; never prints credentials.
"""

import base64
import http.cookiejar
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEFINITION = Path(__file__).with_name("hawkeye-video-safety.json")


class Api:
    def __init__(self, url, user, password):
        self.url = url.rstrip("/")
        self.auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
        self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None
        self.csrf = self.call("GET", "csrf")["token"]

    def call(self, method, path, body=None, expected=(200, 201)):
        headers = {"Authorization": self.auth}
        if self.csrf:
            headers["X-CSRF-TOKEN"] = self.csrf
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(f"{self.url}/api/v1/{path}", method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
        try:
            response = self.client.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read(1048576)
            if response.status not in expected:
                raise SystemExit(f"{method} {path}: HTTP {response.status} {payload[:300].decode(errors='replace')}")
            return json.loads(payload)


def main():
    definition = json.loads((Path(sys.argv[1]) if len(sys.argv) > 1 else DEFINITION).read_text())
    for profile in definition["profiles"]:
        if not re.fullmatch(r".+@sha256:[a-f0-9]{64}", profile["spec"]["image"]):
            raise SystemExit(f"profile {profile['key']}: pin the built image digest first")
    api = Api(os.environ["EDGEAI_API_URL"], os.environ["EDGEAI_API_USER"], os.environ["EDGEAI_API_PASSWORD"])
    profiles = {}
    for profile in definition["profiles"]:
        published = api.call("POST", "profiles/SERVICE", profile)
        profiles[profile["key"]] = published["id"]
        print("profile", profile["key"], profile["version"], published["id"])
    workflow = api.call("POST", "workflows", definition["workflow"])
    print("workflow", workflow["key"], workflow["id"])
    version = definition["version"]
    tasks = [{"key": t["key"], "serviceProfileVersionId": profiles[t["profile"]], "parameters": t["parameters"]} for t in version["tasks"]]
    published = api.call("POST", f"workflows/{workflow['id']}/versions",
                         {"version": version["version"], "tasks": tasks, "dependencies": version["dependencies"]})
    print("version", published["version"], published["id"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
