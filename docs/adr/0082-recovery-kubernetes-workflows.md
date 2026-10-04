# ADR0082 — 종료 증거를 유지한 Kubernetes/VD 작업 상태 복구

상태: 채택, 2026-10-04. ADR0080–0081의 물리적 실행 종료 뒤 기록된 BATCH 작업 상태를 조정한다.

## 결정

실제 Pod 종료는 업무 성공/실패의 증거가 아니다. 동일 복구 UUID·namespace UID·quota·보존한
Pod 종료 기록을 다시 확인하고, DB runtime/명령과 VD allocation의 종료까지 완료된 경우만
작업을 조정한다. 최신 Attempt의 mode/VD/Task/Run/epoch 신원을 확인한다. 이전 Attempt는
새 시도의 상태를 바꾸지 않는다. 미배정·미관측 실행, 기록되지 않은 결과, STREAM이나 진행 중
offload가 포함된 Run은 별도 미해결 목록으로 남긴다.

기록된 CANCELLING 사유로만 취소를 확정한다. 기존 FAILED Attempt를 CANCELLED로 바꾸지
않는다. RETRY_WAIT는 원래 retryOn·횟수·첫 시도의 생성 시각 기준 deadline과 실패 시각 기준
backoff를 검증한다. 기한이 지나면 FAILED로 정리하고, 아직 유효하면 원래 예약을 유지한다.
새 Attempt/runtime/명령/재시도 예약을 생성하지 않는다. 기존 최종 실패는 BATCH 후손을
SKIPPED로 정리하되 이미 기록된 취소 사유와 terminal 결과를 보존한다. 후손에 종료 증거가
없는 producer가 있으면 전체 transaction을 거절한다. DB의 TERMINATED 문자열만으로는
물리적 종료를 대신할 수 없다.

Task들이 완료됐는데 Run만 활성 상태로 남은 경우 기존 결과에 맞춰 Run을 완료한다.
sealed Result와 Task/Attempt는 다시 쓰지 않는다. 결과 없는 RUNNING은 성공/실패를 추정하지
않고 `OUTCOME_NOT_RECORDED`로 남긴다.

ADR0079의 재시도 예산·후손 취소·Run 완료 SQL을 `recovery_workflow_failures.py`로 분리해
공유한다. Kubernetes 호출자는 실제 증명한 runtime ID 집합을 제공하고 후손의 VD allocation
종료도 확인한다. 21개 테이블 전체 행 해시와 쓰기 잠금, 복원 DB OID/marker, 사전·사후 실제
관측으로 입력 변경과 경쟁을 탐지한다. 잠금5초/SQL30초 한도, 원자적 원복, fsync된 개인 intent,
커밋 응답 유실 후 변경0 재확인을 유지한다. V1–V34와 제품 API는 변경하지 않는다.

## 범위

명령은 [복원 작업 상태 조정](../recovery-kubernetes-workflows.md), 검증 근거는
[결합 시험](../evidence/m9-recovery-kubernetes-workflows.md)을 따른다. 기존 kind 복구 게이트에
`--workflows`를 추가하며 원격 CI 성공은 해당 코드의 실행 결과로 별도 판정한다.

결과 파일/journal 회수, 진행 중 전환·STREAM 복구, 전역 writer 중지와 종합 활성화는 남는다.
이 명령의 성공은 격리 해제 또는 M9 전체 완료를 뜻하지 않는다.
