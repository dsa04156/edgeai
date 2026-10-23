// Display rules of the Hawkeye warehouse-safety page; values come only from the stored run record.
type Result = {
  category: string | null;
  matchesReference: boolean | null;
  processor: string;
  sizeBytes: number;
  sizeVramBytes: number;
  inferenceMs: number;
  loadMs: number;
  roundTripMs: number;
};

export const STATE_LABELS: Record<string, string> = {
  QUEUED: "대기 · 런타임 확인 중",
  RUNNING: "추론 중",
  SUCCEEDED: "완료",
  FAILED: "실패",
  INTERRUPTED: "중단됨 · 서버 프로세스 종료",
};

export const CATEGORY_LABELS: Record<string, string> = {
  spill: "바닥 누출",
  forklift_violation: "지게차 위반",
  improper_stacking: "부적절 적재",
  obstacle: "통로 장애물",
  safe: "위험 없음",
};

export const isActive = (state: string) => ["QUEUED", "RUNNING"].includes(state);

/** A missing category means the model output could not be read; it is never shown as safe. */
export const categoryText = (result: Pick<Result, "category">) =>
  result.category == null
    ? "범주 없음 · 판정 실패"
    : `${CATEGORY_LABELS[result.category] ?? result.category} (${result.category})`;

export function referenceText(result: Pick<Result, "category" | "matchesReference">, reference: string) {
  if (result.category == null || result.matchesReference == null) return `참조 라벨 ${reference} · 비교 불가`;
  return `참조 라벨 ${reference}${result.matchesReference ? "과 일치" : "과 다름"}`;
}

export const timingText = (result: Pick<Result, "inferenceMs" | "loadMs" | "roundTripMs">) =>
  `${result.inferenceMs.toFixed(1)} ms (Ollama 처리, 로드 ${result.loadMs.toFixed(1)} ms 포함) · API 왕복 ${result.roundTripMs.toFixed(1)} ms`;

const gb = (bytes: number) => (bytes / 1e9).toFixed(2);
export const placementText = (result: Pick<Result, "processor" | "sizeBytes" | "sizeVramBytes">) =>
  `${result.processor} (VRAM ${gb(result.sizeVramBytes)} GB / ${gb(result.sizeBytes)} GB)`;
