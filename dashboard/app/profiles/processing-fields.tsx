"use client";

import { record } from "../../lib/vd-profile";
import { newProcessingStep, processingNames } from "../../lib/processing";
import type { components } from "../../lib/api-schema";

type Props = { spec: Record<string, unknown>; devices: components["schemas"]["ManagedDevice"][]; update: (patch: Record<string, unknown>) => void; sensorOnly?: boolean };
export function ProcessingFields({ spec, devices, update, sensorOnly = false }: Props) {
  const config = record(spec.processing), pipelines = Array.isArray(config.pipelines) ? config.pipelines.map(record) : [];
  const sources = Object.keys(record(spec.sources));
  let nextName = 1;
  while (pipelines.some(value => value.name === `signal${nextName}`)) nextName++;
  const change = (patch: Record<string, unknown>) => update({ processing: { ...config, ...patch } });
  const pipeline = (index: number, patch: Record<string, unknown>) => change({ pipelines: pipelines.map((value, position) => position === index ? { ...value, ...patch } : value) });
  return <fieldset className="publish-fields"><legend>공통 데이터 처리</legend>
    {!spec.processing ? <button type="button" onClick={() => update({ processing: { apiVersion: "edgeai.processing/v1", sampleCount: 1, outputPort: "output", pipelines: [
      { name: "signal", input: { sourceKey: sources[0] || "", property: "" }, steps: [newProcessingStep("threshold")] },
    ] } })}>공통 처리 설정 추가</button> : <>
      <p className="hint">단계를 위에서 아래로 실행합니다. 센서 값은 Mapper의 배율·보정 적용값이며 다시 변환하지 않습니다.</p>
      <div className="form-row"><label>처리 표본 수<input aria-label="처리 표본 수" required type="number" min={1} max={1024} step={1} value={String(config.sampleCount ?? "")} onChange={e => change({ sampleCount: Number(e.target.value) })} /></label>
        {!sensorOnly && <label>처리 출력 포트<input required maxLength={63} pattern="[a-z][a-z0-9_]*" value={String(config.outputPort || "")} onChange={e => change({ outputPort: e.target.value })} /></label>}</div>
      <p className="hint">서로 다른 관측 시각의 표본이 모두 준비되면 실행할 수 있습니다. 허용 수신 시간 안에 표본 창이 들어와야 합니다.</p>
      {pipelines.map((value, index) => {
        const prefix = `처리 ${index + 1}`, input = record(value.input), steps = Array.isArray(value.steps) ? value.steps.map(record) : [];
        const model = record(record(spec.sources)[String(input.sourceKey)]).deviceModel;
        const properties = [...new Set(devices.filter(device => device.model === model).flatMap(device => {
          const profile = record(device.profile);
          return (Array.isArray(profile.properties) ? profile.properties.map(record) : []).filter(property => !property.dataType || ["float32", "float64", "int16", "uint16", "int32", "uint32"].includes(String(property.dataType))).map(property => String(property.name));
        }))];
        const setStep = (position: number, patch: Record<string, unknown>, replace = false) => pipeline(index, { steps: steps.map((v, i) => i === position ? replace ? patch : { ...v, ...patch } : v) });
        return <fieldset className="publish-fields" key={index}><legend>{prefix}</legend>
          {!sensorOnly && <><label>{prefix} · 결과 이름<input required maxLength={63} pattern="[a-z][a-z0-9_]*" value={String(value.name || "")} onChange={e => pipeline(index, { name: e.target.value })} /></label>
          <label>{prefix} · 입력 종류<select value={"port" in input ? "artifact" : "sensor"} onChange={e => pipeline(index, { input: e.target.value === "artifact" ? { port: "input", output: "signal" } : { sourceKey: sources[0] || "", property: "" } })}>
            <option value="sensor">원본 센서 측정값</option><option value="artifact">다른 VD의 처리 결과</option></select></label></>}
          {"port" in input ? <div className="form-row">{(["port", "output"] as const).map(key => <label key={key}>{prefix} · {key === "port" ? "입력 포트" : "원본 결과 이름"}<input required maxLength={63} pattern="[a-z][a-z0-9_]*" value={String(input[key] || "")} onChange={e => pipeline(index, { input: { ...input, [key]: e.target.value } })} /></label>)}</div> : <div className="form-row">
            {!sensorOnly && <label>{prefix} · 원본 슬롯<select required value={String(input.sourceKey || "")} onChange={e => pipeline(index, { input: { ...input, sourceKey: e.target.value } })}><option value="">원본 선택</option>{sources.map(key => <option key={key}>{key}</option>)}</select></label>}
            <label>{prefix} · 측정 항목<select required value={String(input.property || "")} onChange={e => pipeline(index, { input: { ...input, property: e.target.value } })}><option value="">숫자 측정 항목 선택</option>{Boolean(input.property) && !properties.includes(String(input.property)) && <option value={String(input.property)}>{String(input.property)} · 규격 확인 필요</option>}{properties.map(property => <option key={property}>{property}</option>)}</select></label></div>}
          {steps.map((step, position) => {
            const label = `${prefix} · 단계 ${position + 1}`;
            const numeric = (key: string, name: string, min?: number, max?: number, increment?: number) => <label>{label} · {name}<input required type="number" min={min} max={max} step={increment || "any"} value={String(step[key] ?? "")} onChange={e => setStep(position, { [key]: Number(e.target.value) })} /></label>;
            return <fieldset className="publish-fields" key={position}><legend>{label}</legend>
              <label>{label} · 기능<select value={String(step.op)} onChange={e => setStep(position, newProcessingStep(e.target.value), true)}>{Object.entries(processingNames).map(([op, name]) => <option key={op} value={op}>{name}</option>)}</select></label>
              {step.op === "filter" && <div className="form-row"><label>{label} · 필터 종류<select value={String(step.mode)} onChange={e => setStep(position, { mode: e.target.value })}><option value="lowPass">저역 통과</option><option value="highPass">고역 통과</option></select></label>{numeric("alpha", "필터 계수", 0.000001, 1)}</div>}
              {(step.op === "movingAverage" || step.op === "rms") && numeric("window", "창 크기", 1, 1024, 1)}
              {step.op === "fft" && numeric("sampleRateHz", "표본 주파수(Hz)", 0.000001, 1000000)}
              {step.op === "threshold" && <div className="form-row"><label>{label} · 비교<select value={String(step.operator)} onChange={e => setStep(position, { operator: e.target.value })}>{[["gt", ">"], ["gte", "≥"], ["lt", "<"], ["lte", "≤"], ["eq", "="]].map(([op, text]) => <option key={op} value={op}>{text}</option>)}</select></label>{numeric("value", "기준값")}</div>}
              <div className="toolbar">{([-1, 1] as const).map(direction => <button key={direction} type="button" disabled={position + direction < 0 || position + direction >= steps.length} onClick={() => {
                const next = [...steps]; [next[position], next[position + direction]] = [next[position + direction], next[position]]; pipeline(index, { steps: next });
              }}>{label} · {direction < 0 ? "위로" : "아래로"}</button>)}<button type="button" disabled={steps.length === 1} onClick={() => pipeline(index, { steps: steps.filter((_, i) => i !== position) })}>{label} · 삭제</button></div>
            </fieldset>;
          })}
          <div className="toolbar"><button type="button" disabled={steps.length >= 16} onClick={() => pipeline(index, { steps: [...steps, newProcessingStep("threshold")] })}>{prefix} · 단계 추가</button>
            <button type="button" disabled={pipelines.length === 1} onClick={() => change({ pipelines: pipelines.filter((_, i) => i !== index) })}>{prefix} · 삭제</button></div>
        </fieldset>;
      })}
      <button type="button" disabled={pipelines.length >= 16} onClick={() => change({ pipelines: [...pipelines, { name: `signal${nextName}`, input: { sourceKey: sources[0] || "", property: "" }, steps: [newProcessingStep("threshold")] }] })}>처리 파이프라인 추가</button>
      {!sensorOnly && <p className="hint">다른 VD의 결과는 Workflow Builder에서 앞 작업의 출력 포트를 이 SERVICE 입력 포트에 연결합니다. AI 작업도 같은 출력 파일을 입력으로 사용할 수 있습니다.</p>}
    </>}
  </fieldset>;
}
