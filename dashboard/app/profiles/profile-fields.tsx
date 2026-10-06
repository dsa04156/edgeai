"use client";
import { parse, stringify } from "lossless-json";
import type { components } from "../../lib/api-schema";
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
  return <div className="publish-fields"><div className="toolbar"><button type="button" onClick={() => onChange(stringify(kind === "SERVICE" ? service() : kind === "DEVICE" ? { protocol: "mqtt" } : {
    apiVersion: "edgeai.vd/v1", type: "emulation", serviceProfileVersionId: "", sources: {}, state: { mode: "STATELESS" }, runtime: { maxConcurrentTasks: 1, startupTimeoutSeconds: 120, drainTimeoutSeconds: 120 },
  }, null, 2) || "")}>{kind} 기본 양식 채우기</button></div>
    {kind === "DEVICE" && <label>장치 프로토콜<input value={String(spec.protocol || "")} onChange={e => update({ protocol: e.target.value })} placeholder="mqtt / http / usb" /></label>}
    {kind === "SERVICE" && spec.apiVersion === "edgeai/v1" && <>
      <label>실행 이미지<input value={String(spec.image || "")} onChange={e => update({ image: e.target.value })} placeholder="registry/image@sha256:…" /></label>
      <p className="hint">기존 Runner가 포함된 실행 이미지를 사용하세요. 이미지 digest와 장비 아키텍처가 맞아야 합니다.</p>
      <label>실행 명령 · 한 줄에 인자 하나<textarea rows={3} value={(spec.command as string[] || []).join("\n")} onChange={e => update({ command: e.target.value.split("\n") })} /></label>
      <label>추가 인자 · 한 줄에 하나<textarea rows={2} value={(spec.args as string[] || []).join("\n")} onChange={e => update({ args: e.target.value ? e.target.value.split("\n") : [] })} /></label>
      <label>실행 제한 시간(초)<input type="number" min={1} max={86400} value={String(spec.timeoutSeconds || 120)} onChange={e => update({ timeoutSeconds: Number(e.target.value) })} /></label>
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
    {kind === "VD" && spec.apiVersion === "edgeai.vd/v1" && <>
      <label>가상 장치 종류<select value={String(spec.type)} onChange={e => update({ type: e.target.value })}><option value="emulation">데이터 생성</option><option value="sensorMirror">센서 미러</option><option value="processing">데이터 처리</option></select></label>
      <label>실행 SERVICE 버전<select value={String(spec.serviceProfileVersionId || "")} onChange={e => update({ serviceProfileVersionId: e.target.value })}><option value="">서비스 선택</option>{profiles.filter(p => p.kind === "SERVICE").map(p => <option key={p.id} value={p.id}>{p.key} · {p.version}</option>)}</select></label>
      <fieldset className="publish-fields"><legend>원본 DEVICE Profile</legend>
        {Object.entries(spec.sources as Record<string, { deviceProfileVersionId: string; required: boolean; sourceModes: string[] }> || {}).map(([key, source]) => <div className="form-row" key={key}>
          <label>{key} 원본 규격<select value={source.deviceProfileVersionId} onChange={e => update({ sources: { ...(spec.sources as object), [key]: { ...source, deviceProfileVersionId: e.target.value } } })}><option value="">장치 프로필 선택</option>{profiles.filter(p => p.kind === "DEVICE").map(p => <option key={p.id} value={p.id}>{p.key} · {p.version}</option>)}</select></label>
          <button type="button" onClick={() => update({ sources: Object.fromEntries(Object.entries(spec.sources as object).filter(([name]) => name !== key)) })}>{key} 원본 삭제</button>
        </div>)}
        <button type="button" onClick={() => { const sources = spec.sources as Record<string, unknown> || {}; let index = 1; while (`input${index}` in sources) index++;
          update({ sources: { ...sources, [`input${index}`]: { deviceProfileVersionId: "", required: true, sourceModes: ["LIVE", "REPLAY", "SYNTHETIC"] } } });
        }}>원본 규격 추가</button>
      </fieldset>
    </>}
  </div>;
}
