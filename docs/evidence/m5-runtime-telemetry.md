# M5 실행 측정 — 로컬 검증, 실제 컨테이너/kind 검증 전

자동 오프로딩 정책의 입력 경로다. 측정 수집만으로 자동 전환 구현이나 M5 완료를 판정하지 않는다.
계약은 ADR0008·Runner OpenAPI·TaskDetail.telemetry, 저장소는 Flyway V8이다.
V8은 로컬 PostgreSQL에 적용했으므로 이후 변경은 새 migration으로 한다.

## 구현과 확인 범위

- 실제 Runner cgroup v2 CPU 사용 시간/할당 제한·메모리 사용량/제한을 읽는다. 자기 cgroup이 없으면
  host root 값으로 대체하지 않는다. 무제한/측정 불가는 null이며 노드 잔여량이 아니다.
- 서비스의 선택적인 지연 파일은 원자적 교체·일반 파일·4KiB·새 sequence·최근시각을 요구한다.
  p95/p99·실장비 모델 성능을 의미하지 않는다. 같은 지연 샘플은 재전송하지 않는다.
- 내부 인증/Pod 신원 검증 뒤 Run 잠금으로 현재 producer와 freshness를 재확인한다. 동일 측정의
  동시8개 재전송은 한 행·동일 receipt, 내용 충돌/오래된 순번/시각은409다. 취소/완료/전환 후 거절한다.
- Attempt별 최신64개 보존, 새 Attempt의 측정 부재를 이전 값으로 채우지 않는다.
  UI는 제한 미확인·미수집·60초 만료와 단위를 구분한다. desktop/mobile 스크린샷을 직접 확인했다.
- 전송은 best effort다. 서버 장애/측정 거절은 계산을 계속하고 producer fencing은 프로세스를 종료한다.

| testRunId (2026-10-02) | 검증 |
|---|---|
| 20261002T101352Z-a032a29e | 새 서비스/DTO 구성 후 단위/MVC42 회귀 |
| 20261002T102545Z-1661be73 | 실제 PG63: 신규 HTTP/DB 측정3개·offload 이후 측정 차단/분리 및 기존60 회귀 |
| 20261002T102545Z-b6156543 | 호스트 Runner12: 실제 workload 지연·503/측정409 계속 실행·producer409 종료·기존 실행 회귀, reader fixture3개 |
| 20261002T102220Z-c4fe336d | public/internal OpenAPI·생성 타입·JAR 계약/MVC18 |
| 20261002T102146Z-b2e399b1 | UI lint/type/build·PC/모바일20, 단위·null·시계 진행 후 만료·새 시도 분리 |
| 20261002T102329Z-6d7a06fc | 실제 DB/API PC·모바일8·Swagger30·DB503/복구 |

호스트 시험의 cgroup 파일 fixtures는 측정 단위/예외 처리의 증거다. 실제 container CPU/memory는
CI Runner 시험에서 0.5CPU·128MiB 제한과 함께 검증하도록 추가했으며 아직 통과 판정 전이다.
기존 kind offload 시나리오에는 source/target의 실제 자원+합성 작업 지연 측정, Attempt별 분리와
이전 producer의 늦은 telemetry 거절을 추가했다. 해당 새 CI 결과와 실제 배포는 후속 확인 대상이다.

자동 policy의 임계값·연속 표본·cooldown·이동 예산·판단 근거 저장, failure/Remote 경로는 남아 있다.
이 표본64개 보관은 영속 감사 로그를 대신하지 않는다. GPU/NPU 지표와 실제 모델/장비 수용은 별도다.
