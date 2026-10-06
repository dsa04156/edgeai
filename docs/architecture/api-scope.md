# API 범위

API 정의서의 30개 작업은 설계 초안이다. 공통 prefix는 ADR-0001에서 `/api/v1`으로 정합화했다.
현재 관리 REST 계약은 `contracts/openapi/platform-api.yaml`의43개 operation이다. 원문 초안의30개와 범위는 다르다.
M0 기반·CSRF + M1 Profile3개 + M2 Device/Node10개 + M3 Workflow/Run/Task10개 + M4 Result1개 + M5 전환2개 + M6 VD9개 + M7 장치 토큰/경로 조회2개 + M9 감사 조회2개다.

| 단계 | 설계 영역 | 예정 작업 |
|---|---|---|
| M1 | Profile | 등록·목록·버전 조회 (3) 구현 |
| M2 | Device | 등록·목록·상세·수정·해제·Node 연결·세션·관측 (8) 구현 |
| M2 | Node | 실제 Kubernetes 관측 목록·상세 (2) 구현 |
| M3 | Workflow | 생성·목록·상세·DAG 버전 발행 (4) 구현 |
| M3 | Run | 실행 요청·목록·상세·취소 (4) 구현 |
| M3 | Task | 상세·취소 (2) 구현 |
| M4 | Result | 검증된 결과 조회 (1) 및 실제 실행 전체 경로 검증 완료 |
| M5 | Task Offload | 실행 중 NODE/REMOTE 전환 (1). NODE/REMOTE 모두 참조 제공자의 실제 kind·CI·배포 검증 |
| M6 | VD | 생성·목록·상세·수정·해제 (5), 시작·교체·종료·실행 상태 (4) |
| M5/M6 | Operation | TASK_OFFLOAD 및 VD_PROVISION/VD_REPLACE/VD_DRAIN 합집합 조회 (1) |
| M7 | Stream | 현재 Device Session 토큰 발급(1), Run의 고정 경로/그룹/세대 조회(1), 기존 Run 생성의 streamInputs |
| M9 | 감사 | 관리 요청 접수/HTTP 결과 목록·감사 UUID 조회(2), 현재 Basic 주체·미확정 결과 구분 |

각 슬라이스에서 Request/Response/Error, idempotency, 상태 전이, 권한, 수용시험을 구체화한 뒤 구현한다.
TaskAttempt 생성은 사용자 공개 API가 아니라 내부 재시도·오프로딩 정책이다.
M3에서 AUTO/NODE 요청과 불변 DAG·Idempotency-Key·취소를 확정했다(ADR 0004).
M5/ADR0012에서 기존 Run/Offload에 REMOTE 제공자 선택을 추가했다. 당시 Operation 수는30개였다. M6 등록·실행 관리 추가 후39개다.
Remote 자동 측정 전환은 미지원이며 실제 외부 API 수용시험·상태형 복원은 남아 있다. 참조 제공자의 실제 kind 양방향 전환은 검증했다.
실제 실행/검증된 Result commit은 M4, VD는 M6, STREAM 실행은 M7이다.
ADR0020은 기존 Run 생성에 `execution={mode:VD,vdId}`를 추가한다. Ready·동일 SERVICE 검증,
VD 내부 Task 배정/종료와 결과의 실제 vdRuntimeId를 연결하며 공개 operation 수는39개를 유지한다.

ADR0041은 공개 Run의 STREAM 선택 활성화와 그룹 배정을 연결했다. 후속 ADR0047/0051/0052는
그룹·최종 처리 retry와 수동/자동 NODE 전환, ADR0053/0054는 작업별 최초 배치를 추가한다.
ADR0055는 VD STREAM 실행·그룹/최종 처리 복구, ADR0056은 VD 포함 그룹의 수동 NODE 전환과
동료 VD 유지를 연결한다. Remote STREAM과 VD 자동 전환은 지원하지 않는다.
기본 STREAM 비활성501은 유지한다. 배포별 활성화·검증 판정은 해당 evidence를 따른다.
별도 `stream-api.yaml`에는 Device/Runner 배정·heartbeat·checkpoint·공동 완료·복구와
Device의 Run 경로 조회를 포함한 내부13개 operation이 있다. M7 변경 당시 공개 operation은41개였고,
ADR0066의 [관리 감사 조회](../operations/management-audit.md)2개를 추가해 현재43개다.
[VD 그룹 전환 검증](../evidence/m7-vd-stream-group-offload.md)에서 로컬·실제 Kubernetes와 이미지 CI·배포 범위를 구분한다.
