import assert from "node:assert/strict";
import { test } from "node:test";
import {
  acceleratorText,
  confirmedSummary,
  isCleaning,
  confirmationDocument,
  base64Bytes,
  confirmDraft,
  deviceStatus,
  emptyRunDraft,
  encodeBase64,
  requirementBasis,
  runRequest,
  type ProfilingOptions,
  type ProfilingRun,
  type RunDraft,
} from "../kestra-native/model-profiling.ts";
import { backendRunRefusal } from "./model-profiling-contract.ts";

const options: ProfilingOptions = {
  enabled: true, disabledReason: null,
  image: { reference: "10.254.192.47:5000/edgeai/detector@sha256:" + "a".repeat(64), platform: "linux/arm64" },
  execution: { command: null, args: null },
  limits: {
    durationSeconds: { min: 300, default: 300, max: 1800 }, concurrency: { min: 1, default: 2, max: 16 },
    requestTimeoutSeconds: { min: 1, default: 10, max: 60 }, readinessTimeoutSeconds: { min: 30, default: 300, max: 900 },
    bodyMaxBytes: 65536, methods: ["GET", "POST"], contentTypes: ["application/json", "text/plain", "application/octet-stream"],
    cpuMillicores: { min: 100, max: 8000 }, memoryBytes: { min: 67108864, max: 17179869184 }, acceleratorCount: { min: 1, max: 4 },
    loadTypes: ["HTTP", "COMMAND"],
  },
  nodes: [
    { name: "jetson-01", architecture: "arm64", status: "READY", compatible: true, incompatibleReason: null,
      allocatable: { cpuMillicores: 6000, memoryBytes: 8 * 1024 ** 3 }, accelerators: [{ resourceName: "nvidia.com/gpu", allocatableCount: 1 }], busy: false },
    { name: "jetson-02", architecture: "arm64", status: "READY", compatible: true, incompatibleReason: null,
      allocatable: { cpuMillicores: 6000, memoryBytes: 8 * 1024 ** 3 }, accelerators: [], busy: true },
  ],
};
function http(): RunDraft {
  return { ...emptyRunDraft(options), nodeName: "jetson-01", cpu: "2", memory: "4Gi", port: "8080", path: "/predict", body: '{"x":1}' };
}

test("a new run draft leaves allocation and load empty and takes only the server's timing defaults", () => {
  const draft = emptyRunDraft(options);
  assert.deepEqual([draft.nodeName, draft.cpu, draft.memory, draft.port, draft.body], ["", "", "", "", ""]);
  assert.deepEqual([draft.durationSeconds, draft.concurrency, draft.requestTimeoutSeconds, draft.readinessTimeoutSeconds], ["300", "2", "10", "300"]);
});

test("an HTTP run sends the test allocation and a real inference request; invalid input is refused before sending", () => {
  assert.deepEqual(runRequest(http(), options), {
    nodeName: "jetson-01", allocation: { cpuMillicores: 2000, memoryBytes: 4294967296, accelerator: null }, durationSeconds: 300,
    load: { type: "HTTP", port: 8080, path: "/predict", method: "POST", contentType: "application/json", body: '{"x":1}',
      concurrency: 2, requestTimeoutSeconds: 10, readinessTimeoutSeconds: 300 },
  });
  assert.deepEqual(runRequest({ ...http(), acceleratorResource: "nvidia.com/gpu" }, options).allocation.accelerator, { resourceName: "nvidia.com/gpu", count: 1 });
  for (const [edit, message] of [
    [{ nodeName: "" }, /시험 노드/],
    [{ nodeName: "jetson-02" }, /지금 측정할 수 없습니다/],
    [{ cpu: "7" }, /CPU 할당/],
    [{ memory: "64Gi" }, /메모리 할당/],
    [{ acceleratorResource: "nvidia.com/gpu", acceleratorCount: "2" }, /가속기 할당 수/],
    [{ durationSeconds: "120" }, /측정 시간/],
    [{ path: "/../etc" }, /요청 경로/],
    [{ body: "" }, /실제 추론 입력/],
    [{ body: "x".repeat(65537) }, /64 KiB/],
    [{ port: "0" }, /포트/],
  ] as [Partial<RunDraft>, RegExp][])
    assert.throws(() => runRequest({ ...http(), ...edit }, options), message, JSON.stringify(edit).slice(0, 60));
});

test("each HTTP mode sends exactly the shape the server's strict parser accepts", () => {
  const sent = (edit: Partial<RunDraft>) => runRequest({ ...http(), ...edit }, options);
  // GET carries no content metadata even when a POST body was typed earlier.
  const get = sent({ method: "GET", contentType: "application/json", body: '{"x":1}', bodyBase64: "AAE=" });
  assert.deepEqual(get.load, { type: "HTTP", port: 8080, path: "/predict", method: "GET", concurrency: 2, requestTimeoutSeconds: 10, readinessTimeoutSeconds: 300 });
  const json = sent({ bodyBase64: "AAE=" });
  assert.deepEqual([json.load.contentType, json.load.body, "bodyBase64" in json.load], ["application/json", '{"x":1}', false]);
  const plain = sent({ contentType: "text/plain", body: "한글 입력" });
  assert.deepEqual([plain.load.contentType, plain.load.body, "bodyBase64" in plain.load], ["text/plain", "한글 입력", false]);
  // Binary goes only as bodyBase64; pasted line breaks are dropped before sending.
  const binary = sent({ contentType: "application/octet-stream", body: "ignored", bodyBase64: "AAEC\n/w==" });
  assert.deepEqual([binary.load.contentType, binary.load.bodyBase64, "body" in binary.load], ["application/octet-stream", "AAEC/w==", false]);
  for (const request of [get, json, plain, binary]) assert.equal(backendRunRefusal(JSON.parse(JSON.stringify(request))), null, request.load.type + (request.load as { method?: string }).method);
  // The mirror itself refuses the shapes found in review, so a regression cannot pass silently.
  assert.match(backendRunRefusal({ ...get, load: { ...get.load, contentType: "application/json" } })!, /GET/);
  assert.match(backendRunRefusal({ ...binary, load: { ...binary.load, bodyBase64: undefined, body: "\u0000\u0001" } })!, /bodyBase64|본문/);
});

test("a binary body is validated as standard Base64 and limited by its decoded size", () => {
  const binary = (bodyBase64: string) => ({ ...http(), contentType: "application/octet-stream", bodyBase64 });
  for (const [encoded, message] of [
    ["", /파일을 고르거나 Base64/],
    ["AAE", /표준 Base64/],
    ["AA-_", /표준 Base64/],
    ["A".repeat(87384), /디코딩 후 64 KiB/],
  ] as [string, RegExp][])
    assert.throws(() => runRequest(binary(encoded), options), message, encoded.slice(0, 12));
  const nearLimit = "A".repeat(87376) + "AA==";
  assert.equal(base64Bytes(nearLimit), 65533);
  assert.equal(runRequest(binary(nearLimit), options).load.bodyBase64, nearLimit);
  const bytes = new Uint8Array([0, 1, 2, 255, 128, 10]);
  const encoded = encodeBase64(bytes);
  assert.equal(encoded, Buffer.from(bytes).toString("base64"));
  assert.equal(base64Bytes(encoded), bytes.length);
  assert.equal(encodeBase64(new Uint8Array(65536)).length, 87384);
  assert.throws(() => runRequest({ ...http(), contentType: "text/plain", body: "a\0b" }, options), /NUL/);
});

test("a COMMAND run needs an explicit benchmark command", () => {
  const draft: RunDraft = { ...http(), loadType: "COMMAND", command: "python3\n/opt/bench.py", args: "--seconds\n300" };
  assert.deepEqual(runRequest(draft, options).load, { type: "COMMAND", command: ["python3", "/opt/bench.py"], args: ["--seconds", "300"], readinessTimeoutSeconds: 300 });
  assert.throws(() => runRequest({ ...draft, command: "" }, options), /벤치마크 명령/);
});

const succeeded = {
  id: "00000000-0000-4000-8000-0000000000e1", state: "SUCCEEDED",
  allocation: { cpuMillicores: 2000, memoryBytes: 4294967296, accelerator: { resourceName: "nvidia.com/gpu", count: 1 } },
  result: { recommendation: { policy: "v1", cpu: { requestMillicores: 460, maximumMillicores: 900 }, memory: { requestBytes: 536870912, maximumBytes: 1073741824 },
    accelerators: [], rationale: [] }, loadEvidence: null, measurement: null, devices: [], limitations: [] },
} as unknown as ProfilingRun;

test("accelerator observation status is shown as the server reports it, and a CPU-only run implies no device failure", () => {
  const withResult = (allocation: unknown, result: unknown) => ({ ...succeeded, allocation, result }) as unknown as ProfilingRun;
  const gpu = succeeded.allocation;
  const unsupported = { status: "UNSUPPORTED", reason: "시험 노드에서 기존 exporter가 보고하는 가속기 장치를 찾지 못했습니다." };
  assert.deepEqual(deviceStatus(withResult(gpu, { devices: [], deviceStatus: unsupported })), unsupported);
  assert.equal(deviceStatus(withResult({ ...gpu, accelerator: null }, { devices: [], deviceStatus: { status: "NOT_REQUESTED", reason: "x" } })), null);
  assert.equal(deviceStatus(withResult({ ...gpu, accelerator: null }, { devices: [] })), null);
  // A result stored before deviceStatus existed is not presented as unsupported, only as unrecorded.
  assert.equal(deviceStatus(withResult(gpu, { devices: [] }))?.status, "UNRECORDED");
});

test("confirmation starts from the recommendation, keeps the minimum empty and turns any edit into MANUAL", () => {
  const draft = confirmDraft(succeeded);
  assert.deepEqual(draft, { cpu: { min: "", request: "460m", max: "900m" }, memory: { min: "", request: "512Mi", max: "1Gi" } });
  assert.deepEqual(requirementBasis(draft, succeeded), { cpu: "RECOMMENDED", memory: "RECOMMENDED" });
  assert.deepEqual(confirmationDocument(draft, succeeded), {
    runId: succeeded.id,
    cpu: { minimumMillicores: null, requestMillicores: 460, maximumMillicores: 900, basis: "RECOMMENDED" },
    memory: { minimumBytes: null, requestBytes: 536870912, maximumBytes: 1073741824, basis: "RECOMMENDED" },
    accelerators: [{ resourceName: "nvidia.com/gpu", count: 1, basis: "TEST_ALLOCATION" }],
  });
  const edited = { ...draft, cpu: { ...draft.cpu, request: "500m" }, memory: { ...draft.memory, min: "256Mi" } };
  assert.deepEqual(requirementBasis(edited, succeeded), { cpu: "MANUAL", memory: "MANUAL" });
  const noMax = { ...draft, cpu: { ...draft.cpu, max: "" } };
  assert.equal(confirmationDocument(noMax, succeeded).cpu.maximumMillicores, null);
  assert.throws(() => confirmationDocument({ ...draft, cpu: { ...draft.cpu, request: "" } }, succeeded), /CPU 요청/);
  assert.throws(() => confirmationDocument({ ...draft, cpu: { ...draft.cpu, min: "1" } }, succeeded), /최소 ≤ 요청 ≤ 최대/);
  assert.throws(() => confirmationDocument(draft, { ...succeeded, state: "FAILED" }), /성공한 측정만/);
});

test("an ended run still holds the slot until its temporary Pod is confirmed gone", () => {
  const ended = { ...succeeded, cleanup: { state: "PENDING", completedAt: null } } as ProfilingRun;
  assert.equal(isCleaning(ended), true);
  assert.equal(isCleaning({ ...ended, cleanup: { state: "DONE", completedAt: "2026-10-30T06:10:00Z" } } as ProfilingRun), false);
  assert.equal(isCleaning({ ...ended, state: "MEASURING" } as ProfilingRun), false); // a running run is active, not cleaning
});

test("accelerator counts are allocation units of the resource name, and shared GPUs count slots, not devices", () => {
  assert.equal(acceleratorText("nvidia.com/gpu.shared", 2), "nvidia.com/gpu.shared 2 공유 슬롯");
  assert.equal(acceleratorText("nvidia.com/gpu", 1), "nvidia.com/gpu 1 할당 단위");
});

test("the list summary comes only from the confirmed revision", () => {
  assert.equal(confirmedSummary(null), null);
  assert.equal(confirmedSummary({ status: "NOT_READY", reason: "NO_MEASUREMENT", activeRunId: null, latestRunId: null, profile: null }), null);
  const requirements = {
    cpu: { minimumMillicores: null, requestMillicores: 460, maximumMillicores: 900, basis: "RECOMMENDED" },
    memory: { minimumBytes: null, requestBytes: 805306368, maximumBytes: null, basis: "MANUAL" },
    accelerators: [{ resourceName: "nvidia.com/gpu.shared", count: 1, basis: "TEST_ALLOCATION" }],
  };
  assert.deepEqual(
    confirmedSummary({ status: "READY", reason: null, activeRunId: null, latestRunId: null,
      profile: { id: "p", revision: 1, runId: "r", createdAt: "2026-10-30T06:00:00Z", requirements, scope: {} } } as never),
    { request: "460m · 768Mi", accelerators: [{ resourceName: "nvidia.com/gpu.shared", amount: "1 공유 슬롯" }] },
  );
});
