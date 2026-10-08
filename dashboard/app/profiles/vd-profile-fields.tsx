"use client";
import { useEffect, useState } from "react";
import type { components } from "../../lib/api-schema";
import { record, sourceRequirements, vdTypeNames, type VDSourceRequirement } from "../../lib/vd-profile";

type Profile = components["schemas"]["ProfileVersion"];
type Props = { spec: Record<string, unknown>; profiles: Profile[]; update: (patch: Record<string, unknown>) => void };

function SourceCondition({ name, source, sources, devices, update }: { name: string; source: VDSourceRequirement; sources: Record<string, unknown>; devices: components["schemas"]["ManagedDevice"][]; update: Props["update"] }) {
  const [draftKey, setDraftKey] = useState(name);
  function change(patch: Partial<VDSourceRequirement>) { update({ sources: { ...sources, [name]: { ...record(sources[name]), ...patch } } }); }
  return <fieldset className="publish-fields"><legend>원본 조건 · {name}</legend>
    <div className="form-row"><label>원본 키<input required maxLength={100} pattern="[a-z][a-z0-9]*([._\-][a-z0-9]+)*" value={draftKey}
      onChange={event => { setDraftKey(event.target.value); event.target.setCustomValidity(""); }}
      onBlur={event => {
        const next = event.target.value;
        if (next === name) return;
        if (Object.hasOwn(sources, next)) { event.target.setCustomValidity("이미 사용 중인 원본 키입니다."); event.target.reportValidity(); return; }
        if (event.target.checkValidity()) update({ sources: Object.fromEntries(Object.entries(sources).map(([key, value]) => [key === name ? next : key, value])) });
      }} /></label>
      <label>센서 DeviceModel<select aria-label="센서 DeviceModel" required value={source.deviceModel} onChange={event=>change({deviceModel:event.target.value})}>
        <option value="">센서 모델 선택</option>{[...new Set(devices.map(d=>d.model))].map(model=><option key={model} value={model}>{devices.filter(d=>d.model===model).map(d=>d.id).join(" · ")}</option>)}
      </select></label></div>
    <label className="checkbox-row"><input type="checkbox" checked={source.required} onChange={event => change({ required: event.target.checked })} /> 필수 원본</label>
    <button type="button" onClick={() => update({ sources: Object.fromEntries(Object.entries(sources).filter(([key]) => key !== name)) })}>{name} 원본 조건 삭제</button>
  </fieldset>;
}

export function VDProfileFields({ spec, profiles, update }: Props) {
  const [devices,setDevices]=useState<components["schemas"]["ManagedDevice"][]>([]);
  const [failed,setFailed]=useState(false);
  useEffect(()=>{const controller=new AbortController();void fetch("/api/control-plane/managed-devices",{cache:"no-store",signal:controller.signal}).then(r=>{if(!r.ok)throw new Error("조회 실패");return r.json();}).then((page: components["schemas"]["ManagedDevicePage"])=>setDevices(page.items)).catch(()=>{if(!controller.signal.aborted)setFailed(true);});return()=>controller.abort();},[]);
  const sources = record(spec.sources), requirements = sourceRequirements(spec), runtime = record(spec.runtime);
  return <>
    <p className="hint">템플릿은 원본 조건과 실행 정책을 정의합니다. 실제 장치·노드는 가상 디바이스를 등록할 때 연결합니다.</p>
    <label>가상 장치 종류<select aria-label="가상 장치 종류" value={String(spec.type || "")} onChange={event => update({ type: event.target.value })}>
      {Object.entries(vdTypeNames).map(([type, name]) => <option key={type} value={type}>{name} · {type}</option>)}
    </select></label>
    <label>처리 SERVICE 규격 버전<select aria-label="처리 SERVICE 규격 버전" required value={String(spec.serviceProfileVersionId || "")} onChange={event => update({ serviceProfileVersionId: event.target.value })}>
      <option value="">실행 모듈 선택</option>{profiles.filter(p => p.kind === "SERVICE").map(p => <option key={p.id} value={p.id}>{p.key} · {p.version}</option>)}
    </select></label>
    {!profiles.some(p => p.kind === "SERVICE") && <p className="hint">실행 모듈이 없습니다. <a href="/profiles?kind=SERVICE">SERVICE 규격 등록 →</a></p>}
    <fieldset className="publish-fields"><legend>원본 규격과 조건</legend>
      {spec.type !== "emulation" && <p className="hint">센서 미러와 데이터 처리는 필수 원본이 하나 이상 필요합니다.</p>}
      {Object.entries(requirements).map(([key, source]) => <SourceCondition key={key} name={key} source={source} sources={sources} devices={devices} update={update} />)}
      {!Object.keys(sources).length && <p className="muted">원본 조건이 없습니다. 모의 장치는 원본 없이 구성할 수 있습니다.</p>}
      <button type="button" disabled={Object.keys(sources).length >= 16} onClick={() => {
        let index = 1; while (Object.hasOwn(sources, `input${index}`)) index++;
        update({ sources: { ...sources, [`input${index}`]: { deviceModel: "", required: true } } });
      }}>원본 조건 추가</button>
      {failed&&<p role="alert" className="error">센서 목록을 불러오지 못했습니다. 연결 상태를 확인하고 새로고침하세요.</p>}
      <p className="hint"><a href="/devices?view=sensors">센서 디바이스 등록·관리 →</a></p>
    </fieldset>
    <fieldset className="publish-fields"><legend>상태와 실행 정책</legend>
      <label>상태 관리<select aria-label="상태 관리" value={String(record(spec.state).mode || "STATELESS")} onChange={event => update({ state: { ...record(spec.state), mode: event.target.value } })}>
        <option value="STATELESS">상태 없음 · STATELESS</option>
      </select></label>
      <div className="form-row">{([
        ["maxConcurrentTasks", "동시 작업 수", 16], ["startupTimeoutSeconds", "시작 제한 시간(초)", 600], ["drainTimeoutSeconds", "종료 제한 시간(초)", 600],
      ] as const).map(([key, label, maximum]) => <label key={key}>{label}<input type="number" required min={1} max={maximum} step={1} value={String(runtime[key] ?? "")}
        onChange={event => update({ runtime: { ...runtime, [key]: event.target.value === "" ? "" : Number(event.target.value) } })} /></label>)}</div>
      <p className="hint">원본·실행체를 교체해도 VD ID와 연결 이력을 유지합니다. 상태형 복원 지원은 별도 수용 범위입니다.</p>
    </fieldset>
  </>;
}
