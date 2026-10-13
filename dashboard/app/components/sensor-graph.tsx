"use client";

import type { components } from "../../lib/api-schema";
import { useEffect, useState } from "react";
import type { SensorObservation } from "../../lib/sensor-observations";
import { StatusBadge } from "./status-badge";

type Schema = components["schemas"];
const number = (value: number) => Number(value.toPrecision(8)).toLocaleString();

export function SensorGraph({ device, property, points, failed }: {
  device?: Schema["ManagedDevice"]; property: Schema["ManagedDeviceProperty"]; points: SensorObservation[]; failed: boolean;
}) {
  const [width, setWidth] = useState(680);
  useEffect(() => { const media = window.matchMedia("(max-width: 700px)"); const update = () => setWidth(media.matches ? 340 : 680); update(); media.addEventListener("change", update); return () => media.removeEventListener("change", update); }, []);
  const latest = device?.properties[property.name];
  const values = points.map(point => point.value);
  const minimum = Math.min(...values), maximum = Math.max(...values);
  const bottom = minimum === maximum ? minimum - 1 : minimum;
  const top = minimum === maximum ? maximum + 1 : maximum;
  const from = points[0]?.at ?? 0, to = Math.max(from + 1000, points.at(-1)?.at ?? 0);
  const right = width - 48;
  const x = (at: number) => 64 + (at - from) / (to - from) * (right - 64);
  const y = (value: number) => 180 - (value - bottom) / (top - bottom) * 140;
  const segments: SensorObservation[][] = [];
  for (const point of points) {
    const previous = segments.at(-1)?.at(-1);
    if (!previous || previous.segment !== point.segment || point.at - previous.at > 15000) segments.push([]);
    segments.at(-1)!.push(point);
  }
  return <section aria-label="센서 관측 그래프">
    <div className="toolbar"><StatusBadge state={failed ? "UNKNOWN" : device?.state ?? "UNKNOWN"} /><span className="muted">{device ? `${device.id} · ${device.targetNode}` : "센서 연결 없음"}</span></div>
    <p className="graph-latest"><strong>{latest?.value ?? "—"}</strong> {property.unit}<span className="block muted">마지막 수신 · {latest?.timestamp ? new Date(latest.timestamp).toLocaleString() : "미수신"}</span></p>
    <p className="hint">화면을 연 뒤 수신한 최신값을 최대 30분·360개 표시합니다. 서버에 저장된 센서 이력이 아닙니다. 화면을 나가거나 새로고침하면 초기화됩니다.</p>
    {failed && <p role="alert" className="error">현재 센서 조회에 실패했습니다. 마지막 관측을 표시합니다.</p>}
    {!device && <p role="status" className="notice">이 센서가 현재 목록에 없습니다. 새 관측을 기다립니다.</p>}
    {property.dataType === "string" ? <p className="empty">문자열 측정 항목은 그래프를 표시하지 않습니다. 최신값을 확인하세요.</p>
      : !points.length ? <p className="empty">그래프로 표시할 관측이 없습니다. 연결 후 숫자 또는 참·거짓 값의 수신을 기다립니다.</p>
      : <figure className="continuous-history-chart sensor-live-chart"><svg role="img" aria-label={`${property.name} 실시간 그래프, ${points.length}개 관측`} viewBox={`0 0 ${width} 224`}>
        <title>{property.name} · {property.unit || (property.dataType === "bool" ? "참 1 / 거짓 0" : "값")}</title>
        <path d={`M64 40V180H${right}`} className="continuous-axis" />
        <text x={60} y={44} textAnchor="end">{number(top)}</text><text x={60} y={180} textAnchor="end">{number(bottom)}</text>
        {segments.map((segment, index) => <g key={index}>{segment.length > 1 && <polyline points={segment.map(point => `${x(point.at)},${y(point.value)}`).join(" ")} className="continuous-line" />}{segment.map(point => <circle key={point.at} cx={x(point.at)} cy={y(point.value)} r={3} className="continuous-dot" />)}</g>)}
        <text x={64} y={208}>{new Date(from).toLocaleTimeString()}</text><text x={right} y={208} textAnchor="end">{new Date(to).toLocaleTimeString()}</text>
      </svg><figcaption>수신 시각이 바뀐 값만 추가합니다. 조회 실패·연결 중단·15초 이상 관측 공백은 선을 잇지 않습니다.{property.dataType === "bool" && " 참은 1, 거짓은 0입니다."}</figcaption></figure>}
  </section>;
}
