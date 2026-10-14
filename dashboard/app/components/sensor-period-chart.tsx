"use client";

import type { components } from "../../lib/api-schema";
import { HistoryChart } from "./history-chart";

export function SensorPeriodChart({ chart, unit }: { chart: components["schemas"]["SensorPeriodChart"]; unit: string }) {
  const points = chart.items.map(row => ({ at: Date.parse(row.observedAt), value: Number(row.value), boolean: typeof row.value === "boolean", unit }));
  if (!points.length) return <p className="hint">이 기간에 그래프로 표시할 숫자·참/거짓 기록이 없습니다. 원본 기록 표를 확인하세요.</p>;
  return <HistoryChart key={`${chart.deviceUid}/${chart.property}/${Date.parse(chart.to) - Date.parse(chart.from)}`} data={{ name: chart.property, from: Date.parse(chart.from), to: Date.parse(chart.to), points, connected: true, showSymbol: false }} label={`${chart.property} 기간 전체 그래프, 원본 ${points.length.toLocaleString()}개`} caption={<>선택 기간 · 실제 측정값 {points.length.toLocaleString()}개<span className="block">측정된 시각과 값을 그대로 표시합니다.{chart.displayMode === "SELECTED_ORIGINALS" && ` 긴 기간의 원본 ${chart.totalObservations.toLocaleString()}개 중 각 구간의 최초·최저·최고·마지막 실측값을 선택해 표시합니다. 더 자세히 보려면 조회 기간을 줄이거나 원본 기록 표를 확인하세요.`}</span></>} />;
}
