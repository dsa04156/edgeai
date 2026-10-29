# usage: uv run --with matplotlib python scripts/analyze.py
# results/runs.jsonl·capacity.json·gpu-mon.log → results/summary.md, figures/*.png
import json, os, statistics as st
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager

S = os.path.dirname(os.path.abspath(__file__)); R = f"{S}/../results"; O = f"{S}/../figures"
for f in font_manager.findSystemFonts():
    if "NotoSansCJK-Regular" in f: font_manager.fontManager.addfont(f)
plt.rcParams.update({"font.family": "Noto Sans CJK JP", "axes.spines.top": False, "axes.spines.right": False, "font.size": 11})

C = json.load(open(f"{R}/capacity.json"))["C_rps"]
runs = [json.loads(l) for l in open(f"{R}/runs.jsonl")]
gpu = []
if os.path.exists(f"{R}/gpu-mon.log"):
    for l in open(f"{R}/gpu-mon.log"):
        if l.startswith("GPU "):
            t, u, mu, mem, pw, clk = [x.strip() for x in l[4:].split(",")]
            gpu.append((float(t), int(u), int(mem), float(pw)))

def window(r):  # 측정 구간 앞뒤 3초를 제외한 1초 표본
    xs = [g for g in gpu if r["t0"] + 3 < g[0] < r["t1"] - 3]
    return (st.mean(g[1] for g in xs), max(g[2] for g in xs), st.mean(g[3] for g in xs)) if xs else (None, None, None)

def pod_util(r):  # HAMi가 내보내는 Pod별 사용률(%)·vGPU 메모리 사용량(MiB), 측정 구간 평균
    xs = [x for x in r["samples"] if r["t0"] + 3 < x["t"] < r["t1"] - 3]
    u = lambda k: st.mean(x[k] for x in xs if k in x) if any(k in x for x in xs) else None
    return [u(f"hami_container_device_utilization_ratio@{p['server']}") for p in r["pods"]], \
           [(u(f"hami_vgpu_memory_used_bytes@{p['server']}") or 0) / 2**20 for p in r["pods"]]

LOAD_ORDER = ["low", "mid", "high", "a0", "a-mid", "a-over", "victim-0.3C"]
rows = {}
for r in runs:
    util, mem, pw = window(r); pu, pm = pod_util(r)
    rows.setdefault((r["cond"], r["load"]), []).append({
        "p95": [p["p95_e2el_ms"] for p in r["pods"]], "rps": [p["request_throughput"] for p in r["pods"]],
        "offered": [p["offered_rps"] for p in r["pods"]], "failed": sum(p["num_prompts"] - p["completed"] for p in r["pods"]),
        "restarts": [p["restarts"] for p in r["pods"]], "pu": pu, "pm": pm, "util": util, "mem": mem, "power": pw})

ms = lambda xs: (st.mean(xs), st.stdev(xs) if len(xs) > 1 else 0.0)
fmt = lambda m: f"{m[0]:.0f} ± {m[1]:.0f}"
lines = [f"C(A-full 최대 처리량) = {C:.1f} req/s. 값은 반복 평균 ± 표준편차, p95는 Pod별(srv-a / srv-b).", "",
         "| 조건 | 부하 | 반복 | 제공 req/s (a/b) | 달성 총 req/s | p95 srv-a ms | p95 srv-b ms | 실패 | GPU util % | Pod util % (a/b, HAMi) | Pod vGPU 메모리 MiB (a/b) | GPU 메모리 MiB | 전력 W |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
for (c, l), xs in sorted(rows.items(), key=lambda k: (k[0][0], LOAD_ORDER.index(k[0][1]))):
    off = " / ".join(f"{o:.0f}" for o in xs[0]["offered"])
    tot = ms([sum(x["rps"]) for x in xs])
    pa = fmt(ms([x["p95"][0] for x in xs]))
    pb = fmt(ms([x["p95"][1] for x in xs])) if len(xs[0]["p95"]) > 1 else "—"
    u = [x["util"] for x in xs if x["util"] is not None]
    pu = " / ".join(f"{st.mean(x['pu'][i] for x in xs):.0f}" for i in range(len(xs[0]['pu'])) if all(x['pu'][i] is not None for x in xs))
    pm = " / ".join(f"{st.mean(x['pm'][i] for x in xs):.0f}" for i in range(len(xs[0]['pm'])))
    g = f"{st.mean(u):.0f} | {pu or '—'} | {pm} | {max(x['mem'] for x in xs)} | {st.mean(x['power'] for x in xs if x['power']):.0f}" if u else "— | — | — | — | —"
    lines.append(f"| {c} | {l} | {len(xs)} | {off} | {tot[0]:.1f} ± {tot[1]:.1f} | {pa} | {pb} | {sum(x['failed'] for x in xs)} | {g} |")
open(f"{R}/summary.md", "w").write("\n".join(lines) + "\n"); print("\n".join(lines))

COL = {"A-full": "#2a6fdb", "A-half-default": "#74c0fc", "A-half-force": "#1864ab",
       "B-half-default": "#f59f00", "B-half-force": "#c92a2a", "V-default": "#f59f00", "V-force": "#c92a2a"}
def series(cond, loads, f):
    out = [(i, ms([f(x) for x in rows[(cond, l)]])) for i, l in enumerate(loads) if (cond, l) in rows]
    return [i for i, _ in out], [m for _, (m, _) in out], [s for _, (_, s) in out]

# 1. 부하별 p95 (B는 두 Pod 중 큰 값)
L3 = ["low", "mid", "high"]; xt = [f"{k}\n총 {C*f:.0f} req/s" for k, f in zip(["저(0.3C)", "중(0.6C)", "고(0.9C)"], [.3, .6, .9])]
fig, ax = plt.subplots(figsize=(9, 5.2))
for c, lab in [("A-full", "A 단일 Pod, GPU 전체"), ("A-half-default", "A 단일 Pod, 50% 예산 (default)"), ("A-half-force", "A 단일 Pod, 50% 예산 (force)"),
               ("B-half-default", "B 2 Pod × 50% (default)"), ("B-half-force", "B 2 Pod × 50% (force)")]:
    x, m, s = series(c, L3, lambda r: max(r["p95"]))
    if x: ax.errorbar(x, m, yerr=s, marker="o", capsize=4, lw=2, color=COL[c], label=lab, ls="--" if c.startswith("A-half") else "-")
ax.set_yscale("log"); ax.set_xticks(range(3), xt); ax.set_ylabel("요청 p95 지연 (ms, log, 반복 평균 ± sd)")
ax.set_title("부하별 p95 — 같은 총 부하를 1 Pod 또는 2 Pod로 처리", loc="left", weight="bold"); ax.legend(fontsize=9, frameon=False)
fig.tight_layout(); fig.savefig(f"{O}/fig1_p95_by_load.png", dpi=200); plt.close(fig)

# 2. 달성 총 처리량
fig, ax = plt.subplots(figsize=(9, 5.2)); w = 0.16
for k, c in enumerate(["A-full", "A-half-default", "A-half-force", "B-half-default", "B-half-force"]):
    x, m, s = series(c, L3, lambda r: sum(r["rps"]))
    ax.bar([i + (k - 2) * w for i in x], m, w, yerr=s, capsize=3, color=COL[c], label=c)
for i, f in enumerate([.3, .6, .9]): ax.hlines(C * f, i - 2.6 * w, i + 2.6 * w, color="#343a40", ls=":", lw=1.5)
ax.set_xticks(range(3), xt); ax.set_ylabel("달성 총 처리량 (req/s)"); ax.legend(fontsize=9, frameon=False, ncol=2)
ax.set_title("달성 총 처리량 (점선 = 제공 부하)", loc="left", weight="bold")
fig.tight_layout(); fig.savefig(f"{O}/fig2_throughput.png", dpi=200); plt.close(fig)

# 3. 간섭: 피해 Pod(srv-a, 0.3C 고정) p95 vs 상대 Pod 부하
LA = ["a0", "a-mid", "a-over"]; xa = [f"상대 Pod {f:.1f}C\n({C*f:.0f} req/s)" for f in [0, .3, .6]]
fig, axs = plt.subplots(1, 2, figsize=(12, 4.8))
for c, lab in [("V-default", "default"), ("V-force", "force")]:
    x, m, s = series(c, LA, lambda r: r["p95"][0]); axs[0].errorbar(x, m, yerr=s, marker="o", capsize=4, lw=2, color=COL[c], label=lab)
    x, m, s = series(c, LA, lambda r: r["rps"][1] if len(r["rps"]) > 1 else 0); axs[1].errorbar(x, m, yerr=s, marker="s", capsize=4, lw=2, color=COL[c], label=lab)
axs[0].set_yscale("log"); axs[0].set_ylabel("피해 Pod p95 (ms, log)"); axs[0].set_title(f"피해 Pod (0.3C = {C*.3:.0f} req/s 고정) p95", loc="left", weight="bold")
axs[1].plot(range(3), [0, C * .3, C * .6], ":", color="#343a40", label="상대 Pod 제공 부하"); axs[1].set_ylabel("상대 Pod 달성 req/s")
axs[1].set_title("상대 Pod 달성 처리량", loc="left", weight="bold")
for a in axs: a.set_xticks(range(3), xa); a.legend(fontsize=9, frameon=False)
fig.tight_layout(); fig.savefig(f"{O}/fig3_interference.png", dpi=200); plt.close(fig)

# 4. GPU 사용률 시계열
if gpu:
    t00 = runs[0]["t0"]; fig, ax = plt.subplots(figsize=(13, 4))
    g = [x for x in gpu if t00 - 60 < x[0] < runs[-1]["t1"] + 60]
    ax.plot([(x[0] - t00) / 60 for x in g], [x[1] for x in g], lw=.6, color="#495057")
    seen = set()
    for r in runs:
        ax.axvspan((r["t0"] - t00) / 60, (r["t1"] - t00) / 60, color=COL.get(r["cond"], "#adb5bd"), alpha=.25, lw=0,
                   label=r["cond"] if r["cond"] not in seen else None); seen.add(r["cond"])
    ax.set_xlabel("경과 (분)"); ax.set_ylabel("GPU util % (nvidia-smi 1s)"); ax.set_ylim(0, 105)
    ax.legend(fontsize=8, frameon=False, ncol=7, loc="lower center", bbox_to_anchor=(.5, 1.0))
    fig.tight_layout(); fig.savefig(f"{O}/fig4_gpu_timeline.png", dpi=200); plt.close(fig)
