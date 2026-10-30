import type { components } from "./live-api-schema";

type Schema = components["schemas"];
export type Lab = Schema["OffloadLabState"];
export type LabSession = Schema["OffloadLabSession"];
export type OffloadPolicy = NonNullable<Schema["OffloadPolicy"]>;

/** Editable test preset; every field is sent as entered, nothing is filled in silently. */
export type NumericField = Exclude<keyof LabForm, "edgeNodeId">;
export interface LabForm {
  edgeNodeId: string;
  sourceCpuPercent: number;
  returnCpuPercent: number;
  targetMaxCpuPercent: number;
  targetMaxMemoryPercent: number;
  consecutiveSamples: number;
  maxSampleAgeSeconds: number;
  maxGapSeconds: number;
  minRunningSeconds: number;
  cooldownSeconds: number;
  maxTransfers: number;
  workers: number;
  durationSeconds: number;
}

/** Displayed values of a policy (the session's fixed one, or the preset). */
function policyFields(offload: OffloadPolicy) {
  const node = offload.nodePlacement!;
  return {
    sourceCpuPercent: node.sourceCpuPercent ?? 50,
    returnCpuPercent: node.returnToEdge?.cpuPercent ?? 20,
    targetMaxCpuPercent: node.targetMaxCpuPercent,
    targetMaxMemoryPercent: node.targetMaxMemoryPercent,
    consecutiveSamples: offload.consecutiveSamples,
    maxSampleAgeSeconds: offload.maxSampleAgeSeconds,
    maxGapSeconds: offload.maxGapSeconds,
    minRunningSeconds: offload.minRunningSeconds,
    cooldownSeconds: offload.cooldownSeconds,
    maxTransfers: offload.maxTransfers,
  };
}

/**
 * Locked inputs of an open session as the server stored them: its policy and edge, and the running load's settings. Empty
 * without an open session, so an editable draft is never overwritten by polling.
 */
export function lockedFields(lab: Lab): Partial<LabForm> {
  const session = lab.session;
  if (!session || session.state === "STOPPED") return {};
  const policy = session.policy as OffloadPolicy | null;
  return {
    edgeNodeId: session.edgeNodeId,
    ...(policy?.nodePlacement ? policyFields(policy) : {}),
    ...(session.load.desired === "RUNNING" && session.load.workers != null && session.load.seconds != null
      ? { workers: session.load.workers, durationSeconds: session.load.seconds }
      : {}),
  };
}

export function formFrom(lab: Lab): LabForm {
  return {
    edgeNodeId: "",
    ...policyFields(lab.defaults.offload as OffloadPolicy),
    workers: lab.defaults.load.workers,
    durationSeconds: lab.defaults.load.durationSeconds,
    ...lockedFields(lab),
  };
}

/**
 * One Idempotency-Key per intended action: the same key while the request is retried (also after a failure), a fresh one
 * once the server confirmed it, so a later start after stop/expiry is a new request rather than a replay.
 */
export function actionKeys(create: () => string) {
  const keys = new Map<string, string>();
  return {
    key(name: string) {
      if (!keys.has(name)) keys.set(name, create());
      return keys.get(name)!;
    },
    settle(name: string) {
      keys.delete(name);
    },
    clear() {
      keys.clear();
    },
  };
}

const ranges: [NumericField, string, number, number][] = [
  ["sourceCpuPercent", "edge 과부하 CPU", 1, 100],
  ["returnCpuPercent", "복귀 CPU", 1, 100],
  ["targetMaxCpuPercent", "worker CPU 상한", 1, 100],
  ["targetMaxMemoryPercent", "worker 메모리 상한", 1, 100],
  ["consecutiveSamples", "연속 표본 수", 2, 6],
  ["maxSampleAgeSeconds", "표본 최대 나이", 10, 180],
  ["maxGapSeconds", "표본 간격 상한", 5, 120],
  ["minRunningSeconds", "최소 실행 시간", 10, 3600],
  ["cooldownSeconds", "전환 후 대기", 10, 3600],
  ["maxTransfers", "최대 전환 횟수", 1, 8],
  ["workers", "부하 프로세스 수", 1, 16],
  ["durationSeconds", "부하 시간", 30, 1800],
];

/** The same bounds the API enforces, reported before sending. */
export function problem(form: LabForm): string {
  if (!form.edgeNodeId) return "edge 노드를 선택하세요.";
  for (const [key, label, min, max] of ranges) {
    const value = form[key];
    if (!Number.isInteger(value) || value < min || value > max)
      return `${label}은 ${min}–${max} 사이 정수로 입력하세요.`;
  }
  if (form.returnCpuPercent >= form.sourceCpuPercent)
    return "복귀 CPU는 edge 과부하 CPU보다 낮아야 합니다 (흔들림 방지).";
  if (form.maxTransfers < 2)
    return "왕복(이동 후 복귀)을 보려면 최대 전환 횟수를 2 이상으로 두세요.";
  return "";
}

export function policyFrom(lab: Lab, form: LabForm): OffloadPolicy {
  const base = lab.defaults.offload as OffloadPolicy;
  return {
    ...base,
    consecutiveSamples: form.consecutiveSamples,
    maxSampleAgeSeconds: form.maxSampleAgeSeconds,
    maxGapSeconds: form.maxGapSeconds,
    minRunningSeconds: form.minRunningSeconds,
    cooldownSeconds: form.cooldownSeconds,
    maxTransfers: form.maxTransfers,
    nodePlacement: {
      ...base.nodePlacement!,
      sourceCpuPercent: form.sourceCpuPercent,
      targetMaxCpuPercent: form.targetMaxCpuPercent,
      targetMaxMemoryPercent: form.targetMaxMemoryPercent,
      returnToEdge: { cpuPercent: form.returnCpuPercent, memoryPercent: null, gpuPercent: null },
    },
  };
}

export const loadRunning = (session: LabSession | null | undefined) =>
  session?.load.desired === "RUNNING";
/** The model Run ended on its own or was stopped; the server then stops the load and refuses a new one. */
export const runFinished = (session: LabSession | null | undefined) =>
  ["SUCCEEDED", "FAILED", "CANCELLED"].includes(session?.runState ?? "");

const cleanup: Record<string, string> = { ACTIVE: "정리 대기", STOPPING: "정리 중 (Pod 종료 확인 대기)", STOPPED: "종료됨" };
/**
 * Session status for display. A finished Run is never shown as running, even before the server moved the session on; cleanup
 * is only reported done once the server observed it (STOPPED).
 */
export function sessionStatus(session: LabSession) {
  if (session.runState === "FAILED") return `모델 실패 · ${cleanup[session.state] ?? session.state}`;
  if (runFinished(session) && session.state !== "STOPPED") return `모델 종료 · ${cleanup[session.state] ?? session.state}`;
  return session.state === "ACTIVE" ? "실행 중" : (cleanup[session.state] ?? session.state);
}

/** Why the model Run failed, from its latest attempt; null unless the Run FAILED. */
export function modelFailure(session: LabSession | null | undefined) {
  if (session?.runState !== "FAILED") return null;
  const last = [...session.attempts].sort((a, b) => b.number - a.number)[0];
  return { reason: last?.failureReason ?? null, attempt: last?.number ?? null, nodeName: last?.nodeName ?? null, at: last?.updatedAt ?? null };
}

/** Which controls make sense now; the server still decides. */
export function controls(lab: Lab | null) {
  const session = lab?.session ?? null;
  const open = session != null && session.state !== "STOPPED";
  const active = session?.state === "ACTIVE";
  return {
    start: !!lab?.enabled && !open,
    stop: active,
    loadStart: active && !runFinished(session) && !loadRunning(session),
    loadStop: !!session && loadRunning(session),
  };
}

/** Where the model is observed to run: only a RUNNING producer counts; anything else is unknown. */
export function placement(session: LabSession | null | undefined): "EDGE" | "WORKER" | "UNKNOWN" {
  if (!session?.currentNodeName) return "UNKNOWN";
  return session.currentNodeName === session.edgeNodeName ? "EDGE" : "WORKER";
}

const triggers: Record<string, string> = {
  NODE_CPU: "edge CPU 과부하 → worker",
  NODE_MEM: "edge 메모리 과부하 → worker",
  NODE_GPU: "edge GPU 과부하 → worker",
  NODE_RETURN: "원래 edge 여유 → 복귀",
  MANUAL: "수동 전환",
};
const states: Record<string, string> = {
  DRAINING: "기존 Pod 종료 대기",
  STARTING: "새 Pod 시작 대기",
  CANCELLING: "취소 중",
  SUCCEEDED: "완료",
  FAILED: "실패",
  CANCELLED: "취소됨",
};
export const triggerLabel = (trigger: string) => triggers[trigger] ?? trigger;
export const stateLabel = (state: string) => states[state] ?? state;

/** Latest transition as text, or null when none happened. */
export function transition(session: LabSession | null | undefined) {
  const latest = session?.operations[0];
  if (!latest) return null;
  return {
    label: triggerLabel(latest.trigger),
    state: stateLabel(latest.state),
    active: ["DRAINING", "STARTING", "CANCELLING"].includes(latest.state),
    reason: latest.failureReason,
  };
}

export const percent = (value: number | null | undefined) =>
  value == null ? "—" : `${value.toFixed(1)}%`;
export const shortId = (value: string | null | undefined) =>
  value ? value.slice(0, 8) : "—";

/** Kubernetes CPU quantity ("4", "3500m") in cores; null when unknown. */
export function cores(cpu: string | null | undefined): number | null {
  const match = /^(\d+(?:\.\d+)?)(m?)$/.exec(cpu?.trim() ?? "");
  if (!match) return null;
  const value = Number(match[1]) / (match[2] ? 1000 : 1);
  return value > 0 ? value : null;
}

/**
 * Lab preset for an edge: enough busy-loop processes (one core each, CPU limit = workers) to put the node about 15 points
 * above the overload threshold from idle, bounded by its cores and the API maximum of 16.
 */
export function presetWorkers(edgeCores: number | null, sourceCpuPercent: number) {
  if (!edgeCores) return 3;
  const wanted = Math.ceil((edgeCores * Math.min(100, sourceCpuPercent + 15)) / 100);
  return Math.max(1, Math.min(16, Math.floor(edgeCores), wanted));
}

/** Expected node CPU with the load; an estimate from core counts, not a measurement. */
export function loadEstimate(workers: number, edgeCores: number | null, currentCpuPercent: number | null | undefined, sourceCpuPercent: number) {
  if (!edgeCores) return null;
  const added = (Math.min(workers, edgeCores) / edgeCores) * 100;
  const expected = Math.min(100, (currentCpuPercent ?? 0) + added);
  return { added, expected, observed: currentCpuPercent != null, insufficient: expected < sourceCpuPercent };
}
