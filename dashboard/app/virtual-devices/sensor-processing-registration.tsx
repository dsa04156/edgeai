"use client";

import { useRef, useState, type FormEvent } from "react";
import type { components } from "../../lib/api-schema";
import { record } from "../../lib/vd-profile";
import { validateProcessing } from "../../lib/processing";
import { ProcessingFields } from "../profiles/processing-fields";
import { StatusBadge } from "../components/status-badge";

type Schema = components["schemas"];
export function SensorProcessingRegistration({ devices, initialDevice, ready, catalogError, onRetryCatalog, busy, onBusy, create, onRegistered }: {
  devices: Schema["ManagedDevice"][]; initialDevice: string; ready: boolean; busy: boolean;
  catalogError: string; onRetryCatalog: () => void;
  onBusy: (busy: boolean) => void; create: (body: Schema["ProcessingVirtualDeviceCreate"]) => Promise<Schema["VirtualDevice"]>;
  onRegistered: (vd: Schema["VirtualDevice"]) => Promise<void>;
}) {
  const [deviceId, setDeviceId] = useState(initialDevice);
  const [processing, setProcessing] = useState<Record<string, unknown>>({ apiVersion: "edgeai.processing/v1", sampleCount: 1, outputPort: "output", pipelines: [
    { name: "signal1", input: { sourceKey: "input", property: "" }, steps: [{ op: "threshold", operator: "gt", value: 0 }] },
  ] });
  const [feedback, setFeedback] = useState(""); const [pending, setPending] = useState(false);
  const result = useRef<HTMLParagraphElement>(null);
  const device = devices.find(value => value.id === deviceId);
  const measurement = device?.profile.properties.length === 1 ? device.profile.properties[0] : undefined;
  const numeric = (property: Schema["ManagedDeviceProperty"] | undefined): property is Schema["ManagedDeviceProperty"] => Boolean(property && ["float32", "float64", "int16", "uint16", "int32", "uint32"].includes(property.dataType));
  const selectedProcessing = { ...processing, pipelines: (processing.pipelines as Record<string, unknown>[]).map(pipeline => ({
    ...pipeline, input: { sourceKey: "input", property: measurement?.name || "" },
  })) };
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy || !ready || !numeric(measurement)) return;
    const validation = validateProcessing(selectedProcessing, ["input"]);
    if (validation) { setFeedback(validation); requestAnimationFrame(() => result.current?.focus()); return; }
    const form = new FormData(event.currentTarget);
    onBusy(true); setPending(true); setFeedback("가상 장치를 등록하고 있습니다…");
    try {
      const vd = await create({ key: String(form.get("key")), displayName: String(form.get("displayName")), deviceId,
        maxAgeSeconds: Number(form.get("maxAgeSeconds")), placement: { mode: "AUTO" }, processing: selectedProcessing as Schema["ProcessingVirtualDeviceCreate"]["processing"] });
      await onRegistered(vd);
    } catch (error) { setFeedback(error instanceof Error ? error.message : "등록하지 못했습니다."); requestAnimationFrame(() => result.current?.focus()); }
    finally { onBusy(false); setPending(false); }
  }
  return <form aria-label="센서 처리 장치 등록" onSubmit={event => void submit(event)}><fieldset disabled={busy} className="publish-fields">
    <legend>센서와 처리 선택</legend>
    <p className="hint">센서의 측정값에 적용할 처리를 선택하세요. 공통 실행 이미지와 템플릿은 자동으로 연결합니다.</p>
    {catalogError ? <div><p className="error" role="alert">공통 처리 상태 조회에 실패했습니다. {catalogError}</p><button type="button" onClick={onRetryCatalog}>공통 처리 상태 다시 확인</button></div>
      : !ready && <p className="notice">공통 처리 실행 이미지가 아직 준비되지 않았습니다.</p>}
    <div className="form-row"><label>장치 키<input name="key" required maxLength={100} pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" placeholder="temperature-average" /></label><label>장치 이름<input name="displayName" required maxLength={128} /></label></div>
    <fieldset className="publish-fields"><legend>센서 선택</legend>
      <p className="hint">처리할 센서를 행에서 선택하세요. 선택한 센서의 측정 항목을 자동으로 연결합니다.</p>
      {devices.length > 0 && <div className="table-scroll processing-sensor-list"><table className="registry-table managed-sensors processing-sensors">
        <caption className="sr-only">처리할 센서 선택</caption>
        <thead><tr><th>선택</th><th>센서</th><th>장치 / 노드</th><th>상태</th><th>최신값</th></tr></thead>
        <tbody>{devices.map(value => {
          const field = value.profile.properties.length === 1 ? value.profile.properties[0] : undefined;
          const reading = field ? value.properties[field.name] : undefined;
          return <tr key={value.id} data-selected={deviceId === value.id}>
            <td><label className="sensor-radio"><input type="radio" name="processingSensor" required value={value.id} checked={deviceId === value.id} disabled={!numeric(field)}
              aria-label={`${field?.name || value.id} · ${value.id} 선택`} onChange={() => { setDeviceId(value.id); setFeedback(""); }} /></label></td>
            <td><strong>{field?.name || value.id}</strong><span className="sensor-meta">{value.id}</span>{!numeric(field) && <span className="sensor-meta">측정 항목 하나인 숫자 센서가 필요합니다.</span>}</td>
            <td>{value.profile.sourceName || value.profile.protocolConfig?.deviceId || value.id}<span className="sensor-meta">{value.targetNode}</span></td>
            <td><StatusBadge state={value.state} /></td><td>{reading?.value ?? "—"} {field?.unit}<span className="sensor-meta">{reading?.timestamp ? new Date(reading.timestamp).toLocaleString() : "미수집"}</span></td>
          </tr>;
        })}</tbody>
      </table></div>}
      {numeric(measurement) && <p className="notice">선택한 센서: {measurement.name} · {deviceId}</p>}
    </fieldset>
    {!devices.length && <p className="hint">센서 디바이스를 먼저 등록하세요. <a href="/devices?view=sensors">센서 등록 →</a></p>}
    {device && numeric(measurement) && <><ProcessingFields sensorOnly sensorProperty={measurement.name} devices={[device]} spec={{ sources: { input: { deviceModel: device.model } }, processing: selectedProcessing }} update={patch => setProcessing(record(patch.processing))} />
      <label>최대 수신 경과 시간(초)<input name="maxAgeSeconds" type="number" required min={1} max={300} defaultValue={120} /></label>
      <p className="hint">실행은 자동 배치합니다. 여러 표본을 처리하려면 센서 수집 간격과 표본 수에 맞게 수신 시간을 설정하세요. FFT는 균일한 수집 간격이 필요합니다.</p></>}
    {feedback && <p ref={result} tabIndex={-1} role={pending ? "status" : "alert"} className={pending ? "hint" : "error"}>{feedback}</p>}
    <button className="primary" disabled={!ready || !numeric(measurement)}>{pending ? "등록 중…" : "센서 처리 장치 등록"}</button>
  </fieldset></form>;
}
