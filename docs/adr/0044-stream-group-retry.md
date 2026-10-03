# ADR 0044: 계산 중인 스트림 그룹의 원자적 재시도

2026-10-03. 연결된 계산 중 작업을 함께 fence·중지·재배정한다. 공동 완료 허가 뒤 최종 파일 복구와
장치의 자동 재연결은 별도 경계이므로 공개 STREAM retry 설정은 아직 거절한다.

## 그룹과 영속 상태

ADR0038/0041의 불변 STREAM·동일 Device fanout 구성 요소를 재사용한다. BATCH로만 연결된 작업과
독립 분기를 묶지 않는다. RuntimeLifecycleService와 StreamRecoveryService는 같은 Run 행 잠금을
유지한다. 처리 중 실패를 재시도할 수 있으면 구성원 모두의 현재 Runtime을 STOPPED로 바꾸고
Attempt를FAILED, Task를RETRY_WAIT로 전환하며 V6 task_retry에 전체 예약을 한 트랜잭션으로 남긴다.
새 DB 표나 메모리 작업 큐는 필요하지 않다. API 재시작 뒤에도 기존 retry worker가 같은 예약을 읽는다.

원인을 만든 Runtime에는 원래 실패 코드를, 동반 종료하는 peer에는 STREAM_GROUP_RESTART를 남긴다.
Run 정책의 원래 실패 코드·각 Task의 남은 attempt 예산을 확인한다. 모든 구성원의 최초 Attempt를
기준으로 계산한 기한 중 가장 이른 기한을 공유하고 같은 backoff를 적용한다. 한 구성원이라도
예산이 없거나 결과/공동 완료 허가가 있으면 계산 중 그룹 재시도를 예약하지 않는다.

## 권한 회수와 재배정 장벽

같은 트랜잭션에서 연결된 모든 열린 경로 세대를 REPLACED 사유로 fence한다. 이 철회는
Device 잠금을 뒤늦게 획득하지 않고 기존 Run/Route 잠금 순서만 사용한다. Broker worker가
실제 권한 회수를 확인해야 CLOSED가 된다. Runtime 삭제 의도는 기존 outbox를 사용한다.

retry worker는 모든 구성원의 동일한 최신 실패 Attempt·예약·기한·예산을 재검사한다.
**전체 Runtime의 STOPPED/TERMINATED와 미완료 CREATE 없음**, **전체 이전 세대의 권한 회수**를
모두 확인하기 전에는 새 Attempt를 하나도 만들지 않는다. 준비된 전체 그룹의 새 Attempt/epoch와
Runtime 계획을 한 트랜잭션으로 생성하므로 두 worker의 동시 실행도 한 번만 전진한다.
대기 중 취소·기한 만료·예산 소진은 그룹/하위 정리로 끝나며 독립 분기는 유지한다.

모두 새 claim을 얻으면 기존 Run worker가 다음 route generation을 만든다. 이전 세대는 CLOSED,
현재 Task Attempt는 RETRY이며 각 Task endpoint의 epoch가 증가하고 이전 Runtime이 종료된 경우만
후속 세대를 준비한다. Device Session/epoch는 원래 고정을 유지한다. Session 교체는 승계하지 않는다.
이후 기존 인증 execution API와 서버 검증 checkpoint handover가 새 작업 폴더로 상태를 복원한다.

## 검증 경계와 남은 연결

실제 PostgreSQL에서 동시 실패·두 retry worker·취소·예산/기한과 그룹 장벽을 검사한다.
실제 Spring HTTPS/PG/S3/TLS MQTT·독립 Runner 두 개에서 상태9 후 Run 실패 관측을 주입하고,
오래된 실제 Runner/DeviceSource가 종료된 것을 확인한 뒤 새 Attempt/폴더와 경로로 전환한다.
DeviceSource는 ADR0043 조회 후 기존 명시적 handover로 LOCAL 송신 기록을 보존한다.
불변 checkpoint 인계 이력과 상태9→root14/sink23→BATCH37·고정 S3 결과를 대조한다.

이 시험의 Run 재시도 정책 생성과 Pod provisioning/identity/종료 관측은 내부 fixture다.
공개 API 활성화, 실제 Kubernetes 그룹 장애, 자동 Device 재연결, 공동 완료 허가 뒤 새 Attempt
최종 파일 복구, M5 외부 수용/M7 전체/M8–M10은 남는다. V1–V25는 수정하지 않는다.
