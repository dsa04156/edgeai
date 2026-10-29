# usage: python3 run.py <phase> [...]
#   capacity                    A-full 서버의 최대 처리량(C, req/s) 측정 → results/capacity.json
#   matrix                      조건표 전체 실행 (C 필요). 결과는 results/runs.jsonl 에 1줄/측정 누적
#   oom                         메모리 상한 초과 할당 시험
# 서버 Pod는 조건마다 새로 띄우고, 같은 조건 안의 부하·반복은 무작위 순서로 실행한다.
import json, os, random, re, shlex, subprocess, sys, threading, time, urllib.request

S = os.path.dirname(os.path.abspath(__file__)); R = f"{S}/../results"
NS, NODE = "hami-bench", "etri-ser0002-cgnmsb"
IMG = "docker.io/vllm/vllm-openai:v0.11.0"
MODEL = "/cache/models/Qwen2.5-1.5B-Instruct"
DCGM = os.environ.get("DCGM_URL", "http://10.244.8.34:9400/metrics")
HAMI = os.environ.get("HAMI_URL", "http://10.254.192.129:31992/metrics")
IN_LEN, OUT_LEN, WARM_S, MEAS_S, REPS = 70, 64, 10, 60, 3
LOADS = {"low": 0.3, "mid": 0.6, "high": 0.9}          # 총 제공 부하 = C × 비율
VICTIM, AGGR = 0.3, {"a0": 0.0, "a-mid": 0.3, "a-over": 0.6}  # 피해 Pod 고정 0.3C, 상대 Pod 0~0.6C
HALF = {"cores": "50", "mem": "7680"}; FULL = {"cores": "100", "mem": "0"}

def sh(*a, check=True, inp=None):
    p = subprocess.run(a, capture_output=True, text=True, input=inp)
    if check and p.returncode: raise RuntimeError(f"{a}: {p.stderr[-2000:]}")
    return p.stdout

def kubectl(*a, **k): return sh("kubectl", "-n", NS, *a, **k)

def apply(obj): kubectl("apply", "-f", "-", inp=json.dumps(obj))

def server(name, cores, mem, policy, util="0.85"):
    lim = {"hami.io/vgpu": "1", "nvidia.com/gpucores": cores}
    if mem != "0": lim["nvidia.com/gpumem"] = mem
    apply({"apiVersion": "v1", "kind": "Pod",
      "metadata": {"name": name, "namespace": NS, "labels": {"exp": "srv"}},
      "spec": {"restartPolicy": "Never", "nodeSelector": {"kubernetes.io/hostname": NODE},
        "containers": [{"name": "vllm", "image": IMG,
          "command": ["vllm", "serve", MODEL, "--served-model-name", "qwen", "--port", "8000",
                      "--gpu-memory-utilization", util, "--max-model-len", "2048", "--disable-log-requests"],
          "env": [{"name": "GPU_CORE_UTILIZATION_POLICY", "value": policy}, {"name": "HF_HUB_OFFLINE", "value": "1"}],
          "resources": {"limits": lim, "requests": {"cpu": "2", "memory": "6Gi"}},
          "volumeMounts": [{"name": "c", "mountPath": "/cache", "readOnly": True}, {"name": "shm", "mountPath": "/dev/shm"}]}],
        "volumes": [{"name": "c", "hostPath": {"path": "/var/tmp/hami-bench", "type": "Directory"}},
                    {"name": "shm", "emptyDir": {"medium": "Memory", "sizeLimit": "2Gi"}}]}})

def client():
    apply({"apiVersion": "v1", "kind": "Pod",
      "metadata": {"name": "bench-client", "namespace": NS, "labels": {"exp": "client"}},
      "spec": {"nodeSelector": {"kubernetes.io/hostname": NODE},
        "containers": [{"name": "c", "image": IMG, "command": ["sleep", "infinity"],
          "env": [{"name": "HF_HUB_OFFLINE", "value": "1"}, {"name": "CUDA_VISIBLE_DEVICES", "value": ""}],
          "resources": {"requests": {"cpu": "4", "memory": "4Gi"}, "limits": {"cpu": "12", "memory": "12Gi"}},
          "volumeMounts": [{"name": "c", "mountPath": "/cache", "readOnly": True}]}],
        "volumes": [{"name": "c", "hostPath": {"path": "/var/tmp/hami-bench", "type": "Directory"}}]}})
    kubectl("wait", "--for=condition=Ready", "pod/bench-client", "--timeout=600s")

def ip(name): return kubectl("get", "pod", name, "-o", "jsonpath={.status.podIP}").strip()

def wait_ready(name, timeout=600):
    t = time.time()
    while time.time() - t < timeout:
        ph = kubectl("get", "pod", name, "-o", "jsonpath={.status.phase}", check=False).strip()
        if ph in ("Failed", "Succeeded"): raise RuntimeError(f"{name} {ph}: {kubectl('logs', name, check=False)[-3000:]}")
        if ph == "Running" and subprocess.run(["kubectl", "-n", NS, "exec", "bench-client", "--", "curl", "-sf", f"http://{ip(name)}:8000/health"], capture_output=True).returncode == 0:
            return
        time.sleep(5)
    raise RuntimeError(f"{name} not ready")

def stop_servers():
    kubectl("delete", "pod", "-l", "exp=srv", "--wait=true", "--ignore-not-found")

PER_PROC_RPS = 60  # vllm bench 프로세스 1개는 약 300 req/s에서 스스로 병목이 되므로 부하를 여러 프로세스로 나눈다

def bench_cmd(target, rate, n, tag, seed):
    # 기본 fd 한도 1024에서는 동시 연결이 많을 때 클라이언트가 'Too many open files'로 실패한다
    return ["kubectl", "-n", NS, "exec", "bench-client", "--", "sh", "-c", "ulimit -n 65536 && exec " + shlex.join(["vllm", "bench", "serve", "--backend", "vllm", "--base-url", f"http://{ip(target)}:8000",
            "--model", "qwen", "--tokenizer", MODEL, "--dataset-name", "random",
            "--random-input-len", str(IN_LEN), "--random-output-len", str(OUT_LEN), "--ignore-eos",
            "--num-prompts", str(n), "--request-rate", str(rate), "--seed", str(seed),
            "--request-id-prefix", f"{tag}-", "--save-result", "--save-detailed", "--result-dir", "/tmp/r", "--result-filename", f"{tag}.json"])]

AGG = """import json, sys
lat, ttft, done, dur, n, errs = [], [], 0, 0.0, 0, {}
for f in sys.argv[1:]:
    try: d = json.load(open(f))
    except Exception as e: errs['missing result'] = errs.get('missing result', 0) + 1; continue
    dur = max(dur, d['duration']); n += d['num_prompts']; done += d['completed']
    for t, it, e in zip(d['ttfts'], d['itls'], d['errors']):
        if e: k = e.strip().splitlines()[-1][:120]; errs[k] = errs.get(k, 0) + 1
        else: ttft.append(t * 1000); lat.append((t + sum(it)) * 1000)
lat.sort(); ttft.sort()
q = lambda a, p: round(a[min(len(a) - 1, int(p / 100 * len(a)))], 1) if a else None
print(json.dumps({'completed': done, 'num_prompts': n, 'duration': dur, 'request_throughput': done / dur if dur else 0,
  'p50_e2el_ms': q(lat, 50), 'p95_e2el_ms': q(lat, 95), 'p99_e2el_ms': q(lat, 99), 'p95_ttft_ms': q(ttft, 95), 'errors': errs}))
"""

def fan(target, rate, n, tag, seed):
    """rate(req/s 또는 'inf')·n건을 k개 프로세스로 나눠 동시에 보낸다. 반환: (Popen 목록, 결과 파일 목록)"""
    k = 4 if rate == "inf" else max(1, -(-int(rate) // PER_PROC_RPS))
    r = rate if rate == "inf" else rate / k
    ps = [subprocess.Popen(bench_cmd(target, r, -(-n // k), f"{tag}-p{i}", seed + i), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) for i in range(k)]
    return ps, [f"/tmp/r/{tag}-p{i}.json" for i in range(k)]

def collect(ps, files):
    outs = [p.communicate()[0] for p in ps]
    res = json.loads(kubectl("exec", "bench-client", "--", "python3", "-c", AGG, *files))
    res["rc"] = [p.returncode for p in ps]
    if any(res["rc"]): res["log_tail"] = [o[-400:] for o, p in zip(outs, ps) if p.returncode]
    return res

class Sampler(threading.Thread):
    """1초 간격으로 DCGM·HAMi 지표를 직접 수집한다 (Prometheus 수집 지연 회피)."""
    def __init__(self, tag):
        super().__init__(daemon=True); self.tag, self.stop, self.rows = tag, threading.Event(), []
    def run(self):
        while not self.stop.is_set():
            row = {"t": time.time()}
            for url in (DCGM, HAMI):
                try:
                    for line in urllib.request.urlopen(url, timeout=1).read().decode().splitlines():
                        if line.startswith(("DCGM_FI_DEV_GPU_UTIL", "DCGM_FI_PROF_GR_ENGINE_ACTIVE", "DCGM_FI_DEV_FB_USED",
                                            "DCGM_FI_DEV_POWER_USAGE", "DCGM_FI_PROF_DRAM_ACTIVE", "HostCoreUtilization",
                                            "vGPU_device_memory_usage_in_bytes", "Device_utilization_desc_of_container",
                                            "hami_")):
                            k, v = line.rsplit(" ", 1); pod = re.search(r'pod="([^"]+)"', k)
                            row[k.split("{")[0] + (f"@{pod.group(1)}" if pod else "")] = float(v)
                except Exception as e:
                    row.setdefault("err", []).append(str(e)[:80])
            self.rows.append(row); self.stop.wait(1)

def measure(cond, load, rep, targets):
    """targets: [(server, rate)] — 모든 클라이언트가 동시에 시작한다."""
    tag = f"{cond}-{load}-r{rep}"
    seed = random.randrange(1 << 30)
    kubectl("exec", "bench-client", "--", "sh", "-c", "rm -f /tmp/r/*.json")
    smp = Sampler(tag); smp.start(); t0 = time.time()
    jobs = [(s, r, fan(s, r, max(1, round(r * MEAS_S)), f"{tag}-{s}", seed + 100 * i)) for i, (s, r) in enumerate(targets) if r > 0]
    done = [(s, r, collect(*j)) for s, r, j in jobs]
    t1 = time.time(); smp.stop.set(); smp.join()
    rec = {"tag": tag, "cond": cond, "load": load, "rep": rep, "t0": t0, "t1": t1, "pods": []}
    for s, r, res in done:
        rec["pods"].append({"server": s, "offered_rps": r, "planned": max(1, round(r * MEAS_S)), **res,
                            "restarts": kubectl("get", "pod", s, "-o", "jsonpath={.status.containerStatuses[0].restartCount}", check=False)})
    for x in rec["pods"]: x["failed"] = x["planned"] - x["completed"]
    rec["samples"] = smp.rows
    with open(f"{R}/runs.jsonl", "a") as f: f.write(json.dumps(rec) + "\n")
    p = [(x["server"], x["p95_e2el_ms"], round(x["request_throughput"], 1), x["failed"]) for x in rec["pods"]]
    print(f"{time.strftime('%H:%M:%S')} {tag} {p}", flush=True)
    return rec

def warm(srvs):
    for s in srvs:
        collect(*fan(s, "inf", 400, f"warm-{s}", 0))
    time.sleep(WARM_S)

def capacity():
    client(); stop_servers(); server("srv-a", **FULL, policy="default"); wait_ready("srv-a"); warm(["srv-a"])
    out = []
    for i in range(3):
        r = collect(*fan("srv-a", "inf", 6000, f"cap-{i}", 1000 * i))
        if r["completed"] != r["num_prompts"]: raise RuntimeError(f"capacity run {i}: {r}")
        out.append(r["request_throughput"]); print("capacity", i, r, flush=True)
    stop_servers()
    json.dump({"runs_rps": out, "C_rps": min(out), "note": "rate=inf, 6000 prompts를 4개 클라이언트 프로세스로 동시 전송, A-full; C=3회 중 최솟값"},
              open(f"{R}/capacity.json", "w"), indent=1)

def matrix(only=None):
    C = json.load(open(f"{R}/capacity.json"))["C_rps"]
    plan = [  # (cond, servers[(name, budget, policy)], 부하 목록 → 서버별 rate)
      ("A-full", [("srv-a", FULL, "default")], {l: [C * f] for l, f in LOADS.items()}),
      ("A-half-default", [("srv-a", HALF, "default")], {l: [C * f] for l, f in LOADS.items()}),
      ("A-half-force", [("srv-a", HALF, "force")], {l: [C * f] for l, f in LOADS.items()}),
      ("B-half-default", [("srv-a", HALF, "default"), ("srv-b", HALF, "default")], {l: [C * f / 2] * 2 for l, f in LOADS.items()}),
      ("V-default", [("srv-a", HALF, "default"), ("srv-b", HALF, "default")], {a: [C * VICTIM, C * f] for a, f in AGGR.items()}),
      ("B-half-force", [("srv-a", HALF, "force"), ("srv-b", HALF, "force")], {l: [C * f / 2] * 2 for l, f in LOADS.items()}),
      ("V-force", [("srv-a", HALF, "force"), ("srv-b", HALF, "force")], {a: [C * VICTIM, C * f] for a, f in AGGR.items()}),
    ]
    client(); prev = None
    for cond, srvs, loads in plan:
        if only and cond not in only: continue
        if srvs != prev:  # 서버 구성이 같으면 재기동하지 않는다 (B → V)
            stop_servers()
            # 2-Pod는 0.85에서 srv-b가 물리 메모리 부족(cuMemoryAllocate res=2)으로 기동 실패 → 0.75
            for n, b, pol in srvs: server(n, **b, policy=pol, util="0.75" if len(srvs) > 1 else "0.85")
            for n, _, _ in srvs: wait_ready(n)
            prev = srvs
        warm([n for n, _, _ in srvs])
        runs = [(l, r) for l in loads for r in range(REPS)]; random.shuffle(runs)
        for l, r in runs:
            measure(cond, l, r, list(zip([n for n, _, _ in srvs], loads[l])))
            time.sleep(2)
    stop_servers()

HOG = """import torch, json, time
xs = []
try:
    while True:
        xs.append(torch.empty(128 << 20, dtype=torch.uint8, device='cuda')); torch.cuda.synchronize()
except Exception as e:
    print('RESULT ' + json.dumps({'allocated_mib': len(xs) * 128, 'error': str(e).splitlines()[0][:300]}), flush=True)
time.sleep(30)
"""

def oom():
    """gpumem=2048 Pod가 상한을 넘겨 할당할 때 오류 형태와, 같은 GPU의 서버(srv-a, 0.3C) 영향 확인."""
    C = json.load(open(f"{R}/capacity.json"))["C_rps"]
    client(); stop_servers(); server("srv-a", **HALF, policy="default"); wait_ready("srv-a"); warm(["srv-a"])
    apply({"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "hog", "namespace": NS}, "data": {"hog.py": HOG}})
    hog = {"apiVersion": "v1", "kind": "Pod", "metadata": {"name": "mem-hog", "namespace": NS, "labels": {"exp": "srv"}},
      "spec": {"restartPolicy": "Never", "nodeSelector": {"kubernetes.io/hostname": NODE},
        "containers": [{"name": "hog", "image": "docker.io/pytorch/pytorch:2.8.0-cuda12.8-cudnn9-runtime",
          "command": ["bash", "-c", "sleep 20; python3 /h/hog.py"],
          "resources": {"limits": {"hami.io/vgpu": "1", "nvidia.com/gpucores": "50", "nvidia.com/gpumem": "2048"}},
          "volumeMounts": [{"name": "h", "mountPath": "/h"}]}],
        "volumes": [{"name": "h", "configMap": {"name": "hog"}}]}}
    apply(hog)
    kubectl("wait", "--for=jsonpath={.status.phase}=Running", "pod/mem-hog", "--timeout=300s")
    rec = measure("OOM", "victim-0.3C", 0, [("srv-a", C * VICTIM)])
    kubectl("wait", "--for=jsonpath={.status.phase}=Succeeded", "pod/mem-hog", "--timeout=120s", check=False)
    log = kubectl("logs", "mem-hog", check=False)
    json.dump({"hog_log": log[-3000:], "hog_status": kubectl("get", "pod", "mem-hog", "-o", "jsonpath={.status.containerStatuses[0].state}", check=False),
               "victim": rec["pods"], "srv_restarts": rec["pods"][0]["restarts"]}, open(f"{R}/oom.json", "w"), indent=1)
    print(log[-800:]); stop_servers()

if __name__ == "__main__":
    os.makedirs(R, exist_ok=True)
    {"capacity": capacity, "oom": oom, "matrix": lambda: matrix(sys.argv[2].split(",") if len(sys.argv) > 2 else None)}[sys.argv[1]]()
