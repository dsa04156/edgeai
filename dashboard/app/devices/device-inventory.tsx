"use client";
import { useRef, useState, type FormEvent } from "react";
import { parse, stringify } from "lossless-json";
import type { components } from "../../lib/api-schema";
import { StatusBadge } from "../components/status-badge";
import { ConnectionPanel } from "../components/connection-panel";
import { registryItems } from "../../lib/registry";
import { hardwareLabel } from "../../lib/hardware";
import { useNodeMetrics } from "../../lib/node-metrics";
import { NodeMetricsNotice, NodeUsage } from "../components/node-usage";
import { EmptyState } from "../components/empty-state";
import { MonitoredNodes } from "../components/monitored-nodes";
import { infrastructureLabels, useInfrastructure, type InfrastructureKind } from "../../lib/infrastructure";
import { ClusterSensors } from "../components/cluster-sensors";
import { SensorRegistration } from "../components/sensor-registration";

type Schema = components["schemas"];
type Device = Schema["Device"];
type Detail = Schema["DeviceDetail"];
type Node = Schema["ExecutionNode"];
const sourceNames = { LIVE: "실제 장치", REPLAY: "재생 데이터", SYNTHETIC: "합성 데이터" };
const statusNames: Record<string, string> = { ONLINE: "온라인", OFFLINE: "오프라인", UNKNOWN: "보고 없음", STALE: "관측 만료", RELEASED: "해제됨", READY: "준비됨", NOT_READY: "준비 안 됨", REMOVED: "관측에서 제거됨" };

export function DeviceInventory() {
  const createPanel = useRef<HTMLDetailsElement>(null);
  function openCreatePanel() {
    if (!createPanel.current) return;
    createPanel.current.open = true;
    createPanel.current.scrollIntoView({ block: "start" });
    createPanel.current.querySelector<HTMLInputElement>('input[name="key"]')?.focus({ preventScroll: true });
  }
  const [view, setView] = useState("servers"); const [search, setSearch] = useState(""); const [selectedNode, setSelectedNode] = useState("");
  const [deleting, setDeleting] = useState<string | null>(null);
  const [metricsRefresh, setMetricsRefresh] = useState(0);
  const metrics = useNodeMetrics(!["devices", "sensors"].includes(view), metricsRefresh);
  const infrastructure = useInfrastructure(metricsRefresh);
  const group: InfrastructureKind = view === "servers" ? "EDGE_AI_SERVER" : view === "edge" ? "EDGE_DEVICE" : "UNKNOWN";
  const [auth, setAuth] = useState(""); const [csrf, setCsrf] = useState("");
  const [busy, setBusy] = useState(false); const [error, setError] = useState(""); const [notice, setNotice] = useState("");
  const [page, setPage] = useState<Schema["DevicePage"] | null>(null); const [offset, setOffset] = useState(0);
  const [nodes, setNodes] = useState<Node[]>([]); const [nodeOffset, setNodeOffset] = useState(0); const [nextNodeOffset, setNextNodeOffset] = useState<number | null>(null);
  const [initialProfile, setInitialProfile] = useState("");
  const [profiles, setProfiles] = useState<Schema["ProfileVersion"][]>([]);
  const [detail, setDetail] = useState<Detail | null>(null); const [rawDetail, setRawDetail] = useState(""); const [confirmRelease, setConfirmRelease] = useState(false);
  async function api(path: string, init?: RequestInit, authorization = auth) {
    const response = await fetch(`/api/control-plane/${path}`, { ...init, cache: "no-store", headers: { ...(authorization.startsWith("Basic ") ? { Authorization: authorization } : {}), ...init?.headers } });
    if (!response.ok) {
      const value = await response.json().catch(() => ({}));
      throw new Error(response.status === 401 ? "API 연결을 확인하세요." : response.status === 403 ? "연결이 만료되었습니다. 화면을 새로고침하세요." : value.message || "요청을 처리하지 못했습니다.");
    }
    return response;
  }
  async function run(action: () => Promise<void>) { setBusy(true); setError(""); setNotice(""); try { await action(); } catch (e) { setError(e instanceof Error ? e.message : "요청을 처리하지 못했습니다."); } finally { setBusy(false); } }
  async function loadDevices(nextOffset = offset, authorization = auth) {
    setPage(await (await api(`devices?limit=20&offset=${nextOffset}`, undefined, authorization)).json()); setOffset(nextOffset);
  }
  async function loadNodes(nextOffset = nodeOffset, authorization = auth) {
    const value: Schema["NodePage"] = await (await api(`nodes?limit=20&offset=${nextOffset}`, undefined, authorization)).json();
    setNodes(value.items); setNodeOffset(nextOffset); setNextNodeOffset(value.nextOffset);
  }
  async function show(id: string) {
    const text = await (await api(`devices/${id}`)).text(); setDetail(JSON.parse(text)); setRawDetail(stringify(parse(text), null, 2) || ""); setConfirmRelease(false);
  }
  function connect() {
    void run(async () => {
      const authorization = "dashboard";
      const token = await (await api("csrf", undefined, authorization)).json();
      await loadDevices(0, authorization); await loadNodes(0, authorization);
      setProfiles(await registryItems("profiles/DEVICE", path => api(path, undefined, authorization)));
      const query = new URLSearchParams(window.location.search);
      setInitialProfile(query.get("profile") || ""); setView(query.get("view") === "devices" || query.has("profile") || query.has("device") ? "devices" : ["servers", "edge", "sensors", "unknown"].includes(query.get("view") || "") ? query.get("view")! : "servers"); setSelectedNode(query.get("node") || "");
      setSearch(query.get("search") || "");
      if (query.get("device")) await show(query.get("device")!);
      setAuth(authorization); setCsrf(token.token);
    });
  }

  function write(method: string, body?: object): RequestInit {
    return { method, headers: { "X-CSRF-TOKEN": csrf, ...(body ? { "Content-Type": "application/json" } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) };
  }
  function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = event.currentTarget; const input = new FormData(form);
    void run(async () => {
      const response = await api("devices", write("POST", Object.fromEntries(input)));
      const value: Device = await response.json(); await show(value.id); await loadDevices(0);
      setNotice(response.status === 201 ? "장치를 등록했습니다. 세션의 상태 보고를 기다립니다." : "이미 등록된 장치입니다."); form.reset();
    });
  }
  function remove(device: Device) {
    void run(async () => {
      await api(`devices/${device.id}/registration`, write("DELETE"));
      if (detail?.device.id === device.id) { setDetail(null); setRawDetail(""); }
      setDeleting(null);
      await loadDevices(page?.items.length === 1 ? Math.max(0, offset - 20) : offset);
      setNotice(`${device.displayName} 장치를 삭제했습니다.`);
    });
  }
  return <>
    <ConnectionPanel connected={!!auth} busy={busy} onConnect={connect} />
    <div aria-live="polite" aria-atomic="true">{notice && <p className="notice">{notice}</p>}</div>
    {error && <p role="alert" className="error">{error}</p>}
    <>
      <div className="inventory-controls"><div className="filter-chips" role="group" aria-label="장치 관리 보기">{[["servers", "엣지 AI 서버"], ["edge", "엣지 디바이스"], ["sensors", "센서"], ["devices", "등록 장치"], ...(infrastructure.snapshot?.nodesStatus !== "AVAILABLE" || infrastructure.snapshot.nodes.some(node => node.kind === "UNKNOWN") ? [["unknown", "분류 미확인"]] : [])].map(([value, label]) => <button key={value} aria-pressed={view === value} onClick={() => { setView(value); setSearch(""); }}>{label}</button>)}</div><label>현재 페이지에서 검색<input type="search" value={search} onChange={e => setSearch(e.target.value)} placeholder="이름 · 장비 · 측정 항목" /></label></div>
      {view === "sensors" && <section className="console-panel"><div className="panel-heading"><h2>센서 {infrastructure.snapshot?.sensorsStatus === "AVAILABLE" && <span className="count-badge">{infrastructure.snapshot.sensors.length}</span>}</h2><button onClick={() => setMetricsRefresh(value => value + 1)}>새로고침</button></div><SensorRegistration onRegistered={() => { setSearch(""); setMetricsRefresh(value => value + 1); }} /><ClusterSensors snapshot={infrastructure.snapshot} failed={infrastructure.failed} search={search} /></section>}
      {view === "devices" && auth && <>
      <div className="registry-workspace"><section aria-labelledby="devices-title" aria-busy={busy}>
        <div className="toolbar"><h2 id="devices-title">등록된 장치</h2><div className="toolbar"><button disabled={busy} onClick={() => void run(async () => { await loadDevices(); if (detail) await show(detail.device.id); })}>새로고침</button><button className="primary" onClick={openCreatePanel}>장치 등록</button></div></div>
        {busy && <p role="status">처리 중…</p>}
        {page?.items.length === 0 && <EmptyState title="등록된 장치가 없습니다" description="장치 규격을 선택하고 첫 장치를 연결하세요. 연결 이후 수신 상태를 확인할 수 있습니다." action={profiles.length ? <button onClick={openCreatePanel}>첫 장치 등록</button> : <a className="button-link" href="/profiles?kind=DEVICE">장치 Profile 등록</a>} />}
        {!!page?.items.length && <div className="table-scroll"><table className="registry-table"><caption className="sr-only">등록된 장치 목록</caption><thead><tr><th>장치</th><th>데이터 출처</th><th>연결 상태</th><th>마지막 수신</th><th>관리</th></tr></thead>
          <tbody>{page.items.filter(d => `${d.displayName} ${d.key}`.toLowerCase().includes(search.toLowerCase())).map(d => <tr key={d.id} data-selected={detail?.device.id === d.id}><td><button className="version-link" disabled={busy} onClick={() => void run(() => show(d.id))}>{d.displayName}<span className="mono">{d.key}</span></button></td>
            <td>{sourceNames[d.sourceMode]}</td><td><StatusBadge state={d.connectionStatus} /></td><td>{d.lastSeenAt ? new Date(d.lastSeenAt).toLocaleString() : "—"}</td>
            <td>{deleting === d.id ? <div><span>등록과 관측 이력을 삭제할까요?</span><div className="toolbar"><button disabled={busy} onClick={() => remove(d)}>삭제 확인</button><button disabled={busy} onClick={() => setDeleting(null)}>취소</button></div></div>
              : <button disabled={busy} aria-label={`${d.displayName} 삭제`} onClick={() => setDeleting(d.id)}>삭제</button>}</td></tr>)}</tbody></table></div>}
        {!!page?.items.length && !page.items.some(d => `${d.displayName} ${d.key}`.toLowerCase().includes(search.toLowerCase())) && <p className="empty">현재 페이지에서 일치하는 장치가 없습니다.</p>}
        <div className="pagination"><button disabled={busy || offset === 0} onClick={() => void run(() => loadDevices(Math.max(0, offset - 20)))}>이전 장치</button><span>{offset / 20 + 1} 페이지</span><button disabled={busy || page?.nextOffset == null} onClick={() => void run(() => loadDevices(page!.nextOffset!))}>다음 장치</button></div>
      </section>
      {detail && <section className="panel" aria-labelledby="device-detail-title">
        <div className="toolbar"><h2 id="device-detail-title">{detail.device.displayName}</h2><span className="stage">{statusNames[detail.device.connectionStatus]} · {sourceNames[detail.device.sourceMode]}</span></div>
        <p className="digest mono">장치 ID {detail.device.id}</p>
        <dl><div><dt>Profile 버전 ID</dt><dd className="mono digest">{detail.device.profileVersionId}</dd></div><div><dt>현재 세션 epoch</dt><dd>{detail.device.sessionEpoch || "시작 전"}</dd></div></dl>
        {detail.device.state === "ACTIVE" && <>
          <form className="toolbar filter-form" key={`${detail.device.id}-${detail.device.revision}`} onSubmit={e => {
            e.preventDefault(); const displayName = String(new FormData(e.currentTarget).get("displayName"));
            void run(async () => { await api(`devices/${detail.device.id}`, write("PATCH", { revision: detail.device.revision, displayName })); await show(detail.device.id); await loadDevices(); setNotice("표시 이름을 수정했습니다."); });
          }}><label>표시 이름<input name="displayName" required maxLength={128} defaultValue={detail.device.displayName} /></label><button disabled={busy}>이름 저장</button></form>
          <form className="publish-fields" onSubmit={e => {
            e.preventDefault(); const input = new FormData(e.currentTarget);
            void run(async () => { await api(`devices/${detail.device.id}/attachments/${input.get("nodeId")}`, write("PUT", { port: input.get("port") })); await show(detail.device.id); setNotice("장치와 노드를 연결했습니다."); });
          }}><div className="form-row"><label>연결할 노드<select name="nodeId" required defaultValue=""><option value="" disabled>아래 노드 목록의 현재 페이지에서 선택</option>{nodes.map(n => <option key={n.id} value={n.id} disabled={n.status !== "READY"}>{n.name} · {statusNames[n.status]}</option>)}</select></label>
            <label>연결 포트<input name="port" required maxLength={128} placeholder="camera-0 / ttyUSB0" /></label></div><button disabled={busy || !nodes.some(n => n.status === "READY")}>노드 연결</button></form>
        </>}
        <p><a href={`/virtual-devices?device=${detail.device.id}`}>이 장치로 가상 장치 만들기 →</a></p>
        <h3>최근 연결 이력</h3>{detail.attachments.length ? <ul className="history-list">{detail.attachments.map(a => <li key={a.id}><span className="mono">{nodes.find(n => n.id === a.nodeId)?.name || a.nodeId}</span> · {a.port} · {a.detachedAt ? "종료됨" : "활성"}</li>)}</ul> : <p className="muted">아직 연결된 노드가 없습니다.</p>}
        <h3>최근 관측</h3>{detail.observations.length ? <ul className="history-list">{detail.observations.map(o => <li key={o.id}>{statusNames[o.status]} · sequence {o.sequence} · {new Date(o.receivedAt).toLocaleString()}</li>)}</ul> : <p className="muted">아직 상태 보고를 받지 않았습니다. 장치 에이전트가 세션을 시작한 뒤 보고합니다.</p>}
        <details><summary>세션·관측 데이터 상세</summary><pre aria-label="장치 상세 JSON">{rawDetail}</pre></details>
        {detail.device.state === "ACTIVE" && <div className="release-controls">{confirmRelease ? <><p>이 장치를 해제하면 현재 연결과 세션이 종료됩니다. 기존 이력은 보존됩니다.</p><div className="toolbar"><button disabled={busy} onClick={() => void run(async () => { await api(`devices/${detail.device.id}`, write("DELETE")); await show(detail.device.id); await loadDevices(); setNotice("장치를 해제했습니다."); })}>해제 확정</button><button disabled={busy} onClick={() => setConfirmRelease(false)}>계속 사용</button></div></> : <button disabled={busy} onClick={() => setConfirmRelease(true)}>장치 해제</button>}</div>}
      </section>}
      </div><details className="create-panel" ref={createPanel} open={!!initialProfile || undefined}><summary><h2 id="register-title">새 장치 등록</h2></summary>
        <form onSubmit={register}><fieldset disabled={busy} className="publish-fields"><div className="form-row"><label>장치 키<input name="key" required maxLength={100} pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" placeholder="factory-a-temperature-01" /></label><label>장치 이름<input name="displayName" required maxLength={128} placeholder="A동 온도 센서" /></label></div>
          <label>DEVICE Profile 버전<select name="profileVersionId" required defaultValue={initialProfile}><option value="">프로필 선택</option>{profiles.map(p => <option key={p.id} value={p.id}>{p.key} · {p.version}</option>)}</select></label>
          {!profiles.length && <p className="hint"><a href="/profiles">DEVICE Profile을 먼저 등록하세요 →</a></p>}
          <label>데이터 출처<select name="sourceMode" defaultValue="LIVE"><option value="LIVE">실제 장치</option><option value="REPLAY">재생 데이터</option><option value="SYNTHETIC">합성 데이터</option></select></label>
          <button className="primary">장치 등록</button></fieldset></form>
      </details>
      </>}
      {!["devices", "sensors"].includes(view) && <section aria-labelledby="nodes-title"><div className="toolbar"><h2 id="nodes-title">{infrastructureLabels[group]}</h2><button disabled={busy} onClick={() => { setMetricsRefresh(value => value + 1); void run(() => loadNodes()); }}>새로고침</button></div>
        <NodeMetricsNotice state={metrics} />
        {infrastructure.snapshot?.nodesStatus !== "AVAILABLE" && <p role="status">장비 분류를 확인할 수 없습니다. 수집된 노드는 ‘분류 미확인’에서 볼 수 있습니다.</p>}
        <MonitoredNodes state={metrics} inventory={nodes} infrastructure={infrastructure.snapshot} kind={group} search={search} />
        <details className="create-panel"><summary>Kubernetes 실행 노드 기록</summary>
        <p className="hint">별도로 저장된 Kubernetes 관측 기록입니다. 60초 넘게 갱신되지 않으면 관측 만료로 표시합니다. 자원은 노드의 할당 가능량이며 현재 남은 용량과 다릅니다.</p>
        {nodes.length ? <div className="table-scroll"><table><caption className="sr-only">Kubernetes 실행 노드 목록</caption><thead><tr><th>노드 / 아키텍처</th><th>상태</th><th>할당 가능량</th><th>GPU · NPU 구성</th><th>CPU · 메모리 · 가속기 사용량</th><th>노드 관측 시각</th></tr></thead><tbody>{nodes.filter(n => `${n.name} ${n.architecture} ${hardwareLabel(n)}`.toLowerCase().includes(search.toLowerCase())).map(n => <tr key={n.id} data-selected={selectedNode === n.id}><td>{n.name}<span className="block muted mono">{n.architecture} · {n.operatingSystem}</span></td><td><StatusBadge state={n.status} /></td><td className="mono">CPU {n.cpu}<span className="block">메모리 {n.memory}</span></td><td>{hardwareLabel(n)}</td><td className="node-usage-cell"><NodeUsage node={n} state={metrics} detailed /></td><td>{new Date(n.observedAt).toLocaleString()}</td></tr>)}</tbody></table></div> : <p className="empty">관측된 노드가 없습니다. Kubernetes 연결 설정과 관측 상태를 확인하세요.</p>}
        {!!nodes.length && !nodes.some(n => `${n.name} ${n.architecture} ${hardwareLabel(n)}`.toLowerCase().includes(search.toLowerCase())) && <p className="empty">현재 페이지에서 일치하는 노드가 없습니다.</p>}
        <div className="pagination"><button disabled={busy || nodeOffset === 0} onClick={() => void run(() => loadNodes(Math.max(0, nodeOffset - 20)))}>이전 노드</button><span>{nodeOffset / 20 + 1} 페이지</span><button disabled={busy || nextNodeOffset === null} onClick={() => void run(() => loadNodes(nextNodeOffset!))}>다음 노드</button></div>
        </details>
      </section>}
    </>
  </>;
}
