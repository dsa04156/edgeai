"use client";

import { useEffect, useRef, useState } from "react";
import type { components } from "../../lib/api-schema";
import { SensorPeriodChart } from "./sensor-period-chart";
import { StatusBadge } from "./status-badge";

type Schema = components["schemas"];
type History = Schema["SensorOriginalHistory"];
type Query = { minutes: number; refresh: number; preserve?: boolean; cursor?: string; from?: string; to?: string };
const periods = [[1, "1분"], [5, "5분"], [15, "15분"], [60, "1시간"], [360, "6시간"], [1440, "24시간"], [10080, "7일"], [43200, "30일"]] as const;

export function SensorGraph({ device, deviceUid, property, failed }: {
  device?: Schema["ManagedDevice"]; deviceUid: string; property: Schema["ManagedDeviceProperty"]; failed: boolean;
}) {
  const [query, setQuery] = useState<Query>({ minutes: 5, refresh: 0 });
  const [history, setHistory] = useState<History | null>(null), [error, setError] = useState("");
  const [chart, setChart] = useState<Schema["SensorPeriodChart"] | null>(null);
  const [busy, setBusy] = useState(true), [automatic, setAutomatic] = useState(true);
  const sequence = useRef({ value: 0 });
  useEffect(() => {
    const requests = sequence.current, request = ++requests.value, controller = new AbortController();
    const load = async () => {
      setBusy(true);
      if (!query.cursor && !query.preserve) { setHistory(null); setChart(null); setError(""); }
      const to = query.to ?? new Date().toISOString(), from = query.from ?? new Date(Date.parse(to) - query.minutes * 60000).toISOString();
      const params = new URLSearchParams({ from, to, limit: "1000", order: "desc", ...(query.cursor ? { cursor: query.cursor } : {}) });
      try {
        const read = async <T,>(suffix: string, parameters: URLSearchParams): Promise<T> => {
          const response = await fetch(`/api/control-plane/data-store/sensors/${encodeURIComponent(deviceUid)}/${encodeURIComponent(property.name)}/${suffix}?${parameters}`, { cache: "no-store", signal: AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]) });
          const rows = await response.json();
          if (!response.ok) throw new Error(rows.message || "센서 이력을 조회하지 못했습니다.");
          return rows;
        };
        const [rows, chartRows] = await Promise.all([read<History>("history", params), query.cursor ? Promise.resolve(null) : read<Schema["SensorPeriodChart"]>("chart", new URLSearchParams({ from, to }))]);
        if (request === requests.value) { setHistory(previous => query.cursor && previous ? { ...rows, items: [...previous.items, ...rows.items].slice(0, 2000) } : rows); if (chartRows) setChart(chartRows); setError(""); }
      } catch (error) { if (request === requests.value) setError(error instanceof Error ? error.message : "센서 원본 이력을 조회하지 못했습니다."); }
      finally { if (request === requests.value) setBusy(false); }
    };
    void load(); return () => { ++requests.value; controller.abort(); };
  }, [deviceUid, property.name, query]);
  useEffect(() => {
    if (!automatic || busy) return;
    const timer = setTimeout(() => setQuery(previous => ({ minutes: previous.minutes, refresh: previous.refresh + 1, preserve: true })), 5000);
    return () => clearTimeout(timer);
  }, [automatic, busy]);
  const latest = device?.properties[property.name];
  const retentionDays = history?.retentionDays ?? 30;
  const number = latest?.value?.trim() ? Number(latest.value) : NaN;
  const compact = Number.isFinite(number) ? number.toLocaleString(undefined, { maximumSignificantDigits: 6 }) : latest?.value ?? "—";
  const rounded = Number.isFinite(number) && Number(number.toPrecision(6)) !== number;
  return <section aria-label="센서 원본 그래프" className="sensor-graph">
    <div className="sensor-graph-controls"><div className="graph-current"><strong title={`원본 값: ${latest?.value ?? "미수신"}`}>{rounded ? "≈ " : ""}{compact} <small>{property.unit}</small></strong><StatusBadge state={failed ? "UNKNOWN" : device?.state ?? "UNKNOWN"} /></div><label className="sensor-graph-period"><span>조회 기간</span><select aria-label="조회 기간" value={query.minutes} onChange={event => setQuery(previous => ({ minutes: Number(event.target.value), refresh: previous.refresh + 1 }))}>{periods.filter(([minutes]) => minutes <= retentionDays * 1440).map(([minutes, label]) => <option key={minutes} value={minutes}>{label}</option>)}</select></label><label className="check-row"><input type="checkbox" aria-label="자동 갱신 · 5초" checked={automatic} onChange={event => setAutomatic(event.target.checked)} />자동 갱신<span className="sensor-graph-poll-interval">· 5초</span></label><button aria-busy={busy} onClick={() => setQuery(previous => ({ minutes: previous.minutes, refresh: previous.refresh + 1 }))}>그래프 조회</button></div>
    {failed && <p role="alert" className="error">센서 상태 조회 실패 · 마지막 값</p>}
    {!device && <p className="notice">현재 등록 없음 · 저장된 원본 조회</p>}
    {error && <p role="alert" className="error">{error}{history && " · 마지막 기록"}{automatic && " · 자동 재시도"}</p>}
    {busy && <p role="status" className={history ? "sr-only" : "sensor-graph-loading"}>원본 이력 조회 중…</p>}
    {history && <>{!history.items.length ? <p className="empty">조회한 데이터가 없습니다.</p> : <>
      {chart && <SensorPeriodChart chart={chart} unit={property.unit || ""} inspector />}
      <section className="graph-data" aria-label="센서 데이터"><div className="graph-data-heading"><h3>데이터 <span>{history.items.length.toLocaleString()}개</span></h3>{history.nextCursor && history.items.length < 2000 && <button disabled={busy} onClick={() => { setAutomatic(false); setQuery(previous => ({ minutes: previous.minutes, refresh: previous.refresh + 1, cursor: history.nextCursor, from: history.from, to: history.to })); }}>이전 기록</button>}</div><div className="table-scroll graph-data-scroll" tabIndex={0} aria-label="센서 데이터 목록"><table className="registry-table graph-data-table"><caption className="sr-only">센서 원본 이력</caption><colgroup><col style={{ width: "42%" }} /><col style={{ width: "28%" }} /><col style={{ width: "12%" }} /><col style={{ width: "18%" }} /></colgroup><thead><tr><th>측정 시각</th><th>값</th><th>단위</th><th>식별</th></tr></thead><tbody>{history.items.map((row, index) => <tr key={index}><td title={new Date(row.observedAt).toLocaleString()}>{new Date(row.observedAt).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false })}</td><td title={String(row.value)}>{typeof row.value === "boolean" ? row.value ? "참" : "거짓" : String(row.value)}</td><td title={row.unit || undefined}>{row.unit || "—"}</td><td>{row.quality === "QUALIFIED" ? "확인됨" : "미검증"}</td></tr>)}</tbody></table></div>{history.truncated && history.items.length >= 2000 && <p className="hint">목록 최대 2,000개 · 기간을 줄여 조회</p>}</section>
      </>}
    </>}
    <details className="sensor-graph-info"><summary>센서·수집 정보</summary>
      <dl><dt>센서</dt><dd>{device?.id ?? "현재 등록에서 찾지 못함"}</dd><dt>노드</dt><dd>{device?.targetNode ?? "—"}</dd><dt>원본 최신값</dt><dd>{latest?.value ?? "—"} {property.unit}</dd><dt>센서 UID</dt><dd>{deviceUid}</dd><dt>보관 기간</dt><dd>{retentionDays}일</dd></dl>
      {chart?.displayMode === "SELECTED_ORIGINALS" && <p className="hint">표시 원본: 구간별 최초·최저·최고·마지막 실측값</p>}
    </details>
  </section>;
}
