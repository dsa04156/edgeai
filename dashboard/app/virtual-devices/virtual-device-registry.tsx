"use client";
import { useRef, useState, type FormEvent } from "react";
import type { components } from "../../lib/api-schema";
import { ConnectionPanel } from "../components/connection-panel";

type Schema = components["schemas"];
type VD = Schema["VirtualDevice"];
type Detail = Schema["VirtualDeviceDetail"];
type Source = Schema["VirtualDeviceSources"][number];
const stateNames = { REGISTERED: "등록됨", RELEASED: "해제됨" };
const sourceNames = { LIVE: "실제 장치", REPLAY: "재생 데이터", SYNTHETIC: "합성 데이터" };

function SourceFields({ initial = [] }: { initial?: Source[] }) {
  const sequence = useRef(initial.length);
  const [rows, setRows] = useState(initial.map((source, index) => ({ ...source, row: index })));
  return <fieldset className="publish-fields"><legend>원본 연결</legend>
    <p className="hint">VD Profile에 선언한 원본 키와 호환되는 Device ID를 입력하세요. 필수 원본은 모두 연결해야 합니다.</p>
    {rows.map((source, index) => <div key={source.row} className="publish-fields">
      <div className="form-row"><label>원본 키 {index + 1}<input name="sourceKey" required maxLength={100} defaultValue={source.sourceKey} placeholder="input" /></label>
        <label>원본 Device ID {index + 1}<input name="deviceId" required maxLength={36} defaultValue={source.deviceId} list="vd-source-devices" placeholder="장치 관리에서 확인한 UUID" /></label></div>
      <button type="button" onClick={() => setRows(rows.filter(row => row.row !== source.row))}>원본 {index + 1} 제거</button>
    </div>)}
    {!rows.length && <p className="muted">연결할 원본이 없습니다. 원본 없는 emulation 규격은 이 상태로 등록할 수 있습니다.</p>}
    <button type="button" disabled={rows.length >= 16} onClick={() => setRows([...rows, { row: sequence.current++, sourceKey: "", deviceId: "" }])}>원본 추가</button>
  </fieldset>;
}
function PlacementFields({ initial = { mode: "AUTO" } }: { initial?: VD["placement"] }) {
  const [mode, setMode] = useState(initial.mode);
  return <><label>배치 의도<select name="mode" value={mode} onChange={e => setMode(e.target.value as "AUTO" | "NODE")}><option value="AUTO">자동 배치</option><option value="NODE">특정 노드 지정</option></select></label>
    {mode === "NODE" && <label>배치 Node ID<input name="nodeId" required maxLength={36} defaultValue={initial.mode === "NODE" ? initial.nodeId : ""} placeholder="장치·노드 관리에서 확인한 UUID" /></label>}
    <p className="hint">후속 런타임 생성에 사용할 설정입니다. 현재 이 설정을 저장해도 실행을 시작하지 않습니다.</p></>;
}
function configuration(form: FormData) {
  const keys = form.getAll("sourceKey"), devices = form.getAll("deviceId");
  return { displayName: String(form.get("displayName")), sources: keys.map((key, index) => ({ sourceKey: String(key), deviceId: String(devices[index]) })),
    placement: form.get("mode") === "NODE" ? { mode: "NODE", nodeId: String(form.get("nodeId")) } : { mode: "AUTO" } };
}

export function VirtualDeviceRegistry() {
  const [auth, setAuth] = useState(""); const [csrf, setCsrf] = useState("");
  const [busy, setBusy] = useState(false); const [error, setError] = useState(""); const [notice, setNotice] = useState("");
  const [page, setPage] = useState<{ items: VD[]; nextOffset: number | null } | null>(null); const [offset, setOffset] = useState(0);
  const [profiles, setProfiles] = useState<Schema["ProfileVersion"][]>([]); const [devices, setDevices] = useState<Schema["Device"][]>([]);
  const [detail, setDetail] = useState<Detail | null>(null); const [confirmRelease, setConfirmRelease] = useState(false); const [formVersion, setFormVersion] = useState(0);
  async function api(path: string, init?: RequestInit, authorization = auth) {
    const response = await fetch(`/api/control-plane/${path}`, { ...init, cache: "no-store", headers: { Authorization: authorization, ...init?.headers } });
    if (!response.ok) {
      const value = await response.json().catch(() => ({}));
      throw new Error(response.status === 401 ? "계정 정보를 확인하고 다시 연결하세요." : response.status === 403 ? "연결이 만료되었습니다. 다시 연결하세요." : value.message || "요청을 처리하지 못했습니다.");
    }
    return response;
  }
  async function run(action: () => Promise<void>) { setBusy(true); setError(""); setNotice(""); try { await action(); } catch (e) { setError(e instanceof Error ? e.message : "요청을 처리하지 못했습니다."); } finally { setBusy(false); } }
  async function load(nextOffset = offset, authorization = auth) {
    setPage(await (await api(`virtual-devices?limit=20&offset=${nextOffset}`, undefined, authorization)).json()); setOffset(nextOffset);
  }
  async function show(id: string) { setDetail(await (await api(`virtual-devices/${id}`)).json()); setConfirmRelease(false); }
  function write(method: string, body?: object): RequestInit {
    return { method, headers: { "X-CSRF-TOKEN": csrf, ...(body ? { "Content-Type": "application/json" } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) };
  }
  function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = event.currentTarget, input = new FormData(form);
    void run(async () => {
      const authorization = `Basic ${btoa(String.fromCharCode(...new TextEncoder().encode(`${input.get("username")}:${input.get("password")}`)))}`;
      const token = await (await api("csrf", undefined, authorization)).json();
      await load(0, authorization);
      setProfiles((await (await api("profiles/VD?limit=100", undefined, authorization)).json()).items);
      setDevices((await (await api("devices?limit=100", undefined, authorization)).json()).items);
      setAuth(authorization); setCsrf(token.token); form.reset();
    });
  }
  function disconnect() { setAuth(""); setCsrf(""); setPage(null); setProfiles([]); setDevices([]); setDetail(null); setConfirmRelease(false); setError(""); setNotice(""); }
  function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const input = new FormData(event.currentTarget);
    void run(async () => {
      const response = await api("virtual-devices", write("POST", { key: String(input.get("key")), profileVersionId: String(input.get("profileVersionId")), ...configuration(input) }));
      const value: VD = await response.json(); await show(value.id); await load(0); setFormVersion(formVersion + 1);
      setNotice(response.status === 201 ? "가상 장치와 원본 연결을 등록했습니다." : "이미 등록된 가상 장치입니다. 현재 상태를 확인하세요.");
    });
  }
  return <>
    <ConnectionPanel connected={!!auth} busy={busy} onConnect={login} onDisconnect={disconnect} />
    <div aria-live="polite" aria-atomic="true">{notice && <p className="notice">{notice}</p>}</div>
    {error && <p role="alert" className="error">{error}</p>}
    {auth && <>
      <datalist id="vd-source-devices">{devices.filter(d => d.state === "ACTIVE").map(d => <option key={d.id} value={d.id}>{d.displayName} · {sourceNames[d.sourceMode]}</option>)}</datalist>
      <section aria-labelledby="vds-title" aria-busy={busy}>
        <div className="toolbar"><h2 id="vds-title">등록된 가상 장치</h2><button disabled={busy} onClick={() => void run(async () => { await load(); if (detail) await show(detail.vd.id); })}>VD 새로고침</button></div>
        {busy && <p role="status">처리 중…</p>}
        {page?.items.length === 0 && <p className="empty">등록된 가상 장치가 없습니다. VD Profile과 원본 장치를 준비해 등록하세요.</p>}
        {!!page?.items.length && <div className="table-scroll"><table className="vd-table"><caption className="sr-only">가상 장치 목록</caption><thead><tr><th>가상 장치</th><th>상태</th><th>변경 버전</th><th>배치 의도</th></tr></thead>
          <tbody>{page.items.map(vd => <tr key={vd.id}><td><button className="version-link" disabled={busy} onClick={() => void run(() => show(vd.id))}>{vd.displayName}<span className="mono">{vd.key}</span></button></td><td>{stateNames[vd.state]}</td><td>{vd.revision}</td><td>{vd.placement.mode === "AUTO" ? "자동" : "노드 지정"}</td></tr>)}</tbody></table></div>}
        <div className="pagination"><button disabled={busy || offset === 0} onClick={() => void run(() => load(Math.max(0, offset - 20)))}>이전 VD</button><span>{offset / 20 + 1} 페이지</span><button disabled={busy || page?.nextOffset == null} onClick={() => void run(() => load(page!.nextOffset!))}>다음 VD</button></div>
      </section>
      {detail && <section className="panel" aria-labelledby="vd-detail-title">
        <div className="toolbar"><h2 id="vd-detail-title">{detail.vd.displayName}</h2><span className="stage">{stateNames[detail.vd.state]}</span></div>
        <p className="digest mono">VD ID {detail.vd.id}</p>
        <dl><div><dt>VD Profile 버전</dt><dd className="mono digest">{detail.vd.profileVersionId}</dd></div><div><dt>실행 서비스 버전</dt><dd className="mono digest">{detail.vd.serviceProfileVersionId}</dd></div></dl>
        <h3>현재 원본 연결</h3>{detail.activeSources.length ? <ul className="history-list">{detail.activeSources.map(source => <li key={source.id}><strong>{source.sourceKey}</strong> · {sourceNames[source.sourceMode]}<span className="block mono digest">{source.deviceId}</span></li>)}</ul> : <p className="muted">활성 원본 연결이 없습니다.</p>}
        {detail.vd.state === "REGISTERED" && <form key={`${detail.vd.id}-${detail.vd.revision}`} aria-label="VD 설정 수정" onSubmit={event => {
          event.preventDefault(); const input = new FormData(event.currentTarget);
          void run(async () => { await api(`virtual-devices/${detail.vd.id}`, write("PATCH", { revision: detail.vd.revision, ...configuration(input) })); await show(detail.vd.id); await load(); setNotice("VD 설정을 저장했습니다. 기존 원본 연결 이력은 보존됩니다."); });
        }}><fieldset disabled={busy} className="publish-fields"><legend>설정 수정</legend>
          <label>표시 이름<input name="displayName" required maxLength={128} defaultValue={detail.vd.displayName} /></label>
          <SourceFields initial={detail.activeSources.map(source => ({ sourceKey: source.sourceKey, deviceId: source.deviceId }))} />
          <PlacementFields initial={detail.vd.placement} /><button>VD 설정 저장</button>
        </fieldset></form>}
        <h3>원본 연결 이력</h3>
        {detail.sourceHistoryTruncated && <p className="hint">최근 연결 100개를 표시합니다. 현재 활성 연결은 위에서 모두 확인할 수 있습니다.</p>}
        {detail.sourceHistory.length ? <div className="table-scroll"><table className="vd-table"><caption className="sr-only">VD 원본 연결 이력</caption><thead><tr><th>원본 / Device</th><th>출처</th><th>변경 버전</th><th>연결 상태</th></tr></thead><tbody>{detail.sourceHistory.map(source => <tr key={source.id}><td>{source.sourceKey}<span className="block mono digest">{source.deviceId}</span></td><td>{sourceNames[source.sourceMode]}</td><td>{source.openedRevision} → {source.closedRevision ?? "현재"}</td><td>{source.closedAt ? "종료됨" : "활성"}</td></tr>)}</tbody></table></div> : <p className="muted">원본 연결 이력이 없습니다.</p>}
        <details><summary>가상 장치 상세 데이터</summary><pre aria-label="VD 상세 JSON">{JSON.stringify(detail, null, 2)}</pre></details>
        {detail.vd.state === "REGISTERED" && <div className="release-controls">{confirmRelease ? <><p>이 VD의 원본 연결을 종료합니다. VD ID와 연결 이력, 원본 장치는 보존됩니다.</p><div className="toolbar"><button disabled={busy} onClick={() => void run(async () => { await api(`virtual-devices/${detail.vd.id}`, write("DELETE")); await show(detail.vd.id); await load(); setNotice("가상 장치를 해제했습니다."); })}>VD 해제 확정</button><button disabled={busy} onClick={() => setConfirmRelease(false)}>계속 사용</button></div></> : <button disabled={busy} onClick={() => setConfirmRelease(true)}>VD 해제</button>}</div>}
      </section>}
      <section className="panel" aria-labelledby="vd-register-title"><h2 id="vd-register-title">새 가상 장치 등록</h2>
        <form key={formVersion} onSubmit={register} aria-label="VD 등록"><fieldset disabled={busy} className="publish-fields">
          <div className="form-row"><label>VD 키<input name="key" required maxLength={100} pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" placeholder="factory-a-virtual-sensor" /></label><label>VD 이름<input name="displayName" required maxLength={128} /></label></div>
          <label>VD Profile 버전 ID<input name="profileVersionId" required list="vd-profile-versions" maxLength={36} placeholder="Profile 관리에서 확인한 UUID" /></label>
          <datalist id="vd-profile-versions">{profiles.map(p => <option key={p.id} value={p.id}>{p.key} · {p.version}</option>)}</datalist>
          <p className="hint">Profile과 원본 장치는 처음 100개를 제안합니다. 다른 항목은 관리 화면의 UUID를 입력하세요.</p>
          <SourceFields /><PlacementFields /><button className="primary">VD 등록</button>
        </fieldset></form>
      </section>
    </>}
  </>;
}
