"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { components } from "../../lib/api-schema";
import { ProcessingVersions } from "./processing-versions";

type Schema = components["schemas"];
type History = Schema["ContinuousHistory"];
const stateNames = { STOPPED: "중지됨", STARTING: "시작 대기", RUNNING: "계산 중", WAITING_INPUT: "입력·창 대기", STOPPING: "중지 중", ERROR: "확인 필요" };
const reasons: Record<string, string> = { HEALTHY: "정상", INPUT_MISSING: "입력 미수신", INPUT_STALE: "입력 유효 시간 초과", WINDOW_WARMING: "창 표본 수집 중", RULE_UNAVAILABLE: "계산 엔진 확인 필요", SOURCE_CONFIGURATION_UNAVAILABLE: "센서 연결 확인 필요", STOPPED: "중지됨", ENGINE_BACKPRESSURE: "처리 지연", EVALUATION_TIMEOUT: "계산 응답 지연", HISTORY_UNAVAILABLE: "기록 저장 중단", INPUT_UNAVAILABLE: "입력 수신 중단", WINDOW_RESET: "계산 창 초기화", ENGINE_UNAVAILABLE: "계산 엔진 연결 중단", AWAITING_ENGINE: "계산 엔진 연결 대기" };
const displayValue = (value: number | boolean | undefined) => typeof value === "boolean" ? value ? "참" : "거짓" : value === undefined ? "—" : Number(value.toPrecision(8)).toLocaleString();
function requestKey() { const bytes = crypto.getRandomValues(new Uint8Array(16)); bytes[6] = bytes[6] & 15 | 64; bytes[8] = bytes[8] & 63 | 128; const value = [...bytes].map(n => n.toString(16).padStart(2, "0")).join(""); return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`; }

function HistoryChart({ history, interval }: { history: History; interval: number }) {
  const [width, setWidth] = useState(680);
  useEffect(() => { const media = window.matchMedia("(max-width: 700px)"); const update = () => setWidth(media.matches ? 340 : 680); update(); media.addEventListener("change", update); return () => media.removeEventListener("change", update); }, []);
  const points = history.items.map(row => ({ at: Date.parse(row.evaluationAt), value: row.average ?? (typeof row.value === "boolean" ? Number(row.value) : row.value), generation: row.generation })).filter((row): row is { at: number; value: number; generation: string | undefined } => Number.isFinite(row.value));
  if (!points.length) return <p className="hint">이 기간에 저장된 결과가 없습니다.</p>;
  const from = Date.parse(history.from), to = Date.parse(history.to); const values = points.map(row => row.value);
  let minimum = Math.min(...values), maximum = Math.max(...values); if (minimum === maximum) { minimum -= 1; maximum += 1; }
  const right = width - 48;
  const x = (at: number) => 48 + (at - from) / Math.max(1, to - from) * (right - 48);
  const y = (value: number) => 160 - (value - minimum) / (maximum - minimum) * 132;
  const segments: string[][] = []; let previous: typeof points[number] | undefined;
  const expected = (history.aggregation?.bucketSeconds || interval) * 1000;
  for (const point of points) {
    const gap = previous && history.gaps.some(value => Date.parse(value.from) <= point.at && Date.parse(value.to || history.to) >= previous!.at);
    if (!previous || point.at - previous.at > expected * 1.5 || previous.generation !== point.generation || gap) segments.push([]);
    segments[segments.length - 1].push(`${x(point.at)},${y(point.value)}`); previous = point;
  }
  return <figure className="continuous-history-chart"><svg role="img" aria-label={`${history.outputId} 결과 그래프, ${points.length}개 기록`} viewBox={`0 0 ${width} 200`}>
    <title>{history.outputId} · {new Date(history.from).toLocaleString()}부터 {new Date(history.to).toLocaleString()}까지</title>
    {history.gaps.map((gap, index) => <rect key={index} x={Math.max(48, x(Date.parse(gap.from)))} y={28} width={Math.max(1, Math.min(right, x(Date.parse(gap.to || history.to))) - Math.max(48, x(Date.parse(gap.from))))} height={132} className="continuous-gap" />)}
    <path d={`M48 28V160H${right}`} className="continuous-axis" />
    <text x={44} y={32} textAnchor="end">{displayValue(maximum)}</text><text x={44} y={160} textAnchor="end">{displayValue(minimum)}</text>
    {segments.map((segment, index) => segment.length > 1 ? <polyline key={index} points={segment.join(" ")} className="continuous-line" /> : <circle key={index} cx={Number(segment[0].split(",")[0])} cy={Number(segment[0].split(",")[1])} r={3} className="continuous-dot" />)}
    <text x={48} y={186}>{new Date(from).toLocaleTimeString()}</text><text x={right} y={186} textAnchor="end">{new Date(to).toLocaleTimeString()}</text>
  </svg><figcaption>기록 누락·연결 변경 구간은 선을 이어 표시하지 않습니다.{history.aggregation && ` ${history.aggregation.bucketSeconds}초 구간 평균이며 원본 결과는 보존됩니다.`}</figcaption></figure>;
}

export function ContinuousExecution({ vd, auth, csrf, disabled }: { vd: Schema["VirtualDevice"]; auth: string; csrf: string; disabled: boolean }) {
  const [data, setData] = useState<Schema["VDExecution"] | null>(null); const [outputs, setOutputs] = useState<Schema["ContinuousOutputs"] | null>(null);
  const [error, setError] = useState(""); const [busy, setBusy] = useState(false); const [notice, setNotice] = useState("");
  const [draft, setDraft] = useState<{ action: "provision" | "drain"; key: string; revision: number } | null>(null);
  const [selected, setSelected] = useState(""); const [hours, setHours] = useState(1); const [history, setHistory] = useState<History | null>(null); const [historyError, setHistoryError] = useState("");
  const sequence = useRef({ value: 0 }), chartSequence = useRef(0), commandBusy = useRef(false);
  const api = useCallback(async (path: string, init?: RequestInit) => { const response = await fetch(`/api/control-plane/virtual-devices/${vd.id}/${path}`, { cache: "no-store", signal: AbortSignal.timeout(8000), ...init, headers: { Authorization: auth, ...init?.headers } }); const value = await response.json(); if (!response.ok) throw new Error(value.message || "조회하지 못했습니다."); return value; }, [vd.id, auth]);
  const refresh = useCallback(async () => { const request = ++sequence.current.value; try { const [execution, rows] = await Promise.all([api("execution"), api("outputs")]); if (request === sequence.current.value) { setData(execution); setOutputs(rows); setError(""); } } catch (error) { if (request === sequence.current.value) setError(error instanceof Error ? error.message : "조회하지 못했습니다."); } }, [api]);
  useEffect(() => { const requests = sequence.current; let stopped = false; let timer: ReturnType<typeof setTimeout>; const poll = async () => { await refresh(); if (!stopped) timer = setTimeout(poll, 3000); }; void poll(); return () => { stopped = true; ++requests.value; clearTimeout(timer); }; }, [refresh, vd.revision]);
  async function chart(outputId = selected) {
    if (!outputId) return; const request = ++chartSequence.current; setSelected(outputId); setHistoryError(""); setHistory(null);
    try { const period = Math.min(hours, (outputs?.retentionDays || 7) * 24); const to = new Date(), from = new Date(to.getTime() - period * 3600000); const query = new URLSearchParams({ from: from.toISOString(), to: to.toISOString(), limit: "2000", bucketSeconds: String(Math.max(outputs?.intervalSeconds || 1, Math.ceil(period * 3600 / 1000))) });
      const value: History = await api(`outputs/${outputId}/history?${query}`); if (request === chartSequence.current) setHistory(value);
    } catch (error) { if (request === chartSequence.current) setHistoryError(error instanceof Error ? error.message : "기록을 조회하지 못했습니다."); }
  }
  async function submit() { if (!draft || commandBusy.current) return; commandBusy.current = true; setBusy(true); setNotice("");
    try { await api(draft.action, { method: "POST", headers: { "X-CSRF-TOKEN": csrf, "Idempotency-Key": draft.key, "Content-Type": "application/json" }, body: JSON.stringify({ revision: draft.revision }) }); setDraft(null); setNotice("실행 요청을 저장했습니다."); await refresh(); }
    catch (error) { setNotice(error instanceof Error ? error.message : "실행 요청에 실패했습니다. 같은 요청을 다시 보낼 수 있습니다."); }
    finally { commandBusy.current = false; setBusy(false); }
  }
  const execution = data?.continuous;
  return <section aria-label="연속 처리 결과"><div className="toolbar"><h3>연속 처리</h3><button disabled={busy || disabled} onClick={() => void refresh()}>결과 새로고침</button></div>
    <ProcessingVersions key={vd.id} vdId={vd.id} auth={auth} />
    {error && <p role="alert" className="error">{error} 마지막 조회 값을 표시합니다.</p>}
    {!data && !error && <p role="status">실행 상태 조회 중…</p>}
    {execution && <><p className="stage">{error ? "상태 확인 필요" : stateNames[execution.state]} · {reasons[execution.reason] || "상태 확인 중"}</p>
      <p className="hint">모든 입력이 유효할 때 {outputs?.intervalSeconds || 1}초마다 계산합니다. 보관 기간 {outputs?.retentionDays || 7}일.</p>
      {!draft ? <div className="toolbar"><button disabled={busy || disabled || !!error || !execution.enabled || vd.state !== "REGISTERED" || execution.desiredRunning} onClick={() => setDraft({ action: "provision", key: requestKey(), revision: vd.revision })}>계산 시작</button><button disabled={busy || disabled || !!error || !execution.enabled || !execution.desiredRunning} onClick={() => setDraft({ action: "drain", key: requestKey(), revision: vd.revision })}>계산 중지</button></div>
        : <div className="toolbar"><button disabled={busy || disabled} onClick={() => void submit()}>{busy ? "요청 중…" : draft.action === "provision" ? "계산 시작 요청" : "계산 중지 요청"}</button><button disabled={busy} onClick={() => setDraft(null)}>취소</button></div>}
    </>}
    {notice && <p role="status" className="notice">{notice}</p>}
    {outputs && <div className="table-scroll"><table className="registry-table"><caption className="sr-only">계산 출력 항목별 최신 결과</caption><thead><tr><th>출력 항목</th><th>최신값</th><th>계산 시각</th><th>상태</th><th>기록</th></tr></thead><tbody>{outputs.items.map(row => <tr key={row.outputId}><td><strong>{row.name}</strong><span className="block muted">{row.expression}</span></td><td>{displayValue(row.latest?.value)} {row.unit}</td><td>{row.latest ? new Date(row.latest.evaluationAt).toLocaleString() : "미계산"}</td><td>{error ? "조회 확인 필요" : { VALID: "유효", STALE: "마지막 값", NOT_AVAILABLE: "미수신" }[row.quality]}{row.latest && <span className="block muted">{row.latest.storageStatus === "SKIPPED" ? "저장 정책 제외" : row.latest.historyStored ? "저장 완료" : row.latest.storageStatus === "PENDING" ? "저장 대기" : "기록 저장 누락"}</span>}</td><td><button disabled={busy || !outputs.historyEnabled} onClick={() => void chart(row.outputId)}>{row.name} 그래프</button></td></tr>)}</tbody></table></div>}
    {outputs && !outputs.historyEnabled && <p className="notice">기록 저장소가 연결되지 않았습니다. 최신 결과는 계속 표시됩니다.</p>}
    {selected && <section aria-label={`${selected} 기록`}><div className="toolbar"><h4>{selected} 기록</h4><label>조회 기간<select value={Math.min(hours, (outputs?.retentionDays || 7) * 24)} onChange={event => setHours(Number(event.target.value))}>{[1, 6, 24, 168, 720].filter(value => value <= (outputs?.retentionDays || 7) * 24).map(value => <option key={value} value={value}>{value}시간</option>)}</select></label><button onClick={() => void chart()}>그래프 조회</button></div>
      {historyError && <p role="alert" className="error">{historyError}</p>}{history && <><HistoryChart history={history} interval={outputs?.intervalSeconds || 1} />
        {history.gaps.length > 0 && <ul className="history-list">{history.gaps.map((gap, index) => <li key={index}>{reasons[gap.reason] || "결과 기록 누락"} · {new Date(gap.from).toLocaleString()} → {gap.to ? new Date(gap.to).toLocaleString() : "계속 중"}</li>)}</ul>}
        <details><summary>기록 표 · {history.items.length}개</summary><div className="table-scroll"><table className="registry-table"><thead><tr><th>계산 시각</th><th>값 / 평균</th><th>최솟값</th><th>최댓값</th><th>표본 수</th></tr></thead><tbody>{history.items.map((row, index) => <tr key={index}><td>{new Date(row.evaluationAt).toLocaleString()}</td><td>{displayValue(row.average ?? row.value)}</td><td>{displayValue(row.min)}</td><td>{displayValue(row.max)}</td><td>{row.count || 1}</td></tr>)}</tbody></table></div></details>
        {history.truncated && <p className="hint">표시 한도에 도달했습니다. 조회 기간을 줄여 확인하세요.</p>}
      </>}{!history && !historyError && <p role="status">기록 조회 중…</p>}
    </section>}
  </section>;
}
