import assert from "node:assert/strict";
import { test } from "node:test";
import { controls, formFrom, modelFailure, placement, sessionStatus, policyFrom, problem, transition, type Lab, type LabSession } from "../kestra-native/offload-lab.ts";

const offload = {
  cpuPercent: null, memoryPercent: null, latencyMicros: null, consecutiveSamples: 2, maxSampleAgeSeconds: 90, maxGapSeconds: 75,
  minRunningSeconds: 20, cooldownSeconds: 30, maxTransfers: 2, drainTimeoutSeconds: 120, startTimeoutSeconds: 300,
  nodePlacement: { strategy: "EDGE_TO_WORKER", sourceCpuPercent: 50, sourceMemoryPercent: null, sourceGpuPercent: null, targetMaxCpuPercent: 60,
    targetMaxMemoryPercent: 80, targetMaxGpuPercent: 80, returnToEdge: { cpuPercent: 20, memoryPercent: null, gpuPercent: null } },
};
const lab = (session: Partial<LabSession> | null, enabled = true) =>
  ({ enabled, disabledReason: enabled ? null : "RUNTIME_DISABLED", model: null, loadImage: null, metrics: { status: "AVAILABLE", fetchedAt: null, maxAgeSeconds: 90 },
    nodes: [], defaults: { offload, load: { workers: 3, durationSeconds: 300 } }, session }) as unknown as Lab;
const session = (values: Partial<LabSession>) =>
  ({ state: "ACTIVE", edgeNodeName: "edge-a", currentNodeName: null, operations: [], load: { desired: "NONE" }, ...values }) as unknown as LabSession;

test("the preset form round-trips into the API policy with the return threshold", () => {
  const l = lab(null);
  const form = { ...formFrom(l), edgeNodeId: "6f1c1b7e-2d2b-4b8e-9a0c-0d3f7d1b2c3a" };
  assert.equal(problem(form), "");
  const policy = policyFrom(l, { ...form, sourceCpuPercent: 55, returnCpuPercent: 15 });
  assert.equal(policy.nodePlacement?.sourceCpuPercent, 55);
  assert.deepEqual(policy.nodePlacement?.returnToEdge, { cpuPercent: 15, memoryPercent: null, gpuPercent: null });
  assert.equal(policy.cpuPercent, null);
});

test("invalid presets are explained before any request", () => {
  const form = { ...formFrom(lab(null)), edgeNodeId: "x" };
  assert.match(problem({ ...form, edgeNodeId: "" }), /엣지/);
  assert.match(problem({ ...form, returnCpuPercent: 50 }), /낮아야/);
  assert.match(problem({ ...form, consecutiveSamples: 1 }), /2–6/);
  assert.match(problem({ ...form, workers: 17 }), /1–16/);
  assert.match(problem({ ...form, durationSeconds: 12.5 }), /정수/);
  assert.match(problem({ ...form, maxTransfers: 1 }), /왕복/);
});

test("controls follow the server state, not clicks", () => {
  assert.deepEqual(controls(lab(null)), { start: true, stop: false, loadStart: false, loadStop: false });
  assert.equal(controls(lab(null, false)).start, false);
  assert.deepEqual(controls(lab(session({}))), { start: false, stop: true, loadStart: true, loadStop: false });
  assert.deepEqual(controls(lab(session({ load: { desired: "RUNNING" } as LabSession["load"] }))), { start: false, stop: true, loadStart: false, loadStop: true });
  assert.deepEqual(controls(lab(session({ state: "STOPPING" }))), { start: false, stop: false, loadStart: false, loadStop: false });
  assert.deepEqual(controls(lab(session({ state: "STOPPED" }))), { start: true, stop: false, loadStart: false, loadStop: false });
});

test("a failed model Run is reported at once and blocks load while the server finishes cleanup", () => {
  const attempts = [
    { number: 1, state: "FAILED", nodeName: "edge-a", failureReason: "WORKLOAD_FAILED", updatedAt: "2026-10-30T04:37:09.947Z" },
  ] as unknown as LabSession["attempts"];
  const failed = session({ runState: "FAILED", attempts, load: { desired: "RUNNING" } as LabSession["load"] });
  assert.deepEqual(controls(lab(failed)), { start: false, stop: true, loadStart: false, loadStop: true });
  assert.equal(controls(lab(session({ runState: "SUCCEEDED" }))).loadStart, false);
  assert.deepEqual(modelFailure(failed), { reason: "WORKLOAD_FAILED", attempt: 1, nodeName: "edge-a", at: "2026-10-30T04:37:09.947Z" });
  assert.equal(modelFailure(session({ runState: "RUNNING" })), null);
  assert.equal(modelFailure(session({ runState: "CANCELLED", state: "STOPPED" })), null);
  // The status shown in the heading and details follows the Run, and never claims cleanup before STOPPED is observed.
  assert.equal(sessionStatus(failed), "모델 실패 · 정리 대기");
  assert.equal(sessionStatus(session({ runState: "FAILED", state: "STOPPING" })), "모델 실패 · 정리 중 (Pod 종료 확인 대기)");
  assert.equal(sessionStatus(session({ runState: "FAILED", state: "STOPPED" })), "모델 실패 · 종료됨");
  assert.equal(sessionStatus(session({ runState: "SUCCEEDED" })), "모델 종료 · 정리 대기");
  assert.equal(sessionStatus(session({ runState: "RUNNING" })), "실행 중");
  assert.equal(sessionStatus(session({ runState: "CANCELLED", state: "STOPPED" })), "종료됨");
});

test("placement is only what a RUNNING producer reports; transitions show their real state", () => {
  assert.equal(placement(session({})), "UNKNOWN");
  assert.equal(placement(session({ currentNodeName: "edge-a" })), "EDGE");
  assert.equal(placement(session({ currentNodeName: "worker-b" })), "WORKER");
  assert.equal(transition(session({})), null);
  const draining = transition(session({ operations: [{ trigger: "NODE_RETURN", state: "DRAINING", failureReason: null }] as LabSession["operations"] }));
  assert.deepEqual(draining, { label: "원래 엣지 여유 → 복귀", state: "기존 Pod 종료 대기", active: true, reason: null, direction: "RETURN", tone: "warning" });
});

test("load presets and estimates follow the selected edge's core count", async () => {
  const { cores, presetWorkers, loadEstimate } = await import("../kestra-native/offload-lab.ts");
  assert.equal(cores("4"), 4);
  assert.equal(cores("3500m"), 3.5);
  assert.equal(cores(""), null);
  assert.equal(cores(undefined), null);
  assert.deepEqual([4, 6, 12].map((c) => presetWorkers(c, 50)), [3, 4, 8]);
  assert.equal(presetWorkers(null, 50), 3);
  assert.equal(presetWorkers(32, 90), 16);
  assert.equal(presetWorkers(2, 95), 2);
  // Fixed 3 processes on a 12-core edge cannot reach a 50% threshold from idle.
  const twelve = loadEstimate(3, 12, 3, 50)!;
  assert.equal(twelve.added, 25);
  assert.equal(twelve.expected, 28);
  assert.equal(twelve.insufficient, true);
  assert.equal(loadEstimate(8, 12, 3, 50)!.insufficient, false);
  assert.equal(loadEstimate(3, 4, 3, 50)!.added, 75);
  assert.equal(loadEstimate(16, 4, 50, 50)!.expected, 100);
  assert.equal(loadEstimate(3, 12, null, 50)!.observed, false);
  assert.equal(loadEstimate(3, null, 3, 50), null);
});

test("a reloaded page shows the open session's stored policy and load, never the preset", async () => {
  const { formFrom, lockedFields } = await import("../kestra-native/offload-lab.ts");
  const saved = {
    ...offload, consecutiveSamples: 3, cooldownSeconds: 45, maxTransfers: 4,
    nodePlacement: { ...offload.nodePlacement, sourceCpuPercent: 65, targetMaxCpuPercent: 55, returnToEdge: { cpuPercent: 25, memoryPercent: null, gpuPercent: null } },
  };
  const open = lab(session({ edgeNodeId: "edge-id", policy: saved, load: { desired: "RUNNING", workers: 8, seconds: 600 } } as Partial<LabSession>));
  const form = formFrom(open);
  assert.equal(form.edgeNodeId, "edge-id");
  assert.equal(form.sourceCpuPercent, 65);
  assert.equal(form.returnCpuPercent, 25);
  assert.equal(form.targetMaxCpuPercent, 55);
  assert.equal(form.consecutiveSamples, 3);
  assert.equal(form.cooldownSeconds, 45);
  assert.equal(form.maxTransfers, 4);
  assert.equal(form.workers, 8);
  assert.equal(form.durationSeconds, 600);
  // Stopped load: load inputs stay editable (not forced); no open session: nothing is locked.
  const stopped = lockedFields(lab(session({ edgeNodeId: "edge-id", policy: saved, load: { desired: "STOPPED", workers: 8, seconds: 600 } } as Partial<LabSession>)));
  assert.equal(stopped.sourceCpuPercent, 65);
  assert.equal("workers" in stopped, false);
  assert.deepEqual(lockedFields(lab(null)), {});
  assert.deepEqual(lockedFields(lab(session({ state: "STOPPED", policy: saved }))), {});
});

test("idempotency keys repeat for retries, renew after a confirmed request (start → stop → start)", async () => {
  const { actionKeys } = await import("../kestra-native/offload-lab.ts");
  let n = 0;
  const keys = actionKeys(() => `k${++n}`);
  const first = keys.key("load-s");
  assert.equal(keys.key("load-s"), first); // Failed request retried: same key, server replays instead of duplicating.
  keys.settle("load-s"); // Confirmed start.
  // Stop does not use a key; the next start after stop/expiry is a new request with a new key.
  const second = keys.key("load-s");
  assert.notEqual(second, first);
  assert.equal(keys.key("start"), "k3"); // Independent per action.
  keys.settle("load-s");
  assert.equal(keys.key("start"), "k3");
  keys.clear();
  assert.equal(keys.key("start"), "k4");
});

test("transfers count only SUCCEEDED operations against the session's fixed limit (2 = one round trip)", async () => {
  const { transfers } = await import("../kestra-native/offload-lab.ts");
  const ops = (...states: string[]) => states.map((state) => ({ state })) as LabSession["operations"];
  assert.equal(transfers(null), null);
  assert.deepEqual(transfers(session({ policy: offload, operations: ops("STARTING", "SUCCEEDED") } as Partial<LabSession>)), { done: 1, max: 2, exhausted: false });
  assert.deepEqual(transfers(session({ policy: offload, operations: ops("SUCCEEDED", "SUCCEEDED") } as Partial<LabSession>)), { done: 2, max: 2, exhausted: true });
  assert.deepEqual(transfers(session({ policy: null, operations: ops("FAILED") })), { done: 0, max: null, exhausted: false });
});

test("the timeline uses only event timestamps, newest first, and never turns update or expiry times into events", async () => {
  const { timeline } = await import("../kestra-native/offload-lab.ts");
  assert.deepEqual(timeline(null), []);
  const events = timeline(session({
    createdAt: "2026-10-30T04:46:00Z", stoppedAt: null, runState: "RUNNING",
    attempts: [
      { number: 2, cause: "OFFLOAD", state: "RUNNING", nodeName: "worker-b", observedState: "RUNNING", failureReason: null, createdAt: "2026-10-30T04:47:27Z", updatedAt: "2026-10-30T04:53:00Z" },
      { number: 1, cause: "INITIAL", state: "OFFLOADED", nodeName: "edge-a", observedState: "TERMINATED", failureReason: "OFFLOADED", createdAt: "2026-10-30T04:46:00Z", updatedAt: "2026-10-30T04:47:22Z" },
    ],
    operations: [{ trigger: "NODE_CPU", state: "SUCCEEDED", failureReason: null, decision: { sourceNode: { name: "edge-a" }, targetNode: { name: "worker-b" } }, createdAt: "2026-10-30T04:47:20Z", updatedAt: "2026-10-30T04:47:30Z" }],
    load: { desired: "EXPIRED", generation: 2, workers: 4, seconds: 300, startedAt: "2026-10-30T04:46:30Z", expiresAt: "2026-10-30T04:51:30Z" },
    pods: { status: "OBSERVED", items: [{ role: "MODEL", name: "edgeai-x-abc", nodeName: "worker-b", createdAt: "2026-10-30T04:47:28Z", deletingAt: "2026-10-30T04:52:00Z" }] },
  } as unknown as Partial<LabSession>));
  assert.deepEqual(events.map((e) => [e.at, e.text]), [
    ["2026-10-30T04:52:00Z", "모델 Pod 삭제 요청"],
    ["2026-10-30T04:47:28Z", "모델 Pod 생성"],
    ["2026-10-30T04:47:27Z", "모델 Attempt #2 생성"],
    ["2026-10-30T04:47:20Z", "전환 판단 · 엣지 CPU 과부하 → 워커"],
    ["2026-10-30T04:46:30Z", "부하 시작 (2회차)"],
    ["2026-10-30T04:46:00Z", "시험 시작"],
    ["2026-10-30T04:46:00Z", "모델 Attempt #1 생성"],
  ]);
  assert.equal(events.find((e) => e.text.startsWith("전환"))?.detail, "edge-a → worker-b · 현재 완료");
  assert.match(events.find((e) => e.text.startsWith("부하"))!.detail, /현재 시간 만료/);
  assert.ok(!events.some((e) => ["2026-10-30T04:51:30Z", "2026-10-30T04:47:22Z", "2026-10-30T04:47:30Z"].includes(e.at)));
});

const gpuNodes = [
  { id: "jetson", name: "etri-dev0001-jetorn", role: "EDGE", status: "READY", cpuPercent: 12, gpuPercent: 64.5, observedAt: "2026-10-30T05:00:00Z", gpuObservedAt: "2026-10-30T05:00:20Z", gpuLoadResource: "nvidia.com/gpu.shared" },
  { id: "pi", name: "etri-dev0006-raspi5", role: "EDGE", status: "READY", cpuPercent: 5, gpuPercent: null, observedAt: "2026-10-30T05:00:00Z", gpuObservedAt: null, gpuLoadResource: null },
] as unknown as Lab["nodes"];

test("the GPU choice becomes a GPU-only policy (no CPU threshold) and the CPU choice stays as before", async () => {
  const { loadBody } = await import("../kestra-native/offload-lab.ts");
  const l = lab(null);
  const form = { ...formFrom(l), edgeNodeId: "jetson" };
  assert.equal(form.mode, "CPU");
  const gpu = policyFrom(l, { ...form, mode: "GPU" });
  assert.equal(gpu.nodePlacement?.sourceGpuPercent, 50);
  assert.equal(gpu.nodePlacement?.sourceCpuPercent, null);
  assert.deepEqual(gpu.nodePlacement?.returnToEdge, { cpuPercent: null, memoryPercent: null, gpuPercent: 20 });
  assert.deepEqual([gpu.consecutiveSamples, gpu.minRunningSeconds, gpu.cooldownSeconds, gpu.maxTransfers], [2, 20, 30, 2]);
  const cpu = policyFrom(l, form);
  assert.equal(cpu.nodePlacement?.sourceGpuPercent, null);
  assert.deepEqual(cpu.nodePlacement?.returnToEdge, { cpuPercent: 20, memoryPercent: null, gpuPercent: null });
  // The load request never carries a mode, image, resource or command; GPU load is always one worker.
  assert.deepEqual(loadBody({ ...form, mode: "GPU", workers: 7, durationSeconds: 120 }), { workers: 1, durationSeconds: 120 });
  assert.deepEqual(loadBody({ ...form, workers: 7, durationSeconds: 120 }), { workers: 7, durationSeconds: 120 });
});

test("GPU mode needs an edge with an inventory GPU and its own hysteresis; CPU-only fields are not checked", () => {
  const form = { ...formFrom(lab(null)), mode: "GPU" as const, edgeNodeId: "jetson" };
  assert.equal(problem(form, gpuNodes), "");
  assert.equal(problem({ ...form, workers: 99, sourceCpuPercent: 10, returnCpuPercent: 90 }, gpuNodes), "");
  assert.match(problem({ ...form, edgeNodeId: "pi" }, gpuNodes), /NVIDIA GPU/);
  assert.match(problem({ ...form, edgeNodeId: "unknown" }, gpuNodes), /NVIDIA GPU/);
  assert.match(problem({ ...form, returnGpuPercent: 50 }, gpuNodes), /복귀 GPU.*낮아야/);
  assert.match(problem({ ...form, sourceGpuPercent: 0 }, gpuNodes), /1–100/);
  // CPU mode on a Pi edge is unchanged.
  assert.equal(problem({ ...form, mode: "CPU", edgeNodeId: "pi" }, gpuNodes), "");
});

test("a reloaded GPU session restores the GPU choice from its stored policy", async () => {
  const { lockedFields, loadMode } = await import("../kestra-native/offload-lab.ts");
  const saved = { ...offload, nodePlacement: { ...offload.nodePlacement, sourceCpuPercent: null, sourceGpuPercent: 60, returnToEdge: { cpuPercent: null, memoryPercent: null, gpuPercent: 15 } } };
  const open = lab(session({ edgeNodeId: "jetson", policy: saved, load: { desired: "RUNNING", workers: 1, seconds: 600 } } as Partial<LabSession>));
  const form = formFrom(open);
  assert.equal(form.mode, "GPU");
  assert.equal(form.sourceGpuPercent, 60);
  assert.equal(form.returnGpuPercent, 15);
  assert.equal(form.edgeNodeId, "jetson");
  assert.equal(loadMode(saved), "GPU");
  assert.equal(loadMode(offload), "CPU");
  assert.equal(loadMode(null), null);
  assert.equal(lockedFields(lab(session({ state: "STOPPED", policy: saved }))).mode, undefined);
});

test("the edge metric is the chosen mode's own value and observation time; absent is unknown, never 0", async () => {
  const { metric, timeline } = await import("../kestra-native/offload-lab.ts");
  assert.deepEqual(metric(gpuNodes[0], "GPU"), { label: "GPU", value: 64.5, observedAt: "2026-10-30T05:00:20Z" });
  assert.deepEqual(metric(gpuNodes[0], "CPU"), { label: "CPU", value: 12, observedAt: "2026-10-30T05:00:00Z" });
  assert.deepEqual(metric(gpuNodes[1], "GPU"), { label: "GPU", value: null, observedAt: null });
  const gpuPolicy = { ...offload, nodePlacement: { ...offload.nodePlacement, sourceCpuPercent: null, sourceGpuPercent: 50, returnToEdge: { cpuPercent: null, memoryPercent: null, gpuPercent: 20 } } };
  const events = timeline(session({
    createdAt: "2026-10-30T05:00:00Z", stoppedAt: null, runState: "RUNNING", attempts: [], operations: [], policy: gpuPolicy,
    load: { desired: "RUNNING", generation: 1, workers: 1, seconds: 300, startedAt: "2026-10-30T05:01:00Z", expiresAt: "2026-10-30T05:06:00Z" },
    pods: { status: "OBSERVED", items: [] },
  } as unknown as Partial<LabSession>));
  assert.match(events.find((e) => e.text.startsWith("GPU 부하"))!.detail, /^GPU 1개 · 300초/);
});
