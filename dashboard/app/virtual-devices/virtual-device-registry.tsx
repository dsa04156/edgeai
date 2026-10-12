"use client";
import { useEffect, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import { stringify } from "lossless-json";
import { EmptyState } from "../components/empty-state";
import type { components } from "../../lib/api-schema";
import { VirtualDeviceExecution } from "./virtual-device-execution";
import { registryItems } from "../../lib/registry";
import { compatibleSource, sourceRequirements, record, vdTypeNames, type VDSourceRequirement } from "../../lib/vd-profile";
import { hardwareLabel } from "../../lib/hardware";
import { ConnectionPanel } from "../components/connection-panel";
import { VDPrerequisites } from "./vd-prerequisites";
import { readProfilePage } from "../../lib/profile-version";
import { SensorProcessingRegistration } from "./sensor-processing-registration";

type Schema = components["schemas"];
type VD = Schema["VirtualDevice"];
type Detail = Schema["VirtualDeviceDetail"];
type Source = Omit<Schema["VirtualDeviceSources"][number], "maxAgeSeconds"> & { maxAgeSeconds?: number };
type SaveFeedback = { scope: "register" | "update"; state: "pending" | "error" | "success"; message: string; displayName?: string; refreshError?: string };
const stateNames = { REGISTERED: "등록됨", RELEASED: "해제됨" };
const sourceNames = { physical: "실제 센서", virtual: "Virtual 디바이스", legacy: "기존 원본 · 전환 필요" };

function SourceFields({ initial = [], devices, spec, onSelect }: { initial?: Source[]; devices: Schema["ManagedDevice"][]; spec: unknown; onSelect?: (sourceKey: string, deviceId: string) => void }) {
  const requirements = sourceRequirements(spec);
  const selected = new Map(initial.map(source => [source.sourceKey, source]));
  return <fieldset className="publish-fields"><legend>원본 연결 · Source Binding</legend>
    {Object.entries(requirements).map(([key, condition]) => <SourceSlot key={key} sourceKey={key} condition={condition} devices={devices} initial={selected.get(key)} onSelect={onSelect} />)}
    {!Object.keys(requirements).length && <p className="muted">이 템플릿은 원본 연결을 선언하지 않습니다.</p>}
    <p className="hint">템플릿의 DeviceModel과 일치하는 센서 디바이스를 선택합니다. 오프라인 장치도 연결할 수 있으며 수신 상태는 센서 화면에서 확인합니다. <a href="/devices?view=sensors">센서 디바이스 관리 →</a></p>
  </fieldset>;
}
function SourceSlot({ sourceKey, condition, devices, initial, onSelect }: { sourceKey: string; condition: VDSourceRequirement; devices: Schema["ManagedDevice"][]; initial?: Source; onSelect?: (sourceKey: string, deviceId: string) => void }) {
  const choices = devices.filter(device => compatibleSource(device, condition));
  const [deviceId, setDeviceId] = useState(choices.some(device => device.id === initial?.deviceId) ? initial!.deviceId : "");
  const device = choices.find(item => item.id === deviceId);
  const [properties, setProperties] = useState(initial?.properties || []);
  const [maxAge, setMaxAge] = useState(initial?.maxAgeSeconds ?? 30);
  return <div className="publish-fields"><label>{sourceKey} · {condition.required ? "필수 원본" : "선택 원본"}
    {deviceId && <input type="hidden" name="sourceKey" value={sourceKey} />}
    <select aria-label={`${sourceKey} · ${condition.required ? "필수" : "선택"} 원본`} name={deviceId ? "deviceId" : undefined} required={condition.required} value={deviceId} onChange={event => { setDeviceId(event.target.value); setProperties([]); onSelect?.(sourceKey, event.target.value); }}>
      <option value="">{condition.required ? "호환 장치 선택" : "연결하지 않음"}</option>{choices.map(device => <option key={device.id} value={device.id}>{device.id} · {device.state}</option>)}
    </select></label>
    {device && <>
      <input type="hidden" name="properties" value={JSON.stringify(properties)} />
      <fieldset className="retry-errors"><legend>{sourceKey} · BATCH 측정 항목</legend>
        {(device.profile.properties || []).map(property => <label key={property.name}>
          <input type="checkbox" aria-label={`${sourceKey} · ${property.name} 선택`} checked={properties.includes(property.name)} onChange={event => setProperties(previous => event.target.checked ? [...previous, property.name] : previous.filter(name => name !== property.name))} />
          {property.name}{property.unit ? ` (${property.unit})` : ""}
          <span className="muted"> · {device.properties[property.name]?.timestamp > 0 ? `${device.properties[property.name].value} · ${new Date(device.properties[property.name].timestamp).toLocaleString()}` : "미수집"}</span>
        </label>)}
      </fieldset>
      <label>{sourceKey} · 최대 수신 경과 시간(초)<input aria-label={`${sourceKey} · 최대 수신 경과 시간(초)`} name="maxAgeSeconds" type="number" min={1} max={300} required value={maxAge} onChange={event => setMaxAge(Number(event.target.value))} /></label>
      <p className="hint">실행 요청 시 선택 항목의 최신값을 고정합니다. 항목을 선택하지 않았거나 값이 오래됐으면 실행 요청이 거절됩니다. 표시된 값은 마지막 조회 값입니다.</p>
    </>}
    {!choices.length && <p className="hint">이 DeviceModel과 일치하는 센서 디바이스가 없습니다. 센서 관리에서 장치를 등록하거나 템플릿의 모델을 바꾸세요.</p>}
  </div>;
}
function PlacementFields({ initial = { mode: "AUTO" }, nodes }: { initial?: VD["placement"]; nodes: Schema["ExecutionNode"][] }) {
  const [mode, setMode] = useState(initial.mode);
  return <><label>배치 의도<select aria-label="배치 의도" name="mode" value={mode} onChange={e => setMode(e.target.value as "AUTO" | "NODE")}><option value="AUTO">자동 배치</option><option value="NODE">특정 노드 지정</option></select></label>
    {mode === "NODE" && <label>배치 노드<select aria-label="배치 노드" name="nodeId" required defaultValue={initial.mode === "NODE" ? initial.nodeId : ""}><option value="">노드 선택</option>{nodes.map(n => <option key={n.id} value={n.id} disabled={n.status !== "READY"}>{n.name} · {hardwareLabel(n)}</option>)}</select></label>}
    <p className="hint">새 VD는 등록 후 실행을 시작할 수 있습니다. 실행 중인 VD의 원본·배치를 바꾸면 이전 실행 종료 후 교체합니다.</p></>;
}
function configuration(form: FormData) {
  const keys = form.getAll("sourceKey"), devices = form.getAll("deviceId"), selections = form.getAll("properties"), ages = form.getAll("maxAgeSeconds");
  return { displayName: String(form.get("displayName")), sources: keys.map((key, index) => ({ sourceKey: String(key), deviceId: String(devices[index]), properties: JSON.parse(String(selections[index] || "[]")) as string[], maxAgeSeconds: Number(ages[index] || 30) })),
    placement: form.get("mode") === "NODE" ? { mode: "NODE", nodeId: String(form.get("nodeId")) } : { mode: "AUTO" } };
}

export function VirtualDeviceRegistry({ workflows = false }: { workflows?: boolean }) {
  const createPanel = useRef<HTMLDetailsElement>(null);
  const registerForm = useRef<HTMLFormElement>(null);
  const updateForm = useRef<HTMLFormElement>(null);
  const registerError = useRef<HTMLParagraphElement>(null);
  const updateResult = useRef<HTMLParagraphElement>(null);
  const savedResult = useRef<HTMLParagraphElement>(null);
  function openCreatePanel() {
    if (!createPanel.current) return;
    setSaveFeedback(null); setCreateOpen(true);
    createPanel.current.open = true;
    createPanel.current.scrollIntoView({ block: "start" });
    createPanel.current.querySelector<HTMLInputElement>('input[name="key"]')?.focus({ preventScroll: true });
  }
  const [auth, setAuth] = useState(""); const [csrf, setCsrf] = useState("");
  const [busy, setBusy] = useState(false); const [error, setError] = useState(""); const [notice, setNotice] = useState("");
  const [deleting, setDeleting] = useState<string | null>(null);
  const [page, setPage] = useState<{ items: VD[]; nextOffset: number | null } | null>(null); const [offset, setOffset] = useState(0);
  const [serviceProfiles, setServiceProfiles] = useState<Schema["ProfileVersion"][]>([]);
  const [profiles, setProfiles] = useState<Schema["ProfileVersion"][]>([]); const [devices, setDevices] = useState<Schema["ManagedDevice"][]>([]);
  const [nodes, setNodes] = useState<Schema["ExecutionNode"][]>([]);
  const [selectedProfile, setSelectedProfile] = useState("");
  const [updateProfile, setUpdateProfile] = useState("");
  const [initialDevice, setInitialDevice] = useState("");
  const [processingReady, setProcessingReady] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [sourceSelections, setSourceSelections] = useState<Record<string, string>>({});
  const [saveFeedback, setSaveFeedback] = useState<SaveFeedback | null>(null);
  const [detail, setDetail] = useState<Detail | null>(null); const [confirmRelease, setConfirmRelease] = useState(false); const [formVersion, setFormVersion] = useState(0);
  useEffect(() => {
    if (!saveFeedback || saveFeedback.state === "pending") return;
    const form = saveFeedback.scope === "register" ? registerForm.current : updateForm.current;
    const target = saveFeedback.state === "error" ? form?.querySelector<HTMLElement>("input:invalid, textarea:invalid, select:invalid") || (saveFeedback.scope === "register" ? registerError.current : updateResult.current)
      : saveFeedback.scope === "update" ? updateResult.current : savedResult.current;
    target?.focus({ preventScroll: true }); target?.scrollIntoView({ block: "center" });
  }, [saveFeedback, detail?.vd.revision]);
  async function api(path: string, init?: RequestInit, authorization = auth) {
    const response = await fetch(`/api/control-plane/${path}`, { ...init, cache: "no-store", headers: { ...(authorization.startsWith("Basic ") ? { Authorization: authorization } : {}), ...init?.headers } });
    if (!response.ok) {
      const value = await response.json().catch(() => ({}));
      throw new Error(response.status === 401 ? "API 연결을 확인하세요." : response.status === 403 ? "연결이 만료되었습니다. 화면을 새로고침하세요." : value.message || "요청을 처리하지 못했습니다.");
    }
    return response;
  }
  async function run(action: () => Promise<void>) { setBusy(true); setError(""); setNotice(""); setSaveFeedback(null); try { await action(); } catch (e) { setError(e instanceof Error ? e.message : "요청을 처리하지 못했습니다."); } finally { setBusy(false); } }
  async function load(nextOffset = offset, authorization = auth) {
    setPage(await (await api(`virtual-devices?limit=20&offset=${nextOffset}`, undefined, authorization)).json()); setOffset(nextOffset);
  }
  async function show(id: string) { const value: Detail=await (await api(`virtual-devices/${id}`)).json(); setDetail(value); setUpdateProfile(value.vd.profileVersionId); setConfirmRelease(false); }
  function write(method: string, body?: object): RequestInit {
    return { method, headers: { "X-CSRF-TOKEN": csrf, ...(body ? { "Content-Type": "application/json" } : {}) }, ...(body ? { body: stringify(body) } : {}) };
  }
  function connect() {
    void run(async () => {
      const authorization = "dashboard";
      const token = await (await api("csrf", undefined, authorization)).json();
      await load(0, authorization);
      const [profileItems, deviceItems, nodeItems, serviceItems] = await Promise.all([
        registryItems<Schema["ProfileVersion"]>("profiles/VD", path => api(path, undefined, authorization), readProfilePage),
        api("managed-devices",undefined,authorization).then(r=>r.json()).then((page: Schema["ManagedDevicePage"])=>page.items),
        registryItems<Schema["ExecutionNode"]>("nodes", path => api(path, undefined, authorization)),
        registryItems<Schema["ProfileVersion"]>("profiles/SERVICE", path => api(path, undefined, authorization), readProfilePage),
      ]);
      setServiceProfiles(serviceItems); setProfiles(profileItems); setDevices(deviceItems); setNodes(nodeItems);
      try { setProcessingReady((await (await api("virtual-devices/processing-catalog", undefined, authorization)).json()).ready === true); }
      catch { setProcessingReady(false); }
      const query = new URLSearchParams(window.location.search);
      setSelectedProfile(query.get("profile") || ""); setInitialDevice(query.get("device") || "");
      setCreateOpen(query.has("profile") || query.has("device") || query.get("create") === "1");
      if (query.get("vd")) { const value: Detail=await (await api(`virtual-devices/${query.get("vd")}`,undefined,authorization)).json();setDetail(value);setUpdateProfile(value.vd.profileVersionId); }
      setAuth(authorization); setCsrf(token.token);
    });
  }

  async function save(event: FormEvent<HTMLFormElement>, scope: "register" | "update") {
    event.preventDefault(); if (busy) return;
    const input = new FormData(event.currentTarget);
    const vd = detail?.vd;
    if (scope === "update" && !vd) return;
    setBusy(true); setError(""); setNotice("");
    setSaveFeedback({ scope, state: "pending", message: scope === "register" ? "가상 장치를 등록하고 있습니다…" : "설정을 저장하고 있습니다…" });
    try {
      const response = await api(scope === "register" ? "virtual-devices" : `virtual-devices/${vd!.id}`,
        write(scope === "register" ? "POST" : "PATCH", scope === "register" ? { key: String(input.get("key")), profileVersionId: String(input.get("profileVersionId")), ...configuration(input) }
          : { revision: vd!.revision, profileVersionId: String(input.get("profileVersionId")), ...configuration(input) }));
      const saved: VD = await response.json();
      const feedback: SaveFeedback = { scope, state: "success", displayName: saved.displayName, message: scope === "update" ? "VD 설정을 저장했습니다. 기존 원본 연결 이력은 보존됩니다."
        : response.status === 201 ? "가상 장치와 원본 연결을 등록했습니다." : "이미 등록된 가상 장치입니다. 현재 상태를 확인하세요." };
      setSaveFeedback(feedback);
      if (scope === "register") { setCreateOpen(false); setFormVersion(value => value + 1); }
      const failures: string[] = [];
      try { await show(saved.id); } catch { failures.push("상세"); }
      try { await load(scope === "register" ? 0 : offset); } catch { failures.push("목록"); }
      if (failures.length) setSaveFeedback({ ...feedback, refreshError: `저장은 완료됐지만 ${failures.join("·")} 조회에 실패했습니다. 새로고침으로 저장된 상태를 확인하세요.` });
    } catch (error) { setSaveFeedback({ scope, state: "error", message: error instanceof Error ? error.message : "저장하지 못했습니다." }); }
    finally { setBusy(false); }
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
  const detailTemplate = profiles.find(profile => profile.id === updateProfile);
  const profileName = (id: string, values = profiles) => { const profile = values.find(value => value.id === id); return profile ? `${profile.key} · ${profile.version}` : "프로필 조회 필요"; };
  const deviceName = (id: string) => devices.find(device => device.id === id)?.id || id;
  function onProfileRegistered(profile: Schema["ProfileVersion"]) {
    const update = (previous: Schema["ProfileVersion"][]) => [...previous.filter(value => value.id !== profile.id), profile];
    if (profile.kind === "SERVICE") setServiceProfiles(update);
    else { setProfiles(update); setSelectedProfile(profile.id); setSourceSelections({}); }
  }
  async function onProcessingRegistered(vd: VD) {
    const feedback: SaveFeedback = { scope: "register", state: "success", displayName: vd.displayName, message: "센서 처리 장치를 등록했습니다." };
    setSaveFeedback(feedback); setCreateOpen(false); setFormVersion(value => value + 1);
    try {
      const [templates, services] = await Promise.all([
        registryItems<Schema["ProfileVersion"]>("profiles/VD", path => api(path), readProfilePage),
        registryItems<Schema["ProfileVersion"]>("profiles/SERVICE", path => api(path), readProfilePage),
      ]);
      setProfiles(templates); setServiceProfiles(services); await show(vd.id); await load(0);
    } catch { setSaveFeedback({ ...feedback, refreshError: "등록은 완료됐지만 저장된 상태 조회에 실패했습니다. 새로고침으로 확인하세요." }); }
  }
  function invalid(scope: "register" | "update") {
    setSaveFeedback({ scope, state: "error", message: "필수 입력과 형식을 확인하세요. 원본이 필수인 템플릿은 호환 장치를 선택해야 합니다." });
  }
  const registrationFeedback = saveFeedback?.scope === "register" ? saveFeedback : null;
  const updateFeedback = saveFeedback?.scope === "update" ? saveFeedback : null;
  return <>
    <div className="toolbar"><a href="/profiles?kind=VD">VD 템플릿 관리 →</a><Link href="/docs/concepts/resources">리소스 구조 보기 →</Link></div>
    <ConnectionPanel connected={!!auth} busy={busy} onConnect={connect} />
    <div aria-live="polite" aria-atomic="true">{notice && <p className="notice">{notice}</p>}</div>
    {error && <p role="alert" className="error">{error}</p>}
    {registrationFeedback?.state === "success" && <div>
      <p className="notice" role="status" ref={savedResult} tabIndex={-1}>{registrationFeedback.message} <strong>{registrationFeedback.displayName}</strong></p>
      {registrationFeedback.refreshError && <p className="error" role="alert">{registrationFeedback.refreshError}</p>}
    </div>}
    {auth && <>

      <div className="registry-workspace"><section aria-labelledby="vds-title" aria-busy={busy}>
        <div className="toolbar"><h2 id="vds-title">등록된 가상 장치</h2><div className="toolbar"><button disabled={busy} onClick={() => void run(async () => { await load(); if (detail) await show(detail.vd.id); })}>새로고침</button><button className="primary" disabled={busy} onClick={openCreatePanel}>가상 장치 등록</button></div></div>
        {busy && <p role="status">처리 중…</p>}
        {page?.items.length === 0 && <EmptyState title="등록된 가상 장치가 없습니다" description="센서와 처리 기능을 선택해 가상 장치를 구성하세요." action={<button disabled={busy} onClick={openCreatePanel}>첫 가상 장치 등록</button>} />}
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
        {record(profiles.find(p=>p.id===detail.vd.profileVersionId)?.spec).apiVersion==="edgeai.vd/v2" ? <VirtualDeviceExecution key={detail.vd.id} vd={detail.vd} auth={auth} csrf={csrf} disabled={busy} /> : <p className="notice">기존 앱 등록 장치를 참조하는 VD입니다. 센서용 템플릿의 새 버전을 발행하고 아래에서 템플릿과 원본 센서를 선택해 저장하세요. VD ID와 연결 이력은 유지됩니다.</p>}
        {workflows && <p><a href={`/workflows?service=${detail.vd.serviceProfileVersionId}&vd=${detail.vd.id}`}>이 VD의 활성 Runtime으로 DAG 실행 구성 →</a></p>}
        <h3>현재 원본 연결 · Source Binding</h3>{detail.activeSources.length ? <ul className="history-list">{detail.activeSources.map(source => <li key={source.id}><strong>{source.sourceKey}</strong> · {sourceNames[source.sourceKind]}<span className="block">{deviceName(source.deviceId)}</span><span className="block muted">{source.properties.length ? source.properties.join(", ") : "측정 항목 미선택"} · 최대 {source.maxAgeSeconds}초</span></li>)}</ul> : <p className="muted">활성 원본 연결이 없습니다.</p>}
        {detail.vd.state === "REGISTERED" && <form ref={updateForm} key={`${detail.vd.id}-${detail.vd.revision}`} aria-label="VD 설정 수정" onSubmit={event => void save(event, "update")} onInvalid={() => invalid("update")}><fieldset disabled={busy} className="publish-fields"><legend>설정 수정</legend>
          <label>표시 이름<input name="displayName" required maxLength={128} defaultValue={detail.vd.displayName} /></label>
          <label>VD 템플릿 버전<select aria-label="수정 VD 템플릿 버전" name="profileVersionId" required value={updateProfile} onChange={e=>setUpdateProfile(e.target.value)}><option value="">센서용 템플릿 선택</option>{profiles.filter(p=>record(p.spec).apiVersion==="edgeai.vd/v2").map(p=><option key={p.id} value={p.id}>{p.key} · {p.version}</option>)}</select></label>
          <SourceFields key={updateProfile} devices={devices} spec={detailTemplate?.spec} initial={detail.activeSources.map(source => ({ sourceKey: source.sourceKey, deviceId: source.deviceId, properties: source.properties, maxAgeSeconds: source.maxAgeSeconds }))} />
          <PlacementFields nodes={nodes} initial={detail.vd.placement} />
          {updateFeedback && <p ref={updateResult} tabIndex={-1} role={updateFeedback.state === "error" ? "alert" : "status"} className={updateFeedback.state === "error" ? "error" : updateFeedback.state === "success" ? "notice" : "hint"}>{updateFeedback.message}</p>}
          {updateFeedback?.refreshError && <p className="error" role="alert">{updateFeedback.refreshError}</p>}
          <button>{updateFeedback?.state === "pending" ? "저장 중…" : "VD 설정 저장"}</button>
        </fieldset></form>}
        <h3>원본 연결 이력</h3>
        {detail.sourceHistoryTruncated && <p className="hint">최근 연결 100개를 표시합니다. 현재 활성 연결은 위에서 모두 확인할 수 있습니다.</p>}
        {detail.sourceHistory.length ? <div className="table-scroll"><table className="vd-table"><caption className="sr-only">VD 원본 연결 이력</caption><thead><tr><th>원본 / Device</th><th>출처</th><th>변경 버전</th><th>연결 상태</th></tr></thead><tbody>{detail.sourceHistory.map(source => <tr key={source.id}><td>{source.sourceKey}<span className="block">{deviceName(source.deviceId)}</span><span className="block muted">{source.properties.length ? source.properties.join(", ") : "측정 항목 미선택"} · 최대 {source.maxAgeSeconds}초</span></td><td>{sourceNames[source.sourceKind]}</td><td>{source.openedRevision} → {source.closedRevision ?? "현재"}</td><td>{source.closedAt ? "종료됨" : "활성"}</td></tr>)}</tbody></table></div> : <p className="muted">원본 연결 이력이 없습니다.</p>}
        <details><summary>가상 장치 상세 데이터</summary><pre aria-label="VD 상세 JSON">{JSON.stringify(detail, null, 2)}</pre></details>
        {detail.vd.state === "REGISTERED" && <div className="release-controls">{confirmRelease ? <><p>이 VD의 원본 연결을 종료합니다. VD ID와 연결 이력, 원본 장치는 보존됩니다.</p><div className="toolbar"><button disabled={busy} onClick={() => void run(async () => { await api(`virtual-devices/${detail.vd.id}`, write("DELETE")); await show(detail.vd.id); await load(); setNotice("가상 장치를 해제했습니다."); })}>VD 해제 확정</button><button disabled={busy} onClick={() => setConfirmRelease(false)}>계속 사용</button></div></> : <button disabled={busy} onClick={() => setConfirmRelease(true)}>VD 해제</button>}</div>}
      </section>}
      </div><details className="create-panel" ref={createPanel} open={createOpen} onToggle={event => setCreateOpen(event.currentTarget.open)}><summary><h2 id="vd-register-title">새 가상 장치 등록</h2></summary>
        <SensorProcessingRegistration key={`processing-${formVersion}-${initialDevice}`} devices={devices} initialDevice={initialDevice} ready={processingReady} busy={busy} onBusy={setBusy}
          create={async body => (await api("virtual-devices/processing", write("POST", body))).json()} onRegistered={onProcessingRegistered} />
        <details open={!!selectedProfile}><summary>템플릿으로 등록 · 고급 설정</summary>
        <fieldset disabled={busy} className="publish-fields"><VDPrerequisites profiles={[...serviceProfiles, ...profiles]} nodes={nodes} template={template}
          write={(path, body) => api(path, write("POST", body))} onProfileRegistered={onProfileRegistered} onBusy={setBusy} /></fieldset>
        <h3>가상 장치 구성</h3>
        <form ref={registerForm} key={formVersion} onSubmit={event => void save(event, "register")} onInvalid={() => invalid("register")} aria-label="VD 등록"><fieldset disabled={busy} className="publish-fields">
          <div className="form-row"><label>VD 키<input name="key" required maxLength={100} pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" placeholder="factory-a-virtual-sensor" /></label><label>VD 이름<input name="displayName" required maxLength={128} /></label></div>
          <label>VD 템플릿 버전<select aria-label="VD 템플릿 버전" name="profileVersionId" required value={selectedProfile} onChange={e => { setSelectedProfile(e.target.value); setSourceSelections({}); }}><option value="">프로필 선택</option>{profiles.filter(p=>record(p.spec).apiVersion==="edgeai.vd/v2").map(p => <option key={p.id} value={p.id}>{p.key} · {p.version}</option>)}</select></label>
          {!profiles.length && <p className="hint">위의 VD 템플릿 등록·수정에서 첫 템플릿을 발행하면 여기에 자동 선택됩니다.</p>}
          {template && <>
            <dl><div><dt>템플릿 종류</dt><dd>{vdTypeNames[record(template.spec).type as keyof typeof vdTypeNames] || "규격 확인 필요"}</dd></div><div><dt>처리 SERVICE 모듈</dt><dd>{profileName(String(record(template.spec).serviceProfileVersionId), serviceProfiles)}</dd></div></dl>
            <SourceFields key={selectedProfile} devices={devices} spec={template.spec} onSelect={(slot, deviceId) => setSourceSelections(previous => ({ ...previous, [slot]: deviceId }))} initial={Object.keys(sourceRequirements(template.spec)).map((sourceKey, index) => ({ sourceKey, deviceId: sourceSelections[sourceKey] ?? (index === 0 ? initialDevice : "") }))} />
            <PlacementFields nodes={nodes} />
          </>}
          {registrationFeedback && registrationFeedback.state !== "success" && <p ref={registerError} tabIndex={-1} role={registrationFeedback.state === "error" ? "alert" : "status"} className={registrationFeedback.state === "error" ? "error" : "hint"}>{registrationFeedback.message}</p>}
          <button className="primary" disabled={!template}>{registrationFeedback?.state === "pending" ? "등록 중…" : "VD 등록"}</button>
        </fieldset></form></details>
      </details>
    </>}
  </>;
}
