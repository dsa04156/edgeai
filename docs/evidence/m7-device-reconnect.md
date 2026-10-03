# M7 Device 자동 재연결

2026-10-03. [ADR0046](../adr/0046-device-run-reconnect.md).
M7 전체 완료, 공개 retry 활성화 또는 Kubernetes 장애 수용 판정은 아니다.
후속 공개 retry와 실제 그룹/최종 처리 Kubernetes 장애 검증은
[공개 정책](m7-public-stream-retry.md)과 [최종 처리 수용](m7-finalizer-kubernetes.md)을 따른다.

## 후속 CI·배포 확인

source ca56e2f의 CI37127032786은5jobs/원시결과17개 PASS/0이다.
`20261003T141907Z-7c641df5`에서 Runner 컨테이너111·HTTPS/TLS MQTT87, 완성 API 이미지의
실제 kind AUTO/NODE/cancel·DeviceRunSource·Runner Pod8개·고정 S3 파일6개를 대조했다.
API 교체17.122초 동안 실제 Runner UID2개를 유지했다. 이 CI에는 공개 retry 장애 시나리오가 없다.
GitOps94952ff 배포는 `20261003T141815Z-64242f02`에서 정확한 API/dashboard/MinIO imageID,
Ready·PVC Bound·Argo Synced와 VD 활성화를 확인했다. 기존 공유 Ingress 상태로 aggregate는
Progressing이다. 로컬 MQTT 간헐 실패의 원인 해결 판정은 하지 않는다.

아래는 최초 구현 당시 검증 이력과 당시 남은 범위다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 최초 실제 HTTPS/TLS MQTT/SQLite 자동 재연결 | 20261003T131650Z-deb8e74b | PASS/0,12개 |
| 최종 자동 재연결·fanout 준비 장벽·종료 의도 범위 검사 | 20261003T134008Z-2cf0d2fe | PASS/0,14개·15.504초 |
| 실제 Spring/PG/S3/TLS MQTT·두 Runner 그룹 재시도와 기존 완료 처리 | 20261003T131742Z-4cc9be1d | PASS/0,6개·실패/skip0 |
| 전체 Runner/SDK | 20261003T132522Z-b2367548 | PASS/0,111개·87.396초 |
| 빠른 Runner 종료 관측 경합의 결정적 재현·수정 | 20261003T133108Z-e9724bbb | PASS/0,실제 Runner1개 |
| 최종 소스의 실제 Spring/PG/S3/TLS MQTT·자동 그룹 재연결 | 20261003T133136Z-bf364549 | PASS/0,6개·실패/skip0 |
| 전체 HTTPS/TLS MQTT, 종료 관측 경합 수정 후 | 20261003T133304Z-99839217 | FAIL/1,87개 중86개 통과·기존 source lease 만료1개 |
| 위 기존 source 시험의 단계 시간 진단6회 | 20261003T133748Z-be66a1d3 | PASS/0,6회·18.368초, lease 만료 미재현 |

## 확인 범위

- 세대 미생성/회수 대기·503·조회와 배정 사이409는 전송 권한 없이 대기한다. 같은 프로세스
  owner가 기존 Link/journal을 닫고 새 인증 배정과 LOCAL 인계를 수행한다.
- 처리 미확인 샘플의 순번1과 센서 상태를 유지한 채 generation2로 실제 TLS MQTT 재전송하고,
  다음 샘플은 순번2로 이어진다. Backpressure나 재연결 대기에서는 새 센서 상태를 저장하지 않는다.
- 대기 중 다른 owner의 접근, 세션 교체·인증 거절·Run 취소·timeout·없는 복구 볼륨·잘못된 배정은
  종료하거나 거절한다. HTTP 페이지를 한 단계씩 읽고 모든 선택 경로가 준비되기 전에 전송하지 않는다.
- 실제 자식 프로세스를 SIGKILL한 후 새 owner가 같은 볼륨의 미확인 데이터를 새 세대로 이어 보낸다.
  별도 journal 원자성 시험과 함께 검증하며 디스크 손실 복구로 확대 해석하지 않는다.
- 이미 종료한 경로는 영속 의도의 정확한 FINALIZE만 읽어 완료한다. MQTT broker를 정지해도 복구하며
  SUCCEEDED metadata만으로 성공하지 않는다. journal 인계 직후의 오래된 completion 의도도 커서를
  확인한 후 갱신한다. 이 인계/의도 사이 중단 지점은 명시적 파일 상태 재현이다.

## 실제 그룹 연결

`stream_dag_probe.py`는 두 DeviceRunSource 객체를 처음 한 번만 만든다. 상태9가 서버에
저장된 후 RUNTIME_LOST 관측을 주입한다. 실제 old Runner 두 개의 종료와 Device 이전 transport
폐쇄를 확인한 뒤 서버가 새 Attempt와 경로 세대를 준비한다. 동일 Device owner들이 스스로
device_routes를 조회하고 인계하며, 센서 상태·출력 순번·revision이 이전 checkpoint와 일치한다.
시험 코드의 수동 close/open·handover·새 generation 주입은 제거했다.

실제 두 Runner는 새 폴더/Attempt2에서 외부 상태9를 복원해 root14/sink23·BATCH37을 만든다.
Java가 고정 version의 실제 S3 파일·길이·SHA와 Result producer를 대조한다. 기존 정상 흐름과
취소·최종 처리 복구도 같은6개 시험에 포함된다. Pod 생성/신원/물리 종료 관측과 공개 API에서
아직 거절하는 retry 정책 생성은 내부 fixture다.

## 실패·진단과 후속 게이트

- 전체87개 `20261003T131936Z-0aca679b`는 기존 broker SIGKILL 재연결1개에서20초 제한으로
  실패했다. 당시 네 peer 모두 socket/ready였으며20개 생산 중16개 소비, 위조 거절0이었다.
  연결 단절이나 데이터 유실이라고 단정하지 않는다.
- 별도 진단6회 `20261003T132403Z-0e7da455`는 실제20개 처리·정확한 합·모든 outbox 비움을
 7.641–10.704초에 통과했다. 진단만 관측 상한60초로 실행했고 실제 게이트20초는 유지했다.
  journal commit 최대0.744초를 관측했지만 실패 당시의 지연 원인으로 확정할 수 없다.
  실제 게이트에는 순번 진행과 commit/MQTT 단계의 호출 수·누적/최대 시간만 추가했다.
- 진단 추가 후 전체87개 `20261003T132718Z-8827bcc5`는 MQTT 시험을 통과했지만 다른 기존
  negative Runner 시험1개에서 실패했다. 실제 Runner는 `INVALID_RESPONSE`로 정상 거절했으나
  wait helper의 두 poll 사이 종료를 예기치 않은 종료로 오인했다. 종료 관측 뒤 순수 predicate를
  다시 검사하도록 고쳤다. 기존 실제 Runner 시험에서 첫 poll은 이전 상태, 다음 poll은 실제
  종료를 보도록 강제해 재현·수정 검증했다.
- 그 수정 후 전체87개 `20261003T133304Z-99839217`에서는 위 두 시험 및 신규 자동 재연결14개가
  통과했다. 대신 기존 `test_live_heartbeat_lost_reply_replay_restart_and_end`의 최종 ACK 대기에서
  로컬 배정 lease가 만료됐다. 이 fixture는2초 lease이며 실제 I/O 지연 원인은 아직 미확정이다.
  전체 MQTT 회귀가 모두 통과했다고 표시하지 않는다. 제품의 만료 시 전송 중지 규칙은 유지한다.
  후속 동일 시험6회는18.368초에 통과했고 단계별 최대 pump 시간0.031초·만료0이었다.
  최초 실패의 긴 구간을 재현하지 못했으므로 환경 지연이나 SDK 결함 중 하나로 확정하지 않는다.

새 Kubernetes driver도 DeviceRunSource를 사용하고 모든 선택 경로 준비 및 취소 종료를 기다린다.
이 driver의 실제 이미지/클러스터 실행, 새 CI 및 실제 Kubernetes 그룹/최종 처리 장애 수용은
후속 게이트다. 기존 MQTT 간헐 timeout·짧은 lease 만료는 해결됐다고 판정하지 않는다.
공개 STREAM opt-in 기본 비활성 및 retry/offload/REMOTE/VD 제한은 유지한다.
M5 잔여/M7–M10과 전체 목표는 미완료다.
