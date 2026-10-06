# M7 인증 세션·자동 heartbeat 실행 루프 검증

2026-10-03 KST. [ADR0030](../adr/0030-stream-session-control-loop.md)의 Session이 배정 조회,
heartbeat 순번 재개·재시도·갱신, MQTT/journal/지속 계산의 생성과 정리를 소유한다.
공개 Run/Runner claim의 STREAM 분기, Result 확정과 외부 checkpoint 복원은 아직 연결하지 않았다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| Runner·VD·SDK·journal 전체 | 20261003T022028Z-2420a08f | PASS/0,70개·73.334초 |
| 실제 HTTPS/MQTT 세션 최초7개 | 20261003T021631Z-54b9a0de | PASS/0,14.413초 |
| 실제 MQTT 기존26개+세션9개 전체 | 20261003T022149Z-856bc0f6 | PASS/0,35개·49.026초 |
| 실제 Spring·PostgreSQL·broker·자동 세션 계산 | 20261003T021811Z-db9bc151 | PASS/0,22개·실패/skip0 |

## 직접 확인한 범위

- 실제 HTTPS API fixture의 서버 순번7을0으로 읽고8부터 이어 보냈다. 관측 저장 후 응답을
  끊으면 같은 순번을 재전송한다. 최초 기한을 넘긴 뒤에도 두 입력4+5=9, 이어2+3을 더해14를
  실제 TLS MQTT·지속 계산·SQLite·처리 ACK로 전달했다. 이 fixture의 상대 heartbeat는 명시적 모델이다.
- 503은 기존 순번과 기한 안에서 재시도했다. 429/500/502/503/504와 전송 오류는 retryable
  AssignmentUnavailable로 구분하며, 400/401/403/404/405/409/413은 재시도 대상으로 보지 않는다.
- 상대 관측을 멈추면 우리 heartbeat가 계속되어도 lease를 늘리지 못하고 실제 모델을 종료한다.
  취소·409·전체 세션 timeout도 실제 프로세스를 종료한다. 새 배정으로 전체 실행 시간을 늘릴 수 없다.
- 같은 볼륨에서 재시작하면 journal 상태9를 복구하고 서버 heartbeat 순번을 재개한 뒤1+2를
  더해12를 계산한다. 다른 Run·중복 generation·잘못된 port 방향은 journal 생성 전에 거절한다.
- 입력 END와 출력 ACK가 모두 끝난 뒤에도 세션·MQTT 연결이 남고 heartbeat를 계속한다.
  이는 로컬 settled이며 Task/Run의 성공 또는 S3 Result 확정으로 취급하지 않는다.
- 실제 Spring probe는 Java가 준비한 Run ID·현재 Device/Runner 인증으로 배정을 조회했다.
  consumer heartbeat/refresh를 수동으로 부르지 않고 Session.step이 자동 유지했다. 최초8초
  기한을 넘긴 뒤 계산4+5=9·END·checkpoint3·요청3·처리 ACK·journal 자격 미저장을 확인했다.
  이 시험의 Kubernetes Pod 신원 확인은 명시적 RuntimeGateway fixture다.

실제 HTTPS·MQTT9개는 `test_stream_session.py`이며 기존 `scripts/test/test-stream.sh`/CI 선택에
포함된다. `test-stream-broker.sh`는 실제 PostgreSQL·Mosquitto control/dynamic-security와
해시 고정 Paho 환경을 요구한다. REST 경로·본문·DB schema는 변경하지 않았다.

## 경계와 다음 연결

Session의 generation mapping은 제어 서버의 실행 배정에서 받아야 한다. 현재 구성 요소 시험을
공개 STREAM 실행 또는 실제 Kubernetes의 SERVICE 실행 연결로 확대하지 않는다.
HTTP timeout은 socket I/O의 대기 제한이며 전체 응답의 엄격한 wall-clock 제한은 아니다.
부모가 I/O에 묶여도 계산은 ADR0029의 독립 watchdog이 종료한다.

현재 처리 ACK의 내구성은 같은 볼륨에 한정된다. S3 checkpoint의 검증·확정된 처리 위치와
ACK를 연결하고, 새 Pod/Attempt/generation 복원을 구현해야 한다. SERVICE의 artifact/stream
port·최종 Result, 혼합 BATCH/STREAM 시작 조건과 종료 확인, 운영 broker/TLS·공개 API/UI·
실제 다중 Device Kubernetes 데모가 남는다. M5 잔여·M7–M10 및 전체 목표는 미완료다.
이 변경의 신규 CI·이미지·실제 배포 결과는 후속 확인 대상이다.

## CI 기한 경합 수정

source9af9688의 CI37090538441은 scaffold/storage 성공, runner 실패로 images/gitops가
실행되지 않았다. Runner70개는 통과했으나 실제 MQTT35개 중 세션 timeout 시험에서
초기 검사 뒤 MQTT 내부에서 기한이 지나 `MqttError`가 노출됐다. 모델 정리와 별개로
세션의 종료 원인이 호출 시점에 따라 달라지는 문제다.

실제 hang 모델을 기동한 뒤 Processor 호출 안에서 기한을 넘기는 회귀 시험으로
동일 오류를 재현했다(20261003T024855Z-627e38e1 FAIL/exit1).
Session은 처리 도중 발생한 예외에서도 취소·전체 timeout을 일관된 SessionError로
반환한다. 기한 전의 다른 오류와 BaseException 신호는 원래대로 전파한다.
수정 후 실제 HTTPS/MQTT 전체36개는 20261003T024959Z-d0d98edf에서 PASS/0,
51.523초를 확인했다. 신규 CI·이미지·배포 검증은 별도로 추적한다.

source `922bbfee`의 [CI37091238069](https://github.com/dsa04156/edgeai/actions/runs/37091238069)는
후속 확인에서5 jobs/결과JSON17개 모두 PASS/0이었다. 실제 컨테이너 Runner70개,
HTTPS/MQTT36개와 기존 실제 kind의 Runtime/Remote/VD 수용을 통과했다.
`20261003T032725Z-1e6bedd2`에서 GitOps `64d601d`의 API/dashboard/MinIO 정확한 imageID,
Ready·PVC Bound·Argo Synced·VD 활성화를 확인했다. aggregate health는 기존 Progressing이다.
