"use client";

import type { components } from "../../lib/api-schema";
import { HistoryChart } from "./history-chart";

export function OriginalChart({ history }: { history: components["schemas"]["SensorOriginalHistory"] }) {
  const points = history.items.flatMap(row => typeof row.value === "number" || typeof row.value === "boolean" ? [{ at: Date.parse(row.observedAt), value: Number(row.value), unit: row.unit, boolean: typeof row.value === "boolean" }] : []).filter(point => Number.isFinite(point.at) && Number.isFinite(point.value));
  if (!points.length) return null;
  return <HistoryChart key={`${history.deviceUid}/${history.property}`} data={{ name: history.property, from: Date.parse(history.from), to: Date.parse(history.to), points }} label={`${history.property} 원본 그래프, ${points.length}개 기록`} caption="조회한 원본 관측값입니다. 미수신 구간의 값을 보간하지 않습니다." />;
}
