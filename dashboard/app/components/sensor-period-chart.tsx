"use client";

import type { components } from "../../lib/api-schema";
import { HistoryChart } from "./history-chart";

export function SensorPeriodChart({ chart, unit, boolean = false }: { chart: components["schemas"]["SensorPeriodChart"]; unit: string; boolean?: boolean }) {
  const points = chart.items.map(row => ({ at: (Date.parse(row.from) + Date.parse(row.to)) / 2, value: row.average, min: row.min, max: row.max, count: row.count, bucketFrom: Date.parse(row.from), bucketTo: Date.parse(row.to), unit }));
  if (!points.length) return <p className="hint">이 기간에 그래프로 표시할 숫자·참/거짓 기록이 없습니다. 원본 기록 표를 확인하세요.</p>;
  const count = chart.items.reduce((sum, row) => sum + row.count, 0), seconds = chart.aggregation.bucketSeconds;
  const interval = seconds < 60 ? `${seconds}초` : seconds % 3600 === 0 ? `${seconds / 3600}시간` : seconds % 60 === 0 ? `${seconds / 60}분` : `${seconds}초`;
  return <HistoryChart key={`${chart.deviceUid}/${chart.property}/${Date.parse(chart.to) - Date.parse(chart.from)}`} data={{ name: chart.property, from: Date.parse(chart.from), to: Date.parse(chart.to), points, connected: true, showSymbol: false }} label={`${chart.property} 기간 전체 그래프, 원본 ${count.toLocaleString()}개`} caption={<>선택 기간 전체 · 원본 {count.toLocaleString()}개 · {interval} 구간 평균<span className="block">옅은 선은 구간 최솟값·최댓값입니다. 정확한 개별 값은 원본 기록 표에서 확인합니다.{boolean && " 참/거짓의 평균은 참 비율(0~1)입니다."}</span></>} />;
}
