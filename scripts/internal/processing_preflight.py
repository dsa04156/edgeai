"""Read-only cluster identity and disk-capacity checks before enabling processing."""
import json
import subprocess
import sys

context, ssh_target = sys.argv[1:]


def kubectl(*arguments):
    result = subprocess.run(["kubectl", "--context", context, *arguments],
                            capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise SystemExit("BLOCKED: cannot inspect the explicit Kubernetes context")
    return json.loads(result.stdout)


for name in ("etri-ser0002-cgnmsb", "etri-ser0004-cgnms0"):
    node = kubectl("get", "node", name, "-o", "json")
    labels = node["metadata"]["labels"]
    if labels.get("kubernetes.io/arch") != "amd64" or "node-role.kubernetes.io/worker" not in labels or any(key in labels for key in ("node-role.kubernetes.io/edge", "node-role.kubernetes.io/control-plane")):
        raise SystemExit("BLOCKED: engine/history placement is not an ordinary AMD64 worker")
    if not any(row["type"] == "Ready" and row["status"] == "True" for row in node["status"]["conditions"]):
        raise SystemExit("BLOCKED: required worker is not Ready")
stateful = kubectl("-n", "ekuiper", "get", "statefulset", "my-ekuiper", "-o", "json")
if stateful["metadata"].get("labels", {}).get("app.kubernetes.io/instance") != "my-ekuiper":
    raise SystemExit("BLOCKED: shared engine is not the approved Helm release")
sc = kubectl("get", "storageclass", "local-path", "-o", "json")
if sc.get("provisioner") != "rancher.io/local-path":
    raise SystemExit("BLOCKED: unexpected history storage provisioner")
history = kubectl("-n", "edgeai", "get", "statefulsets", "-o", "json")
for resource in history["items"]:
    if resource["metadata"]["name"] == "edgeai-processing-history" and resource["metadata"].get("labels", {}).get("edgeai.io/processing-history") != "true":
        raise SystemExit("BLOCKED: history resource ownership conflict")
# Strict SSH host-key verification; use an administrator-configured CG002 alias.
# Bind its node hostname before measuring the exact local-path filesystem.
command = "test \"$(hostname)\" = etri-ser0002-cgnmsb && df -PB1 /opt/local-path-provisioner"
probe = subprocess.run(["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
    "-o", "ConnectTimeout=5", "--", ssh_target, command], capture_output=True, text=True, timeout=15)
if probe.returncode:
    raise SystemExit("BLOCKED: actual CG002 local-path disk-free evidence unavailable")
rows = probe.stdout.splitlines()
try:
    available = int(rows[-1].split()[3])
except (IndexError, ValueError):
    raise SystemExit("BLOCKED: invalid disk-free measurement")
if available < 480 * 1024**3:
    raise SystemExit("BLOCKED: history worker needs 400Gi plus 80Gi free reserve")
print(json.dumps({"context": context, "historyNode": "etri-ser0002-cgnmsb", "freeBytes": available,
    "storageExpandable": sc.get("allowVolumeExpansion", False), "capacityCheck": "PASS", "activation": "NOT_PERFORMED"}))
