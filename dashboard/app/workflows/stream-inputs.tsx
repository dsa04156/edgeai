import type { components } from "../../lib/api-schema";

export type StreamInput = components["schemas"]["StreamRunInput"];

export function StreamInputFields({ value, onChange }: { value: StreamInput[]; onChange: (value: StreamInput[]) => void }) {
  function update(index: number, patch: Partial<StreamInput>) {
    onChange(value.map((row, i) => i === index ? { ...row, ...patch } : row));
  }
  return <fieldset className="publish-fields">
    <legend>장치 스트림 입력</legend>
    <p className="hint">장치의 활성 세션을 실행에 고정합니다. 작업 사이의 스트림 연결은 발행한 DAG를 따릅니다. 현재 스트림 실행은 AUTO·NODE에서 자동 재시도·전환 없이 사용합니다.</p>
    {value.length === 0 && <p className="muted">지정한 장치 입력이 없습니다. 장치 데이터를 받는 스트림 포트마다 입력을 추가하세요.</p>}
    {value.map((row, index) => <fieldset className="publish-fields" key={index}>
      <legend>스트림 입력 {index + 1}</legend>
      <label>원본 장치 ID<input required maxLength={36} pattern="[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}" value={row.deviceId} onChange={e => update(index, { deviceId: e.target.value })} /></label>
      <label>장치 출력 포트<input required maxLength={100} value={row.sourcePort} onChange={e => update(index, { sourcePort: e.target.value })} /></label>
      <div className="form-row">
        <label>받는 작업 키<input required maxLength={100} value={row.toTask} onChange={e => update(index, { toTask: e.target.value })} /></label>
        <label>받는 스트림 포트<input required maxLength={100} value={row.toPort} onChange={e => update(index, { toPort: e.target.value })} /></label>
      </div>
      <label>메시지 최대 크기(바이트)<input type="number" min={1} max={262144} step={1} required value={row.maxPayloadBytes} onChange={e => update(index, { maxPayloadBytes: Number(e.target.value) })} /></label>
      <button type="button" onClick={() => onChange(value.filter((_, i) => i !== index))}>스트림 입력 {index + 1} 삭제</button>
    </fieldset>)}
    <button type="button" disabled={value.length >= 128} onClick={() => onChange([...value, { deviceId: "", sourcePort: "samples", toTask: "", toPort: "", maxPayloadBytes: 4096 }])}>장치 스트림 입력 추가</button>
  </fieldset>;
}
