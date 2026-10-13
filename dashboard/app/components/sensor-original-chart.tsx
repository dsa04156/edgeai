"use client";

import type { components } from "../../lib/api-schema";
import { HistoryChart } from "./history-chart";

export function OriginalChart({ history }: { history: components["schemas"]["SensorOriginalHistory"] }) {
  const points = history.items.flatMap(row => typeof row.value === "number" || typeof row.value === "boolean" ? [{ at: Date.parse(row.observedAt), value: Number(row.value), unit: row.unit, boolean: typeof row.value === "boolean" }] : []).filter(point => Number.isFinite(point.at) && Number.isFinite(point.value)).sort((left, right) => left.at - right.at);
  if (!points.length) return null;
  return <HistoryChart key={`${history.deviceUid}/${history.property}`} data={{ name: history.property, from: Date.parse(history.from), to: Date.parse(history.to), points, connected: true, showSymbol: false }} label={`${history.property} 원본 그래프, ${points.length}개 기록`} caption={`${points.length}개 원본 측정값 · 시간순 표시`} />;
}
