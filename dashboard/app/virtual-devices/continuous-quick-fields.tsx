import { useState } from "react";
import type { components } from "../../lib/api-schema";

type Schema = components["schemas"];
type Output = Schema["ContinuousOutputDefinition"];
const calculations = { scalar: "측정값 그대로", movingAverage: "최근 값 평균", minimum: "최근 값 최솟값", maximum: "최근 값 최댓값", threshold: "기준값 비교" };

export function ContinuousQuickFields({ inputs, deviceId, output, selectSensor, changeOutput }: {
  inputs: Schema["ContinuousSensor"][]; deviceId: string; output: Output;
  selectSensor: (id: string) => void; changeOutput: (value: Output) => void;
}) {
  const [comparison, setComparison] = useState(">=");
  const [threshold, setThreshold] = useState("30");
  const sensor = inputs.find(input => input.deviceId === deviceId);
  const boolean = sensor?.type === "bool";
  function calculation(operation: Output["operation"]) {
    changeOutput({ name: output.name, operation, expression: operation === "threshold" ? `a ${comparison} ${threshold}` : "a", unit: operation === "threshold" ? "" : sensor?.unit || "", ...(["movingAverage", "minimum", "maximum"].includes(operation) ? { sampleCount: output.sampleCount ?? 5 } : {}) });
  }
  return <>
    <label>센서<select required aria-label="센서" value={deviceId} onChange={event => selectSensor(event.target.value)}>
      <option value="">측정 항목 선택</option>
      {inputs.map(input => <option key={input.deviceUid} value={input.deviceId}>{input.property}{input.unit ? ` (${input.unit})` : ""} · {input.deviceId} · {input.targetNode}</option>)}
    </select></label>
    {!inputs.length && <p className="hint">측정 항목별 센서를 먼저 등록하세요. <a href="/devices?view=sensors">센서 등록 →</a></p>}
    <label>계산 방식<select aria-label="계산 방식" value={output.operation} onChange={event => calculation(event.target.value as Output["operation"])}>
      {Object.entries(calculations).map(([value, label]) => <option key={value} value={value} disabled={boolean && value !== "scalar"}>{label}</option>)}
    </select></label>
    {output.operation === "threshold" && <div className="form-row">
      <label>비교 조건<select aria-label="비교 조건" value={comparison} onChange={event => { setComparison(event.target.value); changeOutput({ ...output, expression: `a ${event.target.value} ${threshold}` }); }}>
        <option value=">=">기준값 이상</option><option value=">">기준값 초과</option><option value="<=">기준값 이하</option><option value="<">기준값 미만</option>
      </select></label>
      <label>기준값{sensor?.unit ? ` (${sensor.unit})` : ""}<input aria-label="기준값" type="number" step="any" required value={threshold} onChange={event => { setThreshold(event.target.value); changeOutput({ ...output, expression: `a ${comparison} ${event.target.value}` }); }} /></label>
    </div>}
  </>;
}
