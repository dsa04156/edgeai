"use client";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import type { components } from "../../lib/api-schema";
import { formatSensorValue, readingFresh, sensorSeries } from "../../lib/sensor-series";

type Schema = components["schemas"];
type Sensor = Schema["InfrastructureSnapshot"]["sensors"][number];
type Reading = Schema["SensorReading"];
const timeLabel = (value: string | number) => new Date(value).toLocaleTimeString("ko-KR", { hour12: false });
function subscribeWidth(callback: () => void) {
  const media = window.matchMedia("(max-width: 700px)"); media.addEventListener("change", callback);
  return () => media.removeEventListener("change", callback);
}
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/control-plane/${path}`, { ...init, cache: "no-store" });
  const value = await response.json();
  if (!response.ok) throw new Error(value.message || "센서 요청을 처리하지 못했습니다.");
  return value as T;
}

function ReadingChart({ readings, resource, units }: { readings: Reading[]; resource: string; units: string }) {
  const narrow = useSyncExternalStore(subscribeWidth, () => window.matchMedia("(max-width: 700px)").matches, () => false);
  const width = narrow ? 340 : 640, right = width - 25;
  const points = sensorSeries(readings);
  if (!points.length) return <p className="empty">그래프로 표시할 수치 데이터가 없습니다.</p>;
  const minimum = Math.min(...points.map(p => p.value)), maximum = Math.max(...points.map(p => p.value));
  const start = points[0].time, end = points[points.length - 1].time;
  const padding = maximum === minimum ? Math.max(Math.abs(maximum) * .05, 1) : (maximum - minimum) * .1;
  const bottom = minimum - padding, top = maximum + padding;
  const x = (t: number) => end === start ? (65 + right) / 2 : 65 + (t - start) / (end - start) * (right - 65);
  const y = (v: number) => 170 - (v - bottom) / (top - bottom) * 140;
  return <figure className="sensor-chart"><svg viewBox={`0 0 ${width} 210`} role="img" aria-label={`${resource}, ${points.length}개 측정값, 최소 ${minimum} 최대 ${maximum} ${units}`}>
    {[0, .5, 1].map(ratio => <g key={ratio}><line x1="65" x2={right} y1={30 + ratio * 140} y2={30 + ratio * 140} stroke="var(--line)" /><text x="58" y={34 + ratio * 140} textAnchor="end">{Number((top - ratio * (top - bottom)).toPrecision(4))}</text></g>)}
    <polyline points={points.map(p => `${x(p.time)},${y(p.value)}`).join(" ")} fill="none" stroke="var(--signal)" strokeWidth="2" />
    {points.map((p, i) => <circle key={i} cx={x(p.time)} cy={y(p.value)} r={points.length > 100 ? 1 : 2.5} fill="var(--signal)"><title>{timeLabel(p.time)} · {p.value} {units}</title></circle>)}
    <text x="65" y="198">{timeLabel(start)}</text><text x={right} y="198" textAnchor="end">{timeLabel(end)}</text>
  </svg><figcaption>{resource} · {units || "단위 없음"} · {points.length}개 측정값</figcaption></figure>;
}

export function SensorDetail({ sensor, onClose }: { sensor: Sensor; onClose: () => void }) {
  const panel = useRef<HTMLElement>(null);
  useEffect(() => { panel.current?.scrollIntoView({ block: "start" }); panel.current?.focus({ preventScroll: true }); }, []);
  const [resource, setResource] = useState(sensor.properties[0] || "");
  const [limit, setLimit] = useState(100);
  const [refresh, setRefresh] = useState(0);
  const [history, setHistory] = useState<Schema["SensorReadings"] | null>(null);
  const [historyError, setHistoryError] = useState("");
  const [now, setNow] = useState(0);
  const [commands, setCommands] = useState<Schema["SensorCommands"] | null>(null);
  const [commandError, setCommandError] = useState("");
  const [selected, setSelected] = useState("");
  const [values, setValues] = useState<Record<string, string>>({});
  const [pending, setPending] = useState<Schema["SensorCommandRequest"] | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<Schema["SensorCommandResult"] | null>(null);
  useEffect(() => {
    const abort = new AbortController(); let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const query = new URLSearchParams({ device: sensor.name, resource, limit: String(limit) });
        const response = await request<Schema["SensorReadings"]>(`sensors/readings?${query}`, { signal: abort.signal });
        if (!abort.signal.aborted) { setHistory(response); setHistoryError(""); setNow(Date.now()); }
      } catch (e) { if (!abort.signal.aborted) { setHistoryError(e instanceof Error ? e.message : "측정값 조회 실패"); setNow(Date.now()); } }
      if (!abort.signal.aborted) timer = setTimeout(poll, 5000);
    }
    void poll(); return () => { abort.abort(); clearTimeout(timer); };
  }, [sensor.name, resource, limit, refresh]);
  useEffect(() => {
    const abort = new AbortController();
    request<Schema["SensorCommands"]>(`sensors/commands?${new URLSearchParams({ device: sensor.name })}`, { signal: abort.signal })
      .then(value => { if (!abort.signal.aborted) { setCommands(value); setCommandError(""); } })
      .catch(e => { if (!abort.signal.aborted) setCommandError(e.message); });
    return () => abort.abort();
  }, [sensor.name, refresh]);
  const readings = history?.readings.filter(r => !resource || r.resource === resource) || [];
  const latest = readings[readings.length - 1];
  const command = commands?.commands.find(c => c.name === selected);
  async function execute(input: Schema["SensorCommandRequest"]) {
    setBusy(true); setCommandError(""); setResult(null);
    try {
      const csrf = await request<{ token: string }>("csrf");
      const outcome = await request<Schema["SensorCommandResult"]>("sensors/command", { method: "POST", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrf.token }, body: JSON.stringify(input) });
      setResult(outcome); setPending(null);
    } catch (e) { setCommandError(e instanceof Error ? e.message : "명령 응답을 확인할 수 없습니다. 장치 상태를 확인하세요."); setPending(null); }
    finally { setBusy(false); }
  }
  return <section ref={panel} tabIndex={-1} className="sensor-detail" aria-label={`${sensor.name} 측정값과 제어`}>
    <div className="toolbar"><div><h3>{sensor.name}</h3><p className="muted">{sensor.nodeName || "연결 정보 없음"} · {sensor.model}</p></div><button onClick={onClose} disabled={busy}>상세 닫기</button></div>
    <div className="toolbar sensor-filters"><label>측정 항목<select value={resource} onChange={e => { setResource(e.target.value); setHistory(null); }}>
      {!sensor.properties.length && <option value="">전체</option>}{sensor.properties.map(name => <option key={name}>{name}</option>)}</select></label>
      <label>조회 개수<select value={limit} onChange={e => setLimit(Number(e.target.value))}><option value="50">최근 50개</option><option value="100">최근 100개</option><option value="500">최근 500개</option></select></label>
      <button onClick={() => setRefresh(v => v + 1)}>측정값·명령 새로고침</button></div>
    {historyError && <p className="error" role="alert">{historyError}{history && " · 마지막 조회 데이터입니다."}</p>}
    {!history && !historyError && <p role="status">측정값을 불러오는 중…</p>}
    {history && <>
      <div className="sensor-current"><strong>{latest ? `${formatSensorValue(latest)} ${latest.units}` : "—"}</strong><span>{latest ? `${timeLabel(latest.observedAt)} 측정${readingFresh(latest.observedAt, now) ? "" : " · 이전 측정값"}` : "저장된 측정값이 없습니다."}</span></div>
      <ReadingChart readings={readings} resource={resource} units={latest?.units || ""} />
      <details><summary>측정값 표 · {readings.length}개</summary><div className="table-scroll"><table><thead><tr><th>측정 시각</th><th>항목</th><th>값</th><th>단위</th></tr></thead><tbody>{[...readings].reverse().map((reading, i) => <tr key={i}><td>{new Date(reading.observedAt).toLocaleString("ko-KR")}</td><td>{reading.resource}</td><td className="sensor-value">{reading.value}</td><td>{reading.units || "—"}</td></tr>)}</tbody></table></div></details>
    </>}
    <div className="sensor-command"><h3>센서 명령</h3>
      {commandError && <p className="error" role="alert">{commandError}</p>}
      {!commands && !commandError && <p role="status">지원 명령을 불러오는 중…</p>}
      {commands && !commands.commands.length && <p>등록된 명령이 없습니다.</p>}
      {commands && commands.adminState !== "UNLOCKED" && <p className="notice">잠긴 센서에는 명령을 보낼 수 없습니다.</p>}
      {commands && commands.commands.length > 0 && <fieldset disabled={busy || commands.adminState !== "UNLOCKED" || !!pending}>
        <label>명령 선택<select value={selected} onChange={e => { setSelected(e.target.value); setValues({}); setResult(null); }}><option value="">명령 선택</option>{commands.commands.map(c => <option key={c.name} value={c.name}>{c.name} · {c.readable ? "읽기" : ""}{c.writable ? " 쓰기" : ""}</option>)}</select></label>
        {command?.writable && command.parameters.map(p => <label key={p.resource}>{p.resource} ({p.valueType})<input value={values[p.resource] || ""} maxLength={4096} onChange={e => setValues({ ...values, [p.resource]: e.target.value })} /></label>)}
        <div className="toolbar">{command?.readable && <button onClick={() => void execute({ device: sensor.name, command: command.name, method: "GET", values: {} })}>지금 읽기</button>}
          {command?.writable && <button disabled={command.parameters.some(p => values[p.resource] === undefined)} onClick={() => setPending({ device: sensor.name, command: command.name, method: "PUT", values: { ...values } })}>쓰기 명령 검토</button>}</div>
        {command && !command.writable && <p className="hint">읽기 전용 명령입니다.</p>}
      </fieldset>}
      {pending && <div className="notice"><p><strong>{sensor.name}</strong>에 <strong>{pending.command}</strong> 쓰기 명령을 보냅니다.</p><dl>{Object.entries(pending.values).map(([key, value]) => <div key={key}><dt>{key}</dt><dd className="sensor-value">{value}</dd></div>)}</dl><div className="toolbar"><button disabled={busy} onClick={() => void execute(pending)}>명령 전송 확인</button><button disabled={busy} onClick={() => setPending(null)}>취소</button></div></div>}
      {busy && <p role="status">명령 응답을 기다리는 중…</p>}
      {result && <div role="status"><p>{result.method === "PUT" ? "EdgeX가 명령을 처리했습니다. 실제 값은 측정값에서 확인하세요." : "센서에서 직접 읽었습니다."} · {timeLabel(result.completedAt)}</p>{result.readings.map((r, i) => <p key={i}>{r.resource} · {r.value} {r.units} · {timeLabel(r.observedAt)}</p>)}</div>}
    </div>
  </section>;
}
