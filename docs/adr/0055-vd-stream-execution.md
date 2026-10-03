# ADR0055 — VD 스트리밍 작업의 권한·동시 실행·복구

2026-10-04. 구현·검증 중이며 공개 VD 스트리밍이나 M7 완료 판정이 아니다.

VD의 자식 Runner도 Task/Attempt별 스트림 경로·체크포인트·완료 허가를 사용한다.
같은 supervisor Pod를 공유해도 Attempt/epoch/배정 slot은 독립적이다. 대기·재시도 중에도
Task의 최초 VD를 유지하며 모든 이전 자식 프로세스와 broker 권한이 종료된 뒤 새 그룹을 배정한다.

작업의 스트림 인증은 **그 작업의 VD → Device → Run → Route** 순서로 잠근다.
Run 기본 VD를 잠그는 기존 경로는 제거한다. Device와 관리 경로는 VD를 잠그지 않으며
Run/Route 상태로 직렬화한다. 여러 VD가 있는 그룹에서 한 VD의 잠금을 잡은 채 다른 VD를
잠그지 않는다. 공동 완료 판단은 Run 잠금 안에서 각 peer의 현재 Attempt·배정·supervisor
세대/lease를 읽어 확인한다. 이 확인은 peer 자격 증명을 발급하거나 결과를 확정하지 않는다.
각 Runner의 배정·체크포인트·최종 결과는 기존처럼 자신의 VD 잠금과 현재 권한을 다시 검사한다.

생성 시 VD들을 ID 순서로 잠근 뒤 Device 세션을 고정한다. 동시에 살아 있어야 하는 하나의
STREAM component가 같은 VD에 요구하는 작업 수는 그 VD의 maxConcurrentTasks 이하여야 한다.
다른 Run과 경쟁하는 실제 빈 slot이나 Kubernetes 자원 확보를 보장하는 계약은 아니며 기존
dispatch 기한·실패·취소·재시도와 확인된 자식 종료 장벽을 유지한다.

V31은 STREAM Run 기본 정책에 VD를 허용한다. 실제 DB 시험에서 V26의 최종 처리 복구 제약이
VD를 거절하는 것을 확인해 V32에서 해당 모드만 허용한다. 원래 완료 허가·체크포인트·이전 실행
종료·broker 회수 조건은 유지한다. 적용된 V1–V31은 변경하지 않는다.
VD 측정은 공유 Pod 값이므로 작업별 자동 offload는 계속 거절한다. Remote 스트리밍 계약과
실장비 수용은 별도 남은 범위이며 이번 VD 연결로 대체하지 않는다.

필수 검증은 실제 PostgreSQL의 서로 다른 VD·같은 Pod의 서로 다른 Attempt·동시 poll/경로/
체크포인트/완료·그룹 실패/재시도·취소·slot 부족 거절, 실제 broker/S3/자식 Runner와 Kubernetes
종단·API/VD 교체·고정 상태 복원, Swagger/UI·CI·배포다. 구성 요소 성공을 전체 수용으로 해석하지 않는다.
