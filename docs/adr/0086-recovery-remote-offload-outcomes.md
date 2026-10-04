# ADR0086: Remote 전환 대상의 실제 실패와 성공 확정 권한을 구분한다

- 상태: 채택 — 실제 혼합69개·Result15개·실패15개 검증, 새 CI/배포는 후속
- 날짜: 2026-10-04

## 결정

ADR0085의 새 TLS 관측에서 STARTING 대상이 실제 FAILED이면 원래 실행 코드의
`RuntimeLifecycleService.recordFailure` 규칙을 적용한다. Task/Run이 RUNNING이고 대상이
최신 DISPATCHING/RUNNING Attempt이며 취소·Result·예약·기록된 실패와 모순되지 않아야 한다.
source OFFLOADED와 전체 실행 종료·고정 target·원래 namespace 검증은 유지한다.

제공자의 LEASE_EXPIRED는 RUNTIME_TIMEOUT, PROVIDER_RESTART는 RUNTIME_LOST로
매핑하고 다른 허용된 실패 사유는 보존한다. 실제 실패는 시작 기한 만료보다 우선한다.
Operation TARGET_FAILED, 대상 Attempt/runtime 실패, Task와 재시도 예약·후손/Run 조정을
기존22테이블 guard/잠금 아래 같은 transaction에서 처리한다. 재시도는 OFFLOAD Attempt를
횟수에서 제외하고 첫 Attempt 생성 시각의 원래 최대 경과 시간·Run 정책·backoff를 사용한다.
소스 이력·고정 배치·전환 기한·최대 재시도 예산은 바꾸지 않는다.

실제 SUCCEEDED 관측과 S3 파일만으로 원래 start_deadline 안의 시작 허가를 재구성할 수는
없다. STARTING 대상은 미해결로 유지한다. 별도 Remote Result 복구도 같은 Run에
DRAINING/STARTING/CANCELLING 전환이 있으면 거절한다. 검사 뒤 새 전환이 들어오는
경쟁도 감지하도록 task_offload/task_offload_member를 Result 복구의 전체 행 guard와
잠금에 포함한다. 완료 기록이나 claim을 만들어 우회하지 않는다.

## 근거와 범위

수정 전 실제 Remote 성공 파일을 TLS MinIO에 고정 version으로 등록한 뒤, STARTING
대상의 Result 준비가 허용되는 문제를 재현했다. 수정 뒤 같은 재현을 포함한69개,
전환 삽입 경쟁을 추가한 Result15개와 기존 실패15개가 통과했다.
[검증 기록](../evidence/m9-recovery-remote-offload-outcomes.md).

공개 offload 요청·과거 시작 허가 journal·실제 외부 제공자·STREAM/group·전역 writer
종료와 종합 활성화는 별도다. 복원 DB 격리와 기존 producer fence를 유지하며 새 실행을
시작하지 않는다. API JAR과 V1–V34는 변경하지 않는다.
