"use client";

import { useState } from "react";
import type { components } from "../../lib/api-schema";
import { isCurrent, type NodeMetric, type NodeMetricsState } from "../../lib/node-metrics";
import { monitoredNodes } from "../../lib/monitored-nodes";
import { StatusBadge } from "./status-badge";
import type { InfrastructureKind, InfrastructureSnapshot } from "../../lib/infrastructure";

function Usage({ metric, state }: { metric?: NodeMetric; state: NodeMetricsState }) {
  const value = metric && isCurrent(metric, state) ? metric.value : undefined;
  return <div className="resource-usage"><strong>{value === undefined ? "—" : value.toFixed(1)}{value !== undefined && <small>%</small>}</strong>
    {value !== undefined && <meter min={0} max={100} value={value} aria-label={`사용률 ${value.toFixed(1)}%`} />}</div>;
}

export function MonitoredNodes({ state, inventory = [], search = "", infrastructure, kind }: {
  state: NodeMetricsState;
  inventory?: components["schemas"]["ExecutionNode"][];
  search?: string;
  infrastructure?: InfrastructureSnapshot | null;
  kind?: InfrastructureKind;
}) {
  const [filter, setFilter] = useState("ALL");
  const rows = monitoredNodes(state, inventory, infrastructure).filter(row => !kind || row.kind === kind);
  const visible = rows.filter(row => (filter === "ALL" || row.kinds.includes(filter))
    && `${row.name} ${row.kinds.join(" ")} ${row.devices.map(d => d.label).join(" ")}`.toLowerCase().includes(search.toLowerCase()));
  return <>
    <div className="resource-toolbar">
      <div className="filter-chips" role="group" aria-label="모니터링 노드 필터">
        {[["ALL", `전체 ${rows.length}`], ["GPU", "GPU"], ["NPU", "NPU"]].map(([value, label]) =>
          <button key={value} aria-pressed={filter === value} onClick={() => setFilter(value)}>{label}</button>)}
      </div>
    </div>
    <div className="table-scroll resource-table-wrap"><table className="resource-table"><caption className="sr-only">노드별 실측 자원 사용량</caption>
      <thead><tr><th scope="col">디바이스</th><th scope="col">상태</th><th scope="col">CPU</th><th scope="col">메모리</th><th scope="col">GPU / NPU</th><th scope="col">온도 · 전력</th><th scope="col">수집 상태</th></tr></thead>
      <tbody>{visible.map(row => {
        const measurements = !state.failed && state.snapshot?.status === "AVAILABLE" ? state.snapshot.items.find(item => item.nodeName === row.name)?.measurements ?? [] : [];
        const devices = row.devices;
        const fresh = measurements.length > 0 && measurements.every(metric => isCurrent(metric, state));
        const oldest = measurements.length ? Math.min(...measurements.map(m => Date.parse(m.observedAt))) : null;
        const physical = measurements.filter(m => m.unit === "CELSIUS" || m.unit === "WATTS");
        return <tr key={row.name}>
          <th scope="row"><strong>{row.name}</strong>{row.node && <small>{row.node.architecture} · {row.node.operatingSystem}</small>}</th>
          <td data-label="상태"><StatusBadge state={row.status} label={row.status === "READY" ? "Ready" : row.status === "NOT_READY" ? "NotReady" : "Unknown"} /></td>
          <td data-label="CPU"><Usage metric={measurements.find(m => m.key === "CPU_USAGE")} state={state} /></td>
          <td data-label="메모리"><Usage metric={measurements.find(m => m.key === "MEMORY_USAGE")} state={state} /></td>
          <td data-label="GPU / NPU" className="resource-accelerators">{devices.length ? devices.map(device => {
            const values = device.measurements;
            const used = values.find(m => m.key.endsWith("MEMORY_USED"));
            const total = values.find(m => m.key.endsWith("MEMORY_TOTAL"));
            const memory = used && isCurrent(used, state) ? `${(used.value / 1024 ** 3).toFixed(2)}${total && isCurrent(total, state) ? ` / ${(total.value / 1024 ** 3).toFixed(2)}` : ""} GiB` : null;
            return <div className="resource-device" key={device.id}><span>{device.label}</span><Usage metric={values.find(m => m.key.endsWith("_USAGE"))} state={state} />{memory && <small>{memory}</small>}</div>;
          }) : <span className="muted">GPU / NPU 미수집</span>}</td>
          <td data-label="온도 · 전력">{physical.length ? physical.map(m => <span className="physical-metric" key={`${m.key}/${m.device}`}>{m.key.split("_")[0]} {isCurrent(m, state) ? `${m.value.toFixed(1)} ${m.unit === "CELSIUS" ? "°C" : "W"}` : "—"}</span>) : <span className="muted">미수집</span>}</td>
          <td data-label="수집 상태"><StatusBadge state={fresh ? "ONLINE" : "STALE"} label={fresh ? "수집 정상" : "수집 지연"} /><small>{oldest ? new Date(oldest).toLocaleTimeString("ko-KR") : "미수집"}</small></td>
        </tr>;
      })}</tbody>
    </table></div>
    {!visible.length && <p className="empty">조건에 맞는 모니터링 노드가 없습니다.</p>}
  </>;
}
