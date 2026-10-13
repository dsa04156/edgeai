"use client";
import { useEffect, useRef, useState } from "react";
import type { components } from "../../lib/api-schema";

type Property = components["schemas"]["ManagedDeviceProperty"];
type Device = components["schemas"]["ManagedDevice"];
type Profile = components["schemas"]["ManagedDeviceProfile"];
type Protocol = components["schemas"]["ManagedDeviceInput"]["protocol"];

export const newProperty = (protocol: Protocol = "modbus-tcp"): Property => ({ name: "", dataType: protocol === "serial-json" ? "int32" : "float32", scale: 1, offset: 0, unit: "", ...(protocol === "modbus-tcp" ? { register: 40001, function: "holding", byteOrder: "ABCD" } : protocol === "opc-ua" ? { nodeId: "ns=1;s=temperature" } : protocol === "mqtt" ? { topic: "", jsonPath: "temperature" } : protocol === "serial-json" ? { sensor: "temperature", jsonPath: "raw" } : { jsonPath: protocol === "sensehat" ? "temp_humidity" : "temperature" }) });

export function PropertyFields({ protocol, value: p, onChange, index = 0, nameReadOnly = false }: { protocol: Protocol; value: Property; onChange: (field: keyof Property, value: string | number) => void; index?: number; nameReadOnly?: boolean }) {
  return <div className="form-row">
    <label>항목 이름<input aria-label={`항목 ${index + 1} 이름`} required readOnly={nameReadOnly} pattern="[a-z][a-z0-9_]*" maxLength={63} value={p.name} onChange={e => onChange("name", e.target.value)} /></label>
    {protocol === "modbus-tcp" && <label>Register<input aria-label={`항목 ${index + 1} Register`} required type="number" value={p.register} onChange={e => onChange("register", Number(e.target.value))} /></label>}
    {protocol === "opc-ua" && <label>NodeId<input required value={p.nodeId ?? ""} onChange={e => onChange("nodeId", e.target.value)} /></label>}
    {protocol === "mqtt" && <label>입력 topic<input required value={p.topic ?? ""} onChange={e => onChange("topic", e.target.value)} /></label>}
    {protocol === "serial-json" && <label>프레임 sensor<input required value={p.sensor ?? ""} onChange={e => onChange("sensor", e.target.value)} /></label>}
    {["mqtt", "virtual", "serial-json", "sensehat"].includes(protocol) && <label>JSON 경로<input required={protocol === "serial-json" || protocol === "sensehat"} value={p.jsonPath ?? ""} onChange={e => onChange("jsonPath", e.target.value)} placeholder="temperature" /></label>}
    <label>자료형<select value={p.dataType} onChange={e => onChange("dataType", e.target.value)}>{["float32", "float64", "int16", "uint16", "int32", "uint32", "bool", ...(protocol === "modbus-tcp" ? [] : ["string"])].map(t => <option key={t}>{t}</option>)}</select></label>
    {protocol === "modbus-tcp" && <><label>읽기 함수<select value={p.function} onChange={e => onChange("function", e.target.value)}>{["holding", "input", "coil"].map(t => <option key={t}>{t}</option>)}</select></label><label>바이트 순서<select value={p.byteOrder} onChange={e => onChange("byteOrder", e.target.value)}>{["ABCD", "BADC", "CDAB", "DCBA"].map(t => <option key={t}>{t}</option>)}</select></label></>}
    <label>단위<input value={p.unit ?? ""} onChange={e => onChange("unit", e.target.value)} /></label>
    <label>배율<input type="number" step="any" required value={p.scale} onChange={e => onChange("scale", Number(e.target.value))} /></label>
    <label>보정값<input type="number" step="any" required value={p.offset} onChange={e => onChange("offset", Number(e.target.value))} /></label>
  </div>;
}

export function EditDeviceProperty({ device, property, busy, onSave, onCancel }: { device: Device; property: Property; busy: boolean; onSave: (value: Property) => void; onCancel: () => void }) {
  const [value, setValue] = useState<Property>(property);
  const form = useRef<HTMLFormElement>(null);
  useEffect(() => { form.current?.querySelector<HTMLInputElement>('input:not([readonly])')?.focus(); }, []);
  return <form ref={form} aria-label={`${device.id} ${property.name} 측정 항목 수정`} className="panel publish-fields" onSubmit={e => { e.preventDefault(); onSave(value); }}>
    <h3>{device.id} · {property.name} 항목 수정</h3>
    <p className="muted">항목 이름은 Workflow 참조에 사용하므로 유지합니다. 이 항목의 값 위치와 환산 설정을 수정합니다.</p>
    <fieldset disabled={busy}><legend>수정할 항목</legend><PropertyFields protocol={device.profile.protocol} value={value} nameReadOnly onChange={(field, next) => setValue(old => ({ ...old, [field]: next }))} /></fieldset>
    <div className="toolbar"><button className="primary" disabled={busy}>{busy ? "저장 중…" : "항목 저장"}</button><button type="button" disabled={busy} onClick={onCancel}>취소</button></div>
  </form>;
}

export function AddDeviceProperty({ device, profiles, existingNames, busy, onAdd, onCancel }: { device: Device; profiles: Profile[]; existingNames: string[]; busy: boolean; onAdd: (value: Property) => void; onCancel: () => void }) {
  const protocol = device.profile.protocol;
  const existing = new Set(existingNames);
  const choices = profiles.filter(p => p.protocol === protocol).flatMap(profile => profile.properties.filter(p => !existing.has(p.name)).map(p => ({ key: `${profile.name}/${p.name}`, profile: profile.name, property: p })));
  const [selected, setSelected] = useState(choices[0]?.key ?? "");
  const [value, setValue] = useState<Property>(choices[0]?.property ?? newProperty(protocol));
  const form = useRef<HTMLFormElement>(null);
  useEffect(() => { form.current?.querySelector<HTMLSelectElement>("select")?.focus(); }, []);
  const fields = <PropertyFields protocol={protocol} value={value} onChange={(field, next) => setValue(old => ({ ...old, [field]: next }))} />;
  return <form ref={form} aria-label={`${device.id} 측정 항목 추가`} className="panel publish-fields" onInvalidCapture={e => { if (e.target instanceof HTMLElement) { const details = e.target.closest("details"); if (details) details.open = true; } }} onSubmit={e => { e.preventDefault(); onAdd(value); }}>
    <h3>{device.id} · 측정 항목 추가</h3>
    <p className="muted">{device.targetNode} · {protocol} · 기존 연결 설정과 수집 간격을 공유하는 별도 센서를 등록합니다.</p>
    <fieldset disabled={busy}><legend>추가할 항목</legend>
      <label>측정 항목 선택<select value={selected} onChange={e => { const key = e.target.value; setSelected(key); setValue(choices.find(p => p.key === key)?.property ?? newProperty(protocol)); }}>
        <option value="">직접 입력</option>{choices.map(c => <option key={c.key} value={c.key}>{c.property.name} · {c.profile}</option>)}
      </select></label>
      {!choices.length && <p className="muted">추가할 저장 규격이 없습니다. 새 항목의 이름과 값의 위치를 입력하세요.</p>}
      {selected ? <><p><strong>{value.name}</strong> · {value.dataType}{value.unit ? ` · ${value.unit}` : ""}</p><details key={selected}><summary>항목 설정 확인·수정</summary>{fields}</details></> : fields}
    </fieldset>
    <div className="toolbar"><button className="primary" disabled={busy}>{busy ? "추가 중…" : "항목 추가"}</button><button type="button" disabled={busy} onClick={onCancel}>취소</button></div>
  </form>;
}
