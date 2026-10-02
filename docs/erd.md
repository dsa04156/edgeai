# ERD 작업 기준

[원문 ERD](https://app.notion.com/p/3ebbafd382d681cf8568e6d870fe97f3)는 관계·카디널리티 초안이며 DDL 확정본이 아니다.

- ProfileVersion → Device / VirtualDevice / TaskDefinition
- Device ↔ ExecutionNode는 DeviceAttachment로 연결한다.
- Device → DeviceSession → DeviceObservation
- VirtualDevice ↔ Device는 VDSourceBinding으로 연결한다.
- Workflow → WorkflowVersion → TaskDefinition / TaskDependency
- WorkflowVersion → WorkflowRun → Task → TaskAttempt
- TaskAttempt → RuntimeInstance, TaskResult → Artifact
- VirtualDevice → VDRuntimeBinding → VDRuntime (지속 Pod 실행 세대)

RuntimeInstance는 V5의 개별 TaskAttempt 실행이다. VD의 지속 VDRuntime은 V14에서 별도 구현했다.
VD source는 V13의 이름있는0–16개 slot이고, VD당 열린 RuntimeBinding 하나는 V14 partial UNIQUE다.
Task당 활성 Attempt 하나는 V4 partial UNIQUE로 구현했다.
초안의 최소 1개 카디널리티가 생성 직후·Pending 상태에도 성립하는지는 M3/M6에서 검토한다.

Operation은 V7 task_offload, producer claim/명령 outbox는 V5 Runtime 경로로 구체화했다.
RemoteAllocation은 V10에서 구현했다. VDSlot, DataRoute, Checkpoint, 범용 Inbox/ApiIdempotency/AuditEvent는 후속 기능에서 추가한다.
M0 Flyway는 `edgeai` schema만 초기화하며 이 초안을 확정된 테이블로 변환하지 않는다.


## 구현된 M1 테이블

`V2__create_profile_versions.sql`의 `edgeai.profile_version`:
UUID PK, kind, profile_key, version, spec(jsonb), digest, created_at(timestamptz).
(kind, profile_key, version)는 UNIQUE이며 kind/키/버전/spec/digest CHECK를 둔다.
키·버전은 C collation으로 목록 순서와 인덱스를 일치시킨다.
UPDATE/DELETE 행 trigger 및 TRUNCATE 문장 trigger가 발행 후 변경을 거절한다.
Device FK는 V3에서 추가했다. TaskDefinition SERVICE 종류 FK는 V4, VD/SERVICE 종류 FK는 V13이다.

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
V10 remote_allocation은 runtime_instance와1:1로 제공자/설정/sourceMode, 고정 요청과 revision 관측을 보존한다.
Runtime과 Result는 Kubernetes Pod 또는 RemoteAllocation 중 하나의 producer만 참조한다.
V11은 PostgreSQL BEFORE trigger 시점의 생성 컬럼 비교를 제외하고 결과의 원본 컬럼 봉인을 유지한다.
V12는 workflow_run/task_attempt/task_offload에 Remote 제공자 binding을 추가하고 실행 대상 변경을 차단한다.
새 allocation은 Attempt의 고정 제공자와 일치해야 하며 수동 offload는 Node/Remote 중 정확히 하나를 갖는다.
V1–V14는 로컬 적용된 migration이며 수정하지 않는다.

## 구현된 M6 등록·실행 수명 테이블

V13 `virtual_device`는 불변 VD/SERVICE Profile과 REGISTERED/RELEASED·revision·배치 의도를 가진다.
`vd_source_binding`은 slot별 Device·정확한DEVICE Profile/sourceMode와 열린/닫힌 revision을 보존한다.
현재 source의 유일성, 원본 호환성·필수 slot·논리 해제 제약과 장치 해제 보호를 적용한다.

V14 `vd_runtime`은 불변 VD/configuration/generation·Pod 이름/UID·claim nonce·session/Node 신원과
희망/관측 상태·startup/drain/lease 시간을 저장한다. `vd_runtime_binding`은 VD와 실행 세대의 별도
관계 이력이다. VD당 종료 미확인 실행 하나와 열린 runtime binding 하나를 제한하고 지연 제약으로
종료 상태와 binding 폐쇄의 일치를 확인한다. source binding이 닫힌 뒤에도 이전 runtime이 쓰는
Device는 실행 종료 확인 전까지 해제할 수 없다.

`vd_operation`은 PROVISION/REPLACE/DRAIN 요청·동일 키 digest·source/target Runtime·처리 결과다.
`vd_runtime_command`는 실제 CREATE/DELETE의 lease/owner/시도/완료를 따로 저장한다. 실행 요청,
source/runtime 이력과 Operation은 VD 행 잠금 아래 원자적으로 변경한다.
현재 TaskAttempt의 RuntimeInstance를 이 지속 VD의 Task slot에 연결하는 관계는 후속 구현이다.
