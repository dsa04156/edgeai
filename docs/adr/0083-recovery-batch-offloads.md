# ADR0083 — 격리된 복원 DB의 BATCH 전환 상태 조정

상태: 채택, 2026-10-04. ADR0082 명령의 명시적 `--offloads` 옵션으로 처리한다.

## 결정

OffloadService의 우선순위인 기록된 취소→기록된 실패→원래 phase 기한을 따른다.
Job/VD의 실제 종료와 DB runtime/명령/allocation 정리가 선행돼야 한다. source의 기존 claim과
OFFLOADED 이력, 원래 Task/Run, target의 mode/node/exclusions, 최신 epoch를 대조한다.
Result나 재시도 예약과 모순되는 전환은 거절한다. source/target 중 하나라도 종료를 증명하지
못하면 DB의 TERMINATED 문자열만으로 채택하지 않는다. Remote 제공자 증거·STREAM/group
복구가 필요한 작업과 더 새로운 Attempt가 있는 경우는 미해결에 남긴다. 같은 Run의 다른
전환이 미해결이면 해당 Run의 전환을 부분 확정하지 않는다.

기록된 Task 취소 사유를 보존하며 종료가 확인된 Operation을 CANCELLED로 만든다.
DRAINING 기한이 지나면 Operation은 SOURCE_DRAIN_TIMEOUT, Task는 FAILED로 조정한다.
source Attempt의 OFFLOADED와 runtime의 원래 failure_reason은 그대로 둔다. 유효한 DRAINING은
원래 deadline을 유지한다. 새로운 target Attempt, runtime, 명령이나 start deadline은 만들지
않으며 복원 시스템을 활성화하지 않는다.

STARTING을 처리하려면 target의 종료와 고정 placement까지 모두 증명해야 한다. 현재
Kubernetes retirement는 claim된 Pod 신원을 요구하므로 아직 claim되지 않은 target은
미해결에 남는다. 실제 target 시작 기한 경로의 종합 수용은 추가 증거가 필요하다.

Operation/Task, BATCH 후손, Run 변경은 기존21테이블 guard·잠금 아래 한 transaction으로
반영한다. 이미 기록된 취소 사유와 terminal 결과를 보존하며 미관측 후손은 전체 쓰기를
거절한다. 개인 intent/fsync, 입력 경쟁/마지막 쓰기 오류 원복, 커밋 응답 유실 재확인과 변경0
재실행을 유지한다. 기존 workflow 명령도 SOURCE_DRAIN_TIMEOUT 뒤의 FAILED Task와
OFFLOADED source 이력을 모순으로 취급하지 않는다.

## 검증과 남은 범위

[실행법](../operations/recovery/recovery-kubernetes-workflows.md), [검증](../evidence/m9-recovery-batch-offloads.md).
실제 부모/자식 종료 증거와 복원 DB에서 명시적인 전환/claim/과거 시각 fixture를 사용한다.
공개 offload 요청이나 새 target 기동을 이 시험에서 수행했다고 주장하지 않는다.

claim되지 않은 target의 증거 회수, Remote/STREAM/group, checkpoint/journal와 결과 회수,
전역 writer 통제 및 종합 재가동은 남는다. 이 옵션의 성공은 전체 전환 복구 완료가 아니다.
