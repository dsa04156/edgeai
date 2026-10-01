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

RuntimeInstance 독립 테이블, DeviceAttachment 활성 관계, VD source 수,
Task당 active Attempt 1개·VD당 active RuntimeBinding 1개 제약은 후속 확정 항목이다.
초안의 최소 1개 카디널리티가 생성 직후·Pending 상태에도 성립하는지는 M3/M6에서 검토한다.

Operation, ProducerClaim, VDSlot, RemoteAllocation, DataRoute, Checkpoint,
Outbox/Inbox, ApiIdempotency, AuditEvent는 관련 기능을 구현할 때 추가한다.
M0 Flyway는 `edgeai` schema만 초기화하며 이 초안을 확정된 테이블로 변환하지 않는다.
