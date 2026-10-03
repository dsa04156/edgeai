# ADR0053 — 작업별 최초 실행 위치

2026-10-04. AUTO/NODE 구현·로컬/실제 Kubernetes 검증. 게시 이미지·CI·배포 및 M7 전체 수용과 구분한다.

Run의 `execution`은 기본값이며 선택 필드 `taskExecutions`는 발행된 DAG의 task key별
최초 배치를 덮어쓴다. 예: `{"decode":{"mode":"NODE","nodeId":"<Node UID>"},"report":{"mode":"AUTO"}}`.
이번 수직 구현은 AUTO/NODE Run의 AUTO/NODE 덮어쓰기를 연결한다. 여러 VD와 Remote를
섞는 배치 및 STREAM VD/REMOTE 수용은 전체 목표에 남긴다. 현재 단일 VD→Run 잠금과
VD/SERVICE 호환성·slot/producer 계약을 단순 JSON 옵션으로 우회하지 않는다.

WorkflowVersion은 변경하지 않는다. 같은 DAG를 다른 배치로 여러 번 실행할 수 있다.
키는 실제 TaskDefinition에 있어야 하며 개수는128개 이하, 값은 정확한 AUTO 또는 NODE 객체다.
NODE는 관측된 Node UID를 참조한다. Ready/자원/affinity에 의한 실제 배치는 기존 scheduler
계약을 따른다. UUID와 객체 순서를 정규화하고 빈 map과 생략은 같은 요청으로 취급한다.
같은 Idempotency-Key의 배치 변경은409다. 대상/키 오류는 Run·Task·명령을 함께 롤백한다.

V29는 Run의 불변 요청 map과 Task의 불변 `initial_mode`/`initial_node_id`를 저장한다.
기존 Task는 Run 기본값으로 채우며 V1–V28은 변경하지 않는다. Task 생성 시 DB가 기본값과
덮어쓰기를 해석하고 NODE FK를 보존한다. Task identity/최초 배치 변경과 다른 최초 Attempt
배치는 DB에서도 거절한다. 대기 중인 Task에도 최초 위치를 조회할 수 있다.

최초 root, BATCH 후속 해제, STREAM 그룹 해제는 모두 Task 최초 위치를 사용한다.
retry/계산 중 그룹 복구/최종 처리 복구는 직전 Attempt의 실제 위치를 계승한다. 명시적 또는
자동 offload는 새 Attempt의 위치를 바꿀 수 있으며 최초 요청을 다시 적용하지 않는다.
Task 조회의 `initialMode`/`initialNodeId`와 Attempt의 현재 배치를 구분한다.

수용: 공개 API의 생성/재전송/정규화/오류·동시 요청, 실제 PG의 불변/FK/잘못된 INITIAL 거절,
BATCH 후속/STREAM 그룹/재시도 배치, Swagger/화면, 실제 Kubernetes의 다른 초기 노드·복구·
고정 결과·CI/배포를 검증한다. 구현 파일이나 fixture 성공만으로 전체 수용을 판정하지 않는다.

현재 결과와 남은 범위는 [배치 검증 기록](../evidence/m7-task-initial-placement.md)을 따른다.
