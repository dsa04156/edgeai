import assert from "node:assert/strict";
import { test } from "node:test";
import { controls, formFrom, placement, policyFrom, problem, transition, type Lab, type LabSession } from "../kestra-native/offload-lab.ts";

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
  assert.match(problem({ ...form, edgeNodeId: "" }), /edge/);
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

test("placement is only what a RUNNING producer reports; transitions show their real state", () => {
  assert.equal(placement(session({})), "UNKNOWN");
  assert.equal(placement(session({ currentNodeName: "edge-a" })), "EDGE");
  assert.equal(placement(session({ currentNodeName: "worker-b" })), "WORKER");
  assert.equal(transition(session({})), null);
  const draining = transition(session({ operations: [{ trigger: "NODE_RETURN", state: "DRAINING", failureReason: null }] as LabSession["operations"] }));
  assert.deepEqual(draining, { label: "원래 edge 여유 → 복귀", state: "기존 Pod 종료 대기", active: true, reason: null });
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
