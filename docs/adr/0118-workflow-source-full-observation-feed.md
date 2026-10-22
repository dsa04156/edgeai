# ADR0118 — WORKFLOW_DATA 전체 관측 전달(ALL_OBSERVATIONS) feed

2026-10-21 작성, 2026-10-22 Kestra native 제품(feat/kestra-native-console-20261019)에 통합. 상태: 코드·격리 로컬 PostgreSQL 공개 API 시험·Runner 로컬 MQTT 시험 통과. 실제 운영 배포·실장비 장기 수용은 아니다.
[ADR0022](0022-stream-processing-journal.md)와 [ADR0037](0037-service-stream-runner-execution.md)을 따른다.

## 배경

기존 `WORKFLOW_DATA` 입력은 1초마다 최신값 1건을 읽는다. 한 polling 사이의 연속 관측, 같은 시각·역순 관측,
늦게 저장된 관측은 전달되지 않는다. InfluxDB 원본 이력은 관측 시각 순 정렬이고 cursor가 움직이는 기간 조회이므로,
cursor 이후에 늦게 들어온 과거 시각 관측을 보장해서 찾을 수 없다. PostgreSQL에서 sequence 값과 `id > cursor`만 쓰면
번호를 먼저 받은 transaction이 늦게 commit될 때 읽는 쪽이 그 번호를 건너뛸 수 있다(sequence는 rollback과 무관하게 즉시 소비되고,
READ COMMITTED 조회는 commit된 snapshot만 본다).

## 결정

- `dataSource.delivery`를 추가한다. 생략·`LATEST`는 기존 최신값 polling 그대로이며 저장 형식도 바뀌지 않는다.
  `ALL_OBSERVATIONS`는 명시적으로 선택할 때만 Run 매개변수의 고정 원본에 기록된다. 기존 버전·실행·checkpoint는 그대로 동작한다.
- V61 `workflow_source_feed`/`_entry`/`_subscription`은 **활성 소비자 전용의 제한된 전달 queue**다. 원본 보관소가 아니다.
  ADR0022의 원칙대로 모든 고주기 데이터를 중앙 DB에 쌓지 않는다. 진행 중(PENDING/RUNNING) Run의 ACTIVE 구독이 없는 원본은 append하지 않고,
  남은 항목은 보관 기간 뒤 정리한다. InfluxDB 원본 이력이 계속 기록 원장이다.
- 원본별 feed 행을 `SELECT … FOR UPDATE`로 잠근 뒤 `head+1` 위치를 부여하고 같은 transaction에서 항목과 head를 기록한다.
  같은 원본의 append는 commit 순서로 직렬화되므로 commit된 head 이하에는 빈 위치가 없다. 다중 제어 서버 replica에서도 같다.
- append 경계
  - 센서: `SensorArchiveCollector`가 원본 archive 기록 뒤, MQTT ACK 전에 같은 재시도 loop에서 append한다. Mapper 검증(`QUALIFIED`)
    관측만 대상이며 신원은 `sourceGeneration:sessionId:sequence`다. 재전송은 같은 신원으로 중복 append되지 않는다.
    같은 payload의 재전송은 collector의 프로세스 내 완료 캐시(최대 4096건)가 먼저 걸러 archive·feed 기록을 모두 생략한다.
    캐시는 재시작·축출 뒤 비므로 feed 신원 검사와 아래의 `WORKFLOW_SOURCE_GAP` 판정이 계속 최종 중복 경계다.
  - 가상디바이스: `ProcessingEngineService.results`가 수락 receipt를 새로 기록한 결과만 같은 transaction에서 append한다(신원 `evaluationId`).
    엔진 재전송(receipt 중복)은 append하지 않고, rollback되면 둘 다 없어진다. 여러 원본은 key 순서로 잠근다.
- 시작 경계: Run 생성 transaction이 원본 feed를 잠그고 현재 head를 구독 시작 위치로 고정한다. 이 commit 뒤에 수락된 관측부터 전달한다.
- cursor는 `<subscriptionId>:<position>`이며 Run·작업별 구독에 묶인다. 다른 구독, 시작 이전, 아직 commit되지 않은(head 초과) 위치는
  409 `WORKFLOW_CURSOR_INVALID`, 형식 오류는 400이다. Runner는 journal에 commit한 마지막 item의 cursor만 보낸다.
- 정상 종료(drain)는 원본 feed를 잠그고 commit된 head를 `drain_position`으로 고정한다. 그 위치까지 남은 item을 모두 전달한 뒤에만 END다.
- 권한: feed 읽기 뒤 현재 Attempt/epoch/Pod 권한을 확인한 다음에만 소비 위치(ack)를 갱신하거나 구독 종료를 기록하고,
  이 기록은 다른 transaction을 기다릴 수 있으므로 DATA/END를 반환하기 직전에 한 번 더 확인한다. 읽기는 lease를 연장하지 않는다.
  ack 중 정리는 잠금을 얻을 수 있을 때만(`SKIP LOCKED`) 하며, 못 하면 다음 append·ack·정리 주기로 미룬다.
- 한도(설정, 기본값): `edgeai.workflow-data.feed.max-backlog`(10000) 초과 적체, `max-entries`(50000) 초과 정리, `retention-seconds`(3600).
  항목은 진행 중(PENDING/RUNNING) Run의 모든 활성 소비자가 확인하고 보관 기간이 지났거나, 원본별 최대 항목 수를 넘을 때만 지운다.
  취소 중·종료된 Run은 보관을 막지 않는다.
- 새 관측이 없는 원본도 제한된다. `WorkflowSourceFeedWorker`가 Runtime worker 수명주기(`edgeai.runtime.enabled`·`edgeai.runtime.worker-enabled`)에서
  `edgeai.workflow-data.feed.maintenance-ms`(기본 60000) 간격으로 원본 feed를 id 순서 page(256개)로 돌며 각 feed를 별도 transaction·`SKIP LOCKED`로 정리하고,
  진행 중이 아닌 Run의 구독 metadata 중 보관 기간 동안 갱신이 없는 것을 tick당 최대1000개 삭제한다. 연속 VD 설정(`edgeai.continuous.enabled`)과 무관하게
  센서 전용 배치에서도 동작하며 `edgeai.recovery.inspect-only=true`면 아무것도 지우지 않는다. worker를 끈 배치(`worker-enabled=false`)에서는 append·ack 때만 정리된다.
  한 tick은 feed를 id 순서로 최대 256개 방문하고 다음 tick은 마지막 id 다음부터 이어 간다. 한 바퀴는 여러 tick에 걸치며,
  마지막 page가 정확히 256개로 끝나면 빈 page를 읽는 tick이 한 번 더 지나야 처음부터 다시 돌고, 주기는 이전 처리 종료 뒤부터 계산된다.
  방문 시점에 다른 transaction이 잡고 있는 feed는 다음 바퀴로 미뤄진다. 따라서 활성 소비자가 없는 원본의 항목은 보관 기간이 지난 뒤
  잠기지 않은 방문이 있어야 사라지며, 정리 시점의 고정된 상한은 없다.
  그 원본에 새 관측이나 확인 요청이 오면 그때도 정리된다. 만료 대상 구독이 tick당 1000개를 넘으면 다음 tick으로 넘어간다.
  feed 행(원본별 head/floor) 자체는 구독된 적 있는 원본 수만큼 남는다.
  적체 초과, 이미 정리된 위치 이전 cursor(오래된 checkpoint 복원), 정리된 관측보다 오래된 늦은 재전송(중복 여부 판별 불가)은
  409 `WORKFLOW_SOURCE_GAP`이다. 조용히 건너뛰거나 다시 전달하지 않는다.

## Runner

`TaskSource`는 응답 `items`를 메모리에 두고 item마다 emission과 `cursor`가 포함된 상태를 한 journal commit으로 기록한다.
Backpressure 동안 후보를 유지하고, `more=true`면 1초를 기다리지 않고 다음 page를 요청한다. 다음 요청에는 commit된 cursor만 사용하므로
재시작 시 메모리의 미확정 item은 다시 받는다. LATEST 상태(4개 필드)는 그대로 복원되고 실행 중 방식을 바꾸지 않는다.

## 지원 범위와 비보장

- 지원: 숫자/불리언 `QUALIFIED` 센서 측정, 실행 중인 연속 VD 출력. 문자열 센서의 ALL_OBSERVATIONS는 409 `WORKFLOW_SOURCE_UNSUPPORTED`.
- collector가 받기 전(Mapper·broker·네트워크) 손실, 비검증 DataModel 관측, 시작 경계 이전 관측은 전달 대상이 아니다.
- 시작 경계 직전에 처음 수락된 관측이 시작 뒤 MQTT로 재전송되면 feed에는 첫 수락 기록이 없으므로 새 관측으로 한 번 전달될 수 있다.
- 원본 세대·설정·VD revision 변경은 기존과 같이 409 `WORKFLOW_SOURCE_CHANGED`로 실행을 차단한다.
- 정렬은 수락 순서이며 관측 시각 정렬이 아니다. 24시간 운영 soak, 실장비 부하·지연, 장애 복구 수용은 별도다.
