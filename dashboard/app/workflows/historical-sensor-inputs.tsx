import { stringify } from "lossless-json";
import type { components } from "../../lib/api-schema";

export function HistoricalSensorInputs({ input }: { input: components["schemas"]["HistoricalSensorInput"] }) {
  return <section aria-label="실행에 사용한 센서 입력">
    <h3>실행에 사용한 센서 입력</h3>
    <p className="hint">{new Date(input.capturedAt).toLocaleString()}에 고정했습니다. 기존 VD 실행에서 보존한 읽기 전용 이력입니다.</p>
    {Object.entries(input.sources).map(([slot, source]) => <div key={slot}>
      <h4>{slot} · {source.deviceId}</h4>
      <div className="table-scroll"><table><caption className="sr-only">{slot}의 고정 측정값</caption>
        <thead><tr><th>측정 항목</th><th>사용한 값</th><th>수신 시각</th></tr></thead>
        <tbody>{Object.entries(source.properties).map(([name, reading]) => <tr key={name}>
          <td>{name}</td><td>{String(reading.value)} {reading.unit}</td><td>{new Date(reading.observedAt).toLocaleString()}</td>
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
    {input.processing && <details><summary>과거 처리 입력 JSON</summary><pre>{stringify(input.processing, null, 2)}</pre></details>}
  </section>;
}
