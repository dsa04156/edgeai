"""Exercise the renderer through its public CLI using the actual Helm output."""
import copy
import os
from pathlib import Path
import subprocess
import sys

import yaml

manifest = Path(sys.argv[1]).read_text()
image = "ghcr.io/dsa04156/edgeai-processing-agent@sha256:" + "a" * 64
environment = dict(os.environ, EDGEAI_PROCESSING_AGENT_IMAGE=image)


def invoke(source, agent_image=image):
    return subprocess.run([sys.executable, "scripts/internal/processing_post_renderer.py"],
        input=source, text=True, capture_output=True,
        env=dict(environment, EDGEAI_PROCESSING_AGENT_IMAGE=agent_image), check=False)


original = list(yaml.safe_load_all(manifest))
result = invoke(manifest)
assert result.returncode == 0, result.stderr
output = list(yaml.safe_load_all(result.stdout))
old_stateful = next(doc for doc in original if doc["kind"] == "StatefulSet")
stateful = next(doc for doc in output if doc["kind"] == "StatefulSet")
assert stateful["spec"]["volumeClaimTemplates"] == old_stateful["spec"]["volumeClaimTemplates"]
pod = stateful["spec"]["template"]["spec"]
engine = next(c for c in pod["containers"] if c["name"] == "ekuiper")
old_engine = old_stateful["spec"]["template"]["spec"]["containers"][0]
assert engine["volumeMounts"] == old_engine["volumeMounts"]
assert pod["automountServiceAccountToken"] is False
assert pod["serviceAccountName"] == "edgeai-processing"
assert next(c for c in pod["containers"] if c["name"] == "processing-agent")["image"] == image
for doc in output:
    if doc["kind"] == "Service":
        assert [p["port"] for p in doc["spec"]["ports"]] == [10083]
config = yaml.safe_load(next(doc for doc in output if doc["kind"] == "ConfigMap")["data"]["kuiper.yaml"])
assert config["basic"]["restIp"] == config["basic"]["ip"] == config["source"]["httpServerIp"] == "127.0.0.1"
assert config["basic"]["enablePrivateNet"] is True
again = invoke(result.stdout)
assert again.returncode == 0 and list(yaml.safe_load_all(again.stdout)) == output
assert invoke(manifest, "latest").returncode != 0
foreign = copy.deepcopy(original)
next(doc for doc in foreign if doc["kind"] == "StatefulSet")["metadata"]["namespace"] = "foreign"
assert invoke(yaml.safe_dump_all(foreign)).returncode != 0
print("PASS: pinned Helm renderer preserves storage, isolates management, rejects foreign ownership and mutable images")
