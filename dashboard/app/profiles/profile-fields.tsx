"use client";
import { parse, stringify } from "lossless-json";
import type { components } from "../../lib/api-schema";
import { newVDTemplate, record } from "../../lib/vd-profile";
import { VDProfileFields } from "./vd-profile-fields";
type Profile = components["schemas"]["ProfileVersion"];

/** Structured entry for common profiles; the advanced editor preserves the complete spec. */
export function ProfileFields({ kind, value, onChange, profiles }: { kind: Profile["kind"]; value: string; onChange: (value: string) => void; profiles: Profile[] }) {
  let spec: Record<string, unknown>;
  try { spec = parse(value || "{}") as Record<string, unknown>; if (!spec || Array.isArray(spec) || typeof spec !== "object") return null; }
  catch { return null; }
  function update(patch: Record<string, unknown>) { onChange(stringify({ ...spec, ...patch }, null, 2) || ""); }
  const service = () => ({ apiVersion: "edgeai/v1", image: "", command: ["python3", "/opt/edgeai/examples/linear.py"], args: [],
    resources: { requests: { cpu: "100m", memory: "64Mi" }, limits: { cpu: "1", memory: "256Mi" } },
    platform: { os: "linux", architectures: ["amd64"] }, inputs: {}, outputs: { output: { mediaType: "application/json", maxBytes: 1048576 } }, timeoutSeconds: 120, qos: "Burstable" });
  return <div className="publish-fields"><div className="toolbar"><button type="button" onClick={() => onChange(stringify(kind === "SERVICE" ? service() : kind === "DEVICE" ? { protocol: "mqtt" } : newVDTemplate(), null, 2) || "")}>{kind} 기본 양식 채우기</button></div>
    {kind === "DEVICE" && <label>장치 프로토콜<input value={String(spec.protocol || "")} onChange={e => update({ protocol: e.target.value })} placeholder="mqtt / http / usb" /></label>}
    {kind === "SERVICE" && spec.apiVersion === "edgeai/v1" && <>
      <label>실행 이미지<input value={String(spec.image || "")} onChange={e => update({ image: e.target.value })} placeholder="registry/image@sha256:…" /></label>
      <p className="hint">기존 Runner가 포함된 실행 이미지를 사용하세요. 이미지 digest와 장비 아키텍처가 맞아야 합니다.</p>
      <label>실행 명령 · 한 줄에 인자 하나<textarea rows={3} value={(Array.isArray(spec.command) ? spec.command : []).join("\n")} onChange={e => update({ command: e.target.value.split("\n") })} /></label>
      <label>추가 인자 · 한 줄에 하나<textarea rows={2} value={(Array.isArray(spec.args) ? spec.args : []).join("\n")} onChange={e => update({ args: e.target.value ? e.target.value.split("\n") : [] })} /></label>
      <label>실행 제한 시간(초)<input type="number" min={1} max={86400} value={String(spec.timeoutSeconds || 120)} onChange={e => update({ timeoutSeconds: Number(e.target.value) })} /></label>
      <label>QoS<select value={String(spec.qos || "Burstable")} onChange={event => update({ qos: event.target.value })}><option value="Burstable">Burstable</option><option value="Guaranteed">Guaranteed</option></select></label>
      <fieldset className="publish-fields"><legend>CPU·메모리 요구사항</legend>{(["requests", "limits"] as const).map(scope => <div key={scope} className="form-row">{(["cpu", "memory"] as const).map(resource => <label key={resource}>{scope} · {resource}
        <input value={String(record(record(spec.resources)[scope])[resource] ?? "")} onChange={event => {
          const resources = record(spec.resources), values = { ...record(resources[scope]) };
          if (event.target.value) values[resource] = event.target.value; else delete values[resource];
          update({ resources: { ...resources, [scope]: values } });
        }} placeholder={resource === "cpu" ? "100m" : "256Mi"} />
      </label>)}</div>)}</fieldset>
      {(["inputs", "outputs"] as const).map(direction => {
        const entries = spec[direction] as Record<string, { mediaType: string; maxBytes?: number; required?: boolean }> || {};
        return <fieldset className="publish-fields" key={direction}><legend>{direction === "inputs" ? "BATCH 입력" : "BATCH 출력"} 포트</legend>
          {Object.entries(entries).map(([key, port]) => <div className="form-row" key={key}><label>{key} 형식<input value={port.mediaType} onChange={e => update({ [direction]: { ...entries, [key]: { ...port, mediaType: e.target.value } } })} /></label><button type="button" onClick={() => update({ [direction]: Object.fromEntries(Object.entries(entries).filter(([name]) => name !== key)) })}>{key} 포트 삭제</button></div>)}
          <button type="button" onClick={() => { let index = 1; while (`${direction === "inputs" ? "input" : "output"}${index}` in entries) index++;
            update({ [direction]: { ...entries, [`${direction === "inputs" ? "input" : "output"}${index}`]: direction === "inputs" ? { mediaType: "application/json", maxBytes: 1048576, required: true } : { mediaType: "application/json", maxBytes: 1048576 } } });
          }}>{direction === "inputs" ? "입력" : "출력"} 포트 추가</button>
        </fieldset>;
      })}
    </>}
    {kind === "VD" && spec.apiVersion === "edgeai.vd/v1" && <VDProfileFields spec={spec} profiles={profiles} update={update} />}
  </div>;
}
