"""Attach the shared processing agent to the pinned upstream Helm chart.

This renderer consumes/produces manifests only. It never contacts Kubernetes,
reads credentials, changes a PVC, or deletes a non-owned rule.
"""
import copy
import os
import re
import sys

import yaml


def render(documents, image):
    if not re.fullmatch(r"[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}", image or ""):
        raise ValueError("EDGEAI_PROCESSING_AGENT_IMAGE must be an immutable image digest")
    documents = copy.deepcopy(documents)
    selected = [d for d in documents if d and d.get("kind") == "StatefulSet"
                and d.get("metadata", {}).get("name") == "my-ekuiper"]
    if len(selected) != 1 or selected[0]["metadata"].get("namespace") != "ekuiper":
        raise ValueError("Expected exactly the my-ekuiper StatefulSet in namespace ekuiper")
    stateful = selected[0]
    if stateful["metadata"].get("labels", {}).get("app.kubernetes.io/instance") != "my-ekuiper":
        raise ValueError("Expected the existing my-ekuiper Helm release")
    configs = [d for d in documents if d and d.get("kind") == "ConfigMap"
               and d.get("metadata", {}).get("name") == "my-ekuiper"
               and d["metadata"].get("namespace") == "ekuiper"]
    if len(configs) != 1:
        raise ValueError("Pinned chart ConfigMap is missing")
    config = yaml.safe_load(configs[0]["data"]["kuiper.yaml"])
    config.setdefault("basic", {}).update(ip="127.0.0.1", port=20498,
        restIp="127.0.0.1", restPort=9081, enablePrivateNet=True, pprof=False,
        prometheus=False)
    config.setdefault("source", {}).update(httpServerIp="127.0.0.1", httpServerPort=10081)
    configs[0]["data"]["kuiper.yaml"] = yaml.safe_dump(config, sort_keys=False)
    template = stateful["spec"]["template"]
    owner = {"app.kubernetes.io/part-of": "edgeai", "edgeai.io/processing-engine": "central"}
    template.setdefault("metadata", {}).setdefault("labels", {}).update(owner)
    stateful["metadata"].setdefault("labels", {}).update(owner)
    pod = template["spec"]
    if pod.get("nodeSelector", {}).get("kubernetes.io/hostname") != "etri-ser0004-cgnms0":
        raise ValueError("Shared engine must remain on the approved ordinary AMD64 worker")
    pod.update(serviceAccountName="edgeai-processing", automountServiceAccountToken=False,
               terminationGracePeriodSeconds=30)
    containers = pod["containers"]
    engines = [c for c in containers if c["name"] == "ekuiper"]
    if len(engines) != 1 or engines[0]["image"] != "lfedge/ekuiper:2.4.2":
        raise ValueError("Expected the pinned full eKuiper 2.4.2 image")
    engine = engines[0]
    # The old Pod-IP REST probe becomes invalid after binding REST to loopback.
    engine["readinessProbe"] = {"httpGet": {"path": "/readyz", "port": 10083},
                                "periodSeconds": 5, "failureThreshold": 3}
    engine["resources"] = {"requests": {"cpu": "1", "memory": "2Gi"},
                           "limits": {"cpu": "4", "memory": "4Gi"}}
    agent = {"name": "processing-agent", "image": image, "imagePullPolicy": "IfNotPresent",
        "securityContext": {"runAsNonRoot": True, "runAsUser": 10001,
            "runAsGroup": 10001, "allowPrivilegeEscalation": False,
            "readOnlyRootFilesystem": True, "capabilities": {"drop": ["ALL"]}},
        "resources": {"requests": {"cpu": "500m", "memory": "512Mi"},
                      "limits": {"cpu": "2", "memory": "1Gi"}},
        "ports": [{"name": "processing", "containerPort": 10083}],
        "env": [{"name": "EDGEAI_PROCESSING_BACKEND_URL",
                 "value": "https://edgeai-api.edgeai.svc:18443"},
                {"name": "EDGEAI_PROCESSING_POD_UID", "valueFrom": {
                    "fieldRef": {"fieldPath": "metadata.uid"}}},
                {"name": "EDGEAI_PROCESSING_CREDENTIAL_FILE", "value": "/var/run/processing/credential/credential"},
                {"name": "EDGEAI_PROCESSING_POD_TOKEN_FILE", "value": "/var/run/processing/identity/token"},
                {"name": "EDGEAI_PROCESSING_CA_FILE", "value": "/var/run/processing/credential/ca.crt"}],
        "volumeMounts": [
            {"name": "processing-credential", "mountPath": "/var/run/processing/credential", "readOnly": True},
            {"name": "processing-identity", "mountPath": "/var/run/processing/identity", "readOnly": True}],
        "startupProbe": {"httpGet": {"path": "/healthz", "port": "processing"},
                         "periodSeconds": 2, "failureThreshold": 30},
        "livenessProbe": {"httpGet": {"path": "/healthz", "port": "processing"}, "periodSeconds": 10},
        "readinessProbe": {"httpGet": {"path": "/readyz", "port": "processing"}, "periodSeconds": 5}}
    containers[:] = [c for c in containers if c["name"] != "processing-agent"] + [agent]
    volumes = pod.setdefault("volumes", [])
    volumes[:] = [v for v in volumes if v["name"] not in ("processing-credential", "processing-identity")]
    volumes += [{"name": "processing-credential", "secret": {
        "secretName": "edgeai-processing-agent", "defaultMode": 292}},
        {"name": "processing-identity", "projected": {"defaultMode": 292, "sources": [
            {"serviceAccountToken": {"path": "token", "audience": "edgeai-processing-engine", "expirationSeconds": 600}}]}}]
    for document in documents:
        if document and document.get("kind") == "Service" and document.get("metadata", {}).get("name") in ("my-ekuiper", "my-ekuiper-headless"):
            if document["metadata"].get("namespace") != "ekuiper":
                raise ValueError("Unexpected chart service namespace")
            document["spec"]["ports"] = [{"name": "processing", "port": 10083, "targetPort": "processing"}]
    return documents


if __name__ == "__main__":
    try:
        docs = list(yaml.safe_load_all(sys.stdin))
        output = render(docs, os.environ.get("EDGEAI_PROCESSING_AGENT_IMAGE"))
        yaml.safe_dump_all(output, sys.stdout, sort_keys=False)
    except (ValueError, KeyError, TypeError, yaml.YAMLError) as error:
        print("Processing Helm render failed: " + str(error), file=sys.stderr)
        sys.exit(1)
