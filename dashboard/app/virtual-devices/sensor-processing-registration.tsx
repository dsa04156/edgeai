"use client";

import { useRef, useState, type FormEvent } from "react";
import type { components } from "../../lib/api-schema";
import { record } from "../../lib/vd-profile";
import { validateProcessing } from "../../lib/processing";
import { ProcessingFields } from "../profiles/processing-fields";

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
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy || !ready) return;
    const validation = validateProcessing(processing, ["input"]);
    if (validation) { setFeedback(validation); requestAnimationFrame(() => result.current?.focus()); return; }
    const form = new FormData(event.currentTarget);
    onBusy(true); setPending(true); setFeedback("가상 장치를 등록하고 있습니다…");
    try {
      const vd = await create({ key: String(form.get("key")), displayName: String(form.get("displayName")), deviceId,
        maxAgeSeconds: Number(form.get("maxAgeSeconds")), placement: { mode: "AUTO" }, processing: processing as Schema["ProcessingVirtualDeviceCreate"]["processing"] });
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
    <label>센서 선택<select aria-label="센서 선택" required value={deviceId} onChange={event => {
      setDeviceId(event.target.value); setFeedback("");
      setProcessing(previous => ({ ...previous, pipelines: (previous.pipelines as Record<string, unknown>[]).map(pipeline => ({ ...pipeline, input: { sourceKey: "input", property: "" } })) }));
    }}><option value="">센서 디바이스 선택</option>{devices.map(value => <option key={value.id} value={value.id}>{value.id} · {value.state}</option>)}</select></label>
    {!devices.length && <p className="hint">센서 디바이스를 먼저 등록하세요. <a href="/devices?view=sensors">센서 등록 →</a></p>}
    {device && <><ProcessingFields sensorOnly devices={[device]} spec={{ sources: { input: { deviceModel: device.model } }, processing }} update={patch => setProcessing(record(patch.processing))} />
      <label>최대 수신 경과 시간(초)<input name="maxAgeSeconds" type="number" required min={1} max={300} defaultValue={120} /></label>
      <p className="hint">실행은 자동 배치합니다. 여러 표본을 처리하려면 센서 수집 간격과 표본 수에 맞게 수신 시간을 설정하세요. FFT는 균일한 수집 간격이 필요합니다.</p></>}
    {feedback && <p ref={result} tabIndex={-1} role={pending ? "status" : "alert"} className={pending ? "hint" : "error"}>{feedback}</p>}
    <button className="primary" disabled={!ready || !device}>{pending ? "등록 중…" : "센서 처리 장치 등록"}</button>
  </fieldset></form>;
}
