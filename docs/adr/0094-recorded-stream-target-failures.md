# ADR 0094: 복원 STREAM 전환 실패 이력의 일관성 검사

상태: 채택. 2026-10-05.

`RuntimeLifecycleService.recordFailure`는 STARTING 그룹의 선택 작업이나 peer가 실패하면
`JdbcOffloadRepository.failedAttempt`와 같은 transaction에서 Operation을
FAILED/TARGET_FAILED로 바꾼다. 실패한 Attempt/runtime 사유를 남기고 연결된 peer와 후속
작업을 취소한다. 전환 중 실패에는 일반 그룹/단일 작업 재시도를 예약하지 않는다.

복원한 이력이 이 경계를 유지하는지 `--offloads`에서 검사한다. FAILED/TARGET_FAILED
Operation이 연결된 그룹은 활성 전환과 같은 불변 member/source/target/checkpoint/배치/
공유 시작 기한 검사를 거친다. 실패한 Task의 최신 target Attempt도 FAILED여야 하고 해당
runtime에 기존 서버가 기록하는 실패 사유가 있어야 한다. 다른 member는 기록된 취소
사유와 일치하는 CANCELLING/CANCELLED/SKIPPED 이력을 가져야 한다. 실패 member가 여러
개이면 모두 검사한다. 누락된 사유·OFFLOADED 등 다른 경계의 사유·모순된 retry queue·
새 Attempt·부분 결과/완료 권한은 추정해서 고치지 않는다.

이 검사를 거친 뒤 실제 producer 및 원본 broker 종료를 다시 확인하고 남은 peer 취소와
stale Run만 조정한다. 이미 실패한 Operation/Attempt/runtime과 source/member/checkpoint의
전체 이력은 변경하지 않는다. Operation이 이미 FAILED인 경우 이를 CANCELLED로 바꾸지
않는다. 재시도 정책이 WORKLOAD_FAILED를 허용하더라도 새 queue/Attempt는 만들지 않는다.
`--offloads`가 없으면 해당 그룹을 미해결로 남긴다. 기본 그룹 취소로 우회하지 않는다.

기존34테이블 guard/잠금·복원 DB OID/marker·실제 namespace/Pod 증명·TLS broker 관측을
재사용한다. transaction 중 충돌/마지막 SQL 오류는 전체 원복하며, 실제 COMMIT 응답 유실은
같은 복구 ID로 재관측한다. 미해결 그룹이 있으면 Run 완료를 확정하지 않는다.

회귀 fixture는 서버의 원자적 실패 결과를 명시적으로 기록한다. 선택 target은
WORKLOAD_FAILED, claim 전 peer는 관측 가능한 JOB_FAILED 사유를 사용한다. 실제 부모/자식
컨테이너 종료와 복원 DB를 사용하지만 이 시험에서 실제 Runner 실패 API를 호출했다고
주장하지 않는다. 기록되지 않은 실패·외부 시작 성공 권한·finalization·VD/자동 복원 종단과
종합 재가동은 별도 남은 범위다.

서버와 같은 CANCELLING Run 이력으로 전체36개를 `170123Z-963eee1a`에서 통과했다.
[실제 검증 근거](../evidence/m9-recorded-stream-target-failures.md)를 따른다.
