import type { components } from "../lib/api-schema";
import { MAX_CPU_MILLICORES, MAX_MEMORY_BYTES, cpuQuantity, memoryQuantity, parseCpu, parseInteger, parseMemory } from "./resource-profile.ts";

// Model resource measurement (/api/v1/ai-models/{id}/profiling…): a temporary Job runs the registered image under a
// declared test allocation and load; a SUCCEEDED run's observations back a confirmed requirement revision. The server
// re-validates everything; nothing here decides readiness.
type Api = components["schemas"];
export type ProfilingOptions = Api["AiModelProfilingOptions"];
export type ProfilingNode = ProfilingOptions["nodes"][number];
export type ProfilingRun = Api["AiModelProfilingRun"];
export type RunState = ProfilingRun["state"];
export type RunRequest = Api["AiModelProfilingRunRequest"];
export type Allocation = Api["AiModelProfilingAllocation"];
export type ModelResourceProfile = Api["AiModelResourceProfile"];
export type Availability = Api["AiModelAvailability"];
export type RequirementBasis = Api["AiModelRequirementBasis"];
export type LoadType = RunRequest["load"]["type"];
type Range = { min: number; max: number };
/** Run progress while an HTTP load is running; the API leaves it open-ended, so only these counters are read. */
export type Progress = { requests?: number; succeeded?: number; failed?: number };

export const TERMINAL: RunState[] = ["SUCCEEDED", "FAILED", "CANCELLED"];
export const isActive = (run: ProfilingRun) => !TERMINAL.includes(run.state);
/** An ended run whose temporary Job/Pod is not yet confirmed gone still holds the model and node; a new run waits. */
export const isCleaning = (run: ProfilingRun) => !isActive(run) && run.cleanup.state !== "DONE";

/**
 * An accelerator count is in units of that extended resource as the device plugin exposes it, not physical devices:
 * a shared resource (e.g. nvidia.com/gpu.shared) counts time-sliced slots.
 */
export const acceleratorUnit = (resourceName: string) => (/\.shared$/.test(resourceName) ? "공유 슬롯" : "할당 단위");
export const acceleratorText = (resourceName: string, count: number) => `${resourceName} ${count} ${acceleratorUnit(resourceName)}`;
export const ACCELERATOR_UNIT_NOTE = "가속기 수는 그 자원 이름의 할당 단위입니다. 공유 자원(.shared)은 물리 GPU 개수가 아니라 공유 슬롯 수입니다.";

export const STATE_LABEL: Record<RunState, string> = {
  PENDING: "대기",
  STARTING: "Pod 시작 중",
  WARMING_UP: "준비 확인 중",
  MEASURING: "부하 측정 중",
  COLLECTING: "지표 수집 중",
  SUCCEEDED: "측정 성공",
  FAILED: "측정 실패",
  CANCELLED: "취소됨",
};
export const AVAILABILITY_LABEL: Record<string, string> = {
  READY: "사용 가능",
  NO_MEASUREMENT: "측정 전",
  MEASURING: "측정 중",
  MEASUREMENT_FAILED: "측정 실패",
  AWAITING_CONFIRMATION: "확정 대기",
};
export const FAILURE_LABEL: Record<string, string> = {
  UNSCHEDULABLE: "노드에 배치하지 못함",
  IMAGE_PULL_FAILED: "이미지를 받지 못함",
  IMAGE_MISMATCH: "실행된 이미지가 등록 digest와 다름",
  CONTAINER_CONFIG_ERROR: "컨테이너 설정 오류(root 실행 이미지 등)",
  CONTAINER_EXITED: "컨테이너가 중간에 종료됨",
  OOM_KILLED: "메모리 할당 초과(OOMKilled)",
  RESTARTED: "컨테이너 재시작 발생",
  DEADLINE_EXCEEDED: "제한 시간 초과",
  READINESS_TIMEOUT: "준비 요청이 성공하지 않음",
  DRIVER_UNREACHABLE: "측정 서버가 Pod에 연결하지 못함",
  LOAD_FAILED: "부하 요청 성공률·성공 수 미달",
  TOO_SHORT: "실행 시간이 요청 시간보다 짧음",
  COMMAND_FAILED: "벤치마크 명령 실패(exit≠0)",
  DRIVER_INTERRUPTED: "API 재시작으로 부하 중단",
  INSUFFICIENT_SAMPLES: "관측 표본 부족",
  METRICS_UNAVAILABLE: "지표를 조회하지 못함",
  KUBERNETES_UNAVAILABLE: "Kubernetes를 확인하지 못함",
  OWNERSHIP_CONFLICT: "같은 이름의 다른 Job 발견",
  JOB_MISSING: "측정 Job이 사라짐",
};
export const DISABLED_LABEL: Record<string, string> = {
  KUBERNETES_DISABLED: "서버에 Kubernetes 연결이 설정되지 않았습니다.",
  PROMETHEUS_DISABLED: "서버에 Prometheus 연결이 설정되지 않았습니다.",
  PROFILING_DISABLED: "서버에서 모델 측정이 꺼져 있습니다.",
  PLATFORM_NOT_RUNNABLE: "이 이미지 플랫폼은 측정할 수 없습니다.",
};
export const INCOMPATIBLE_LABEL: Record<string, string> = {
  OS_MISMATCH: "OS가 다름",
  ARCHITECTURE_MISMATCH: "이미지 아키텍처와 다름",
  NOT_READY: "준비되지 않음",
};
export const REQUIREMENT_BASIS_LABEL: Record<string, string> = { RECOMMENDED: "측정 추천", MANUAL: "사용자 지정", TEST_ALLOCATION: "시험 할당" };

const CONTENT_TYPES = ["application/json", "text/plain", "application/octet-stream"] as const;

export type RunDraft = {
  nodeName: string;
  cpu: string;
  memory: string;
  acceleratorResource: string;
  acceleratorCount: string;
  durationSeconds: string;
  loadType: LoadType;
  port: string;
  path: string;
  method: string;
  contentType: string;
  /** Text body for application/json and text/plain. */
  body: string;
  /** Standard Base64 of the raw bytes for application/octet-stream (pasted or filled from a file). */
  bodyBase64: string;
  concurrency: string;
  requestTimeoutSeconds: string;
  readinessTimeoutSeconds: string;
  command: string;
  args: string;
};

/** Load and allocation fields start empty; only the server's own timing defaults are filled in. */
export function emptyRunDraft(options: ProfilingOptions | null): RunDraft {
  const l = options?.limits as { [K in "durationSeconds" | "concurrency" | "requestTimeoutSeconds" | "readinessTimeoutSeconds"]: { default?: number } } | undefined;
  const text = (value: number | undefined) => (value == null ? "" : String(value));
  return {
    nodeName: "", cpu: "", memory: "", acceleratorResource: "", acceleratorCount: "1",
    durationSeconds: text(l?.durationSeconds.default ?? 300),
    loadType: "HTTP", port: "", path: "/", method: "POST", contentType: "application/json", body: "", bodyBase64: "",
    concurrency: text(l?.concurrency.default), requestTimeoutSeconds: text(l?.requestTimeoutSeconds.default),
    readinessTimeoutSeconds: text(l?.readinessTimeoutSeconds.default),
    command: "", args: "",
  };
}

const lines = (text: string) => text.split("\n").map((l) => l.trim()).filter(Boolean);
function bounded(label: string, text: string, range: Range, unit = "") {
  const value = parseInteger(text);
  if (value == null || value < range.min || value > range.max) throw new Error(`${label}은(는) ${range.min}–${range.max}${unit} 사이 정수로 입력하세요.`);
  return value;
}

export function runRequest(draft: RunDraft, options: ProfilingOptions): RunRequest {
  const l = options.limits;
  const node = options.nodes.find((n) => n.name === draft.nodeName);
  if (!node) throw new Error("시험 노드를 선택하세요.");
  if (!node.compatible || node.busy || node.status !== "READY") throw new Error(`${node.name} 노드에서는 지금 측정할 수 없습니다.`);
  // Unknown node capacity leaves only the server limit; the server checks the node again.
  const cpuMax = Math.min(l.cpuMillicores.max, node.allocatable.cpuMillicores ?? Infinity, MAX_CPU_MILLICORES);
  const cpu = parseCpu(draft.cpu);
  if (cpu == null || cpu < l.cpuMillicores.min || cpu > cpuMax)
    throw new Error(`CPU 할당은 ${cpuQuantity(l.cpuMillicores.min)}–${cpuQuantity(cpuMax)}로 입력하세요(예 2 또는 2000m).`);
  const memoryMax = Math.min(l.memoryBytes.max, node.allocatable.memoryBytes ?? Infinity, MAX_MEMORY_BYTES);
  const memory = parseMemory(draft.memory);
  if (memory == null || memory < l.memoryBytes.min || memory > memoryMax)
    throw new Error(`메모리 할당은 ${memoryQuantity(l.memoryBytes.min)}–${memoryQuantity(memoryMax)}로 입력하세요(예 4Gi).`);
  let accelerator: Allocation["accelerator"] = null;
  if (draft.acceleratorResource) {
    const offered = node.accelerators.find((a) => a.resourceName === draft.acceleratorResource);
    if (!offered) throw new Error("이 노드에 없는 가속기 자원입니다.");
    const count = bounded("가속기 할당 수", draft.acceleratorCount, { min: l.acceleratorCount.min, max: Math.min(l.acceleratorCount.max, offered.allocatableCount) });
    accelerator = { resourceName: offered.resourceName, count };
  }
  const durationSeconds = bounded("측정 시간", draft.durationSeconds, l.durationSeconds, "초");
  const readinessTimeoutSeconds = bounded("준비 대기", draft.readinessTimeoutSeconds, l.readinessTimeoutSeconds, "초");
  let load: RunRequest["load"];
  if (draft.loadType === "HTTP") {
    const port = bounded("포트", draft.port, { min: 1, max: 65535 });
    const path = draft.path.trim();
    if (!path.startsWith("/") || path.length > 512 || /[\r\n#]|\.\./.test(path)) throw new Error("요청 경로는 /로 시작하고 512자 이하이며 # · .. · 줄바꿈을 쓸 수 없습니다.");
    const method = l.methods.find((m) => m === draft.method);
    if (!method) throw new Error("요청 방식을 선택하세요.");
    // The server refuses content metadata on GET and takes binary bodies only as bodyBase64.
    let body: Pick<Extract<RunRequest["load"], { type: "HTTP" }>, "contentType" | "body" | "bodyBase64"> = {};
    if (method === "POST") {
      const contentType = CONTENT_TYPES.find((c) => c === draft.contentType && l.contentTypes.includes(c));
      if (!contentType) throw new Error("본문 형식을 선택하세요.");
      const maxKiB = Math.floor(l.bodyMaxBytes / 1024);
      if (contentType === "application/octet-stream") {
        const encoded = draft.bodyBase64.replace(/\s+/g, "");
        if (!encoded) throw new Error("POST 요청에는 실제 추론 입력 본문이 필요합니다. 바이너리 본문은 파일을 고르거나 Base64로 입력하세요.");
        const bytes = base64Bytes(encoded);
        if (bytes == null) throw new Error("바이너리 본문은 표준 Base64(A–Z a–z 0–9 + /, = 채움)로 입력하세요.");
        if (bytes > l.bodyMaxBytes) throw new Error(`요청 본문은 디코딩 후 ${maxKiB} KiB 이하여야 합니다.`);
        body = { contentType, bodyBase64: encoded };
      } else {
        if (!draft.body.trim()) throw new Error("POST 요청에는 실제 추론 입력 본문이 필요합니다.");
        if (draft.body.includes("\0")) throw new Error("텍스트 본문에는 NUL 문자를 쓸 수 없습니다. 바이너리는 application/octet-stream으로 보내세요.");
        if (new TextEncoder().encode(draft.body).length > l.bodyMaxBytes) throw new Error(`요청 본문은 ${maxKiB} KiB 이하로 입력하세요.`);
        body = { contentType, body: draft.body };
      }
    }
    load = {
      type: "HTTP", port, path, method,
      ...body,
      concurrency: bounded("동시 요청", draft.concurrency, l.concurrency),
      requestTimeoutSeconds: bounded("요청 제한 시간", draft.requestTimeoutSeconds, l.requestTimeoutSeconds, "초"),
      readinessTimeoutSeconds,
    };
  } else {
    const command = lines(draft.command), args = lines(draft.args);
    if (!command.length) throw new Error("이미지 안에서 부하를 주는 벤치마크 명령을 입력하세요.");
    if (command.length + args.length > 32 || [...command, ...args].some((v) => v.length > 4096 || v.includes("\0")))
      throw new Error("명령·인자는 합쳐 32줄, 줄마다 4096자 이하입니다.");
    load = { type: "COMMAND", command, args, readinessTimeoutSeconds };
  }
  return { nodeName: node.name, allocation: { cpuMillicores: cpu, memoryBytes: memory, accelerator }, durationSeconds, load };
}

/** Decoded byte count of canonical standard Base64 (what the server's decoder accepts), or null when it is not. */
export function base64Bytes(encoded: string): number | null {
  if (!encoded || encoded.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) return null;
  return (encoded.length / 4) * 3 - (encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0);
}
/** Standard Base64 of raw bytes, chunked so a 64 KiB body does not overflow the argument list. */
export function encodeBase64(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

export type ConfirmDraft = { cpu: { min: string; request: string; max: string }; memory: { min: string; request: string; max: string } };

/** The server's recommendation fills request/maximum; the minimum stays empty because one run cannot establish it. */
export function confirmDraft(run: ProfilingRun): ConfirmDraft {
  const r = run.result?.recommendation;
  return {
    cpu: { min: "", request: r ? cpuQuantity(r.cpu.requestMillicores) : "", max: r ? cpuQuantity(r.cpu.maximumMillicores) : "" },
    memory: { min: "", request: r ? memoryQuantity(r.memory.requestBytes) : "", max: r ? memoryQuantity(r.memory.maximumBytes) : "" },
  };
}

function confirmed(label: string, draft: { min: string; request: string; max: string }, parse: (t: string) => number | null, hint: string) {
  const read = (text: string, name: string, required: boolean) => {
    if (!text.trim()) {
      if (required) throw new Error(`${label} ${name}을(를) 입력하세요(${hint}).`);
      return null;
    }
    const value = parse(text);
    if (value == null) throw new Error(`${label} ${name}이(가) 올바르지 않습니다(${hint}).`);
    return value;
  };
  const min = read(draft.min, "최소", false), request = read(draft.request, "요청", true)!, max = read(draft.max, "최대", false);
  if ((min != null && min > request) || (max != null && request > max)) throw new Error(`${label}은(는) 최소 ≤ 요청 ≤ 최대여야 합니다.`);
  return { min, request, max };
}

/** Basis follows the contract: RECOMMENDED only when request and maximum equal the run's recommendation and no minimum is set. */
export function requirementBasis(draft: ConfirmDraft, run: ProfilingRun): { cpu: RequirementBasis; memory: RequirementBasis } {
  const r = run.result?.recommendation;
  const same = (d: ConfirmDraft["cpu"], parse: (t: string) => number | null, request?: number, maximum?: number) =>
    !d.min.trim() && request != null && parse(d.request) === request && parse(d.max) === maximum;
  return {
    cpu: same(draft.cpu, parseCpu, r?.cpu.requestMillicores, r?.cpu.maximumMillicores) ? "RECOMMENDED" : "MANUAL",
    memory: same(draft.memory, parseMemory, r?.memory.requestBytes, r?.memory.maximumBytes) ? "RECOMMENDED" : "MANUAL",
  };
}

export function confirmationDocument(draft: ConfirmDraft, run: ProfilingRun) {
  if (run.state !== "SUCCEEDED") throw new Error("성공한 측정만 요구량으로 확정할 수 있습니다.");
  const cpu = confirmed("CPU", draft.cpu, parseCpu, "예 250m 또는 0.25");
  const memory = confirmed("메모리", draft.memory, parseMemory, "예 512Mi");
  const basis = requirementBasis(draft, run);
  const accelerator = run.allocation.accelerator;
  return {
    runId: run.id,
    cpu: { minimumMillicores: cpu.min, requestMillicores: cpu.request, maximumMillicores: cpu.max, basis: basis.cpu },
    memory: { minimumBytes: memory.min, requestBytes: memory.request, maximumBytes: memory.max, basis: basis.memory },
    // Only the tested allocation can be confirmed; another accelerator configuration needs its own run.
    accelerators: accelerator ? [{ resourceName: accelerator.resourceName, count: accelerator.count, basis: "TEST_ALLOCATION" as const }] : [],
  };
}

export type DeviceStatus = NonNullable<NonNullable<ProfilingRun["result"]>["deviceStatus"]>;
export const DEVICE_STATUS_LABEL: Record<string, string> = { UNSUPPORTED: "가속기 장치 지표 없음", OBSERVED: "가속기 장치 관측", UNRECORDED: "가속기 관측 상태 미기록" };
/**
 * What the result says about accelerator observation. A run without an accelerator says nothing (no failure is
 * implied); with one, the server's status and reason are shown as given, also when devices is empty, so the tested
 * allocation count is never read as an observed requirement.
 */
export function deviceStatus(run: ProfilingRun): { status: string; reason: string } | null {
  const status = run.result?.deviceStatus;
  if (status) return status.status === "NOT_REQUESTED" ? null : status;
  if (!run.allocation.accelerator || run.result?.devices?.length) return null;
  return { status: "UNRECORDED", reason: "이 측정 결과에는 장치 관측 상태가 기록되지 않았습니다. 가속기 수량은 시험 할당값일 뿐 관측한 요구량이 아닙니다." };
}

export const allocationText = (a: Allocation) =>
  [`CPU ${cpuQuantity(a.cpuMillicores)}`, `메모리 ${memoryQuantity(a.memoryBytes)}`, ...(a.accelerator ? [acceleratorText(a.accelerator.resourceName, a.accelerator.count)] : [])].join(" · ");

/** The list shows only the confirmed requirement; registration-time values are never mixed in. */
export function confirmedSummary(availability: Availability | null | undefined) {
  const r = availability?.profile?.requirements;
  if (!r) return null;
  return {
    request: `${cpuQuantity(r.cpu.requestMillicores)} · ${memoryQuantity(r.memory.requestBytes)}`,
    accelerators: r.accelerators.map((a) => ({ resourceName: a.resourceName, amount: `${a.count} ${acceleratorUnit(a.resourceName)}` })),
  };
}
export const loadText = (load: ProfilingRun["load"]) =>
  load.type === "HTTP"
    ? `HTTP ${load.method ?? ""} :${load.port ?? ""}${load.path ?? ""} · 동시 ${load.concurrency ?? "—"}`
    : `명령 ${[...(load.command ?? []), ...(load.args ?? [])].join(" ")}`;
