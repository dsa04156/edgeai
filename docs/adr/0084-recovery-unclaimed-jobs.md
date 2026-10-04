# ADR0084: claim 전 Job의 보존된 자식으로 종료를 증명한다

- 상태: 채택 — 실제 격리 Kubernetes/PostgreSQL56개 검증, 새 CI/배포는 후속
- 날짜: 2026-10-04

## 배경

Job UID가 DB에 기록된 뒤 Runner가 claim하기 전에 장애가 날 수 있다. ADR0080의
기본 경로는 producer Pod UID가 없으면 실행을 미해결로 남긴다. ADR0083의 STARTING
전환도 대상 종료 증거가 없으면 원래 시작 기한을 확정할 수 없다.

## 결정

실행 정리와 workflow 조정에 명시적 `--unclaimed-jobs`를 추가한다. DB에 이미 기록된
Job UID·namespace·Run/Task/Attempt/epoch와 실제 Job을 대조한다. 단일 완료·parallelism1·
backoff0·Never restart·기본 Job controller 계약을 확인한다. Job은 suspend 상태이며
quota가 실제 Pod/Job 생성 요청을 거절해야 한다. 같은 Job의 보존 Pod 전체에 대해
정확한 controller apiVersion/kind/name/UID, 신원 labels와 기존 종료 보고서를 확인한다.
하나의 Job에 여러 Pod가 있을 수 있으므로 첫 Pod만 선택하지 않는다.

Job의 active/ready 및 완료 계수·미반영 종료 UID도 보존된 자식과 대조한다. Job UID가
미기록이거나 자식이 하나도 남아 있지 않으면 미해결이다. API404나 새로 관측한 UID를
원래 producer의 종료 증거로 바꾸지 않는다. 원본 DB의 claim/node 필드는 NULL로 보존한다.
보고서 `unclaimedJobs`에 runtime/Job/전체 Pod UID와 `producerClaimRecorded=false`를
별도로 남기고 기존 guard/잠금/원자 transaction·커밋 후 관측 재검증을 적용한다.

동일 옵션으로 새 증거를 조회한 workflow 복구는 ADR0083의 STARTING 기한 처리를
사용한다. 만료된 대상은 TARGET_START_TIMEOUT, 원본은 OFFLOADED와 원래 사유를 유지한다.
새 Attempt, claim, 배정, 결과, 기한을 생성하지 않는다. 기본 옵션의 보수적 동작은 유지한다.

## 범위

실제 합성 부모/자식 컨테이너와 DB 이력 fixture로 검증한다. 보존 Pod가 없는 Job,
미관측 과거 실행·외부 writer·Remote/STREAM/group/journal·종합 서비스 재개는 별도다.
Job controller 상태만으로 전역 중지나 실행 이력의 완전성을 주장하지 않는다.
`globalQuiescenceProven=false`, `activated=false`와 복원 격리를 유지한다.

[실제 검증](../evidence/m9-recovery-unclaimed-jobs.md)을 따른다.
