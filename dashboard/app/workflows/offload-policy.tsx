"use client";
import type { components } from "../../lib/api-schema";

export type AutomaticOffloadPolicy = NonNullable<components["schemas"]["OffloadPolicy"]>;
const initial: AutomaticOffloadPolicy = { cpuPercent: 90, memoryPercent: 90, latencyMicros: null, consecutiveSamples: 3,
  maxSampleAgeSeconds: 30, maxGapSeconds: 10, minRunningSeconds: 30, cooldownSeconds: 120, maxTransfers: 1, drainTimeoutSeconds: 60, startTimeoutSeconds: 120 };
const thresholds = [
  ["cpuPercent", "CPU 사용률 기준(%)", 100], ["memoryPercent", "메모리 사용률 기준(%)", 100], ["latencyMicros", "서비스 지연 기준(μs)", 600000000],
] as const;
const limits = [
  ["consecutiveSamples", "연속 측정 수", 2, 6], ["maxSampleAgeSeconds", "측정 유효 시간(초)", 10, 60],
  ["maxGapSeconds", "최대 측정 간격(초)", 5, 30], ["minRunningSeconds", "실행 후 판단 대기(초)", 10, 3600],
  ["cooldownSeconds", "전환 후 판단 대기(초)", 10, 3600], ["maxTransfers", "작업별 자동 전환 한도", 1, 8],
  ["drainTimeoutSeconds", "자동 전환 종료 제한(초)", 1, 600], ["startTimeoutSeconds", "자동 전환 시작 제한(초)", 1, 600],
] as const;

export function OffloadPolicyFields({ value, onChange }: { value: AutomaticOffloadPolicy | null; onChange: (value: AutomaticOffloadPolicy | null) => void }) {
  return <fieldset className="publish-fields"><legend>측정 기반 자동 전환</legend>
    <label className="checkbox-row"><input type="checkbox" checked={value !== null} onChange={e => onChange(e.target.checked ? { ...initial } : null)} />자동 노드 전환 사용</label>
    <p className="hint">활성화하면 처음 지정한 노드에서도 다른 호환 노드로 옮길 수 있습니다. 일반 작업은 같은 입력으로 재시작하고, 스트리밍은 연결된 작업을 함께 멈춘 뒤 체크포인트에서 이어갑니다.</p>
    {value && <>
      <p className="hint">아래 기준 중 하나가 연속으로 충족되면 전환합니다. 사용하지 않을 기준은 비우세요. CPU·메모리는 컨테이너 제한 대비 사용률이며, 지연 1,000μs는 1ms입니다.</p>
      <div className="form-row offload-policy-grid">{thresholds.map(([key, label, max]) => <label key={key}>{label}<input type="number" min={1} max={max} step={1} value={value[key] ?? ""} onChange={e => onChange({ ...value, [key]: e.target.value === "" ? null : Number(e.target.value) })} /></label>)}</div>
      <details><summary>판단·반복 전환 제한 설정</summary><div className="form-row offload-policy-grid">{limits.map(([key, label, min, max]) => <label key={key}>{label}<input type="number" required min={min} max={max} step={1} value={value[key]} onChange={e => onChange({ ...value, [key]: Number(e.target.value) })} /></label>)}</div></details>
      <p className="hint">누락되거나 오래된 측정은 판단하지 않습니다. 이전 실행 노드는 자동 전환 대상에서 제외하며, 전환 후 성능 향상은 별도 확인이 필요합니다.</p>
      <p className="hint">스트리밍은 연결된 작업 모두의 체크포인트·대기 시간·전환 한도를 확인합니다. 기준을 넘은 작업만 배치 정책을 바꾸고, 함께 재시작하는 작업도 전환 횟수를 사용합니다.</p>
    </>}
  </fieldset>;
}

export function OffloadPolicySummary({ value }: { value: AutomaticOffloadPolicy | null | undefined }) {
  if (!value) return <p className="hint">측정 기반 자동 전환 꺼짐</p>;
  const triggers = [value.cpuPercent == null ? null : `CPU ${value.cpuPercent}%`, value.memoryPercent == null ? null : `메모리 ${value.memoryPercent}%`,
    value.latencyMicros == null ? null : `서비스 지연 ${value.latencyMicros / 1000}ms`].filter(Boolean).join(" / ");
  return <p className="hint">자동 전환 켜짐 · {triggers} 이상 연속 {value.consecutiveSamples}회 · 작업별 최대 {value.maxTransfers}회 · 전환 후 {value.cooldownSeconds}초 대기</p>;
}
