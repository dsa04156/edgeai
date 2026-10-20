import type { components } from "../../lib/api-schema";
import { ContinuousQuickCalculation } from "./continuous-quick-fields";
import { presetChoices, presetUnit, type ContinuousPreset } from "./continuous-presets";

type Schema = components["schemas"];
export function ContinuousPresetFields({ preset, inputs, sources, output, selectSensor, changeOutput }: {
  preset: ContinuousPreset; inputs: Schema["ContinuousSensor"][]; sources: Schema["VirtualDeviceSources"];
  output: Schema["ContinuousOutputDefinition"]; selectSensor: (index: number, id: string) => void;
  changeOutput: (value: Schema["ContinuousOutputDefinition"]) => void;
}) {
  const unit = presetUnit(preset, inputs, sources);
  return <>
    {preset.id === "temperatureDifference" && <p className="hint">온도 1에서 온도 2를 뺀 값입니다.</p>}
    <div className="form-row">{preset.slots.map((slot, index) => {
      const choices = presetChoices(preset, index, inputs, sources);
      const selected = sources[index].deviceId;
      return <label key={index}>{slot.label} 센서<select aria-label={`${slot.label} 센서`} required value={selected} onChange={event => selectSensor(index, event.target.value)}>
        <option value="">측정 항목 선택</option>
        {choices.map(sensor => <option key={sensor.deviceUid} value={sensor.deviceId}>{sensor.property} ({sensor.unit}) · {sensor.deviceId} · {sensor.targetNode}</option>)}
        {selected && !choices.some(sensor => sensor.deviceId === selected) && <option value={selected} disabled>{selected} · 연결 확인 필요</option>}
      </select>{!choices.length && <span className="hint">같은 단위의 서로 다른 {slot.label} 측정 항목이 필요합니다.</span>}</label>;
    })}</div>
    <ContinuousQuickCalculation output={output} expression={preset.expression} unit={unit} combined changeOutput={changeOutput} />
    {unit && <p className="hint">결과 단위: {unit}</p>}
  </>;
}
