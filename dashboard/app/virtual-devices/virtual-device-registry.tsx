"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { components, operations } from "../../lib/api-schema";
import { ContinuousFields } from "./continuous-fields";
import { ContinuousFlow } from "./continuous-flow";
import { ContinuousExecution } from "./continuous-execution";
import { RegistrationDialog } from "../components/registration-dialog";
import { PageHeading } from "../components/page-heading";

type Schema = components["schemas"];
type VD = Schema["VirtualDevice"];
type Detail = Schema["VirtualDeviceDetail"];

export function VirtualDeviceRegistry({ initiallyOpen = false, embedded = false, search = "", onInventoryChange }: { initiallyOpen?: boolean; embedded?: boolean; search?: string; onInventoryChange?: () => void }) {
  const [page, setPage] = useState<operations["listVirtualDevices"]["responses"][200]["content"]["application/json"] | null>(null);
  const [detail, setDetail] = useState<Detail | null>(null);
  const [inputs, setInputs] = useState<Schema["ContinuousSensor"][]>([]);
  const [enabled, setEnabled] = useState(false);
  const [availability, setAvailability] = useState<"loading" | "enabled" | "disabled" | "unavailable">("loading");
  const [error, setError] = useState(""); const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false); const [offset, setOffset] = useState(0);
  const [csrf, setCsrf] = useState(""); const [formVersion, setFormVersion] = useState(0);
  const [createOpen, setCreateOpen] = useState(initiallyOpen);
  const [graph, setGraph] = useState<VD | null>(null);
  const settingsTrigger = useRef<HTMLButtonElement | null>(null);
  const api = useCallback(async (path: string, init?: RequestInit) => {
    const response = await fetch(`/api/control-plane/${path}`, { cache: "no-store", signal: AbortSignal.timeout(8000), ...init });
    const body = await response.json(); if (!response.ok) throw new Error(body.message || `조회 실패 (${response.status})`); return body;
  }, []);
  const load = useCallback(async (next = offset) => { setPage(await api(`virtual-devices?limit=20&offset=${next}`)); setOffset(next); }, [api, offset]);
  const catalog = useCallback(async () => {
    try { const value: Schema["ContinuousCatalog"] = await api("virtual-devices/continuous-catalog"); setEnabled(value.enabled); setInputs(value.inputs); setAvailability(value.enabled ? "enabled" : "disabled"); }
    catch { setEnabled(false); setAvailability("unavailable"); }
  }, [api]);
  useEffect(() => {
    let stopped = false; let timer: ReturnType<typeof setTimeout>;
    const refresh = async () => { await catalog(); if (!stopped) timer = setTimeout(refresh, 10000); };
    void refresh(); void api("virtual-devices?limit=20&offset=0").then(setPage).catch(e => setError(e.message));
    void api("csrf").then(value => setCsrf(value.token)).catch(e => setError(e.message));
    return () => { stopped = true; clearTimeout(timer); };
  }, [api, catalog]);
  async function show(id: string) { setDetail(await api(`virtual-devices/${id}`)); }
  async function run(action: () => Promise<void>) { setBusy(true); setError(""); try { await action(); } catch (e) { setError(e instanceof Error ? e.message : "처리 실패"); } finally { setBusy(false); } }
  function write(method: string, body?: object): RequestInit { return { method, headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrf }, ...(body ? { body: JSON.stringify(body) } : {}) }; }
  async function created(vd: VD) {
    setNotice(`${vd.displayName} 가상디바이스를 등록했습니다.`); setFormVersion(n => n + 1);
    onInventoryChange?.();
    try { await load(0); } catch { setError("등록은 완료됐지만 목록 조회에 실패했습니다. 새로고침으로 확인하세요."); }
    setCreateOpen(false);
  }
  const visible = page?.items.filter(vd => `${vd.displayName} ${vd.key}`.toLowerCase().includes(search.toLowerCase())) ?? [];
  return <>
    {!embedded && <PageHeading eyebrow="INFRASTRUCTURE / VIRTUAL DEVICES" title="가상디바이스" description="센서 입력과 계산을 선택하고, 항목별 결과와 그래프를 확인합니다."
      action={<button className="primary" aria-haspopup="dialog" disabled={busy} onClick={() => setCreateOpen(true)}>가상디바이스 등록</button>} />}
    {!detail && notice && <p className="notice" role="status">{notice}</p>}
    {!detail && error && <p className="error" role="alert">{error}</p>}
    <div className="registry-workspace">
      <section aria-labelledby="vds-title" aria-busy={busy}>
        <div className="toolbar"><h2 id="vds-title">등록된 가상디바이스</h2><button disabled={busy} onClick={() => void run(async () => { await load(); await catalog(); onInventoryChange?.(); if (detail) await show(detail.vd.id); })}>새로고침</button>{embedded && <button className="primary" aria-haspopup="dialog" disabled={busy} onClick={() => setCreateOpen(true)}>가상디바이스 등록</button>}</div>
        {page?.items.length === 0 && <p className="hint">등록된 가상디바이스가 없습니다. 가상디바이스 등록에서 입력 센서와 계산을 선택하세요.</p>}
        {!!page?.items.length && !visible.length && <p className="hint">현재 페이지에서 일치하는 가상디바이스가 없습니다.</p>}
        {!!visible.length && <div className="table-scroll"><table className="vd-table"><caption className="sr-only">가상디바이스 목록</caption><thead><tr><th>가상디바이스</th><th>등록 상태</th><th>변경 버전</th><th>관리</th></tr></thead><tbody>{visible.map(vd => <tr key={vd.id}>
          <td><button className="version-link" title={`${vd.displayName} · ${vd.key}`} aria-haspopup="dialog" disabled={busy} onClick={() => setGraph(vd)}>{vd.displayName}<span className="mono" title={vd.key}>{vd.key}</span></button></td><td>{vd.state === "RELEASED" ? "해제됨" : "등록됨"}</td><td>{vd.revision}</td><td><div className="vd-management"><ContinuousExecution key={vd.id} vd={vd} auth="" csrf={csrf} disabled={busy || !csrf} managementOnly /><button disabled={busy} aria-haspopup="dialog" aria-label={`${vd.displayName} 설정`} onClick={event => { settingsTrigger.current = event.currentTarget; void run(() => show(vd.id)); }}>설정</button></div></td>
        </tr>)}</tbody></table></div>}
        <div className="pagination"><button disabled={busy || offset === 0} onClick={() => void run(() => load(Math.max(0, offset - 20)))}>이전</button><span>{offset / 20 + 1} 페이지</span><button disabled={busy || page?.nextOffset == null} onClick={() => void run(() => load(page!.nextOffset!))}>다음</button></div>
      </section>
    </div>
    <RegistrationDialog open={!!detail} title={`${detail?.vd.displayName ?? "가상디바이스"} · 가상디바이스 설정`} busy={busy} closeLabel="닫기" onClose={() => { setDetail(null); requestAnimationFrame(() => settingsTrigger.current?.focus({ preventScroll: true })); }}>
      {detail && <section className="vd-settings">
        {notice && <p className="notice" role="status">{notice}</p>}
        {error && <p className="error" role="alert">{error}</p>}
        <h3>현재 원본 연결</h3><ul className="history-list">{detail.activeSources.map(source => <li key={source.id}>{source.sourceKey} · {source.deviceId} · {source.properties.join(", ")} · 최대 {source.maxAgeSeconds}초</li>)}</ul>
        {detail.vd.state === "REGISTERED" && detail.continuous && <ContinuousFields key={`${detail.vd.id}-${detail.vd.revision}`} inputs={inputs} enabled={enabled} busy={busy}
          initial={{ displayName: detail.vd.displayName, sources: detail.activeSources.map(source => ({ sourceKey: source.sourceKey, deviceId: source.deviceId, properties: source.properties, maxAgeSeconds: source.maxAgeSeconds })), continuous: detail.continuous }}
          save={async body => { setBusy(true); try { await api(`virtual-devices/${detail.vd.id}/continuous`, write("PATCH", { revision: detail.vd.revision, ...body })); setNotice("연속 처리 설정을 저장했습니다."); try { await show(detail.vd.id); await load(); } catch { setError("설정은 저장됐지만 상태 조회에 실패했습니다. 다시 조회해 확인하세요."); } } finally { setBusy(false); } }} />}
        <details><summary>계산 결과·처리 버전</summary><ContinuousExecution key={detail.vd.id} vd={detail.vd} auth="" csrf={csrf} disabled={busy} showControls={false} /></details>
        {detail.vd.state === "REGISTERED" && <details><summary>가상디바이스 해제</summary><p>계산을 중지하고 원본 연결을 종료합니다. 등록과 결과 이력은 보존됩니다.</p><button disabled={busy || !csrf} onClick={() => void run(async () => { await api(`virtual-devices/${detail.vd.id}`, write("DELETE")); onInventoryChange?.(); await show(detail.vd.id); await load(); })}>해제 확정</button></details>}
      </section>}
    </RegistrationDialog>
    <ContinuousFlow availability={availability} />
    <RegistrationDialog open={!!graph} title={`${graph?.displayName ?? "가상디바이스"} · 가상디바이스 그래프`} subtitle={graph?.key} variant="graph" onClose={() => setGraph(null)}>
      {graph && <ContinuousExecution key={graph.id} vd={graph} auth="" csrf="" disabled={false} graphOnly />}
    </RegistrationDialog>
    <RegistrationDialog open={createOpen} title="가상디바이스 등록" busy={busy} onClose={() => setCreateOpen(false)}>
      {availability === "unavailable" && <div><p className="error" role="alert">연속 처리 상태 조회에 실패했습니다.</p><button disabled={busy} onClick={() => void catalog()}>연속 처리 상태 다시 조회</button></div>}
      <ContinuousFields key={formVersion} inputs={inputs} enabled={enabled && !!csrf} busy={busy} save={async body => { setBusy(true); try { await created(await api("virtual-devices/continuous", write("POST", body))); } finally { setBusy(false); } }} />
    </RegistrationDialog>
  </>;
}
