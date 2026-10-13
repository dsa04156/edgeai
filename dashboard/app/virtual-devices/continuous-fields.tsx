"use client";

import { useRef, useState, type FormEvent } from "react";
import type { components } from "../../lib/api-schema";

type Schema = components["schemas"];
type Source = Schema["VirtualDeviceSources"][number];
type Configuration = Schema["ContinuousConfiguration"];
type Output = Schema["ContinuousOutputDefinition"];
const operations = { scalar: "계산식", movingAverage: "이동 평균", minimum: "창 최솟값", maximum: "창 최댓값", threshold: "임계값", filter: "조건 필터" };
const windowOperation = (op: Output["operation"]) => ["movingAverage", "minimum", "maximum"].includes(op);

export function ContinuousFields({ inputs, initial, enabled, busy, save }: {
  inputs: Schema["ContinuousSensor"][]; enabled: boolean; busy: boolean;
  initial?: { displayName: string; sources: Source[]; continuous: Configuration };
  save: (body: { displayName: string; sources: Source[]; continuous: Configuration; key?: string }) => Promise<void>;
}) {
  const [sources, setSources] = useState<Source[]>(initial?.sources || [{ sourceKey: "a", deviceId: "", properties: [], maxAgeSeconds: 30 }]);
  const [configuration, setConfiguration] = useState<Configuration>(initial?.continuous || { intervalSeconds: 1, retentionDays: 7, outputs: [{ name: "value", expression: "a", operation: "scalar", unit: "" }] });
  const [feedback, setFeedback] = useState(""); const [pending, setPending] = useState(false);
  const feedbackElement = useRef<HTMLParagraphElement>(null);
  const choices = inputs;
  function source(index: number, patch: Partial<Source>) { setSources(rows => rows.map((row, n) => n === index ? { ...row, ...patch } : row)); }
  function output(index: number, patch: Partial<Output>) { setConfiguration(value => ({ ...value, outputs: value.outputs.map((row, n) => n === index ? { ...row, ...patch } : row) })); }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (pending || busy || !enabled) return;
    const form = new FormData(event.currentTarget); setPending(true); setFeedback("");
    try { await save({ ...(initial ? {} : { key: String(form.get("key")) }), displayName: String(form.get("displayName")), sources, continuous: configuration }); }
    catch (error) { setFeedback(error instanceof Error ? error.message : "저장하지 못했습니다."); requestAnimationFrame(() => feedbackElement.current?.focus()); }
    finally { setPending(false); }
  }
  return <form aria-label={initial ? "연속 처리 설정 수정" : "연속 처리 장치 등록"} onSubmit={event => void submit(event)}><fieldset disabled={busy || pending || !enabled} className="publish-fields">
    <legend>{initial ? "연속 처리 설정 수정" : "연속 처리 장치 등록"}</legend>
    <p className="hint">여러 센서의 최신값을 조합합니다. 새 센서는 선택 목록에 추가되며, 저장한 연결은 여기서 수정할 때 변경됩니다.</p>
    <p className="hint">센서 수와 관계없이 이 등록으로 가상디바이스 1개를 만듭니다. 추가한 출력은 각각 별도 결과 행과 그래프로 표시합니다.</p>
    {!enabled && <p className="notice">연속 처리 기능이 아직 활성화되지 않았습니다.</p>}
    <div className="form-row">{!initial && <label>장치 키<input name="key" required maxLength={100} pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" placeholder="temperature-average" /></label>}<label>장치 이름<input name="displayName" required maxLength={128} defaultValue={initial?.displayName} /></label></div>
    <fieldset className="publish-fields"><legend>입력 센서 · 최대 16개</legend>
      {sources.map((row, index) => <div key={index} className="publish-fields"><div className="form-row">
        <label>입력 {index + 1} 별칭<input aria-label={`입력 ${index + 1} 별칭`} required pattern="[a-z][a-z0-9_]{0,31}" value={row.sourceKey} onChange={event => source(index, { sourceKey: event.target.value })} /></label>
        <label>입력 {index + 1} 센서<select aria-label={`입력 ${index + 1} 센서`} required value={row.deviceId} onChange={event => { const device = choices.find(item => item.deviceId === event.target.value); source(index, { deviceId: event.target.value, properties: device ? [device.property] : [] }); }}>
          <option value="">측정 항목 선택</option>{choices.map(device => <option key={device.deviceUid} value={device.deviceId}>{device.property} · {device.deviceId} · {device.targetNode}</option>)}
          {row.deviceId && !choices.some(device => device.deviceId === row.deviceId) && <option value={row.deviceId}>{row.deviceId} · 연결 확인 필요</option>}
        </select></label>
        <label>입력 {index + 1} 유효 시간(초)<input aria-label={`입력 ${index + 1} 유효 시간(초)`} type="number" min={1} max={300} required value={row.maxAgeSeconds} onChange={event => source(index, { maxAgeSeconds: Number(event.target.value) })} /></label>
      </div><button type="button" disabled={sources.length === 1} onClick={() => setSources(rows => rows.filter((_, n) => n !== index))}>입력 {index + 1} 삭제</button></div>)}
      <button type="button" disabled={sources.length >= 16} onClick={() => setSources(rows => { let n = rows.length; while (rows.some(row => row.sourceKey === `input${n}`)) n++; return [...rows, { sourceKey: `input${n}`, deviceId: "", properties: [], maxAgeSeconds: 30 }]; })}>입력 센서 추가</button>
      {!choices.length && <p className="hint">측정 항목별 센서를 먼저 등록하세요. <a href="/devices?view=sensors">센서 등록 →</a></p>}
    </fieldset>
    <div className="form-row"><label>계산 간격(초)<input type="number" required min={1} max={3600} value={configuration.intervalSeconds} onChange={event => setConfiguration(value => ({ ...value, intervalSeconds: Number(event.target.value) }))} /></label>
      <label>결과 보관 기간(일)<input type="number" required min={1} max={30} value={configuration.retentionDays} onChange={event => setConfiguration(value => ({ ...value, retentionDays: Number(event.target.value) }))} /></label></div>
    <fieldset className="publish-fields"><legend>출력 항목 · 최대 16개</legend>
      <p className="hint">별칭으로 식을 작성하세요. 예: (a + b) / 2, a &gt; 30, a &gt; 30 &amp;&amp; b &lt; 80. 입력 누락이나 연결 변경 시 창을 다시 모읍니다.</p>
      {configuration.outputs.map((row, index) => <fieldset key={index} className="publish-fields"><legend>출력 {index + 1}</legend>
        <div className="form-row"><label>항목 이름<input aria-label={`출력 ${index + 1} 이름`} required pattern="[a-z][a-z0-9_]{0,62}" value={row.name} onChange={event => output(index, { name: event.target.value })} /></label>
          <label>처리<select aria-label={`출력 ${index + 1} 처리`} value={row.operation} onChange={event => { const operation = event.target.value as Output["operation"]; const replacement: Output = { name: row.name, expression: row.expression, operation, unit: row.unit, ...(windowOperation(operation) ? { sampleCount: row.sampleCount || 5 } : {}), ...(operation === "filter" || row.condition ? { condition: row.condition || "a > 0" } : {}) }; setConfiguration(value => ({ ...value, outputs: value.outputs.map((old, n) => n === index ? replacement : old) })); }}>{Object.entries(operations).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
          <label>단위<input aria-label={`출력 ${index + 1} 단위`} maxLength={32} value={row.unit} onChange={event => output(index, { unit: event.target.value })} /></label></div>
        <label>{row.operation === "threshold" ? "참/거짓 계산식" : "계산식"}<input aria-label={`출력 ${index + 1} 계산식`} required maxLength={1024} value={row.expression} onChange={event => output(index, { expression: event.target.value })} /></label>
        {windowOperation(row.operation) && <label>창 표본 수<input aria-label={`출력 ${index + 1} 창 표본 수`} type="number" required min={1} max={1024} value={row.sampleCount || 1} onChange={event => output(index, { sampleCount: Number(event.target.value) })} /></label>}
        <label>출력 조건{row.operation !== "filter" && " (선택)"}<input aria-label={`출력 ${index + 1} 조건`} required={row.operation === "filter"} maxLength={1024} value={row.condition || ""} onChange={event => { const next = { ...row }; if (event.target.value) next.condition = event.target.value; else delete next.condition; setConfiguration(value => ({ ...value, outputs: value.outputs.map((old, n) => n === index ? next : old) })); }} /></label>
        <button type="button" disabled={configuration.outputs.length === 1} onClick={() => setConfiguration(value => ({ ...value, outputs: value.outputs.filter((_, n) => n !== index) }))}>출력 {index + 1} 삭제</button>
      </fieldset>)}
      <button type="button" disabled={configuration.outputs.length >= 16} onClick={() => setConfiguration(value => { let n = value.outputs.length; while (value.outputs.some(row => row.name === `value${n}`)) n++; return { ...value, outputs: [...value.outputs, { name: `value${n}`, expression: sources[0].sourceKey, operation: "scalar", unit: "" }] }; })}>출력 항목 추가</button>
    </fieldset>
    {feedback && <p ref={feedbackElement} tabIndex={-1} role="alert" className="error">{feedback}</p>}
    <button className="primary">{pending ? "저장 중…" : initial ? "연속 처리 설정 저장" : "연속 처리 장치 등록"}</button>
  </fieldset></form>;
}
