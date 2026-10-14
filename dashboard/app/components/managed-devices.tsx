"use client";
import { Fragment, useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import type { components } from "../../lib/api-schema";
import { useInfrastructure } from "../../lib/infrastructure";
import { StatusBadge } from "./status-badge";
import { AddDeviceProperty, EditDeviceProperty, newProperty as property, PropertyFields } from "./managed-device-property";
import { RegistrationDialog } from "./registration-dialog";
import { SensorGraph } from "./sensor-graph";

type Device = components["schemas"]["ManagedDevice"];
type Input = components["schemas"]["ManagedDeviceInput"];
type Property = components["schemas"]["ManagedDeviceProperty"];
type Profile = components["schemas"]["ManagedDeviceProfile"];
type Protocol = Input["protocol"];
type Deletion = { deviceId: string; property?: string };
const sourceName = (device: Device) => device.profile.sourceName ?? device.profile.protocolConfig?.deviceId ?? device.id;
const ports = { "modbus-tcp": 502, "opc-ua": 4840, mqtt: 1883, virtual: 8080, "serial-json": 0, sensehat: 0 };

export function ManagedDevices({ search = "" }: { search?: string }) {
  const infrastructure = useInfrastructure();
  const [items, setItems] = useState<Device[] | null>(null); const [profiles, setProfiles] = useState<Profile[]>([]);
  const [properties, setProperties] = useState<Property[]>([property()]); const [editing, setEditing] = useState<{ deviceId: string; property: Property } | null>(null);
  const [busy, setBusy] = useState(false); const [error, setError] = useState(""); const [notice, setNotice] = useState(""); const [failed, setFailed] = useState(false);
  const [deleting, setDeleting] = useState<Deletion | null>(null); const form = useRef<HTMLFormElement>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [graph, setGraph] = useState<{ deviceId: string; deviceUid: string; property: Property } | null>(null);
  const section = useRef<HTMLElement>(null); const noticeElement = useRef<HTMLParagraphElement>(null);
  const registerButton = useRef<HTMLButtonElement>(null);
  const errorElement = useRef<HTMLParagraphElement>(null); const [addingId, setAddingId] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [protocol, setProtocol] = useState<Protocol>("modbus-tcp");
  const localHardware = protocol === "serial-json" || protocol === "sensehat";
  const request = useCallback(async (path: string, init?: RequestInit) => {
    const response = await fetch(`/api/control-plane/${path}`, { ...init, cache: "no-store" });
    if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.message || `요청 실패 (${response.status})`); }
    return response;
  }, []);
  useEffect(() => {
    let stopped = false; let timer: ReturnType<typeof setTimeout>;
    async function load() {
      try { const value = await (await request("managed-devices")).json(); if (!stopped) { setItems(value.items); setFailed(false); } }
      catch { if (!stopped) setFailed(true); }
      if (!stopped) timer = setTimeout(load, 5000);
    }
    void load(); void request("device-profiles").then(r => r.json()).then(v => { if (!stopped) setProfiles(v.items); }).catch(() => {});
    return () => { stopped = true; clearTimeout(timer); };
  }, [request, refresh]);
  async function write(path: string, method: string, body?: object) {
    const token = await (await request("csrf")).json();
    return request(path, { method, headers: { "X-CSRF-TOKEN": token.token, ...(body ? { "Content-Type": "application/json" } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  }
  function open() {
    setAddingId(null); setEditing(null); setDeleting(null);
    setError(""); setNotice(""); setCreateOpen(true);
  }
  function input(): Input {
    if (!form.current || !form.current.reportValidity()) throw new Error("필수 입력을 확인하세요.");
    const fields = new FormData(form.current);
    const protocolConfig: NonNullable<Input["protocolConfig"]> = { timeoutMs: Number(fields.get("timeoutMs")) };
    if (protocol === "modbus-tcp") protocolConfig.unitId = Number(fields.get("unitId"));
    if (protocol === "opc-ua") { protocolConfig.securityMode = "None"; protocolConfig.path = String(fields.get("path")); }
    if (protocol === "virtual") protocolConfig.path = String(fields.get("path"));
    if (protocol === "mqtt") protocolConfig.staleMs = Number(fields.get("staleMs"));
    if (localHardware) { protocolConfig.devicePath = String(fields.get("devicePath")); protocolConfig.deviceId = String(fields.get("deviceId")); }
    if (protocol === "serial-json") protocolConfig.baudRate = Number(fields.get("baudRate")) as NonNullable<Input["protocolConfig"]>["baudRate"];
    return { name: String(fields.get("name")), ...(fields.get("sourceName") ? { sourceName: String(fields.get("sourceName")) } : {}), kind: protocol === "virtual" ? "virtual" : "physical", protocol, ...(localHardware ? {} : { host: String(fields.get("host")), port: Number(fields.get("port")) }), targetNode: String(fields.get("targetNode")), collectionInterval: Number(fields.get("interval")), protocolConfig, properties };
  }
  async function action(fn: () => Promise<void>) { setBusy(true); setError(""); setNotice(""); try { await fn(); } catch (e) { setError(e instanceof Error ? e.message : "요청 실패"); requestAnimationFrame(() => errorElement.current?.focus()); } finally { setBusy(false); } }
  function addTo(device: Device | null) {
    const previous = addingId; setEditing(null); setAddingId(device?.id ?? null); setError(""); setNotice(""); setDeleting(null);
    setCreateOpen(false);
    if (!device) requestAnimationFrame(() => {
      const target = Array.from(section.current?.querySelectorAll<HTMLButtonElement>("button") ?? []).find(button => button.getAttribute("aria-label") === `${previous} 항목 추가`);
      (target ?? registerButton.current)?.focus();
    });
  }
  function editProperty(device: Device | null, field?: Property) {
    const previous = editing;
    setEditing(device && field ? { deviceId: device.id, property: field } : null); setAddingId(null); setDeleting(null); setError(""); setNotice("");
    setCreateOpen(false);
    requestAnimationFrame(() => {
      if (device) section.current?.querySelector<HTMLFormElement>('form[aria-label$="측정 항목 수정"]')?.scrollIntoView({ block: "start" });
      else {
        const target = Array.from(section.current?.querySelectorAll<HTMLButtonElement>("button") ?? []).find(button => button.getAttribute("aria-label") === `${previous?.deviceId} ${previous?.property.name} 항목 수정`);
        (target ?? registerButton.current)?.focus();
      }
    });
  }
  async function saveProperty(device: Device, value: Property) {
    const result: Device = await (await write(`managed-devices/${encodeURIComponent(device.id)}/properties/${encodeURIComponent(value.name)}`, "PUT", value)).json();
    setItems(current => current?.map(d => d.id === result.id ? result : d) ?? null); setEditing(null);
    setRefresh(v => v + 1); setNotice(`${device.id} · ${value.name} 항목을 수정했습니다.`);
    requestAnimationFrame(() => noticeElement.current?.focus());
  }
  async function addProperty(device: Device, value: Property) {
    const result: Device = await (await write(`managed-devices/${encodeURIComponent(device.id)}/properties`, "POST", value)).json();
    setItems(current => current ? [...current.filter(d => d.id !== result.id), result] : [result]); setAddingId(null); setEditing(null);
    setRefresh(v => v + 1); setNotice(`${device.id} · ${value.name} 항목을 추가했습니다. 첫 수집값을 기다립니다.`);
    requestAnimationFrame(() => noticeElement.current?.focus());
  }
  function selectDeletion(next: Deletion | null) {
    const target = next ?? deleting; setDeleting(next); if (next) { setEditing(null); setAddingId(null); }
    if (!target) return;
    const label = `${target.deviceId} ${target.property ?? "장치"} ${next ? "삭제 확인" : target.property ? "항목 삭제" : "삭제"}`;
    requestAnimationFrame(() => {
      const button = Array.from(section.current?.querySelectorAll<HTMLButtonElement>("button") ?? []).find(button => button.getAttribute("aria-label") === label);
      (button ?? registerButton.current)?.focus();
    });
  }
  async function remove(device: Device, field?: Property) {
    const path = `managed-devices/${encodeURIComponent(device.id)}${field ? `/properties/${encodeURIComponent(field.name)}` : ""}`;
    await write(path, "DELETE");
    setDeleting(null);
    setItems(current => current?.flatMap(d => {
      if (d.id !== device.id) return [d];
      if (!field) return [];
      const remaining = d.profile.properties.filter(p => p.name !== field.name);
      if (!remaining.length) return [];
      const values = { ...d.properties }; delete values[field.name];
      return [{ ...d, profile: { ...d.profile, properties: remaining }, properties: values }];
    }) ?? null);
    if (editing?.deviceId === device.id) {
      setEditing(null); setProtocol("modbus-tcp"); setProperties([property()]);
      setCreateOpen(false);
    }
    setRefresh(v => v + 1);
    setNotice(field ? `${device.id} · ${field.name} 항목을 삭제했습니다.` : `${device.id} 장치 등록을 삭제했습니다.`);
    requestAnimationFrame(() => noticeElement.current?.focus());
  }
  function submit(e: FormEvent<HTMLFormElement>) { e.preventDefault(); void action(async () => {
    const body = input(); await write("managed-devices", "POST", body);
    setRefresh(v => v + 1); setNotice(`${body.name} 설정을 적용했습니다. 연결 상태 보고를 기다립니다.`); setCreateOpen(false); form.current?.reset(); setProperties([property(protocol)]);
  }); }
  function update(index: number, field: keyof Property, value: string | number) { setProperties(old => old.map((p, i) => i === index ? { ...p, [field]: value } : p)); }
  const filtered = items?.filter(d => d.id === addingId || d.id === editing?.deviceId || d.id === deleting?.deviceId || `${d.id} ${sourceName(d)} ${d.targetNode} ${d.profile.properties.map(p => p.name).join(" ")}`.toLowerCase().includes(search.toLowerCase()));
  const addingDevice = items?.find(d => d.id === addingId);
  const graphDevice = items?.find(d => d.id === graph?.deviceId && d.uid === graph?.deviceUid && d.profile.properties.some(p => p.name === graph?.property.name));
  const graphProperty = graphDevice?.profile.properties.find(p => p.name === graph?.property.name) ?? graph?.property;
  return <section ref={section} aria-label="센서 등록과 수집 상태">
    <div className="toolbar sensor-list-toolbar"><h2>등록 센서</h2><span className="list-update-note" title="측정 항목마다 별도 센서로 표시합니다. 연결 상태와 최신값은 5초마다 갱신합니다.">5초마다 갱신</span><button onClick={() => setRefresh(v => v + 1)}>새로고침</button><button ref={registerButton} className="primary" disabled={busy} onClick={() => open()}>새 장치 등록</button></div>
    {failed && <p role="alert" className="error">현재 상태 조회에 실패했습니다. {items ? "아래는 마지막 조회 결과입니다." : "API 연결을 확인하세요."}</p>}
    {!createOpen && !addingDevice && notice && <p ref={noticeElement} tabIndex={-1} role="status" className="notice">{notice}</p>}
    {!createOpen && !addingDevice && error && <p ref={errorElement} tabIndex={-1} role="alert" className="error">{error}</p>}
    <RegistrationDialog open={createOpen} title="센서 등록" busy={busy} onClose={() => setCreateOpen(false)}>
      {createOpen && notice && <p role="status" className="notice">{notice}</p>}
      {createOpen && error && <p ref={errorElement} tabIndex={-1} role="alert" className="error">{error}</p>}
      <form ref={form} onSubmit={submit} className="publish-fields" aria-label="센서 등록">
        <div className="form-row"><label>센서 이름<input name="name" required pattern={"[a-z0-9][a-z0-9.\\-]*"} maxLength={63} defaultValue="" /></label><label>종류<input readOnly aria-label="장치 종류" value={protocol === "virtual" ? "Virtual" : "Physical"} /></label><label>프로토콜<select aria-label="프로토콜" value={protocol} onChange={e => { const next = e.target.value as Protocol; setProtocol(next); setProperties([property(next)]); }}>{Object.keys(ports).map(p => <option key={p}>{p}</option>)}</select></label></div>
        <label>저장한 측정 항목<select key={protocol} defaultValue="" onChange={e => { const [profile, name] = e.target.value.split("/"); const selected = profiles.find(p => p.name === profile)?.properties.find(p => p.name === name); if (selected) setProperties([selected]); }}><option value="">직접 입력</option>{profiles.filter(p => p.protocol === protocol).flatMap(p => p.properties.map(field => <option key={`${p.name}/${field.name}`} value={`${p.name}/${field.name}`}>{field.name} · {p.name}</option>))}</select></label>
        {!localHardware && <label>물리 장치 이름 (선택)<input name="sourceName" maxLength={63} placeholder="같은 연결을 사용하는 센서의 장치 이름" /></label>}
        {!localHardware && <div className="form-row"><label>Host<input name="host" required maxLength={253} defaultValue="" placeholder="192.168.0.31" /></label><label>Port<input key={protocol} name="port" type="number" required min={1} max={65535} defaultValue={ports[protocol]} /></label>{protocol === "modbus-tcp" && <label>Unit ID<input name="unitId" type="number" required min={1} max={247} defaultValue={1} /></label>}{protocol === "mqtt" && <label>메시지 유효 시간 (ms)<input name="staleMs" type="number" required min={100} max={60000} defaultValue={5000} /></label>}{(protocol === "opc-ua" || protocol === "virtual") && <label>런타임 경로<input key={protocol} name="path" required={protocol === "virtual"} defaultValue={protocol === "virtual" ? "/properties" : ""} /></label>}</div>}
        {localHardware && <div className="form-row"><label>장치 경로<input key={protocol} name="devicePath" required defaultValue={protocol === "serial-json" ? "/dev/edgeai/arduino-001" : "/dev/i2c-1"} readOnly={protocol === "sensehat"} /></label><label>소스 Device ID<input name="deviceId" required maxLength={63} defaultValue={protocol === "serial-json" ? "arduino-001" : "sensehat-001"} /></label>{protocol === "serial-json" && <label>Baud rate<select name="baudRate" defaultValue={115200}>{[9600, 19200, 38400, 57600, 115200].map(rate => <option key={rate} value={rate}>{rate}</option>)}</select></label>}</div>}
        {protocol === "serial-json" && <p className="muted">JSON 줄 형식의 시리얼 장치입니다. device_id·sensor와 값의 JSON 경로를 지정합니다. Arduino 값은 환산 전 원시값입니다.</p>}
        {protocol === "sensehat" && <p className="muted">ARM64 Raspberry Pi의 Sense HAT v1을 I²C로 읽습니다. temp_humidity·temp_pressure·humidity·pressure·compass·pitch·roll·yaw·gyro_x·gyro_y·gyro_z를 지원합니다.</p>}
        {protocol === "opc-ua" && <p className="muted">현재 OPC-UA는 Anonymous / SecurityMode None을 지원합니다.</p>}
        {protocol === "virtual" && <p className="muted">Container·Emulator·AI Service가 HTTP JSON property endpoint를 제공해야 합니다.</p>}
        <div className="form-row"><label>대상 Edge Node<select name="targetNode" required defaultValue=""><option value="">노드 선택</option>{infrastructure.snapshot?.nodes.filter(n => n.kind === "EDGE_DEVICE").map(n => <option key={n.name} value={n.name}>{n.name}</option>)}</select></label><label>수집 간격 (ms)<input name="interval" type="number" required min={100} max={60000} defaultValue={1000} /></label><label>읽기 제한 시간 (ms)<input name="timeoutMs" type="number" required min={100} max={5000} defaultValue={localHardware ? 4000 : 1000} /></label></div>
        <fieldset><legend>측정 항목</legend>{protocol === "modbus-tcp" && <p className="muted">Holding 40001 / Input 30001 / Coil 1은 각각 주소 0입니다.</p>}<PropertyFields protocol={protocol} value={properties[0]} onChange={(field, value) => update(0, field, value)} /></fieldset>
        <div className="toolbar"><button disabled={busy} className="primary">{busy ? "처리 중…" : "장치 등록"}</button><button disabled={busy} type="button" onClick={() => void action(async () => { const result = await (await write("device-connections/test", "POST", input())).json(); if (!result.connected) throw new Error(result.error); setNotice(`연결 확인: ${result.value}`); })}>연결 테스트</button><button disabled={busy} type="button" onClick={() => void action(async () => { const d = input(); await write("device-profiles", "POST", { name: d.name, protocol: d.protocol, properties }); setRefresh(v => v + 1); setNotice("Property 규격을 저장했습니다."); })}>Property 규격 저장</button></div>
      </form>
    </RegistrationDialog>
    {filtered?.length === 0 && <p className="empty">{search ? "일치하는 센서가 없습니다." : "등록된 센서가 없습니다. 새 장치 등록에서 연결 설정을 입력하세요."}</p>}
    {!!filtered?.length && <div className="table-scroll"><table className="registry-table managed-sensors"><caption className="sr-only">측정 항목별 센서와 최신값</caption>
      <colgroup><col style={{ width: "17%" }} /><col style={{ width: "17%" }} /><col style={{ width: "9%" }} /><col style={{ width: "17%" }} /><col style={{ width: "17%" }} /><col style={{ width: "23%" }} /></colgroup>
      <thead><tr><th>센서</th><th>장치 / 노드</th><th>상태</th><th>최신값</th><th>수집 시각</th><th>관리</th></tr></thead>
      <tbody>{filtered.flatMap(d => d.profile.properties.map(p => <Fragment key={`${d.id}/${p.name}`}>
        <tr>
          <td><button className="version-link" title={`${p.name} · ${d.id}`} aria-haspopup="dialog" aria-label={`${d.id} ${p.name} 그래프`} onClick={() => setGraph({ deviceId: d.id, deviceUid: d.uid, property: p })}>{p.name}<span className="sensor-meta" title={d.id}>{d.id}</span></button></td>
          <td data-label="장치 / 노드" title={`${sourceName(d)} · ${d.targetNode} · ${d.profile.protocol}`}>{sourceName(d)}<span className="sensor-meta">{d.targetNode} · {d.profile.protocol}</span></td>
          <td data-label="상태"><StatusBadge state={failed ? "UNKNOWN" : d.state} /></td><td data-label="최신값" title={`${d.properties[p.name]?.value ?? "—"} ${p.unit ?? ""}`}>{d.properties[p.name]?.value ?? "—"} {p.unit}</td>
          <td data-label="수집 시각" title={d.properties[p.name]?.timestamp ? new Date(d.properties[p.name].timestamp).toLocaleString() : undefined}>{d.properties[p.name]?.timestamp ? new Date(d.properties[p.name].timestamp).toLocaleString() : "—"}</td>
          <td data-label="관리"><div className="sensor-actions"><button disabled={busy} aria-label={`${d.id} ${p.name} 항목 수정`} onClick={() => editProperty(d, p)}>항목 수정</button>
            <button disabled={busy} aria-label={`${d.id} 항목 추가`} title="같은 연결에 측정 항목 추가" onClick={() => addTo(d)}>항목 추가</button>
            {deleting?.deviceId === d.id && deleting.property === p.name ? <>
              <span>{p.name} 센서를 삭제할까요?</span><button disabled={busy} aria-label={`${d.id} ${p.name} 삭제 확인`} onClick={() => void action(() => remove(d, p))}>삭제 확인</button>
              <button disabled={busy} onClick={() => selectDeletion(null)}>취소</button>
            </> : <button disabled={busy} aria-label={`${d.id} ${p.name} 항목 삭제`} onClick={() => selectDeletion({ deviceId: d.id, property: p.name })}>항목 삭제</button>}
          </div></td>
        </tr>
        {editing?.deviceId === d.id && editing.property.name === p.name && <tr className="sensor-editor"><td colSpan={6}><EditDeviceProperty device={d} property={editing.property} busy={busy} onSave={field => void action(() => saveProperty(d, field))} onCancel={() => editProperty(null)} /></td></tr>}
      </Fragment>))}</tbody>
    </table></div>}
    <RegistrationDialog open={!!graph} title={`${graph?.property.name ?? "센서"} · 센서 그래프`} subtitle={`${graph?.deviceId ?? ""} · ${graphDevice?.targetNode ?? "저장된 센서 기록"}`} variant="sensor-graph" onClose={() => setGraph(null)}>
      {graph && graphProperty && <SensorGraph key={`${graph.deviceUid}/${graph.property.name}`} device={graphDevice} deviceUid={graph.deviceUid} property={graphProperty} failed={failed} />}
    </RegistrationDialog>
    <RegistrationDialog open={!!addingDevice} title="측정 항목 추가" busy={busy} onClose={() => addTo(null)}>
      {addingDevice && <>
        {error && <p ref={errorElement} tabIndex={-1} role="alert" className="error">{error}</p>}
        <AddDeviceProperty key={addingDevice.id} device={addingDevice} profiles={profiles} existingNames={(items ?? []).filter(other => sourceName(other) === sourceName(addingDevice) && other.targetNode === addingDevice.targetNode && other.profile.protocol === addingDevice.profile.protocol).flatMap(other => other.profile.properties.map(field => field.name))} busy={busy} onAdd={field => void action(() => addProperty(addingDevice, field))} />
      </>}
    </RegistrationDialog>
  </section>;
}
