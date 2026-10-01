# API 범위

API 정의서의 30개 작업은 설계 초안이다. 공통 prefix는 ADR-0001에서 `/api/v1`으로 정합화했다.
현재 구현된 REST 계약은 `contracts/openapi/platform-api.yaml`의 M0 경로뿐이다.

| 단계 | 설계 영역 | 예정 작업 |
|---|---|---|
| M1 | Profile | 등록·목록·버전 조회 (3) |
| M2 | Device | 등록·목록·상세·수정·해제·Node 연결·세션·관측 (8) |
| M2 | Node | 목록·상세 (2) |
| M3 | Workflow | 생성·목록·상세·DAG 버전 발행 (4) |
| M3 | Run | 실행·목록·상세·취소 (4) |
| M3–M4 | Task / Result | 상세·취소·결과 조회 (3) |
| M6 | VD | 생성·목록·상세·수정·해제 (5) |
| 해당 비동기 기능 구현 시 | Operation | 상태 조회 (1) |

각 슬라이스에서 Request/Response/Error, idempotency, 상태 전이, 권한, 수용시험을 구체화한 뒤 구현한다.
TaskAttempt 생성은 사용자 공개 API가 아니라 내부 재시도·오프로딩 정책이다.
AUTO/NODE/VD 모드, 발행 버전 불변성, 검증된 Result commit 계약은 이후 단계에서 확정한다.
