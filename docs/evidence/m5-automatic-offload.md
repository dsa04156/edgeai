# M5 자동 전환 — 구현·로컬 검증, 실제 kind 수용 전

범위는 ADR0009, RunCreate.offload, Operation.trigger/decision, Attempt.excludedNodeNames와 V9다.
전체 M5 완료나 최종 최적화 알고리즘의 성능 수용을 의미하지 않는다. Remote·상태형 복원은 남는다.

## 확인한 동작

- 기본 비활성, Run에 불변 정책을 저장하며 같은 키/다른 정책은409다. 모든 SERVICE가 RESTART를
  선언해야 한다. 최초 NODE 지정도 자동 전환 후 AUTO가 될 수 있음을 화면/Swagger에 명시한다.
- CPU/메모리 제한 대비 사용률 또는 서비스 개별 지연이 같은 지표에서 연속 충족되어야 한다.
  정확한 정수 단위로 비교하고 미수집/무제한, 순번 누락, 시각 만료, 다른 Attempt, 재사용 지연을 거절한다.
- 서버의 warmup/cooldown 경과와 그 이후 새 표본을 모두 요구한다. 허용된 클라이언트 시각 오차도
  서버 대기 시간을 단축하지 못한다. 자동 한도와 전체8회 한도를 적용한다.
- 실제 PostgreSQL에서 동시8개 평가가 Operation 하나만 생성했다. 취소와 경쟁, 이미 확정한 Result,
  대체 노드 부재는 producer/결과를 잘못 바꾸지 않는다. 결정 표본은 측정 삭제 후에도 남는다.
- 이전 실행 노드는 AUTO 제외 목록으로 저장하고 RETRY도 유지한다. claim에서도 제외 노드를 거절한다.
  연속 전환에서 이미 방문한 두 노드만 있으면 전환하지 않고 새 호환 노드가 관측되면 전환한다.
- UI에서 선택적 임계값, 판단 대기/표본/전환 제한, 입력 오류,503 이후 동일 키 재전송, 판단 근거를
  PC/모바일로 확인했다. 펼친 설정 화면과 이력 스크린샷을 직접 검사했다.

| testRunId (2026-10-02) | 증거 범위 |
|---|---|
| 20261002T110904Z-41c3a4a2 | 단위/MVC47, CPU 정수 단위·시계 오차·샘플 경계·PodSpec 제외 규칙 |
| 20261002T111036Z-5ce0c152 | 실제 PostgreSQL69, 자동 정책 신규6개 및 기존63 회귀 |
| 20261002T110107Z-dce5503b | public/internal OpenAPI·생성 타입·JAR YAML 일치·MVC18 |
| 20261002T110544Z-e80b235c | UI lint/type/build·PC/모바일22 |
| 20261002T110714Z-4b64b47c | 실제 DB/API 브라우저8·한국어 Swagger30·DB503/복구 |
| 20261002T110449Z-c7f6dee0 | 실제 K8s server dry-run: AUTO/NODE/두 노드 제외 AUTO Job 승인 |

## 발견·수정한 문제

OpenAPI 최상위 anyOf를 타입 생성기가 선택 필드만 있는 유니온으로 확장해 TypeScript 검사에 실패했다.
모든 임계값이 null인 객체를 금지하는 not 조건으로 같은 의미를 표현해 타입·입력 검증을 유지했다.
첫 UI fixture는 버튼의 표시 이름만 사용했지만 실제 접근성 이름에는 key도 포함됐다. 실제 DOM 이름으로
수정했다. 수정 후 전체22개를 통과했다. 모바일의 펼친 제한 설정은 grid로 줄바꿈한다.

Kubernetes server dry-run은 matchFields NotIn의 values가 둘인 문서를 거절했다. field requirement는
값 하나만 허용하므로 이름별 NotIn을 동일 term 안에서 AND로 묶었다. 실제 서버에 동일 두 노드 제외
fixture를 다시 제출해 통과했다. server dry-run은 실제 scheduler bind/실행의 증거를 대신하지 않는다.

## 새 실제 수용시험

smoke-runtime.py --faults에 실제 cgroup 메모리/CPU와 실제 합성 workload 지연으로 각각 자동 전환하는
세 시나리오를 추가했다. 시험 임계값은 제어 경로 재현용이며 운영 권장값이 아니다. source 종료,
새 Pod/다른 Node, scheduler affinity, 동일 Task/new Attempt/epoch, late commit 차단, 판단 이력 보존,
이동 한도, 실제 S3 계산 결과와 cleanup을 확인한다. 메모리 사례는 STARTING 상태에서 API를
재시작해 영속 Operation/decision 복구를 검증한다. 해당 신규 CI의 통과는 아직 판정하지 않았다.

로컬 Docker 권한 제한은 유지한다. 실제 container/kind는 GitHub runner에서 확인한다.
실행 측정 자체의 이전 코드61b6caa는 CI36996007482·kind15Run·실제 배포까지 통과했으며
자세한 근거는 m5-runtime-telemetry.md다. 그 증거를 이번 자동 정책의 실제 실행 통과로 재사용하지 않는다.
