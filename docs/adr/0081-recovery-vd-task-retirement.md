# ADR0081 — 종료한 VD supervisor에 연결된 작업 할당 정리

상태: 채택, 2026-10-04. ADR0080의 동일 복구 명령을 VD 내부 Task runtime까지 확장한다.

## 결정

VD supervisor의 물리적 종료와 Task의 업무 결과를 구분한다. 실제 Kubernetes에서 종료가
확인된 VD runtime에 연결된 allocation만 처리한다. VD/generation/session/Pod/namespace와
claim된 Task의 Pod/node 신원이 일치해야 하며, 과거 닫힌 allocation의 runtime이 다시 실행
상태인 모순은 거절한다. 미배정 작업과 종료를 확인하지 못한 supervisor는 `unresolved`에 남긴다.

`vd_task_allocation`을 포함한14테이블 전체 행 해시와 잠금으로 동시 배정·변경을 탐지한다.
하나의 transaction에서 Task runtime을 STOPPED/TERMINATED로 만들고, VD supervisor·binding을
종료한 뒤 열린 allocation을 `POD_GONE`으로 닫는다. 이미 닫힌 allocation은 사유·시각·완료
sequence·exit code를 그대로 보존한다. 새 exit code나 완료 sequence를 추정하지 않는다.

Task/Attempt/Run, Result/artifact, 재시도 예산/예약은 이 단계에서 바꾸지 않는다. 새로운 Job,
명령, Attempt도 만들지 않는다. 성공 결과가 있어도 allocation이 열린 경우 실제 supervisor
종료 증거가 있어야 닫을 수 있다. 커밋 응답 유실·원자적 원복·변경0 재실행과 사후 실제 관측은
ADR0080의 계약을 따른다. 기존 데이터베이스 제약·V1–V34는 유지한다.

## 검증과 남은 범위

실제 부모/자식 프로세스와 복원 DB를 사용하는 기존 시험에 `--vd-tasks`를 추가했다.
실행 중·배정만 됨·과거 PROCESS_EXIT·과거 NOT_STARTED·성공 후 열린 할당·미배정의6가지 이력을 구성한다.
claim/allocation/성공 Result는 명시적 SQL fixture이며 SDK claim·S3 파일 검증은 아니다.
실제 allocation만 추가되는 경쟁, 마지막 closure UPDATE 실패, allocation 테이블 잠금,
기존 닫힌 이력·확정 결과 보존과 미배정 작업 미해결 판정을 검증한다.
최종23개 결합 시험과 기존16개 회귀가 통과했다. 실제 부모/자식3쌍 중 VD Pod의 두 컨테이너는
모두 종료를 확인하며 성공 결과가 있는 열린 allocation도 물리적 종료 이후에만 닫는다.

kind CI의 동일 게이트에 확장 시험을 포함한다. 전체 테이블 보존 검사는 같은 전체 행을
하나의 SQL snapshot에서 해시해 Compose 호출 비용을 줄인다. 누락 테이블을 허용하지 않는다.
[검증 기록](../evidence/m9-recovery-vd-tasks.md), [명령](../operations/recovery/recovery-kubernetes-retirement.md).

작업 결과/취소/재시도 및 진행 중 offload 조정, STREAM/journal, 전역 writer 통제와 복원
시스템 활성화는 종합 복구에서 연결해야 한다. 이 결정만으로 M9 완료를 판정하지 않는다.
