"use client";

import { useRef, useState, type FormEvent } from "react";
import type { components } from "../../lib/api-schema";
import { ContinuousQuickFields } from "./continuous-quick-fields";

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
  const [advanced, setAdvanced] = useState(Boolean(initial));
  const [registrationKey, setRegistrationKey] = useState("");
  const [displayName, setDisplayName] = useState(initial?.displayName || "");
  const formElement = useRef<HTMLFormElement>(null);
  const feedbackElement = useRef<HTMLParagraphElement>(null);
  const choices = inputs;
  function source(index: number, patch: Partial<Source>) { setSources(rows => rows.map((row, n) => n === index ? { ...row, ...patch } : row)); }
  function output(index: number, patch: Partial<Output>) { setConfiguration(value => ({ ...value, outputs: value.outputs.map((row, n) => n === index ? { ...row, ...patch } : row) })); }
  const storage = configuration.resultStorage || { mode: "ALL" as const };
  function storageOutput(name: string, checked: boolean, condition = "") {
    setConfiguration(value => {
      const current = value.resultStorage?.mode === "SELECTED" ? value.resultStorage.outputs : [];
      return { ...value, resultStorage: { mode: "SELECTED", outputs: [...current.filter(row => row.name !== name), ...(checked ? [{ name, ...(condition ? { condition } : {}) }] : [])] } };
    });
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (pending || busy || !enabled) return;
    const key = initial ? undefined : registrationKey || `vd-${[...crypto.getRandomValues(new Uint8Array(16))].map(value => value.toString(16).padStart(2, "0")).join("")}`;
    if (key) setRegistrationKey(key);
    setPending(true); setFeedback("");
    try { await save({ ...(initial ? {} : { key }), displayName, sources, continuous: configuration }); }
    catch (error) { setFeedback(error instanceof Error ? error.message : "저장하지 못했습니다."); requestAnimationFrame(() => feedbackElement.current?.focus()); }
    finally { setPending(false); }
  }
  const nameField = <label>장치 이름<input name="displayName" required maxLength={128} value={displayName} onChange={event => setDisplayName(event.target.value)} placeholder="예: 온도 평균" /></label>;
  const feedbackField = feedback && <p ref={feedbackElement} tabIndex={-1} role="alert" className="error">{feedback}</p>;
  const submitButton = <button className="primary">{pending ? "저장 중…" : initial ? "연속 처리 설정 저장" : "연속 처리 장치 등록"}</button>;
  return <form ref={formElement} className={!advanced ? "continuous-quick" : undefined} aria-label={initial ? "연속 처리 설정 수정" : "연속 처리 장치 등록"} onSubmit={event => void submit(event)}><fieldset disabled={busy || pending || !enabled} className="publish-fields">
    {advanced && <legend>{initial ? "연속 처리 설정 수정" : "연속 처리 장치 등록"}</legend>}
    <p className="hint">{advanced ? "입력 센서와 계산식을 지정합니다. 여러 출력도 가상 장치 1개로 등록됩니다." : "센서를 선택하고 원하는 계산을 고르세요. 계산은 eKuiper가 수행합니다."}</p>
    {!enabled && <p className="notice">연속 처리 기능이 아직 활성화되지 않았습니다.</p>}
    {!advanced ? <>
      <ContinuousQuickFields inputs={choices} deviceId={sources[0].deviceId} output={configuration.outputs[0]} selectSensor={id => {
        const device = choices.find(item => item.deviceId === id);
        source(0, { deviceId: id, properties: device ? [device.property] : [] });
        setConfiguration(value => ({ ...value, outputs: value.outputs.map(row => device?.type === "bool" ? { name: row.name, expression: "a", operation: "scalar", unit: device.unit || "" } : { ...row, unit: row.operation === "threshold" ? "" : device?.unit || "" }) }));
      }} changeOutput={row => setConfiguration(value => ({ ...value, outputs: [row] }))} />
      {nameField}
      <p className="hint">{configuration.intervalSeconds}초마다 계산 · {windowOperation(configuration.outputs[0].operation) && `최근 ${configuration.outputs[0].sampleCount}개 값 사용 · `}모든 결과 {configuration.retentionDays}일 보관</p>
      {feedbackField}{submitButton}
      <details><summary>고급 설정 · 표본 수·주기·보관 기간</summary><div className="form-row">
        {windowOperation(configuration.outputs[0].operation) && <label>최근 표본 수<input type="number" required min={1} max={1024} value={configuration.outputs[0].sampleCount ?? 5} onChange={event => output(0, { sampleCount: Number(event.target.value) })} /></label>}
        <label>계산 간격(초)<input type="number" required min={1} max={3600} value={configuration.intervalSeconds} onChange={event => setConfiguration(value => ({ ...value, intervalSeconds: Number(event.target.value) }))} /></label>
        <label>결과 보관 기간(일)<input type="number" required min={1} max={30} value={configuration.retentionDays} onChange={event => setConfiguration(value => ({ ...value, retentionDays: Number(event.target.value) }))} /></label>
      </div></details>
      <button type="button" onClick={() => { setAdvanced(true); requestAnimationFrame(() => formElement.current?.querySelector<HTMLInputElement>("input")?.focus()); }}>여러 센서·직접 계산식 설정</button>
    </> : <>
    <div className="form-row">{!initial && <label>장치 키<input name="key" value={registrationKey} onChange={event => setRegistrationKey(event.target.value)} maxLength={100} pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" placeholder="비워 두면 자동 생성" /></label>}{nameField}</div>
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
    <label>결과 저장 정책<select aria-label="결과 저장 정책" value={storage.mode} onChange={event => { const mode = event.target.value as "NONE" | "SELECTED" | "ALL"; setConfiguration(value => ({ ...value, resultStorage: mode === "SELECTED" ? { mode, outputs: [] } : { mode } })); }}><option value="NONE">저장 안 함</option><option value="SELECTED">선택 저장</option><option value="ALL">전체 저장</option></select></label>
    <p className="hint">처리 정의와 버전은 항상 보관합니다. 결과 저장 정책은 계산과 출력 조건을 바꾸지 않습니다. 선택 저장은 아래에서 항목과 조건을 지정하세요.</p>
    <fieldset className="publish-fields"><legend>출력 항목 · 최대 16개</legend>
      <p className="hint">별칭으로 식을 작성하세요. 예: (a + b) / 2, a &gt; 30, a &gt; 30 &amp;&amp; b &lt; 80. 입력 누락이나 연결 변경 시 창을 다시 모읍니다.</p>
      {configuration.outputs.map((row, index) => <fieldset key={index} className="publish-fields"><legend>출력 {index + 1}</legend>
        <div className="form-row"><label>항목 이름<input aria-label={`출력 ${index + 1} 이름`} required pattern="[a-z][a-z0-9_]{0,62}" value={row.name} onChange={event => { const name = event.target.value; setConfiguration(value => ({ ...value, outputs: value.outputs.map((old, n) => n === index ? { ...old, name } : old), ...(value.resultStorage?.mode === "SELECTED" ? { resultStorage: { mode: "SELECTED", outputs: value.resultStorage.outputs.map(selected => selected.name === row.name ? { ...selected, name } : selected) } } : {}) })); }} /></label>
          <label>처리<select aria-label={`출력 ${index + 1} 처리`} value={row.operation} onChange={event => { const operation = event.target.value as Output["operation"]; const replacement: Output = { name: row.name, expression: row.expression, operation, unit: row.unit, ...(windowOperation(operation) ? { sampleCount: row.sampleCount || 5 } : {}), ...(operation === "filter" || row.condition ? { condition: row.condition || "a > 0" } : {}) }; setConfiguration(value => ({ ...value, outputs: value.outputs.map((old, n) => n === index ? replacement : old) })); }}>{Object.entries(operations).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
          <label>단위<input aria-label={`출력 ${index + 1} 단위`} maxLength={32} value={row.unit} onChange={event => output(index, { unit: event.target.value })} /></label></div>
        <label>{row.operation === "threshold" ? "참/거짓 계산식" : "계산식"}<input aria-label={`출력 ${index + 1} 계산식`} required maxLength={1024} value={row.expression} onChange={event => output(index, { expression: event.target.value })} /></label>
        {windowOperation(row.operation) && <label>창 표본 수<input aria-label={`출력 ${index + 1} 창 표본 수`} type="number" required min={1} max={1024} value={row.sampleCount ?? 1} onChange={event => output(index, { sampleCount: Number(event.target.value) })} /></label>}
        <label>출력 조건{row.operation !== "filter" && " (선택)"}<input aria-label={`출력 ${index + 1} 조건`} required={row.operation === "filter"} maxLength={1024} value={row.condition || ""} onChange={event => { const next = { ...row }; if (event.target.value) next.condition = event.target.value; else delete next.condition; setConfiguration(value => ({ ...value, outputs: value.outputs.map((old, n) => n === index ? next : old) })); }} /></label>
        {storage.mode === "SELECTED" && <><label><input type="checkbox" aria-label={`출력 ${index + 1} 저장`} checked={storage.outputs.some(selected => selected.name === row.name)} onChange={event => storageOutput(row.name, event.target.checked)} /> 이 항목 저장</label>{storage.outputs.some(selected => selected.name === row.name) && <label>저장 조건 (선택)<input aria-label={`출력 ${index + 1} 저장 조건`} maxLength={1024} placeholder="value >= 10" value={storage.outputs.find(selected => selected.name === row.name)?.condition || ""} onChange={event => storageOutput(row.name, true, event.target.value)} /><span className="hint">계산된 결과를 value로 참조합니다. 비워 두면 이 항목의 모든 결과를 저장합니다.</span></label>}</>}
        <button type="button" disabled={configuration.outputs.length === 1} onClick={() => setConfiguration(value => ({ ...value, outputs: value.outputs.filter((_, n) => n !== index), ...(value.resultStorage?.mode === "SELECTED" ? { resultStorage: { mode: "SELECTED", outputs: value.resultStorage.outputs.filter(selected => selected.name !== row.name) } } : {}) }))}>출력 {index + 1} 삭제</button>
      </fieldset>)}
      <button type="button" disabled={configuration.outputs.length >= 16} onClick={() => setConfiguration(value => { let n = value.outputs.length; while (value.outputs.some(row => row.name === `value${n}`)) n++; return { ...value, outputs: [...value.outputs, { name: `value${n}`, expression: sources[0].sourceKey, operation: "scalar", unit: "" }] }; })}>출력 항목 추가</button>
    </fieldset>
    </>}
    {advanced && <>{feedbackField}{submitButton}</>}
  </fieldset></form>;
}
