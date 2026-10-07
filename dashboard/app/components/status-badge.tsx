const labels: Record<string, string> = {
  READY: "준비됨", NOT_READY: "준비 안 됨", UNKNOWN: "보고 없음", REMOVED: "제거됨", STALE: "관측 만료",
  ONLINE: "온라인", OFFLINE: "오프라인", RELEASED: "해제됨", REGISTERED: "등록됨", ACTIVE: "활성",
  PENDING: "실행 대기", WAITING: "선행 작업 대기", RUNNING: "실행 중", SUCCEEDED: "완료", FAILED: "실패",
  CANCELLING: "취소 중", CANCELLED: "취소됨", RETRY_WAIT: "재시도 대기", OFFLOADING: "위치 전환 중", SKIPPED: "건너뜀",
};
export function StatusBadge({ state, label }: { state: string; label?: string }) {
  const tone = ["READY", "ONLINE", "SUCCEEDED", "ACTIVE"].includes(state) ? "good" : ["FAILED", "NOT_READY", "OFFLINE"].includes(state) ? "bad" : ["STALE", "RETRY_WAIT", "CANCELLING"].includes(state) ? "warning" : ["RUNNING", "OFFLOADING"].includes(state) ? "running" : "neutral";
  return <span className={`status-badge ${tone}`}><i aria-hidden="true" />{label || labels[state] || state}</span>;
}
