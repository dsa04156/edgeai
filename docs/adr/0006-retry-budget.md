# ADR 0006: Task를 유지하는 제한된 자동 재시도

상태: 채택, 로컬 검증 완료 후 실제 kind 검증 진행. M5의 재시도 계약이며 실행 중 offload·Remote 계약은 후속 구현이다.

## 근거와 범위

Notion API/ERD/실행 지침은 Task ID를 유지하고 재시도마다 새 Attempt를 만들도록 한다.
Run 생성에 선택적인 `retry` 정책을 받으며 공개 Attempt 생성 API는 추가하지 않는다.
AUTO/NODE 정책과 입력·Profile 버전은 재시도 중 유지한다. 실행 위치 전환은 별도 offload다.

## 계약

- `maxAttempts`: 최초 실행을 포함한 Task별 최대 1–8회.
- `backoffSeconds`: 실패 후 다음 시도까지 최소 1–300초의 고정 대기 시간.
- `maxElapsedSeconds`: 첫 Attempt 생성부터 새 Attempt를 시작할 수 있는 1–86400초의 창.
  실행 중인 Attempt의 timeout은 SERVICE 규격을 따른다. 창 만료는 실행 중 작업을 중단하지 않는다.
- `retryOn`: 중복 없는 오류 코드 목록. WORKLOAD_FAILED, TIMEOUT, STORAGE_FAILED,
  RUNNER_FAILED, DISPATCH_TIMEOUT, RUNTIME_TIMEOUT, RUNTIME_LOST, JOB_FAILED만 허용한다.
  입력·출력 오류, 결과 누락, 소유권 충돌, 취소는 자동 재시도하지 않는다.
- 정책 생략은 `{maxAttempts:1,backoffSeconds:1,maxElapsedSeconds:86400,retryOn:[]}`이다.
  이 기본 정책을 명시해도 기존 v1 Idempotency digest를 유지한다. 다른 정책은 digest에 포함한다.
  `maxAttempts > 1`이면 retryOn은 비어 있을 수 없다. 배열 순서는 의미가 없다.

## 영속 상태와 경쟁 처리

실패 시 Run 행 잠금 아래 이전 Attempt를 FAILED로 기록하고 Runtime을 STOPPED로 fence한다.
예산이 남으면 Task는 RETRY_WAIT, 하위 Task는 WAITING을 유지한다. 이전 Attempt와 Runtime은
덮어쓰지 않는다. task_retry에 실패 Attempt, namespace, 다음 시각과 마감 시각을 저장한다.
worker 재시작 후에도 이 행을 읽어 처리한다. 같은 Run 잠금으로 취소·commit·중복 worker를 직렬화한다.

새 Attempt는 대기 시간이 지났고 이전 Runtime의 실제 종료를 확인했으며 미완료 CREATE 명령이
없을 때만 생성한다. 같은 Task에서 number/epoch를 증가시키고 새 claim/Job/저장소 경로를 만든다.
늦은 이전 producer는 상태·epoch/claim 검사로 차단한다. 늦은 CREATE는 기존 reconciliation이
다시 발견해 삭제하며, 이전 producer에는 결과 확정 권한이 없다.

예산 소진 또는 창 만료는 Task FAILED와 하위 SKIPPED로 전파한다. 종료가 지연되어도 창 만료를
처리하며 cleanup 명령은 유지한다. RETRY_WAIT 취소는 예약을 지우고 물리 Runtime 종료까지
CANCELLING으로 남는다. 여러 Runtime 중 하나의 늦은 종료 확인으로 취소가 조기 완료되면 안 된다.

## 검증 게이트

실제 PostgreSQL에서 재시도 성공·횟수 소진·마감·backoff·종료 대기·취소·중복 worker·늦은 commit·
하위 Task 해제를 검사한다. OpenAPI/Swagger, UI 정책 입력·상태·Attempt 이력을 검증한다.
실제 kind에서 실패한 Job 뒤 새 Attempt/Pod 실행·동일 Task·단일 Result·cleanup을 추가 검증한다.
합성 워크로드는 실장비 또는 외부 Remote API 수용시험을 대체하지 않는다.
