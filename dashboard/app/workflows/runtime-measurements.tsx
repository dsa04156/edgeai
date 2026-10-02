"use client";
import { useEffect, useState } from "react";
import type { components } from "../../lib/api-schema";

type Sample = components["schemas"]["RuntimeTelemetry"];
export function RuntimeMeasurements({ sample, active }: { sample: Sample | null | undefined; active: boolean }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!sample) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [sample]);
  const stale = sample && now - Date.parse(sample.observedAt) > 60000;
  const cpu = sample?.cpuUsageMicros == null ? "미수집" : sample.cpuLimitMillicores == null
    ? `${(sample.cpuUsageMicros / 1000).toFixed(1)}ms 사용 · 제한 미확인`
    : `${(100 * sample.cpuUsageMicros / (sample.intervalMillis * sample.cpuLimitMillicores)).toFixed(1)}% · 설정된 CPU 제한 기준`;
  const memory = sample?.memoryBytes == null ? "미수집" : `${(sample.memoryBytes / 1048576).toFixed(1)} MiB` +
    (sample.memoryLimitBytes == null ? " · 제한 미확인" : ` / ${(sample.memoryLimitBytes / 1048576).toFixed(1)} MiB`);
  return <div role="region" aria-label="실행 측정">
    <h3>실행 측정</h3>
    {!sample ? <p className="muted">현재 시도의 측정값이 아직 없습니다.</p> : <>
      <p className="hint">{!active ? "실행이 종료된 시도의 마지막 측정" : stale ? "측정 만료 · 60초 이상 새 측정 없음" : "최근 수집된 측정"} · {new Date(sample.observedAt).toLocaleString("ko-KR")}</p>
      <ul className="history-list">
        <li>CPU: {cpu}</li>
        <li>메모리: {memory}</li>
        <li>서비스 지연: {sample.latencyMicros == null ? "미수집" : `${(sample.latencyMicros / 1000).toFixed(2)}ms · 서비스가 보고한 개별 측정`}</li>
      </ul>
      <p className="hint">CPU·메모리는 해당 실행의 cgroup 측정이며 노드의 잔여 자원이 아닙니다. 서비스 지연은 p95/p99가 아닙니다.</p>
    </>}
  </div>;
}
