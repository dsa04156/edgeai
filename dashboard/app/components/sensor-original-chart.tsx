"use client";

import type { components } from "../../lib/api-schema";
import { HistoryChart } from "./history-chart";

export function OriginalChart({ history }: { history: components["schemas"]["SensorOriginalHistory"] }) {
  const values = history.items.flatMap(row => typeof row.value === "number" || typeof row.value === "boolean" ? [{ at: Date.parse(row.observedAt), value: Number(row.value), unit: row.unit, boolean: typeof row.value === "boolean", configuration: row.configurationSha256, generation: row.sourceGeneration }] : []).filter(point => Number.isFinite(point.at) && Number.isFinite(point.value)).sort((left, right) => left.at - right.at);
  const intervals = values.slice(1).map((point, index) => point.at - values[index].at).filter(interval => interval > 0).sort((left, right) => left - right);
  const interval = intervals[Math.floor((intervals.length - 1) / 2)];
  const points = values.map((point, index) => ({ ...point, breakBefore: index > 0 && (point.at - values[index - 1].at > interval * 3 || point.configuration !== values[index - 1].configuration || point.generation !== values[index - 1].generation) }));
  if (!points.length) return null;
  return <HistoryChart key={`${history.deviceUid}/${history.property}`} data={{ name: history.property, from: Date.parse(history.from), to: Date.parse(history.to), points, connected: true, showSymbol: false }} label={`${history.property} 원본 그래프, ${points.length}개 기록`} caption={`${points.length}개 원본 측정값 · 시간순 표시`} />;
}
