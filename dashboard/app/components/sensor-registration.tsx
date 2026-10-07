"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import type { components } from "../../lib/api-schema";

type Schema = components["schemas"];
type Template = Schema["SensorRegistrationTemplate"];

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/control-plane/${path}`, { ...init, cache: "no-store", signal: AbortSignal.timeout(22000) });
  const value = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(response.status === 404 ? "센서 등록 API에 연결할 수 없습니다. 백엔드 실행 버전을 확인하세요." : value.message || "센서 등록 요청을 처리하지 못했습니다.");
  return value as T;
}

export function SensorRegistration({ onRegistered }: { onRegistered: () => void }) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [templates, setTemplates] = useState<Template[] | null>(null);
  const [selected, setSelected] = useState("");
  const [name, setName] = useState("");
  const [endpoint, setEndpoint] = useState("");
  const [physicalId, setPhysicalId] = useState("");
  const [baudRate, setBaudRate] = useState<NonNullable<Schema["SensorRegistrationRequest"]["baudRate"]>>(115200);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const button = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLElement>(null);
  const inFlight = useRef(false);
  const template = templates?.find(t => t.id === selected);
  useEffect(() => { if (open) panel.current?.focus(); }, [open]);
  useEffect(() => { if (!open && notice && !busy) button.current?.focus(); }, [open, notice, busy]);

  function choose(value: Template | undefined) {
    setSelected(value?.id || ""); setEndpoint(value?.endpoint || ""); setPhysicalId(""); setBaudRate(115200);
  }
  async function load() {
    setLoading(true); setError(""); setTemplates(null);
    try {
      const catalog = await api<Schema["SensorRegistrationCatalog"]>("sensors/registration-options");
      if (!Array.isArray(catalog.templates)) throw new Error("등록 가능한 센서 종류를 확인하지 못했습니다.");
      setTemplates(catalog.templates); choose(catalog.templates[0]);
    } catch (e) { setError(e instanceof Error ? e.message : "센서 종류 조회 실패"); }
    finally { setLoading(false); }
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!template || inFlight.current) return;
    inFlight.current = true; setBusy(true); setError(""); setNotice("");
    const request: Schema["SensorRegistrationRequest"] = { name, templateId: template.id, endpoint, physicalDeviceId: physicalId,
      baudRate: template.protocol === "SERIAL" ? baudRate : null };
    try {
      const csrf = await api<{ token: string }>("csrf");
      const result = await api<Schema["SensorRegistrationResult"]>("sensors/registrations", {
        method: "POST", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": csrf.token }, body: JSON.stringify(request),
      });
      setNotice(`${result.name}: ${result.created ? "센서를 등록했습니다." : "같은 설정으로 이미 등록된 센서입니다."} 목록에서 센서를 선택해 측정값 수신을 확인하세요.`);
      onRegistered(); setOpen(false);
    } catch (e) {
      setError(e instanceof Error && e.name !== "TimeoutError" ? e.message : "등록 응답을 확인하지 못했습니다. 목록을 확인하고 같은 이름·설정으로 다시 요청하세요.");
      onRegistered();
    } finally { inFlight.current = false; setBusy(false); }
  }
  return <div className="sensor-registration">
    <div className="toolbar"><button ref={button} className="primary" aria-expanded={open} aria-controls="sensor-registration-form" disabled={busy || loading}
      onClick={() => { if (open) { setOpen(false); button.current?.focus(); } else { setOpen(true); setNotice(""); void load(); } }}>{open ? "등록 닫기" : "센서 등록"}</button></div>
    {notice && <p className="notice" role="status">{notice}</p>}
    {open && <section id="sensor-registration-form" ref={panel} tabIndex={-1} className="sensor-detail" aria-label="센서 등록" aria-busy={busy || loading}>
      <h3>센서 연결 정보</h3>
      <p className="muted">지원하는 센서 종류를 선택하고 연결 정보를 입력하세요. 센서 하나가 목록의 한 행으로 등록됩니다.</p>
      {error && <p className="error" role="alert">{error}</p>}
      {loading && <p role="status">등록 가능한 센서 종류를 불러오는 중…</p>}
      {!loading && templates === null && <button onClick={() => void load()}>센서 종류 다시 조회</button>}
      {templates?.length === 0 && <p className="empty">등록 가능한 수집기와 프로필이 없습니다. 지원 수집기 배포 후 다시 조회하세요.</p>}
      {!!templates?.length && <form onSubmit={event => void submit(event)}>
        <fieldset disabled={busy} className="sensor-registration-fields">
          <legend className="sr-only">센서 종류와 연결 설정</legend>
          <label>센서 종류<select value={selected} onChange={event => choose(templates.find(t => t.id === event.target.value))}>
            {templates.map(t => <option key={t.id} value={t.id}>{t.label}</option>)}
          </select></label>
          {template && <>
            <p className="muted sensor-registration-target">연결 장비: {template.nodeName}<br />수집기: {template.serviceName}<br />측정 항목: {template.resources.join(" · ")}</p>
            <label>센서 이름<input required maxLength={64} pattern="[A-Za-z0-9][A-Za-z0-9_\-]{0,63}" value={name} onChange={event => setName(event.target.value)} placeholder="예: arduino-temperature-002" aria-describedby="sensor-name-help" /></label>
            <small id="sensor-name-help" className="muted">영문·숫자·하이픈·밑줄로 중복되지 않는 이름을 입력하세요.</small>
            <label>물리 장치 ID<input required maxLength={64} pattern="[A-Za-z0-9][A-Za-z0-9_\-]{0,63}" value={physicalId} onChange={event => setPhysicalId(event.target.value)} placeholder={template.protocol === "SERIAL" ? "예: arduino-002" : "예: sensehat-001"} aria-describedby="sensor-physical-help" /></label>
            <small id="sensor-physical-help" className="muted">같은 보드의 센서들은 동일한 장치 ID를 사용합니다.</small>
            <label>{template.protocol === "SERIAL" ? "Serial 연결 경로" : "I²C 버스"}<input required maxLength={80} value={endpoint} readOnly={template.protocol === "I2C"}
              pattern={template.protocol === "SERIAL" ? "/dev/edgeai/[A-Za-z0-9][A-Za-z0-9_\\-]{0,63}" : undefined} onChange={event => setEndpoint(event.target.value)} aria-describedby="sensor-endpoint-help" /></label>
            <small id="sensor-endpoint-help" className="muted">{template.protocol === "SERIAL" ? "선택한 수집기에 연결된 /dev/edgeai/ 아래 장치 경로를 입력하세요. 새 USB 장치는 먼저 연결 경로를 준비해야 합니다." : "현재 수집기는 /dev/i2c-1의 Sense HAT를 지원합니다."}</small>
            {template.protocol === "SERIAL" && <label>통신 속도<select value={baudRate} onChange={event => setBaudRate(template.baudRates.find(rate => String(rate) === event.target.value) ?? 115200)}>{template.baudRates.map(rate => <option key={rate} value={rate}>{rate} baud</option>)}</select></label>}
            <div className="toolbar"><button type="submit" className="primary">{busy ? "등록 중…" : "등록하기"}</button><button type="button" onClick={() => { setOpen(false); button.current?.focus(); }}>취소</button></div>
          </>}
        </fieldset>
      </form>}
    </section>}
  </div>;
}
