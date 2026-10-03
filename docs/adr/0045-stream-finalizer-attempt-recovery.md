# ADR 0045: 완료 허가 뒤 새 Attempt의 최종 처리 복구

2026-10-03. ADR0040의 같은 Attempt 복구와 ADR0044의 계산 중 그룹 재시도를 연결한다.
공동 완료 허가 뒤에는 계산을 다시 시작하지 않는다. 실패한 Task만 새 Attempt에서 기존 최종
상태를 읽고 파일 생성·검증된 Result 확정을 수행한다. 이미 성공한 peer와 독립 분기는 보존한다.

## 영속 허가와 재시도

기존 stream_task_completion은 checkpoint/원본 Attempt의 불변 이력이다. V26의
stream_finalization_recovery가 새 Attempt·직전 실패 Attempt·최초 허가 Attempt를 연결한다.
연속 재시도도 동일한 최초 허가를 가리킨다. 원래 checkpoint bytes·S3 version·producer identity·
grant time은 변경하지 않으며 새 checkpoint를 만들지 않는다. V1–V25는 변경하지 않는다.

Run 잠금 아래 기존 실패 코드 정책·Task별 예산·최초 Attempt 기준 기한·backoff를 적용한다.
실패한 Task에 닿는 경로를 fence하고 물리 종료/DELETE는 기존 outbox로 처리한다. 새 Attempt는
그 Task의 모든 기존 Runtime STOPPED/TERMINATED, 미완료 CREATE 없음, 해당 경로 CLOSED 후에만
생성한다. 같은 트랜잭션에서 인계 이력과 Runtime 계획을 저장한다. DB도 동일 Task·연속 epoch/
number·RETRY·현재 활성 Run·원본 허가·미확정 Result·종료/회수 조건을 검사한다.
인계 이력은 수정/삭제/TRUNCATE할 수 없다. 허가된 Task의 checkpoint는 후속 Attempt에서도 전진할 수 없다.

현재 Attempt에 직접 또는 명시적 인계 허가가 있으면 Run worker는 MQTT 세대를 새로 만들지 않는다.
Result 준비와 S3 검증 후 commit은 모두 현재 producer 신원·Run 상태·인계된 허가를 확인한다.
취소·기한 만료·예산 소진은 기존 실패/하위 정리 경로로 처리한다. 성공 Result는 원본 체크포인트의
Attempt가 아니라 실제 결과를 확정한 새 Attempt/epoch/Pod에 귀속된다.

## HTTP와 Runner

기존 같은 Attempt FINALIZE 응답 형식은 유지한다. 새 Attempt에는 선택적 checkpointActor
`{attemptId, epoch}`를 추가해 원본 checkpoint 실행 주체를 명시한다. 응답 최상위 identity와
모든 요청 인증은 현재 Attempt/epoch/Pod이다. 입력/출력 논리 경로와 generationIds는 원본 checkpoint
이력이며 새 MQTT 권한이 아니다. finalized API는 명시적 인계 이력과 원래 허가된 정확한 ID만 허용한다.

Runner는 원본 epoch가 현재보다 작은 별도 Attempt인지 확인하고 이 identity를 finalized 조회와
고정 S3 다운로드 검증에만 전달한다. 일반 latest/upload/commit/handover의 현재 actor 검증은 유지한다.
Task·Run·실행 digest·포트·메모리 한도·종료 커서·크기·SHA·version을 확인하고 S3 I/O 뒤 동일 허가를
재조회한 다음 최종 파일 작업을 시작한다. MQTT 연결·계산 모델·새 journal은 만들지 않는다.

## 검증 범위와 남은 작업

PG 동시 worker·반복 재시도·peer Result 보존·취소/예산·위조 인계/물리 종료/CREATE/권한 회수 장벽,
실제 Runner 종료/빈 폴더/정지한 broker, 실제 Spring/PG/S3/TLS MQTT에서 새 Attempt의 결과14를 검사한다.
상세 실행 결과와 fixture 경계는 [검증 기록](../evidence/m7-finalizer-attempt-recovery.md)을 따른다.

공개 STREAM retry는 Device 자동 재연결과 전체 장애 수용까지 계속 거절한다. Kubernetes에서
이 새 복구를 수행하는 시험, 운영 STREAM 활성화, M5 잔여·M7 전체·M8–M10은 남는다.
