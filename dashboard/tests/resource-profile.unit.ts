import assert from "node:assert/strict";
import { test } from "node:test";
import {
  basis,
  conditions,
  emptyAccelerator,
  emptyDraft,
  formatValue,
  memoryQuantity,
  parseCpu,
  parseMemory,
  profileDocument,
  suggestions,
  windowEnding,
  type Analysis,
} from "../kestra-native/resource-profile.ts";

const usage = (status: string, p95: number | null) => ({ status, unit: "millicores", min: p95, average: p95, p95, max: p95, samples: 240, firstSample: null, lastSample: null, reason: null });
const analysis = {
  id: "00000000-0000-4000-8000-0000000000aa",
  measurement: { method: "cadvisor", stepSeconds: 15, pods: [], cpu: usage("AVAILABLE", 245.503), memory: { ...usage("STALE", 4.2e8), unit: "bytes" } },
  recommendation: { engine: "KRR", status: "AVAILABLE", reason: null, window: null, cpu: { requestMillicores: 393, limitMillicores: null, info: null },
    memory: { requestBytes: 530579456, limitBytes: 530579456, info: null }, podCount: 1, warnings: [], provenance: {} },
} as unknown as Analysis;

test("CPU and memory quantities parse to integer base units and reject NaN, fractions and decimal suffixes", () => {
  assert.equal(parseCpu("250m"), 250);
  assert.equal(parseCpu("0.25"), 250);
  assert.equal(parseCpu("2"), 2000);
  for (const bad of ["NaN", "-1", "0", "0m", "1.0005", "250 m", "", "Infinity", "1e3"]) assert.equal(parseCpu(bad), null, bad);
  assert.equal(parseMemory("512Mi"), 536870912);
  assert.equal(parseMemory("1Gi"), 1073741824);
  assert.equal(parseMemory("530579456"), 530579456);
  for (const bad of ["1.5Gi", "1G", "512MB", "NaN", "0", "-5Mi"]) assert.equal(parseMemory(bad), null, bad);
  assert.equal(memoryQuantity(530579456), "506Mi");
  assert.equal(memoryQuantity(1073741824), "1Gi");
  assert.equal(memoryQuantity(1000), "1000");
});

test("absent observations render as a dash, never as zero", () => {
  assert.equal(formatValue(null, "millicores"), "—");
  assert.equal(formatValue(undefined, "bytes"), "—");
  assert.equal(formatValue(0, "percent"), "0%");
  assert.equal(formatValue(245.503, "millicores"), "245.5m");
  assert.equal(formatValue(429260800, "bytes"), "409.4 MiB");
});

test("only AVAILABLE measurements and KRR values are adoptable, and the basis follows the confirmed value", () => {
  const hints = suggestions(analysis);
  assert.deepEqual(hints.cpu, { measured: 246, recommended: 393 });
  assert.deepEqual(hints.memory, { measured: null, recommended: 530579456 }, "a stale measurement is not adoptable");
  assert.equal(basis(393, hints.cpu), "RECOMMENDED");
  assert.equal(basis(246, hints.cpu), "MEASURED");
  assert.equal(basis(400, hints.cpu), "MANUAL", "an edited value is the user's own");
  assert.deepEqual(suggestions(null).cpu, { measured: null, recommended: null });
  const noData = { ...analysis, recommendation: { ...analysis.recommendation, status: "NO_DATA" } } as unknown as Analysis;
  assert.equal(suggestions(noData).cpu.recommended, null);
});

test("the save document carries integer ranges, derived bases and accelerator quantities separate from device memory", () => {
  const draft = emptyDraft(["amd64", "arm64"]);
  draft.cpu = { min: "100m", request: "393m", max: "2" };
  draft.memory = { min: "256Mi", request: "530579456", max: "1Gi" };
  draft.accelerators = [{ ...emptyAccelerator(), min: "1", request: "1", max: "1", deviceMemory: "4Gi", vendor: "NVIDIA", models: "RTX 5080, RTX 5060 Ti" }];
  draft.architectures = ["arm64", "amd64"];
  draft.runtimeName = "onnxruntime";
  draft.conditions = "batchSize=1\nresolution = 640x640";
  const document = profileDocument("00000000-0000-4000-8000-000000000001", analysis, draft);
  assert.deepEqual(document.resources.cpu, { minMillicores: 100, requestMillicores: 393, maxMillicores: 2000, basis: "RECOMMENDED" });
  assert.deepEqual(document.resources.memory, { minBytes: 268435456, requestBytes: 530579456, maxBytes: 1073741824, basis: "RECOMMENDED" });
  assert.deepEqual(document.resources.accelerators, [{ kind: "GPU", resourceName: "nvidia.com/gpu", unit: "COUNT", min: 1, request: 1, max: 1,
    deviceMemoryBytes: 4294967296, vendor: "NVIDIA", models: ["RTX 5080", "RTX 5060 Ti"] }]);
  assert.equal(document.analysisId, analysis.id);
  assert.deepEqual(document.compatibility, { architectures: ["amd64", "arm64"], runtime: { name: "onnxruntime" } });
  assert.deepEqual(document.inputConditions, { batchSize: "1", resolution: "640x640" });

  const manual = profileDocument("00000000-0000-4000-8000-000000000001", null, { ...draft, accelerators: [] });
  assert.equal(manual.analysisId, null);
  assert.equal(manual.resources.cpu.basis, "MANUAL", "without an analysis nothing is measured or recommended");
});

test("invalid ranges name the row and never send", () => {
  const draft = emptyDraft([]);
  draft.memory = { min: "256Mi", request: "512Mi", max: "1Gi" };
  draft.cpu = { min: "300m", request: "250m", max: "1" };
  assert.throws(() => profileDocument("x", null, draft), /CPU은 최소 ≤ 요청 ≤ 최대/);
  draft.cpu = { min: "", request: "250m", max: "1" };
  assert.throws(() => profileDocument("x", null, draft), /CPU 최소값.*0으로 채우지 않습니다/);
  draft.cpu = { min: "100m", request: "250m", max: "1001" };
  assert.throws(() => profileDocument("x", null, draft), /CPU 최대값이 허용 한도/);
  draft.cpu = { min: "100m", request: "250m", max: "1" };
  draft.accelerators = [{ ...emptyAccelerator(), resourceName: "nvidia.com/gpucores", unit: "PERCENT", min: "10", request: "30", max: "150" }];
  assert.throws(() => profileDocument("x", null, draft), /가속기 1.*허용 한도/);
  draft.accelerators = [{ ...emptyAccelerator(), resourceName: "gpu", min: "1", request: "1", max: "1" }];
  assert.throws(() => profileDocument("x", null, draft), /vendor 도메인/);
  assert.throws(() => conditions("no equals sign"), /key=value/);
});

test("windows end on a whole second and span the preset", () => {
  assert.deepEqual(windowEnding(new Date("2026-10-29T15:00:00.750Z"), 1), { start: "2026-10-29T14:00:00.000Z", end: "2026-10-29T15:00:00.000Z" });
});
