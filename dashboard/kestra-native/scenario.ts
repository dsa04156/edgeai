// Display rules of the GPU/NPU comparison page; values come only from the server run record.
type Summary = {
  target: string;
  processed: number;
  succeeded: number;
  failed: number;
  meanInferenceMs: number | null;
  p50InferenceMs: number | null;
  p95InferenceMs: number | null;
  meanRoundTripMs: number | null;
  computeImagesPerSecond: number | null;
  callWallSeconds: number;
  callWallImagesPerSecond: number | null;
  pairedLabeledCorrect: number | null;
};
type Comparison = {
  targets: Summary[];
  pairedCount: number;
  labelsTrusted: boolean;
};

export const STATE_LABELS: Record<string, string> = {
  QUEUED: "대기 · 런타임 확인 중",
  RUNNING: "실행 중",
  STOPPING: "중지 중 · 처리 중인 1장 완료 대기",
  STOPPED: "중지됨",
  SUCCEEDED: "완료",
  FAILED: "실패",
  INTERRUPTED: "중단됨 · 서버 프로세스 종료",
};

export const isActive = (state: string) =>
  ["QUEUED", "RUNNING", "STOPPING"].includes(state);

export function imageCountProblem(value: number | undefined, max: number) {
  return value !== undefined && Number.isInteger(value) && value >= 1 && value <= max
    ? ""
    : `이미지 수는 1~${max} 사이의 정수로 입력하세요.`;
}

const ms = (value: number | null) => (value == null ? "—" : `${value.toFixed(2)} ms`);
const rate = (value: number | null) => (value == null ? "—" : `${value.toFixed(1)} 장/s`);

export function accuracyText(correct: number | null, paired: number, trusted: boolean) {
  if (!trusted || correct == null) return "라벨 미사용";
  if (paired === 0) return "—";
  return `${correct}/${paired} (${((correct / paired) * 100).toFixed(1)}%)`;
}

export function targetRows(comparison: Comparison) {
  return comparison.targets.map((t) => ({
    id: t.target,
    target: t.target,
    processed: `${t.processed} (성공 ${t.succeeded} · 실패 ${t.failed})`,
    roundTrip: ms(t.meanRoundTripMs),
    compute:
      t.meanInferenceMs == null
        ? "—"
        : `${ms(t.meanInferenceMs)} (p50 ${t.p50InferenceMs!.toFixed(2)} · p95 ${t.p95InferenceMs!.toFixed(2)})`,
    callWallRate: rate(t.callWallImagesPerSecond),
    computeRate: rate(t.computeImagesPerSecond),
    accuracy: accuracyText(t.pairedLabeledCorrect, comparison.pairedCount, comparison.labelsTrusted),
  }));
}

export function coverageNote(
  run: { state: string; requestedCount: number; dispatchedCount: number },
  comparison: { pairedCount: number } | null,
) {
  const parts = [`요청 ${run.requestedCount}장`, `전송 ${run.dispatchedCount}장`];
  if (comparison) parts.push(`양쪽 성공(비교 기준) ${comparison.pairedCount}장`);
  const partial = ["STOPPED", "FAILED", "INTERRUPTED"].includes(run.state) && run.dispatchedCount < run.requestedCount;
  return `${partial ? `${run.state === "STOPPED" ? "중지로" : "종료로"} 일부만 처리됨 · ` : ""}${parts.join(" · ")}`;
}

export const precisionDiffers = (targets: { precision: string }[]) =>
  new Set(targets.map((t) => t.precision)).size > 1;

// Labels of the fixed dataset are the ten Imagenette classes (ImageNet-1k numbering).
const IMAGENETTE: Record<number, string> = {
  0: "tench",
  217: "English springer",
  482: "cassette player",
  491: "chain saw",
  497: "church",
  566: "French horn",
  569: "garbage truck",
  571: "gas pump",
  574: "golf ball",
  701: "parachute",
};
export const className = (index: number | null | undefined) =>
  index == null ? "—" : IMAGENETTE[index] ? `${index} ${IMAGENETTE[index]}` : `${index}`;

/** A missing target result is pending only while the run can still record it; a finished run never will. */
export function outcomeText(
  value: { status: string; top1: number | null; failureCode: string | null } | null,
  runState: string,
) {
  if (!value) return isActive(runState) ? "처리 중" : runState === "INTERRUPTED" ? "결과 없음 · 실행 중단" : "결과 없음";
  return value.status === "SUCCEEDED" ? className(value.top1) : `실패 · ${value.failureCode}`;
}
