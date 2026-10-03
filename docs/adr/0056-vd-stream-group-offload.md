# ADR0056 — VD가 포함된 스트림 그룹의 노드 전환

2026-10-04. 구현·검증 중이며 M7 완료 판정이 아니다.

기존 공개 `POST /tasks/{taskId}/offload`의 NODE 전환을 VD가 포함된 STREAM 그룹에 연결한다.
선택 작업은 현재와 다른 호환 READY 노드로 이동하고, 동료 작업은 직전 Attempt의
AUTO/NODE/VD 배치를 유지한다. Task의 최초 배치는 변경하지 않는다. VD를 새 전환 대상으로
지정하는 API를 추가하지 않으며 Remote STREAM과 VD 공유 자원의 자동 전환은 별도 남은 범위다.

Run 잠금 아래 각 VD 구성원의 현재 배정·supervisor 세대·lease·producer를 읽어 검사한다.
다른 VD mutex는 획득하지 않는다. 같은 잠금에서 전체 그룹 계획·체크포인트를 고정하고 경로와
producer를 차단한다. 자격 발급·결과 확정은 기존 자기 VD → Device → Run 잠금을 유지한다.
어떤 VD가 drain/유실됐거나 체크포인트가 오래되었으면 그룹을 차단하기 전에 거절한다.

V33은 구성원의 `target_vd_id`를 추가한다. 기존 행은 null을 유지하고 기존 source/target·
체크포인트·배치 이력을 수정하지 않는다. 선택 작업의 NODE 계획과 동료의 기존 VD를 DB에서
검사한다. 새 Attempt는 전체 이전 Runtime 종료·VD 자식 slot 회수·경로 권한 회수 뒤 생성한다.
VD supervisor는 유지하며 해당 Run 자식만 종료한다. 다른 Run의 자식과 배정은 보존한다.

VD peer가 마지막으로 claim해도 전체 Operation을 성공으로 전환한다. 이 성공은 실행 재개이며
결과 확정이 아니다. API 재시작·멱등 요청·취소·기한·한도·늦은 producer 차단·외부 상태 인계는
기존 NODE 그룹 전환과 같은 계약을 따른다. 적용된 V1–V32는 변경하지 않는다.

필수 증거는 공개 API/실제 PG의 같은·다른 VD 및 VD/NODE 그룹 전환, 잘못된 DB 계획 거절,
전체 물리 종료/회수 장벽과 모든 claim, 취소/경합/실패, 실제 supervisor·Runner·S3·TLS broker의
상태 인계와 Kubernetes 노드 이동, Swagger/UI·CI·배포다. 구성 요소 검증과 전체 수용을 구분한다.
