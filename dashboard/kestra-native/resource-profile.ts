import type { components } from "../lib/api-schema";

// 3-3 resource requirement profile: units, editable draft and request documents. The server re-validates everything.
type Api = components["schemas"];
export type Analysis = Api["ResourceAnalysis"];
export type Usage = Api["ResourceUsage"];
export type Basis = Api["ResourceBasis"];
export type AcceleratorUnit = "COUNT" | "MEBIBYTES" | "PERCENT";

export const MAX_CPU_MILLICORES = 1_000_000;
export const MAX_MEMORY_BYTES = 16 * 1024 ** 4;

export const STATUS_LABEL: Record<string, string> = {
  AVAILABLE: "관측됨",
  NO_DATA: "데이터 없음",
  STALE: "관측 만료",
  INSUFFICIENT: "표본 부족",
  UNSUPPORTED: "미지원",
  AMBIGUOUS: "대상 모호",
  NOT_FOUND: "대상 없음",
  UNAVAILABLE: "조회 불가",
  PARTIAL: "일부 추천",
  INVALID_REPORT: "보고서 오류",
  NOT_REQUESTED: "요청 안 함",
  MEASURED: "실측",
  UNMEASURED: "미측정",
};
export const ATTRIBUTION_LABEL: Record<string, string> = {
  TARGET_ASSOCIATED: "대상 Pod 연관 · 장치 전체",
  NODE_DEVICE_SHARED: "공유 장치 · 귀속 불가",
  NODE_DEVICE_UNKNOWN_OWNER: "장치 전체 · 소유 미확인",
};
export const BASIS_LABEL: Record<Basis, string> = { MEASURED: "실측", RECOMMENDED: "KRR 추천", MANUAL: "사용자 지정" };

const MEMORY_UNITS: [string, number][] = [["Ti", 1024 ** 4], ["Gi", 1024 ** 3], ["Mi", 1024 ** 2], ["Ki", 1024]];

/** Kubernetes CPU quantity of integer millicores (always "Nm", as the handoff contract does). */
export const cpuQuantity = (millicores: number) => `${millicores}m`;
/** Exact Kubernetes memory quantity: the largest binary suffix that divides the byte count. */
export function memoryQuantity(bytes: number) {
  for (const [suffix, unit] of MEMORY_UNITS) if (bytes % unit === 0) return `${bytes / unit}${suffix}`;
  return String(bytes);
}
/** Human reading of an observed value; absent values are "—", never 0. */
export function formatValue(value: number | null | undefined, unit: string) {
  if (value == null || !Number.isFinite(value)) return "—";
  if (unit === "millicores") return `${Math.round(value * 10) / 10}m`;
  if (unit === "percent") return `${Math.round(value * 10) / 10}%`;
  if (unit === "bytes") {
    for (const [suffix, size] of MEMORY_UNITS) if (value >= size) return `${Math.round((value / size) * 10) / 10} ${suffix}B`;
    return `${Math.round(value)} B`;
  }
  return String(value);
}

/** "250m", "0.25" or "2" cores → integer millicores; anything else (NaN, negative, sub-millicore) is invalid. */
export function parseCpu(text: string): number | null {
  const value = text.trim();
  let result: number;
  if (/^[0-9]+m$/.test(value)) result = Number(value.slice(0, -1));
  else if (/^[0-9]+(\.[0-9]{1,3})?$/.test(value)) result = Math.round(Number(value) * 1000);
  else return null;
  return Number.isSafeInteger(result) && result > 0 ? result : null;
}
/** Integer bytes or Ki/Mi/Gi/Ti binary quantities → bytes. Decimal suffixes and fractions are rejected. */
export function parseMemory(text: string): number | null {
  const match = /^([0-9]+)(Ki|Mi|Gi|Ti)?$/.exec(text.trim());
  if (!match) return null;
  const unit = match[2] ? MEMORY_UNITS.find(([suffix]) => suffix === match[2])![1] : 1;
  const result = Number(match[1]) * unit;
  return Number.isSafeInteger(result) && result > 0 ? result : null;
}
export function parseInteger(text: string): number | null {
  const value = text.trim();
  if (!/^[0-9]+$/.test(value)) return null;
  const result = Number(value);
  return Number.isSafeInteger(result) && result > 0 ? result : null;
}

export type RangeDraft = { min: string; request: string; max: string };
export type AcceleratorDraft = RangeDraft & {
  kind: "GPU" | "NPU";
  resourceName: string;
  unit: AcceleratorUnit;
  deviceMemory: string;
  vendor: string;
  models: string;
};
export type ProfileDraft = {
  cpu: RangeDraft;
  memory: RangeDraft;
  accelerators: AcceleratorDraft[];
  architectures: string[];
  runtimeName: string;
  runtimeVersion: string;
  conditions: string;
  note: string;
};
export const emptyRange = (): RangeDraft => ({ min: "", request: "", max: "" });
export const emptyAccelerator = (): AcceleratorDraft => ({
  ...emptyRange(), kind: "GPU", resourceName: "nvidia.com/gpu", unit: "COUNT", deviceMemory: "", vendor: "", models: "",
});
export function emptyDraft(architectures: string[]): ProfileDraft {
  return { cpu: emptyRange(), memory: emptyRange(), accelerators: [], architectures: [...architectures], runtimeName: "", runtimeVersion: "", conditions: "", note: "" };
}

/** Candidate request values the reviewer may adopt; measured p95 is rounded up to whole millicores/bytes. */
export function suggestions(analysis: Analysis | null) {
  const usage = (u: Usage | undefined) => (u && u.status === "AVAILABLE" && u.p95 != null ? Math.ceil(u.p95) : null);
  const recommendation = analysis?.recommendation;
  const recommended = recommendation && ["AVAILABLE", "PARTIAL"].includes(recommendation.status);
  return {
    cpu: { measured: usage(analysis?.measurement.cpu), recommended: recommended ? recommendation!.cpu.requestMillicores ?? null : null },
    memory: { measured: usage(analysis?.measurement.memory), recommended: recommended ? recommendation!.memory.requestBytes ?? null : null },
  };
}

/**
 * The basis is derived, not chosen: a request equal to the cited KRR value is RECOMMENDED, equal to the measured p95
 * is MEASURED, and anything typed or edited is MANUAL, so a manual value can never be labelled measured/recommended.
 */
export function basis(request: number | null, suggestion: { measured: number | null; recommended: number | null }): Basis {
  if (request != null && request === suggestion.recommended) return "RECOMMENDED";
  if (request != null && request === suggestion.measured) return "MEASURED";
  return "MANUAL";
}

export function range(label: string, draft: RangeDraft, parse: (text: string) => number | null, max: number, unitHint: string) {
  const values = [draft.min, draft.request, draft.max].map((text) => parse(text));
  const names = ["최소", "요청", "최대"];
  values.forEach((value, index) => {
    if (value == null) throw new Error(`${label} ${names[index]}값을 ${unitHint}(으)로 입력하세요. 빈 값은 0으로 채우지 않습니다.`);
  });
  const [min, request, upper] = values as number[];
  if (min > request || request > upper) throw new Error(`${label}은 최소 ≤ 요청 ≤ 최대여야 합니다.`);
  if (upper > max) throw new Error(`${label} 최대값이 허용 한도를 넘습니다.`);
  return { min, request, max: upper };
}

export function conditions(text: string): Record<string, string> {
  const result: Record<string, string> = {};
  for (const line of text.split("\n").map((l) => l.trim()).filter(Boolean)) {
    const index = line.indexOf("=");
    const key = index > 0 ? line.slice(0, index).trim() : "";
    if (!/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/.test(key) || !line.slice(index + 1).trim())
      throw new Error(`입력 조건은 한 줄에 key=value 형식으로 입력하세요: ${line.slice(0, 40)}`);
    result[key] = line.slice(index + 1).trim();
  }
  if (Object.keys(result).length > 32) throw new Error("입력 조건은 32개 이하입니다.");
  return result;
}

/** Confirmed GPU/NPU requirements; the server applies the same resource-name, unit and range rules. */
export function acceleratorDocuments(drafts: AcceleratorDraft[]) {
  return drafts.map((a, index) => {
    const label = `가속기 ${index + 1}(${a.resourceName || "자원 이름 없음"})`;
    if (!/^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)+\/[A-Za-z0-9]([-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$/.test(a.resourceName))
      throw new Error(`${label}: vendor 도메인 확장 자원 이름(예 nvidia.com/gpu)을 입력하세요.`);
    const values = range(label, a, parseInteger, a.unit === "PERCENT" ? 100 : 1_048_576, "양의 정수");
    const deviceMemoryBytes = a.deviceMemory.trim() ? parseMemory(a.deviceMemory) : null;
    if (a.deviceMemory.trim() && deviceMemoryBytes == null) throw new Error(`${label}: 장치 메모리 요구량은 bytes 또는 Ki/Mi/Gi로 입력하세요.`);
    return {
      kind: a.kind, resourceName: a.resourceName, unit: a.unit, ...values, deviceMemoryBytes,
      vendor: a.vendor.trim() || null,
      models: a.models.split(",").map((m) => m.trim()).filter(Boolean),
    };
  });
}

export function profileDocument(serviceProfileVersionId: string, analysis: Analysis | null, draft: ProfileDraft): Api["ResourceProfileCreate"] {
  const cpu = range("CPU", draft.cpu, parseCpu, MAX_CPU_MILLICORES, "millicores(예 250m) 또는 core");
  const memory = range("메모리", draft.memory, parseMemory, MAX_MEMORY_BYTES, "bytes 또는 Ki/Mi/Gi");
  const hints = suggestions(analysis);
  const accelerators = acceleratorDocuments(draft.accelerators);
  return {
    serviceProfileVersionId,
    analysisId: analysis?.id ?? null,
    inputConditions: conditions(draft.conditions),
    compatibility: {
      architectures: [...draft.architectures].sort() as ("amd64" | "arm64")[],
      runtime: draft.runtimeName.trim() ? { name: draft.runtimeName.trim(), ...(draft.runtimeVersion.trim() ? { version: draft.runtimeVersion.trim() } : {}) } : null,
    },
    resources: {
      cpu: { minMillicores: cpu.min, requestMillicores: cpu.request, maxMillicores: cpu.max, basis: basis(cpu.request, hints.cpu) },
      memory: { minBytes: memory.min, requestBytes: memory.request, maxBytes: memory.max, basis: basis(memory.request, hints.memory) },
      accelerators,
    },
    note: draft.note.trim() || null,
  };
}

/** Default observation window ending now; presets are hours. */
export function windowEnding(now: Date, hours: number) {
  const end = new Date(Math.floor(now.getTime() / 1000) * 1000);
  return { start: new Date(end.getTime() - hours * 3600_000).toISOString(), end: end.toISOString() };
}
