# usage: gen.py <name> <pods> <gpucores> <gpumem MiB, 0=제한 없음> <start epoch>
import sys, json
name, n, cores, mem, start = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5]
for i in range(n):
    lim = {"hami.io/vgpu": "1", "nvidia.com/gpucores": cores}
    if mem != "0": lim["nvidia.com/gpumem"] = mem
    pod = {"apiVersion": "v1", "kind": "Pod",
      "metadata": {"name": f"{name}-{i:02d}", "namespace": "hami-bench", "labels": {"exp": name}},
      "spec": {"restartPolicy": "Never", "nodeSelector": {"kubernetes.io/hostname": "etri-ser0002-cgnmsb"},
        "containers": [{"name": "bench", "image": "docker.io/pytorch/pytorch:2.8.0-cuda12.8-cudnn9-runtime",
          "command": ["python3", "/bench/bench_llm.py"],
          "env": [{"name": "START_AT", "value": start}, {"name": "DURATION_S", "value": "60"}, {"name": "PYTHONPATH", "value": "/cache/pylib"}, {"name": "HF_HUB_OFFLINE", "value": "1"}],
          "resources": {"limits": lim, "requests": {"cpu": "1", "memory": "2Gi"}},
          "volumeMounts": [{"name": "b", "mountPath": "/bench"}, {"name": "c", "mountPath": "/cache", "readOnly": True}]}],
        "volumes": [{"name": "b", "configMap": {"name": "llm-bench"}}, {"name": "c", "hostPath": {"path": "/var/tmp/hami-bench", "type": "Directory"}}]}}
    print("---"); print(json.dumps(pod))
