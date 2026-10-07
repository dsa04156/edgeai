import type { components } from "../../lib/api-schema";
import { accelerators } from "../../lib/hardware";
import { nodeAccelerators } from "../../lib/node-accelerators";
import { isCurrent, type NodeMetric, type NodeMetricsState } from "../../lib/node-metrics";

export function NodeMetricsNotice({ state }: { state: NodeMetricsState }) {
  const status = state.failed ? "UNAVAILABLE" : state.snapshot?.status;
  if (status === "AVAILABLE" && !state.snapshot?.unmappedSeries) return null;
  return <p className={`metrics-note ${status === "UNAVAILABLE" ? "metrics-unavailable" : ""}`} role="status">
    {status === "AVAILABLE" ? ""
      : status === "DISABLED" ? "사용량 모니터링 연결 대기"
      : status === "UNAVAILABLE" ? "사용량을 불러오지 못했습니다. 자동으로 다시 조회합니다."
      : "사용량을 불러오는 중…"}
    {!!state.snapshot?.unmappedSeries && <span className="block">일부 지표의 노드 연결을 확인할 수 없습니다.</span>}
  </p>;
}

function MetricBar({ label, metric, state }: { label: string; metric?: NodeMetric; state: NodeMetricsState }) {
  const current = metric && isCurrent(metric, state);
  const value = current ? metric.value : undefined;
  return <div className="usage-row" title={metric ? `${new Date(metric.observedAt).toLocaleString("ko-KR")} 수집${current ? "" : " · 수집 상태 확인 필요"}` : "수집된 지표 없음"}>
    <span>{label}</span>
    {value === undefined ? <span className="usage-track unavailable" aria-hidden="true" /> : <meter min={0} max={100} value={value} aria-label={`${label} 사용률`}>{value.toFixed(1)}%</meter>}
    <strong>{value === undefined ? "—" : `${value.toFixed(1)}%`}</strong>
  </div>;
}

function format(metric: NodeMetric) {
  if (metric.unit === "BYTES") return `${(metric.value / 1024 ** 3).toFixed(2)} GiB`;
  return `${metric.value.toFixed(1)} ${metric.unit === "CELSIUS" ? "°C" : "W"}`;
}

export function NodeUsage({ node, state, detailed = false }: {
  node: Pick<components["schemas"]["ExecutionNode"], "name" | "allocatable">;
  state: NodeMetricsState;
  detailed?: boolean;
}) {
  const item = state.snapshot?.items.find(item => item.nodeName === node.name);
  const measurements = item?.measurements ?? [];
  const host = (key: NodeMetric["key"]) => measurements.find(metric => metric.key === key);
  const devices = nodeAccelerators(item, state);
  const hardware = accelerators(node);
  const missingKinds = ["GPU", "NPU"].filter(kind => !devices.some(device => device.kind === kind)
    && hardware.some(([key]) => kind === "GPU" ? key.includes("gpu") : /npu|hailo/.test(key)));
  const stale = measurements.some(metric => !isCurrent(metric, state));
  const lastObserved = measurements.length ? Math.min(...measurements.map(metric => Date.parse(metric.observedAt))) : null;
  return <div className="node-usage" aria-label={`${node.name} 사용량`}>
    <MetricBar label="CPU" metric={host("CPU_USAGE")} state={state} />
    <MetricBar label="메모리" metric={host("MEMORY_USAGE")} state={state} />
    {devices.map(device => {
      const values = device.measurements;
      const utilization = values.find(m => m.key.endsWith("_USAGE"));
      return <div key={device.id} className="accelerator-usage">
        <MetricBar label={device.label} metric={utilization} state={state} />
        {detailed && <div className="usage-details">{values.filter(m => m.unit !== "PERCENT").map(m => <span key={m.key}>
          {m.key.endsWith("_MEMORY_USED") ? "메모리 사용 " : m.key.endsWith("_MEMORY_TOTAL") ? "메모리 총량 " : m.unit === "CELSIUS" ? "온도 " : "전력 "}
          <strong>{isCurrent(m, state) ? format(m) : "—"}</strong>
        </span>)}</div>}
      </div>;
    })}
    {missingKinds.map(kind => <MetricBar key={kind} label={kind} state={state} />)}
    <span className={`usage-timestamp${stale ? " stale" : ""}`}>
      {state.failed || state.snapshot?.status === "UNAVAILABLE" ? "조회 실패" : state.snapshot?.status === "DISABLED" ? "모니터링 연결 대기"
        : !state.snapshot ? "조회 중" : !measurements.length ? "수집된 지표 없음"
        : stale ? "일부 지표 수집 지연 · 상태 확인 필요" : `${new Date(lastObserved!).toLocaleTimeString("ko-KR")} 수집`}
    </span>
  </div>;
}
