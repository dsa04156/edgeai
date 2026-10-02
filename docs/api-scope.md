# API 범위

API 정의서의 30개 작업은 설계 초안이다. 공통 prefix는 ADR-0001에서 `/api/v1`으로 정합화했다.
현재 REST 계약은 `contracts/openapi/platform-api.yaml`의 30개 operation이다. 원문 초안의 30개와 범위는 다르다.
M0 기반·CSRF + M1 Profile3개 + M2 Device/Node10개 + M3 Workflow/Run/Task10개 + M4 Result1개 + M5 전환2개다.

| 단계 | 설계 영역 | 예정 작업 |
|---|---|---|
| M1 | Profile | 등록·목록·버전 조회 (3) 구현 |
| M2 | Device | 등록·목록·상세·수정·해제·Node 연결·세션·관측 (8) 구현 |
| M2 | Node | 실제 Kubernetes 관측 목록·상세 (2) 구현 |
| M3 | Workflow | 생성·목록·상세·DAG 버전 발행 (4) 구현 |
| M3 | Run | 실행 요청·목록·상세·취소 (4) 구현 |
| M3 | Task | 상세·취소 (2) 구현 |
| M4 | Result | 검증된 결과 조회 (1) 및 실제 실행 전체 경로 검증 완료 |
| M5 | Task Offload | 실행 중 다른 노드로 전환 (1) 구현·검증 중 |
| M6 | VD | 생성·목록·상세·수정·해제 (5) |
| M5 | Operation | TASK_OFFLOAD 상태 조회 (1) 구현·검증 중 |

각 슬라이스에서 Request/Response/Error, idempotency, 상태 전이, 권한, 수용시험을 구체화한 뒤 구현한다.
TaskAttempt 생성은 사용자 공개 API가 아니라 내부 재시도·오프로딩 정책이다.
M3에서 AUTO/NODE 요청과 불변 DAG·Idempotency-Key·취소를 확정했다(ADR 0004).
실제 실행/검증된 Result commit은 M4, VD는 M6, STREAM 실행은 M7이다.
