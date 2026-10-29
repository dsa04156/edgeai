import json, os, socket, subprocess, time
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

dur = float(os.environ.get("DURATION_S", "60"))
start_at = float(os.environ.get("START_AT", "0"))
new_tokens = int(os.environ.get("NEW_TOKENS", "64"))
path = os.environ.get("MODEL_PATH", "/cache/models/Qwen2.5-0.5B-Instruct")
tok = AutoTokenizer.from_pretrained(path)
model = AutoModelForCausalLM.from_pretrained(path, torch_dtype=torch.float16).eval().to("cuda")
msgs = [{"role": "user", "content": "공장 진동 센서 값이 평소보다 30% 높게 측정됐습니다. 점검해야 할 항목을 설명해 주세요."}]
inputs = tok.apply_chat_template(msgs, add_generation_prompt=True, return_tensors="pt", return_dict=True).to("cuda")
gen = dict(max_new_tokens=new_tokens, min_new_tokens=new_tokens, do_sample=False, pad_token_id=tok.eos_token_id)

with torch.inference_mode():
    for _ in range(3):
        model.generate(**inputs, **gen)
    torch.cuda.synchronize()
    torch.cuda.reset_peak_memory_stats()
    while time.time() < start_at:
        time.sleep(0.05)
    lat = []
    t0 = time.time()
    while time.time() - t0 < dur:
        s = time.perf_counter()
        model.generate(**inputs, **gen)
        torch.cuda.synchronize()
        lat.append((time.perf_counter() - s) * 1000)
    elapsed = time.time() - t0

lat.sort()
pct = lambda p: lat[min(len(lat) - 1, int(round(p / 100 * (len(lat) - 1))))]
smi = subprocess.run(["nvidia-smi", "--query-gpu=memory.used,memory.total", "--format=csv,noheader,nounits"],
                     capture_output=True, text=True).stdout.strip()
print("RESULT " + json.dumps({
    "pod": socket.gethostname(), "model": os.path.basename(path), "prompt_tokens": int(inputs["input_ids"].shape[1]),
    "new_tokens": new_tokens, "requests": len(lat), "start": t0, "elapsed_s": round(elapsed, 2),
    "p50_ms": round(pct(50), 1), "p95_ms": round(pct(95), 1), "p99_ms": round(pct(99), 1),
    "req_per_s": round(len(lat) / elapsed, 3), "tokens_per_s": round(len(lat) * new_tokens / elapsed, 1),
    "torch_max_alloc_mib": round(torch.cuda.max_memory_allocated() / 2**20, 1),
    "torch_max_reserved_mib": round(torch.cuda.max_memory_reserved() / 2**20, 1),
    "nvidia_smi_in_pod_used_total_mib": smi,
}), flush=True)
