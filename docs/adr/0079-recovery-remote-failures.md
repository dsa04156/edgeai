# ADR0079: 복원 Remote 실패·취소·재시도 대기 정리

상태: 실제 PG16/API/Remote 결합15개 검증 완료.
[검증 근거](../evidence/m9-recovery-remote-failures.md). 새 CI/배포는 별도 확인한다.

ADR0075는 Remote 관측·runtime·명령을 종료하고 ADR0078은 성공 Result를 확정한다.
실패·취소 작업은 별도 CLI에서 원래 실행 정책을 유지하며 정리한다. 원본 Remote와 DB가
없어도 ADR0076의 개인 bundle과 같은 복원 DB를 대조할 수 있다. S3 접속이나 새 실행은 없다.

ADR0078과 동일한 전체 Remote 신원/관측/종료 검사 함수를 공유한다. 현재 시도의 binding,
최신 epoch, Task/Attempt 상태를 확인하고 과거 시도는 후속 시도를 덮어쓰지 않는다.
확정 Result와 이미 종료한 Task는 보존한다. 아직 DB에 반영하지 않은 Remote 성공은
`successesPending`으로 남겨 성공 결과 확정 단계에서 처리한다.

사용자 취소는 복원 DB의 cancellation reason과 상태가 근거다. 제공자가 SUCCEEDED여도
기록된 취소를 성공으로 바꾸지 않는다. 종료가 확인된 CANCELLING Task를 CANCELLED 또는
UPSTREAM 사유의 SKIPPED로 바꾸고 활성 시도만 CANCELLED로 종료한다. 과거 FAILED 시도는
그대로 남긴다. 일반 API에서 이미 취소한 대기 자식도 변경하지 않는다.
다른 종류의 후속 실행에도 이미 CANCELLING 사유가 있으면 이를 UPSTREAM_FAILED로 덮어쓰지 않는다.

예상하지 않은 제공자 CANCELLED는 RUNTIME_LOST, LEASE_EXPIRED는 RUNTIME_TIMEOUT,
PROVIDER_RESTART는 RUNTIME_LOST로 매핑한다. WORKLOAD_FAILED/INPUT_INVALID/
OUTPUT_INVALID는 원래 원인을 보존한다. 복구 시각 때문에 제공자의 확정 실패 원인을 다른
원인으로 덮어쓰지 않는다. 새 실패는 Attempt와 runtime 원인에 기록하며 다음 기준을 모두
만족할 때만 기존 task_retry에 예약한다.

- 원래 Run의 retryOn에 해당 원인이 포함된다.
- INITIAL/RETRY 횟수가 maxAttempts 미만이다. OFFLOAD는 기존 정책대로 제외한다.
- 첫 시도의 createdAt + maxElapsedSeconds보다 현재 DB 시각 + backoffSeconds가 이르다.

복구가 재시도 기한을 연장하지 않는다. 예약은 RETRY_WAIT이며 새 Attempt/runtime/명령은
생성하지 않는다. 기존 예약은 failedAttempt/namespace/원래 deadline/availableAt/횟수·원인
정합성을 확인하고 기한 전에는 시간을 바꾸지 않는다. 기한이 지나면 FAILED로 종료한다.
실패가 확정되면 BATCH 후손을 재귀적으로 SKIPPED 처리하되 이미 종료한 결과를 보존한다.
종료하지 않은 후속 producer나 충돌 Result가 있으면 전체 transaction을 거절한다.

16개 관련 테이블의 전체 행 guard, 명시적 복원 OID/marker, SHARE ROW EXCLUSIVE 잠금,
5초 lock/30초 statement 제한과 개인 intent fsync를 사용한다. 실패·예약·취소·후손·Run은
한 transaction이며 중간 SQL 오류는 전체 rollback이다. commit 응답 유실은 반영 여부를
단정하지 않고 같은 입력 재실행으로 확인한다. 기한이 지나지 않은 동일 상태는0변경이다.

STREAM과 진행 중인 offload가 포함된 Run은 별도 복구로 남긴다. 새 worker 기동·retry dispatch·
장치 journal·전체 복구 활성화는 수행하지 않는다. bundle의 서명/외부 원본 인증이나 S3 결과
보존을 이 CLI에서 검증했다고 주장하지 않는다.
