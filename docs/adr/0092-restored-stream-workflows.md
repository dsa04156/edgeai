# ADR 0092: 복원 STREAM 그룹의 기록된 취소와 재시도 기한 조정

상태: 채택. 2026-10-05.

STREAM 재시도는 연결된 작업 전체가 같은 기한과 backoff를 사용한다. 같은 Device의
fanout도 하나의 그룹이다. BATCH 복구의 작업별 재시도 계산을 그대로 적용하면 그룹의
가장 이른 시작 시각과 peer의 STREAM_GROUP_RESTART 사유를 잃을 수 있다.

기존 BATCH 복구의 STREAM 판별이 task_dependency만 보던 오류를 실제 복원 DB에서
재현했다. Device 입력만 있는 Run에는 STREAM task dependency가 없어 경로가 열린 상태에서
작업 2개를 취소했다. Kubernetes/Remote 업무 복구와 BATCH offload 판별에 실제 data_route
존재 여부를 추가하고 해당 테이블도 transaction guard·잠금에 포함한다. 이 경로는 전용
STREAM 복구로 넘기며 기존 BATCH 처리는 유지한다.

복원 DB에서 공개 Run의 고정된 경로를 읽어 StreamRunPlan과 같은 연결 그룹을 계산한다.
원래 namespace·broker digest·복구 UUID·DB OID/marker/restore receipt를 대조한다.
현재 Kubernetes 차단·보존한 컨테이너 종료와 원본 TLS broker 권한 회수를 새로 관측한다.
그룹의 모든 runtime 이력이 증명 범위 안에서 STOPPED/TERMINATED이고 명령과 VD 할당이
종료됐으며 이전 generation이 CLOSED일 때만 기록된 업무 상태를 조정한다.

RETRY_WAIT 그룹은 각 최신 FAILED Attempt와 원래 retry 정책·남은 횟수를 대조한다.
공유 cutoff는 그룹 전체 첫 Attempt 중 가장 이른 created_at + maxElapsed다. 모든 queue의
cutoff·available_at·namespace가 원래 실패 시각과 backoff에 맞아야 한다. 실제 허용 실패가
최소 하나 있어야 하며 peer의 STREAM_GROUP_RESTART는 그대로 보존한다. 기한 전에는
예약을 유지하고 기한이 지난 경우에만 그룹 전체를 FAILED로 확정한다. 후속 작업도 종료
증거와 기존 결과를 확인한 뒤 SKIPPED/CANCELLED로 정리한다. 새 Attempt는 만들지 않는다.

CANCELLING 그룹은 저장된 취소 이유와 Attempt 이력을 보존해 취소를 확정한다.
이미 FAILED/OFFLOADED인 Attempt는 덮어쓰지 않는다. 모든 Task가 terminal일 때 Run을
조정한다. 성공 Task에는 정확한 committed Result/Attempt/runtime이 있어야 한다.
최종 처리 권한을 받은 재시도 그룹, 진행 중 offload, 일부 producer 미확인, 미기록 결과는
미해결로 남긴다. 불변 checkpoint·완료 기록과 고정 S3 파일은 보존한다.

34개 관련 테이블의 전체 행 guard와 SHARE ROW EXCLUSIVE 잠금을 사용한다. 실행 직전
DB·Kubernetes·broker를 다시 확인하고 SQL 내부에서도 DB 신원과 guard를 검사한다.
lock timeout은 5초, statement timeout은 30초다. transaction 오류는 전체 원복한다.
COMMIT 이후에도 원래 차단과 DB 상태를 다시 관측한다. 응답 유실·관측 실패는 완료로
추정하지 않고 개인 intent와 격리를 보존하며 같은 UUID로 다시 관측할 수 있다.

이 결정은 종합 복구와 재가동의 일부다. 새 generation/grant·Secret·실행을 발급하지 않고
DB marker·원본 broker 차단·namespace 차단을 유지한다. 원본 장치의 모든 물리 프로세스,
외부 시작 권한과 전체 writer 회수·STREAM offload/finalization 복구·서비스 활성화는 남는다.
API/JAR·DB migration 변경 없이 복구 CLI로 제공하며 검증은 실제 PG·TLS S3/MQTT·Kubernetes의
전용 자원으로 수행한다. 합성 업무와 명시적 DB binding fixture를 실제 모델 수용으로 확대하지 않는다.
