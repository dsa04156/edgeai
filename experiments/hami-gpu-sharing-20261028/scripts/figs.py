# usage: python figs.py  (matplotlib, Noto Sans CJK 필요; DCGM 그래프는 Prometheus 접근 필요)
import json, os, urllib.request, urllib.parse
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.patches import FancyBboxPatch
S = os.path.dirname(os.path.abspath(__file__)); R = f"{S}/../results"; O = f"{S}/../figures"
PROM = os.environ.get("PROM_URL", "http://10.100.231.126:9090")
for f in font_manager.findSystemFonts():
    if "NotoSansCJK-Regular" in f or "NotoSansCJK-Medium" in f: font_manager.fontManager.addfont(f)
plt.rcParams.update({"font.family": "Noto Sans CJK JP", "axes.spines.top": False, "axes.spines.right": False, "font.size": 11})
C = {"solo": "#2a6fdb", "split": "#e8590c", "c10": "#2b8a3e", "grey": "#868e96"}
load = lambda n: [json.loads(l) for l in open(f"{R}/{n}.jsonl")]
NS = [1, 2, 5, 10]
solo = load("solo")[0]; c10 = {n: load(f"c10-n{n}") for n in NS}; s10 = c10[10]
base = solo["p95_ms"]

# 1. 공유 구조
fig, ax = plt.subplots(figsize=(11, 6)); ax.set_xlim(0, 110); ax.set_ylim(0, 62); ax.axis("off")
def box(x, y, w, h, t, fc, fs=10, bold=False):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.4,rounding_size=1.2", fc=fc, ec="#343a40", lw=1.2))
    ax.text(x + w / 2, y + h / 2, t, ha="center", va="center", fontsize=fs, weight="bold" if bold else "normal")
box(2, 50, 106, 9, "Qwen2.5-0.5B-Instruct LLM 추론 Pod ×10  (resources.limits:  hami.io/vgpu=1 · nvidia.com/gpucores=10 · nvidia.com/gpumem=1536)", "#fff4e6", fs=10.5)
box(2, 37, 32, 9, "HAMi Admission Webhook\nschedulerName → hami-scheduler", "#e7f5ff")
box(39, 37, 32, 9, "hami-scheduler (kube-scheduler v1.31\n+ HAMi extender: 코어·메모리 배분)", "#e7f5ff")
box(76, 37, 32, 9, "hami-device-plugin\nRTX 5080 → vGPU 10개 광고", "#e7f5ff")
for x in (34, 71): ax.annotate("", (x + 5, 41.5), (x, 41.5), arrowprops=dict(arrowstyle="->", lw=1.5))
for i in range(10):
    box(2 + i * 10.6, 20, 9.6, 12, f"Pod {i:02d}\nHAMi-core\n코어 10%\n1536MiB", "#fff4e6", fs=8.5)
box(2, 3, 106, 12, "NVIDIA GeForce RTX 5080 (etri-ser0002-cgnmsb) · 16,303 MiB · 100 core-units\n10 × (코어 10% + 1,536 MiB 메모리 상한) = 100% / 15,360 MiB", "#e6fcf5", fs=10.5, bold=True)
for y in (15.5, 32.5): ax.annotate("", (55, y), (55, y + 4), arrowprops=dict(arrowstyle="->", lw=1.5))
ax.set_title("실험 ① GPU 논리 자원 공유 구조 — RTX 5080, HAMi 10분할", fontsize=14, weight="bold", loc="left")
fig.tight_layout(); fig.savefig(f"{O}/fig1_hami_10split_structure.png", dpi=200); plt.close(fig)

# 2. 할당·동시성
fig, axs = plt.subplots(1, 2, figsize=(12, 4.8), gridspec_kw={"width_ratios": [1.3, 1]})
rows = sorted(s10, key=lambda r: r["pod"]); t0 = min(r["start"] for r in rows)
for i, r in enumerate(rows):
    axs[0].barh(i, r["elapsed_s"], left=r["start"] - t0, color=C["split"], alpha=.85)
    axs[0].text(r["start"] - t0 + r["elapsed_s"] + 1, i, f'{r["tokens_per_s"]:.1f} tok/s · p95 {r["p95_ms"]:.0f}ms', va="center", fontsize=8.5)
axs[0].set_yticks(range(len(rows)), [r["pod"] for r in rows]); axs[0].invert_yaxis()
axs[0].set_xlim(-2, 100); axs[0].set_xlabel("측정 구간 (s, 동일 시작시각 동기화)")
axs[0].set_title("10개 Pod 동시 실행 구간과 Pod별 성능", loc="left", weight="bold")
used, total = [int(v) for v in rows[0]["nvidia_smi_in_pod_used_total_mib"].split(",")]
b = axs[1].bar(["HAMi 할당\n(Pod 상한)", "Pod 내부\nnvidia-smi 총량", "Pod 내부\n사용량", "torch 최대\nreserved"],
               [1536, total, used, rows[0]["torch_max_reserved_mib"]], color=[C["grey"]] + [C["split"]] * 3, alpha=.85)
axs[1].bar_label(b, fmt="%d MiB", fontsize=9); axs[1].set_ylabel("MiB")
axs[1].set_title(f"Pod별 메모리 격리 ({rows[0]['pod']} 기준)", loc="left", weight="bold")
fig.tight_layout(); fig.savefig(f"{O}/fig2_allocation_concurrency.png", dpi=200); plt.close(fig)

# 3. 부하별 p95
fig, ax = plt.subplots(figsize=(9, 5.2)); xs = range(len(NS))
p95 = [max(r["p95_ms"] for r in c10[n]) for n in NS]; p50 = [max(r["p50_ms"] for r in c10[n]) for n in NS]
ax.plot(xs, p95, "o-", color=C["c10"], lw=2.2, label="Core 10% Pod p95 (동시 Pod 중 최댓값)")
ax.plot(xs, p50, "s--", color=C["c10"], alpha=.5, label="Core 10% Pod p50")
for x, v in zip(xs, p95): ax.annotate(f"{v:.0f}ms\n(+{(v/base-1)*100:.0f}%)", (x, v), textcoords="offset points", xytext=(0, 10), ha="center", fontsize=9)
ax.axhline(base, color=C["solo"], ls=":", lw=1.8, label=f"단독 GPU 100% p95 = {base:.0f}ms")
ax.set_xticks(list(xs), [f"{n}개\n(코어 합계 {n*10}%)" for n in NS]); ax.set_xlabel("동시 실행 Pod 수 (각 gpucores=10, gpumem=1536)")
ax.set_ylabel(f"요청 지연 (ms, 입력 {solo['prompt_tokens']}·출력 {solo['new_tokens']} 토큰)"); ax.set_ylim(0, max(p95) * 1.3); ax.legend(loc="upper left", fontsize=9, frameon=False)
ax.set_title("실험 ② 부하별 LLM 추론 p95 지연 — RTX 5080, HAMi Core 10%", loc="left", weight="bold")
fig.tight_layout(); fig.savefig(f"{O}/fig3_p95_by_load.png", dpi=200); plt.close(fig)

# 4. 처리량
fig, axs = plt.subplots(1, 2, figsize=(12, 4.6))
names = ["단독\n100%"] + [f"C10×{n}" for n in NS]
groups = [[solo]] + [c10[n] for n in NS]
agg = [sum(r["tokens_per_s"] for r in g) for g in groups]; per = [a / len(g) for a, g in zip(agg, groups)]
cols = [C["solo"]] + [C["c10"]] * (len(NS) - 1) + [C["split"]]
for a, v, t in ((axs[0], agg, "GPU 합산 출력 처리량 (tok/s)"), (axs[1], per, "Pod당 출력 처리량 (tok/s)")):
    b = a.bar(names, v, color=cols, alpha=.85); a.bar_label(b, fmt="%.0f", fontsize=9)
    a.set_title(t, loc="left", weight="bold"); a.tick_params(axis="x", labelsize=9)
fig.tight_layout(); fig.savefig(f"{O}/fig4_throughput.png", dpi=200); plt.close(fig)

# 5. DCGM 시계열 (Prometheus)
def q(expr, a, b):
    u = f"{PROM}/api/v1/query_range?" + urllib.parse.urlencode({"query": expr, "start": a, "end": b, "step": "5"})
    res = json.load(urllib.request.urlopen(u))["data"]["result"]
    return [(float(t), float(v)) for t, v in res[0]["values"]] if res else []
win = [(l.split()[0], int(l.split()[1].split("=")[1])) for l in open(f"{R}/windows.txt")]
lo, hi = min(s for _, s in win) - 60, max(s for _, s in win) + 90
sel = '{modelName="NVIDIA GeForce RTX 5080"}'
fig, axs = plt.subplots(3, 1, figsize=(12, 7.5), sharex=True)
for a, (expr, lab) in zip(axs, [(f"DCGM_FI_DEV_GPU_UTIL{sel}", "GPU 사용률 (%)"), (f"DCGM_FI_DEV_FB_USED{sel}", "프레임버퍼 사용 (MiB)"), (f"DCGM_FI_DEV_POWER_USAGE{sel}", "전력 (W)")]):
    d = q(expr, lo, hi)
    a.plot([(t - lo) / 60 for t, _ in d], [v for _, v in d], color="#495057", lw=1.6); a.set_ylabel(lab, fontsize=9)
    for k, s in win:
        a.axvspan((s - lo) / 60, (s + 60 - lo) / 60, color=C["solo"] if k == "solo" else C["split"] if k == "c10-n10" else C["c10"], alpha=.12)
for k, s in win:
    axs[0].text((s + 30 - lo) / 60, 103, k.replace("c10-n", "C10×"), ha="center", fontsize=8)
axs[-1].set_xlabel("경과 시간 (분)"); axs[0].set_ylim(0, 112)
axs[0].set_title("DCGM 지표 (Prometheus, RTX 5080) — 측정 구간 음영 (scrape 주기로 수십 초 지연 표시)", loc="left", weight="bold")
fig.tight_layout(); fig.savefig(f"{O}/fig5_dcgm_timeline.png", dpi=200); plt.close(fig)
print("ok")
