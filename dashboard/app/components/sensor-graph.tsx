"use client";

import { useEffect, useRef, useState } from "react";
import type { components } from "../../lib/api-schema";
import { OriginalChart } from "../data-store/data-store-browser";
import { StatusBadge } from "./status-badge";

type Schema = components["schemas"];
type History = Schema["SensorOriginalHistory"];
type Query = { minutes: number; refresh: number; preserve?: boolean; cursor?: string; from?: string; to?: string };
const periods = [[15, "15분"], [60, "1시간"], [360, "6시간"], [1440, "24시간"], [10080, "7일"], [43200, "30일"]] as const;

export function SensorGraph({ device, deviceUid, property, failed }: {
  device?: Schema["ManagedDevice"]; deviceUid: string; property: Schema["ManagedDeviceProperty"]; failed: boolean;
}) {
  const [query, setQuery] = useState<Query>({ minutes: 15, refresh: 0 });
  const [history, setHistory] = useState<History | null>(null), [error, setError] = useState("");
  const [busy, setBusy] = useState(true), [automatic, setAutomatic] = useState(true);
  const sequence = useRef({ value: 0 });
  useEffect(() => {
    const requests = sequence.current, request = ++requests.value, controller = new AbortController();
    const load = async () => {
      setBusy(true);
      if (!query.cursor && !query.preserve) { setHistory(null); setError(""); }
      const to = query.to ?? new Date().toISOString(), from = query.from ?? new Date(Date.parse(to) - query.minutes * 60000).toISOString();
      const params = new URLSearchParams({ from, to, limit: "1000", ...(query.cursor ? { cursor: query.cursor } : {}) });
      try {
        const response = await fetch(`/api/control-plane/data-store/sensors/${encodeURIComponent(deviceUid)}/${encodeURIComponent(property.name)}/history?${params}`, { cache: "no-store", signal: AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]) });
        const rows: History & { message?: string } = await response.json();
        if (!response.ok) throw new Error(rows.message || "센서 원본 이력을 조회하지 못했습니다.");
        if (request === requests.value) { setHistory(previous => query.cursor && previous ? { ...rows, items: [...previous.items, ...rows.items].slice(0, 2000) } : rows); setError(""); }
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
  return <section aria-label="센서 원본 그래프">
    <div className="toolbar"><StatusBadge state={failed ? "UNKNOWN" : device?.state ?? "UNKNOWN"} /><span className="muted">{device ? `${device.id} · ${device.targetNode}` : `센서 UID · ${deviceUid}`}</span></div>
    <p className="graph-latest"><strong>{latest?.value ?? "—"}</strong> {property.unit}<span className="block muted">마지막 수신 · {latest?.timestamp ? new Date(latest.timestamp).toLocaleString() : "미수신"}</span></p>
    <p className="hint">InfluxDB에 저장된 센서 원본을 조회합니다. 페이지를 닫거나 새로고침해도 저장된 이력은 유지됩니다. 원본 보관 기간 {retentionDays}일.</p>
    {failed && <p role="alert" className="error">현재 센서 상태 조회에 실패했습니다. 최신값은 마지막 조회 결과입니다.</p>}
    {!device && <p className="notice">현재 등록에서 이 센서 UID를 찾지 못했습니다. 저장된 원본 이력을 표시합니다.</p>}
    <div className="toolbar"><label>조회 기간<select aria-label="조회 기간" value={query.minutes} onChange={event => setQuery(previous => ({ minutes: Number(event.target.value), refresh: previous.refresh + 1 }))}>{periods.filter(([minutes]) => minutes <= retentionDays * 1440).map(([minutes, label]) => <option key={minutes} value={minutes}>{label}</option>)}</select></label><label className="check-row"><input type="checkbox" checked={automatic} onChange={event => setAutomatic(event.target.checked)} />자동 갱신 · 5초</label><button onClick={() => setQuery(previous => ({ minutes: previous.minutes, refresh: previous.refresh + 1 }))}>그래프 조회</button></div>
    {error && <p role="alert" className="error">{error}{history && " 마지막으로 조회한 기록을 표시합니다."} {automatic ? "5초 후 자동으로 다시 조회합니다." : "저장소 연결을 확인한 뒤 그래프 조회로 다시 시도하세요."}</p>}
    {busy && <p role="status">원본 이력 조회 중…</p>}
    {history && <>{!history.items.length ? <p className="empty">이 기간에 저장된 센서 원본이 없습니다.</p> : <>
      <OriginalChart history={history} />
      {!history.items.some(row => typeof row.value === "number" || typeof row.value === "boolean") && <p className="hint">문자열 원본은 기록 표로 확인하세요.</p>}
      {history.items.some(row => row.quality === "UNVERIFIED") && <p className="notice">이전 Mapper의 기록이 포함되어 있습니다. 수집 설정이 검증되지 않은 원본입니다.</p>}
      <details><summary>기록 표 · {history.items.length}개</summary><div className="table-scroll"><table className="registry-table"><caption className="sr-only">센서 원본 이력</caption><thead><tr><th>측정 시각</th><th>값</th><th>단위</th><th>식별 상태</th></tr></thead><tbody>{history.items.map((row, index) => <tr key={index}><td>{new Date(row.observedAt).toLocaleString()}</td><td>{typeof row.value === "boolean" ? row.value ? "참" : "거짓" : String(row.value)}</td><td>{row.unit || "—"}</td><td>{row.quality === "QUALIFIED" ? "확인됨" : "미검증"}</td></tr>)}</tbody></table></div></details>
      </>}
      {history.nextCursor && history.items.length < 2000 && <button disabled={busy} onClick={() => { setAutomatic(false); setQuery(previous => ({ minutes: previous.minutes, refresh: previous.refresh + 1, cursor: history.nextCursor, from: history.from, to: history.to })); }}>다음 기록</button>}
      {history.truncated && <p className="hint">조회 기간의 일부 기록을 표시합니다.{history.items.length >= 2000 ? " 표시 한도 2000개에 도달했습니다. 기간을 줄여 확인하세요." : " 다음 기록으로 이어서 조회할 수 있습니다. 이어서 조회하면 자동 갱신을 멈춥니다."}</p>}
    </>}
  </section>;
}
