import type { components } from "../../lib/api-schema";

export function VDSensorInputs({ input }: { input: components["schemas"]["VDSensorInput"] }) {
  return <section aria-label="실행에 사용한 센서 입력">
    <h3>실행에 사용한 센서 입력</h3>
    <p className="hint">{new Date(input.capturedAt).toLocaleString()}에 고정했습니다. 재요청·재시도에서도 이 값을 사용합니다.</p>
    {input.processing && <p className="notice">공통 처리 · {input.processing.sampleCount}개 표본 · {input.processing.pipelines.map(p => `${p.name}: ${p.steps.map(s => s.op).join(" → ")}`).join(" · ")}</p>}
    {Object.entries(input.sources).map(([slot, source]) => <div key={slot}>
      <h4>{slot} · {source.deviceId}</h4>
      <div className="table-scroll"><table><caption className="sr-only">{slot}의 고정 측정값</caption>
        <thead><tr><th>측정 항목</th><th>사용한 값</th><th>수신 시각</th></tr></thead>
        <tbody>{Object.entries(source.properties).map(([name, reading]) => <tr key={name}>
          <td>{name}{reading.samples && <span className="block muted">고정 표본 {reading.samples.length}개</span>}</td><td>{String(reading.value)} {reading.unit}</td><td>{new Date(reading.observedAt).toLocaleString()}</td>
        </tr>)}</tbody>
      </table></div>
      <details><summary>{slot} · 원본 신원과 연결 이력</summary>
        <p className="digest mono">센서 UID {source.deviceUid}</p>
        <p className="digest mono">모델 {source.deviceModel}</p>
        <p className="digest mono">Binding {source.bindingId}</p>
        <p className="digest mono">VD {input.vdId} · revision {input.vdRevision}</p>
        <p className="hint">최대 수신 경과 시간 {source.maxAgeSeconds}초</p>
      </details>
    </div>)}
  </section>;
}
