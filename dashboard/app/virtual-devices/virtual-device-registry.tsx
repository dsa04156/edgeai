"use client";
import { useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import { EmptyState } from "../components/empty-state";
import type { components } from "../../lib/api-schema";
import { VirtualDeviceExecution } from "./virtual-device-execution";
import { registryItems } from "../../lib/registry";
import { compatibleSource, sourceRequirements, record, vdTypeNames, type VDSourceRequirement } from "../../lib/vd-profile";
import { hardwareLabel } from "../../lib/hardware";
import { ConnectionPanel } from "../components/connection-panel";

type Schema = components["schemas"];
type VD = Schema["VirtualDevice"];
type Detail = Schema["VirtualDeviceDetail"];
type Source = Schema["VirtualDeviceSources"][number];
const stateNames = { REGISTERED: "등록됨", RELEASED: "해제됨" };
const sourceNames = { LIVE: "실제 장치", REPLAY: "재생 데이터", SYNTHETIC: "합성 데이터" };

function SourceFields({ initial = [], devices, spec }: { initial?: Source[]; devices: Schema["Device"][]; spec: unknown }) {
  const requirements = sourceRequirements(spec);
  const selected = new Map(initial.map(source => [source.sourceKey, source.deviceId]));
  return <fieldset className="publish-fields"><legend>원본 연결 · Source Binding</legend>
    {Object.entries(requirements).map(([key, condition]) => <SourceSlot key={key} sourceKey={key} condition={condition} devices={devices} initialDevice={selected.get(key) || ""} />)}
    {!Object.keys(requirements).length && <p className="muted">이 템플릿은 원본 연결을 선언하지 않습니다.</p>}
    <p className="hint">규격 버전과 허용 출처가 일치하는 활성 Device만 선택합니다. 원본 변경 이력은 보존됩니다. <a href="/devices?view=devices">Device 등록·연결 관리 →</a></p>
  </fieldset>;
}
function SourceSlot({ sourceKey, condition, devices, initialDevice }: { sourceKey: string; condition: VDSourceRequirement; devices: Schema["Device"][]; initialDevice: string }) {
  const choices = devices.filter(device => compatibleSource(device, condition));
  const [deviceId, setDeviceId] = useState(choices.some(device => device.id === initialDevice) ? initialDevice : "");
  return <div className="publish-fields"><label>{sourceKey} · {condition.required ? "필수 원본" : "선택 원본"}
    {deviceId && <input type="hidden" name="sourceKey" value={sourceKey} />}
    <select aria-label={`${sourceKey} · ${condition.required ? "필수" : "선택"} 원본`} name={deviceId ? "deviceId" : undefined} required={condition.required} value={deviceId} onChange={event => setDeviceId(event.target.value)}>
      <option value="">{condition.required ? "호환 장치 선택" : "연결하지 않음"}</option>{choices.map(device => <option key={device.id} value={device.id}>{device.displayName} · {sourceNames[device.sourceMode]}</option>)}
    </select></label>
    {!choices.length && <p className="hint">이 원본 조건과 일치하는 장치가 없습니다. DEVICE 버전과 데이터 출처를 확인하세요.</p>}
  </div>;
}
function PlacementFields({ initial = { mode: "AUTO" }, nodes }: { initial?: VD["placement"]; nodes: Schema["ExecutionNode"][] }) {
  const [mode, setMode] = useState(initial.mode);
  return <><label>배치 의도<select aria-label="배치 의도" name="mode" value={mode} onChange={e => setMode(e.target.value as "AUTO" | "NODE")}><option value="AUTO">자동 배치</option><option value="NODE">특정 노드 지정</option></select></label>
    {mode === "NODE" && <label>배치 노드<select aria-label="배치 노드" name="nodeId" required defaultValue={initial.mode === "NODE" ? initial.nodeId : ""}><option value="">노드 선택</option>{nodes.map(n => <option key={n.id} value={n.id} disabled={n.status !== "READY"}>{n.name} · {hardwareLabel(n)}</option>)}</select></label>}
    <p className="hint">새 VD는 등록 후 실행을 시작할 수 있습니다. 실행 중인 VD의 원본·배치를 바꾸면 이전 실행 종료 후 교체합니다.</p></>;
}
function configuration(form: FormData) {
  const keys = form.getAll("sourceKey"), devices = form.getAll("deviceId");
  return { displayName: String(form.get("displayName")), sources: keys.map((key, index) => ({ sourceKey: String(key), deviceId: String(devices[index]) })),
    placement: form.get("mode") === "NODE" ? { mode: "NODE", nodeId: String(form.get("nodeId")) } : { mode: "AUTO" } };
}

export function VirtualDeviceRegistry({ workflows = false }: { workflows?: boolean }) {
  const createPanel = useRef<HTMLDetailsElement>(null);
  function openCreatePanel() {
    if (!createPanel.current) return;
    createPanel.current.open = true;
    createPanel.current.scrollIntoView({ block: "start" });
    createPanel.current.querySelector<HTMLInputElement>('input[name="key"]')?.focus({ preventScroll: true });
  }
  const [auth, setAuth] = useState(""); const [csrf, setCsrf] = useState("");
  const [busy, setBusy] = useState(false); const [error, setError] = useState(""); const [notice, setNotice] = useState("");
  const [deleting, setDeleting] = useState<string | null>(null);
  const [page, setPage] = useState<{ items: VD[]; nextOffset: number | null } | null>(null); const [offset, setOffset] = useState(0);
  const [serviceProfiles, setServiceProfiles] = useState<Schema["ProfileVersion"][]>([]);
  const [profiles, setProfiles] = useState<Schema["ProfileVersion"][]>([]); const [devices, setDevices] = useState<Schema["Device"][]>([]);
  const [nodes, setNodes] = useState<Schema["ExecutionNode"][]>([]);
  const [selectedProfile, setSelectedProfile] = useState("");
  const [initialDevice, setInitialDevice] = useState("");
  const [detail, setDetail] = useState<Detail | null>(null); const [confirmRelease, setConfirmRelease] = useState(false); const [formVersion, setFormVersion] = useState(0);
  async function api(path: string, init?: RequestInit, authorization = auth) {
    const response = await fetch(`/api/control-plane/${path}`, { ...init, cache: "no-store", headers: { ...(authorization.startsWith("Basic ") ? { Authorization: authorization } : {}), ...init?.headers } });
    if (!response.ok) {
      const value = await response.json().catch(() => ({}));
      throw new Error(response.status === 401 ? "API 연결을 확인하세요." : response.status === 403 ? "연결이 만료되었습니다. 화면을 새로고침하세요." : value.message || "요청을 처리하지 못했습니다.");
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
  function connect() {
    void run(async () => {
      const authorization = "dashboard";
      const token = await (await api("csrf", undefined, authorization)).json();
      await load(0, authorization);
      const [profileItems, deviceItems, nodeItems, serviceItems] = await Promise.all([
        registryItems<Schema["ProfileVersion"]>("profiles/VD", path => api(path, undefined, authorization)),
        registryItems<Schema["Device"]>("devices", path => api(path, undefined, authorization)),
        registryItems<Schema["ExecutionNode"]>("nodes", path => api(path, undefined, authorization)),
        registryItems<Schema["ProfileVersion"]>("profiles/SERVICE", path => api(path, undefined, authorization)),
      ]);
      setServiceProfiles(serviceItems); setProfiles(profileItems); setDevices(deviceItems); setNodes(nodeItems);
      const query = new URLSearchParams(window.location.search);
      setSelectedProfile(query.get("profile") || ""); setInitialDevice(query.get("device") || "");
      if (query.get("vd")) setDetail(await (await api(`virtual-devices/${query.get("vd")}`, undefined, authorization)).json());
      setAuth(authorization); setCsrf(token.token);
    });
  }

  function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const input = new FormData(event.currentTarget);
    void run(async () => {
      const response = await api("virtual-devices", write("POST", { key: String(input.get("key")), profileVersionId: String(input.get("profileVersionId")), ...configuration(input) }));
      const value: VD = await response.json(); await show(value.id); await load(0); setFormVersion(formVersion + 1);
      setNotice(response.status === 201 ? "가상 장치와 원본 연결을 등록했습니다." : "이미 등록된 가상 장치입니다. 현재 상태를 확인하세요.");
    });
  }
  function remove(vd: VD) {
    void run(async () => {
      await api(`virtual-devices/${vd.id}/registration`, write("DELETE"));
      if (detail?.vd.id === vd.id) setDetail(null);
      setDeleting(null);
      await load(page?.items.length === 1 && offset > 0 ? Math.max(0, offset - 20) : offset);
      setNotice(`${vd.displayName} 등록을 영구 삭제했습니다. 원본 장치와 프로필은 유지됩니다.`);
    });
  }
  const template = profiles.find(profile => profile.id === selectedProfile);
  const detailTemplate = profiles.find(profile => profile.id === detail?.vd.profileVersionId);
  const profileName = (id: string, values = profiles) => { const profile = values.find(value => value.id === id); return profile ? `${profile.key} · ${profile.version}` : "프로필 조회 필요"; };
  const deviceName = (id: string) => devices.find(device => device.id === id)?.displayName || "장치 조회 필요";
  return <>
    <div className="toolbar"><a href="/profiles?kind=VD">VD 템플릿 관리 →</a><Link href="/docs/concepts/resources">리소스 구조 보기 →</Link></div>
    <ConnectionPanel connected={!!auth} busy={busy} onConnect={connect} />
    <div aria-live="polite" aria-atomic="true">{notice && <p className="notice">{notice}</p>}</div>
    {error && <p role="alert" className="error">{error}</p>}
    {auth && <>
      <datalist id="vd-source-devices">{devices.filter(d => d.state === "ACTIVE").map(d => <option key={d.id} value={d.id}>{d.displayName} · {sourceNames[d.sourceMode]}</option>)}</datalist>
      <div className="registry-workspace"><section aria-labelledby="vds-title" aria-busy={busy}>
        <div className="toolbar"><h2 id="vds-title">등록된 가상 장치</h2><div className="toolbar"><button disabled={busy} onClick={() => void run(async () => { await load(); if (detail) await show(detail.vd.id); })}>새로고침</button><button className="primary" onClick={openCreatePanel}>가상 장치 등록</button></div></div>
        {busy && <p role="status">처리 중…</p>}
        {page?.items.length === 0 && <EmptyState title="등록된 가상 장치가 없습니다" description="원본 장치와 처리 규격을 연결해 가상 장치를 구성하세요." action={profiles.length ? <button onClick={openCreatePanel}>첫 가상 장치 등록</button> : <a className="button-link" href="/profiles?kind=VD">가상 장치 Profile 등록</a>} />}
        {!!page?.items.length && <div className="table-scroll"><table className="vd-table"><caption className="sr-only">가상 장치 목록</caption><thead><tr><th>가상 장치</th><th>상태</th><th>변경 버전</th><th>배치 의도</th><th>등록 관리</th></tr></thead>
          <tbody>{page.items.map(vd => <tr key={vd.id}><td><button className="version-link" disabled={busy} onClick={() => void run(() => show(vd.id))}>{vd.displayName}<span className="mono">{vd.key}</span></button></td><td>{stateNames[vd.state]}</td><td>{vd.revision}</td><td>{vd.placement.mode === "AUTO" ? "자동" : "노드 지정"}</td><td>
            {vd.state !== "RELEASED" ? <button disabled={busy} onClick={() => void run(async () => { await show(vd.id); setNotice("상세 화면에서 VD를 해제한 뒤 미사용 등록을 삭제할 수 있습니다."); })}>해제 관리</button>
              : deleting === vd.id ? <div><p>등록과 원본 연결 기록을 영구 삭제할까요? 실행·작업 참조가 있으면 삭제되지 않습니다.</p><div className="toolbar"><button disabled={busy} onClick={() => remove(vd)}>영구 삭제 확인</button><button disabled={busy} onClick={() => setDeleting(null)}>취소</button></div></div>
                : <button disabled={busy} aria-label={`${vd.displayName} 영구 삭제`} onClick={() => setDeleting(vd.id)}>영구 삭제</button>}
          </td></tr>)}</tbody></table></div>}
        <div className="pagination"><button disabled={busy || offset === 0} onClick={() => void run(() => load(Math.max(0, offset - 20)))}>이전 VD</button><span>{offset / 20 + 1} 페이지</span><button disabled={busy || page?.nextOffset == null} onClick={() => void run(() => load(page!.nextOffset!))}>다음 VD</button></div>
      </section>
      {detail && <section className="panel" aria-labelledby="vd-detail-title">
        <div className="toolbar"><h2 id="vd-detail-title">{detail.vd.displayName}</h2><span className="stage">{stateNames[detail.vd.state]}</span></div>
        <p className="digest mono">VD ID {detail.vd.id}</p>
        <p className="hint">원본과 실행체를 교체해도 이 ID를 유지합니다. 등록 상태와 실행 준비 상태는 각각 확인합니다.</p>
        <dl><div><dt>VD 템플릿</dt><dd>{profileName(detail.vd.profileVersionId)}</dd></div><div><dt>처리 SERVICE 모듈</dt><dd>{profileName(detail.vd.serviceProfileVersionId, serviceProfiles)}</dd></div></dl>
        <VirtualDeviceExecution key={detail.vd.id} vd={detail.vd} auth={auth} csrf={csrf} disabled={busy} />
        {workflows && <p><a href={`/workflows?service=${detail.vd.serviceProfileVersionId}&vd=${detail.vd.id}`}>이 VD의 활성 Runtime으로 DAG 실행 구성 →</a></p>}
        <h3>현재 원본 연결 · Source Binding</h3>{detail.activeSources.length ? <ul className="history-list">{detail.activeSources.map(source => <li key={source.id}><strong>{source.sourceKey}</strong> · {sourceNames[source.sourceMode]}<span className="block">{deviceName(source.deviceId)}</span></li>)}</ul> : <p className="muted">활성 원본 연결이 없습니다.</p>}
        {detail.vd.state === "REGISTERED" && <form key={`${detail.vd.id}-${detail.vd.revision}`} aria-label="VD 설정 수정" onSubmit={event => {
          event.preventDefault(); const input = new FormData(event.currentTarget);
          void run(async () => { await api(`virtual-devices/${detail.vd.id}`, write("PATCH", { revision: detail.vd.revision, ...configuration(input) })); await show(detail.vd.id); await load(); setNotice("VD 설정을 저장했습니다. 기존 원본 연결 이력은 보존됩니다."); });
        }}><fieldset disabled={busy || !detailTemplate} className="publish-fields"><legend>설정 수정</legend>
          <label>표시 이름<input name="displayName" required maxLength={128} defaultValue={detail.vd.displayName} /></label>
          <SourceFields devices={devices} spec={detailTemplate?.spec} initial={detail.activeSources.map(source => ({ sourceKey: source.sourceKey, deviceId: source.deviceId }))} />
          <PlacementFields nodes={nodes} initial={detail.vd.placement} /><button>VD 설정 저장</button>
        </fieldset></form>}
        <h3>원본 연결 이력</h3>
        {detail.sourceHistoryTruncated && <p className="hint">최근 연결 100개를 표시합니다. 현재 활성 연결은 위에서 모두 확인할 수 있습니다.</p>}
        {detail.sourceHistory.length ? <div className="table-scroll"><table className="vd-table"><caption className="sr-only">VD 원본 연결 이력</caption><thead><tr><th>원본 / Device</th><th>출처</th><th>변경 버전</th><th>연결 상태</th></tr></thead><tbody>{detail.sourceHistory.map(source => <tr key={source.id}><td>{source.sourceKey}<span className="block">{deviceName(source.deviceId)}</span></td><td>{sourceNames[source.sourceMode]}</td><td>{source.openedRevision} → {source.closedRevision ?? "현재"}</td><td>{source.closedAt ? "종료됨" : "활성"}</td></tr>)}</tbody></table></div> : <p className="muted">원본 연결 이력이 없습니다.</p>}
        <details><summary>가상 장치 상세 데이터</summary><pre aria-label="VD 상세 JSON">{JSON.stringify(detail, null, 2)}</pre></details>
        {detail.vd.state === "REGISTERED" && <div className="release-controls">{confirmRelease ? <><p>이 VD의 원본 연결을 종료합니다. VD ID와 연결 이력, 원본 장치는 보존됩니다.</p><div className="toolbar"><button disabled={busy} onClick={() => void run(async () => { await api(`virtual-devices/${detail.vd.id}`, write("DELETE")); await show(detail.vd.id); await load(); setNotice("가상 장치를 해제했습니다."); })}>VD 해제 확정</button><button disabled={busy} onClick={() => setConfirmRelease(false)}>계속 사용</button></div></> : <button disabled={busy} onClick={() => setConfirmRelease(true)}>VD 해제</button>}</div>}
      </section>}
      </div><details className="create-panel" ref={createPanel} open={!!selectedProfile || !!initialDevice || undefined}><summary><h2 id="vd-register-title">새 가상 장치 등록</h2></summary>
        <form key={formVersion} onSubmit={register} aria-label="VD 등록"><fieldset disabled={busy} className="publish-fields">
          <div className="form-row"><label>VD 키<input name="key" required maxLength={100} pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" placeholder="factory-a-virtual-sensor" /></label><label>VD 이름<input name="displayName" required maxLength={128} /></label></div>
          <label>VD 템플릿 버전<select aria-label="VD 템플릿 버전" name="profileVersionId" required value={selectedProfile} onChange={e => setSelectedProfile(e.target.value)}><option value="">프로필 선택</option>{profiles.map(p => <option key={p.id} value={p.id}>{p.key} · {p.version}</option>)}</select></label>
          {!profiles.length && <p className="hint"><a href="/profiles?kind=VD">VD Profile을 먼저 등록하세요 →</a></p>}
          {template && <>
            <dl><div><dt>템플릿 종류</dt><dd>{vdTypeNames[record(template.spec).type as keyof typeof vdTypeNames] || "규격 확인 필요"}</dd></div><div><dt>처리 SERVICE 모듈</dt><dd>{profileName(String(record(template.spec).serviceProfileVersionId), serviceProfiles)}</dd></div></dl>
            <SourceFields key={selectedProfile} devices={devices} spec={template.spec} initial={Object.keys(sourceRequirements(template.spec)).map((sourceKey, index) => ({ sourceKey, deviceId: index === 0 ? initialDevice : "" }))} />
            <PlacementFields nodes={nodes} />
          </>}
          <button className="primary" disabled={!template}>VD 등록</button>
        </fieldset></form>
      </details>
    </>}
  </>;
}
