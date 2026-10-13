"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { components } from "../../lib/api-schema";

type Schema = components["schemas"];
type Sensor = Schema["DataStoreCatalog"]["sensors"][number];
type History = Schema["SensorOriginalHistory"];
const collectionNames: Record<string, string> = { DISABLED: "원본 수집 비활성", COLLECTING: "원본 수집 연결됨", CONNECTING: "수집 연결 중", PARTIAL: "일부 센서 경로 확인 필요", NO_SOURCES: "수집 대상 없음", CATALOG_UNAVAILABLE: "센서 목록 확인 필요", STORE_UNAVAILABLE: "저장 대기" };
const display = (value: string | number | boolean) => typeof value === "boolean" ? value ? "참" : "거짓" : typeof value === "number" ? value.toLocaleString() : value;

async function request<T>(path: string): Promise<T> {
  const response = await fetch(`/api/control-plane/${path}`, { cache: "no-store", signal: AbortSignal.timeout(8000) });
  const value = await response.json();
  if (!response.ok) throw new Error(value.message || "이력을 조회하지 못했습니다.");
  return value;
}

export function OriginalChart({ history }: { history: History }) {
  const [width, setWidth] = useState(680);
  useEffect(() => { const media = window.matchMedia("(max-width: 700px)"); const update = () => setWidth(media.matches ? 340 : 680); update(); media.addEventListener("change", update); return () => media.removeEventListener("change", update); }, []);
  const points = history.items.filter(row => typeof row.value === "number" || typeof row.value === "boolean").map(row => ({ at: Date.parse(row.observedAt), value: Number(row.value) }));
  if (!points.length) return null;
  const values = points.map(row => row.value), low = Math.min(...values), high = Math.max(...values);
  const right = width - 58;
  const x = (at: number) => 85 + (at - Date.parse(history.from)) / Math.max(1, Date.parse(history.to) - Date.parse(history.from)) * (right - 85);
  const y = (value: number) => high === low ? 96 : 156 - (value - low) / (high - low) * 120;
  return <figure className="continuous-history-chart sensor-original-chart"><svg role="img" aria-label={`${history.property} 원본 그래프, ${points.length}개 기록`} viewBox={`0 0 ${width} 200`}><title>{history.property} 원본 관측값</title><path d={`M85 28V156H${right}`} className="continuous-axis" /><text x={81} y={32} textAnchor="end">{high.toPrecision(7)}</text><text x={81} y={156} textAnchor="end">{low.toPrecision(7)}</text>{points.map((point, index) => <circle key={index} cx={x(point.at)} cy={y(point.value)} r={3} className="continuous-dot" />)}<text x={85} y={184}>{new Date(history.from).toLocaleTimeString()}</text><text x={right} y={184} textAnchor="end">{new Date(history.to).toLocaleTimeString()}</text></svg><figcaption>조회한 원본 관측값입니다. 미수신 구간의 값을 보간하지 않습니다.</figcaption></figure>;
}

export function DataStoreBrowser() {
  const [catalog, setCatalog] = useState<Schema["DataStoreCatalog"] | null>(null), [error, setError] = useState("");
  const [loading, setLoading] = useState(true), [selected, setSelected] = useState<Sensor | null>(null), [hours, setHours] = useState(1);
  const [history, setHistory] = useState<History | null>(null), [historyError, setHistoryError] = useState("");
  const [historyBusy, setHistoryBusy] = useState(false);
  const sequences = useRef({ catalog: 0, history: 0 }), historyHeading = useRef<HTMLHeadingElement>(null);
  const refresh = useCallback(() => {
    const sequence = ++sequences.current.catalog;
    return request<Schema["DataStoreCatalog"]>("data-store").then(rows => { if (sequence === sequences.current.catalog) { setCatalog(rows); setError(""); } }, error => { if (sequence === sequences.current.catalog) setError(error instanceof Error ? error.message : "저장소를 확인하세요."); }).finally(() => { if (sequence === sequences.current.catalog) setLoading(false); });
  }, []);
  useEffect(() => { const guard = sequences.current; void refresh(); return () => { ++guard.catalog; ++guard.history; }; }, [refresh]);
  async function load(sensor = selected, next = false) {
    if (!sensor || historyBusy) return;
    const sequence = ++sequences.current.history; setSelected(sensor); setHistoryBusy(true); setHistoryError("");
    const to = next && history ? history.to : new Date().toISOString();
    const from = next && history ? history.from : new Date(Date.parse(to) - Math.min(hours, (catalog?.rawRetentionDays || 30) * 24) * 3600000).toISOString();
    const params = new URLSearchParams({ from, to, limit: "1000", ...(next && history?.nextCursor ? { cursor: history.nextCursor } : {}) });
    if (!next) setHistory(null);
    try {
      const rows = await request<History>(`data-store/sensors/${sensor.deviceUid}/${sensor.property}/history?${params}`);
      if (sequence === sequences.current.history) { setHistory(previous => next && previous ? { ...rows, items: [...previous.items, ...rows.items] } : rows); if (!next) requestAnimationFrame(() => historyHeading.current?.focus()); }
    } catch (error) { if (sequence === sequences.current.history) setHistoryError(error instanceof Error ? error.message : "이력을 조회하지 못했습니다."); }
    finally { if (sequence === sequences.current.history) setHistoryBusy(false); }
  }
  return <><section aria-label="저장소 상태" className="publish-fields"><div className="toolbar"><h2>{error ? "InfluxDB · 상태 확인 필요" : catalog?.status === "READY" ? "InfluxDB · 연결됨" : catalog?.status === "DISABLED" ? "InfluxDB · 연결 전" : "저장소 조회 중"}</h2><button disabled={loading} onClick={() => { setLoading(true); void refresh(); }}>저장소 새로고침</button></div>
    {error && <p className="error" role="alert">{error}{catalog && " 마지막으로 조회한 목록을 표시합니다."}</p>}
    {loading && <p role="status">저장소 조회 중…</p>}
    {catalog && <><p>{error ? "마지막 조회" : collectionNames[catalog.collector.status] || "수집 상태 확인 필요"} · 센서 원본 보관 {catalog.rawRetentionDays}일</p><p className="hint">VD 처리 정의·입력 연결·설정 버전은 PostgreSQL에 보관합니다. 결과 데이터는 VD별 저장 정책에 따라 InfluxDB에 보관합니다. <a href="/virtual-devices">가상 디바이스 결과·처리 버전 조회 →</a></p></>}
  </section>
  {catalog?.status === "READY" && <section aria-label="보관 중인 센서 원본"><h2>센서 원본</h2>{!catalog.sensors.length ? <p className="hint">보관 중인 원본이 없습니다. 수집 상태와 센서의 MQTT 출력 경로를 확인하세요.</p> : <div className="table-scroll"><table className="registry-table"><caption className="sr-only">센서 UID별 보관 중인 원본 항목</caption><thead><tr><th>센서 · 항목</th><th>최근 측정 시각</th><th>식별 상태</th><th>이력</th></tr></thead><tbody>{catalog.sensors.map(sensor => <tr key={`${sensor.deviceUid}:${sensor.property}`}><td><strong>{sensor.deviceId}</strong><span className="block muted">{sensor.property} · {sensor.unit || "단위 없음"}</span></td><td>{new Date(sensor.observedAt).toLocaleString()}</td><td>{sensor.quality === "QUALIFIED" ? "수집 설정 확인됨" : "이전 Mapper · 설정 미검증"}</td><td><button disabled={historyBusy} onClick={() => void load(sensor)} aria-label={`${sensor.deviceId} ${sensor.property} 이력`}>원본 이력</button></td></tr>)}</tbody></table></div>}{catalog.truncated && <p className="hint">처음 500개 보관 항목을 표시합니다.</p>}</section>}
  {selected && <section aria-label={`${selected.deviceId} ${selected.property} 원본 이력`}><div className="toolbar"><h2 ref={historyHeading} tabIndex={-1}>{selected.deviceId} · {selected.property}</h2><label>조회 기간<select value={hours} onChange={event => setHours(Number(event.target.value))}>{[1, 6, 24, 168, 720].filter(hours => hours <= (catalog?.rawRetentionDays || 30) * 24).map(hours => <option key={hours} value={hours}>{hours}시간</option>)}</select></label><button disabled={historyBusy} onClick={() => void load()}>기간 조회</button></div>
    {historyError && <p className="error" role="alert">{historyError}</p>}{historyBusy && <p role="status">원본 이력 조회 중…</p>}
    {history && <>{!history.items.length ? <p className="hint">이 기간에 보관된 원본이 없습니다.</p> : <><OriginalChart history={history} /><div className="table-scroll"><table className="registry-table"><caption className="sr-only">센서 원본 이력</caption><thead><tr><th>측정 시각</th><th>값</th><th>식별 상태</th><th>원본</th></tr></thead><tbody>{history.items.map((row, index) => <tr key={index}><td>{new Date(row.observedAt).toLocaleString()}</td><td>{display(row.value)} {row.unit}</td><td>{row.quality === "QUALIFIED" ? "확인됨" : "미검증"}</td><td><details><summary>원본 · 식별 정보</summary><pre>{JSON.stringify({ deviceUid: row.deviceUid, configurationSha256: row.configurationSha256, sourceGeneration: row.sourceGeneration, timeBasis: row.timeBasis, rawPayload: row.rawPayload }, null, 2)}</pre></details></td></tr>)}</tbody></table></div></>}
      {history.nextCursor && <button disabled={historyBusy || history.items.length >= 2000} onClick={() => void load(selected, true)}>다음 기록</button>}{history.items.length >= 2000 && history.truncated && <p className="hint">표시 한도 2000개에 도달했습니다. 조회 기간을 줄여 확인하세요.</p>}
    </>}
  </section>}
  </>;
}
