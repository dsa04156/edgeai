type Availability = "loading" | "disconnected" | "unavailable" | "disabled" | "enabled";

const availabilityText: Record<Availability, string> = {
  loading: "연속 처리 상태 조회 중…",
  disconnected: "서버 연결 확인 필요",
  unavailable: "연속 처리 상태 확인 필요",
  disabled: "연속 처리 비활성",
  enabled: "연속 처리 등록 가능",
};

export function ContinuousFlow({ availability }: { availability: Availability }) {
  return <section aria-labelledby="continuous-flow-title" className="continuous-flow">
    <div className="toolbar"><h2 id="continuous-flow-title">연속 처리 흐름</h2><span className="muted" role="status">{availabilityText[availability]}</span></div>
    <ol aria-label="센서부터 결과까지" className="continuous-flow-stages">
      <li><strong>센서 노드에서 수집</strong><span className="muted">입력 경로 예시</span><p>Jetson 센서 → Mapper → NanoMQ</p><p>Pi 센서 → Mapper → NanoMQ</p></li>
      <li><strong>중앙에서 계산</strong><p>처리 agent ↔ eKuiper</p><span className="muted">유효한 입력으로 평균·최댓값·임계값 등을 계산</span></li>
      <li><strong>결과 저장</strong><p>백엔드 → 결과 DB</p><span className="muted">계산 결과와 기록 누락 구간을 보관</span></li>
      <li><strong>항목별 조회</strong><p>최신값 · 상태 · 그래프</p><span className="muted">결과는 항목마다 별도 행과 그래프로 표시</span></li>
    </ol>
    <p className="hint">센서 등록만으로 가상디바이스가 생기지는 않습니다. 입력과 계산을 등록하면 장치 1개가 생기고, 평균·최댓값·경보를 함께 넣으면 결과 3행으로 표시됩니다.</p>
    {availability === "enabled" && <p className="hint">등록 기능이 활성화되어 있습니다. 실제 계산 상태는 각 장치 상세에서 확인하세요.</p>}
    {availability === "disabled" && <p className="hint">중앙 계산 연결이 준비되면 연속 처리 등록을 사용할 수 있습니다. 기존 표본·FFT 처리는 별도로 이용할 수 있습니다.</p>}
    {availability === "unavailable" && <p className="hint">현재 서버에서 연속 처리 상태를 확인하지 못했습니다. 등록 패널에서 다시 조회할 수 있습니다.</p>}
  </section>;
}
