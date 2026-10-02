# ERD 작업 기준

[원문 ERD](https://app.notion.com/p/3ebbafd382d681cf8568e6d870fe97f3)는 관계·카디널리티 초안이며 DDL 확정본이 아니다.

- ProfileVersion → Device / VirtualDevice / TaskDefinition
- Device ↔ ExecutionNode는 DeviceAttachment로 연결한다.
- Device → DeviceSession → DeviceObservation
- VirtualDevice ↔ Device는 VDSourceBinding으로 연결한다.
- Workflow → WorkflowVersion → TaskDefinition / TaskDependency
- WorkflowVersion → WorkflowRun → Task → TaskAttempt
- TaskAttempt → RuntimeInstance, TaskResult → Artifact
- VirtualDevice → VDRuntimeBinding → RuntimeInstance

RuntimeInstance는 V5 독립 테이블로 구현했다. VD source 수와
VD당 active RuntimeBinding 1개 제약은 후속 확정 항목이다.
Task당 활성 Attempt 하나는 V4 partial UNIQUE로 구현했다.
초안의 최소 1개 카디널리티가 생성 직후·Pending 상태에도 성립하는지는 M3/M6에서 검토한다.

Operation은 V7 task_offload, producer claim/명령 outbox는 V5 Runtime 경로로 구체화했다.
VDSlot, RemoteAllocation, DataRoute, Checkpoint, 범용 Inbox/ApiIdempotency/AuditEvent는 후속 기능에서 추가한다.
M0 Flyway는 `edgeai` schema만 초기화하며 이 초안을 확정된 테이블로 변환하지 않는다.


## 구현된 M1 테이블

`V2__create_profile_versions.sql`의 `edgeai.profile_version`:
UUID PK, kind, profile_key, version, spec(jsonb), digest, created_at(timestamptz).
(kind, profile_key, version)는 UNIQUE이며 kind/키/버전/spec/digest CHECK를 둔다.
키·버전은 C collation으로 목록 순서와 인덱스를 일치시킨다.
UPDATE/DELETE 행 trigger 및 TRUNCATE 문장 trigger가 발행 후 변경을 거절한다.
Device FK는 V3에서 추가했다. TaskDefinition SERVICE 종류 FK는 V4에서 추가했으며 VD FK는 후속 단계다.

## 구현된 M2 테이블

`V3__device_node_observation.sql`: execution_node, device, device_attachment,
device_session, device_observation. Node UID와 Device UUID는 서로 다른 식별자다.
Device는 (profile_version_id, profile_kind=DEVICE) FK로 규격 종류를 보장한다.
Device당 활성 attachment/session을 partial UNIQUE로 제한하고,
Observation의 (session_id, device_id) FK로 다른 장치의 세션 사용을 막는다.
장치 해제는 상태 변경이며 기존 관측·세션·연결 이력은 삭제하지 않는다.

## 구현된 M3 테이블

`V4__workflow_run_task.sql`: workflow, workflow_version, task_definition,
task_dependency, workflow_run, task, task_attempt.
발행 트랜잭션에서 DAG와 task/edge 행을 함께 저장하고 published로 봉인한다.
봉인된 버전은 UPDATE/DELETE/TRUNCATE, 하위 정의/의존성은 이후 INSERT까지 trigger로 차단한다.
Run은 (workflow_version_id, version_published=true) FK로 발행된 버전만 참조한다.
Task는 Run과 Definition의 동일 버전을 composite FK로 보장한다.
TaskDefinition은 SERVICE ProfileVersion만 참조하고 입력 포트당 producer 하나를 PK로 보장한다.

Run의 Idempotency-Key UUID는 UNIQUE다. Run·Task·root Attempt 생성은 원자적이며
대기 중인 하위 Task는 Attempt 없이 존재한다. task별 활성 Attempt는 partial UNIQUE,
number/epoch는 각각 task와 UNIQUE다. 취소는 Run 행 잠금으로 직렬화하고 이력을 보존한다.
TaskResult/Artifact/RuntimeInstance는 M4의 V5에서 추가했다.

## 구현된 M4–M5 테이블

V5 runtime_instance·runtime_command는 Attempt별 실행/producer 신원·epoch와 CREATE/DELETE lease를 저장한다.
task_result·result_artifact는 검증된 고정 object version을 원자적으로 봉인한다.
V6 workflow_run 재시도 정책과 task_retry는 동일 Task의 새 Attempt 예약·마감을 보존한다.
V7 task_attempt의 mode/node_id/cause는 각 Attempt의 실제 대상과 INITIAL/RETRY/OFFLOAD를 구분한다.
task_offload는 Task/Run/이전·새 Attempt FK, target Node, idempotency UNIQUE, drain/start 마감,
상태/실패 코드를 저장한다. Task당 진행 중 Operation 하나를 partial UNIQUE로 제한한다.
V8 runtime_telemetry는 (attempt_id,sequence) PK·현재 producer의 관측/수신 시각·측정 구간·
CPU/memory/선택적 latency를 저장한다. Attempt별 최신64개 보관은 서비스 트랜잭션이 관리한다.
V9는 workflow_run.offload_policy, task_attempt.excluded_node_names, task_offload.trigger/decision/제외 목록을 추가한다.
자동 target Node는 null이며 scheduler가 선택한다. 수동/자동 결정 일관성과 제외 배열 제약을 적용한다.
V1–V9는 로컬 적용된 migration이며 수정하지 않는다.
