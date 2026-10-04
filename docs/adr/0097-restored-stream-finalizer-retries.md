# ADR 0097: 복원된 STREAM 최종 저장 작업의 원래 재시도 기한

상태: 채택, 실제 복원 기본26개 및 전환 결합44개 통과. 2026-10-05.

계산 완료 권한을 받은 STREAM 작업은 `RuntimeLifecycleService.retryTask`와
`StreamRecoveryService.retryFinalizer`에서 개별 작업으로 재시도한다. 일반 STREAM 그룹의
공유 기한을 적용하면 원래 작업별 예산과 이미 완료된 peer의 결과를 잘못 해석할 수 있다.

복원 CLI의 `--finalizers`는 원본 Kubernetes producer와 broker의 종료를 새로 확인한 뒤,
RETRY_WAIT 작업의 최신 FAILED Attempt·원래 실패 사유·namespace·재시도 횟수·backoff와
첫 Attempt 기준의 기한을 대조한다. 원래 completion grant, 가장 최신의 봉인된 checkpoint,
Task/Attempt/runtime/Pod 신원과 finalizer 상속 이력도 일치해야 한다. 상속은 원래 grant까지
연속된 RETRY/epoch/number와 FAILED predecessor를 요구한다.

기한 전 queue는 그대로 보존한다. 기한이 지난 finalizer 중 가장 이른 기한의 작업 하나를
FAILED로 확정하고, 온라인의 affected 규칙에 따라 미완료 peer와 후속 작업을 정리한다.
이미 확정된 성공 결과와 terminal 상태는 유지한다. 기존 FAILED Attempt·원래 실패 사유·
completion/grant·checkpoint·상속 이력을 다시 쓰지 않는다. 새 Attempt, generation,
grant, Result 또는 모델 계산을 만들지 않는다.

옵션이 없으면 최종 처리 재시도를 일반 그룹 재시도로 우회할 수 없다. 활성 전환은 기존
offload 경로로 보내며, 계산 중 peer·봉인되지 않은 retry·모순된 기한/상속은 미해결 또는
차단으로 남긴다. 전체 테이블 guard·잠금·DB 신원 대조·원복·COMMIT 뒤 증명 재조회는
기존 STREAM 복구 transaction에 그대로 적용한다. 복원 DB와 서비스는 격리를 유지한다.

이 경로는 기록된 최종 저장 재시도의 기한 정리다. 새 finalizer 실행, 실제 결과 재생성,
외부 시작 권한과 종합 복구 활성화는 별도 구현·수용 대상이다. 시험은 실제 복원 DB와
TLS MQTT/S3·Kubernetes 종료 증거를 사용하되 업무 상태 binding은 명시적 fixture다.
[검증 근거](../evidence/m9-restored-stream-finalizers.md)를 따른다.
