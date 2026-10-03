# ADR0030 — 인증 배정·heartbeat·계산의 자동 실행 루프

상태: 구성 요소 구현·로컬 검증 완료. 공개 Run/Runner claim의 STREAM 분기는 후속 연결이다.

`stream_session.Session`은 제어 서버가 제공한 Run ID와 named port별 generation ID를 받아
기존 BindingClient→Link→Journal→Processor를 소유한다. 현재는 입력이 있는 계산 Task의
실행 구성 요소이며 Device 센서 producer 또는 BATCH→STREAM 변환기의 완료를 뜻하지 않는다.
배정 조회에서 현재 Attempt·Run·generation·입출력 방향·단일 broker 연결을 검증한 뒤
private journal/workload 디렉터리를 연다. 토큰·배정 자격은 journal에 저장하지 않는다.

각 route의 sequence0으로 서버에 저장된 순번을 읽고 마지막 승인 순번+1을 전송한다.
응답 유실 후에는 같은 순번을 재전송한다. 재시작해도 로컬 추정 순번으로 시작하지 않는다.
429/500/502/503/504와 전송 오류만 기존 기한 안에서 재시도한다. 401/403/404/409,
나머지 HTTP 거절·잘못된 응답·다른 신원/규격은 계산과 연결을 종료한다.
배정 조회 자체를 heartbeat 또는 lease 연장으로 해석하지 않는다.

한 번의 step은 MQTT/계산 처리와 필요한 route heartbeat 하나를 수행한다. 기한에 맞춰
요청 간격을 정하고, 남은 기한의1/4·최대1초를 socket timeout으로 사용한다. 이 값은
전체 HTTP 응답의 엄격한 wall-clock timeout이 아니다. ADR0029의 독립 watchdog이 부모의
HTTP 대기와 무관하게 계산 lease/step timeout/취소를 강제한다. 모든 갱신은 기존 Link와
계산이 살아 있는 동안만 적용한다. 전체 세션 실행 기한은 갱신할 수 없고 각 배정 기한의
상한으로 사용한다. 실제 서비스 부하에서 많은 route의 요청 비용은 M8 측정 대상이다.

세션은 동일 볼륨의 journal·실행 규격을 복구하고 계산을 재개할 수 있다. 로컬
complete/settled에서도 heartbeat와 ACK 재전송을 유지한다. 제어 서버가 별도로 종료를
승인하기 전까지 Result를 확정하거나 성공 종료하지 않는다.
실제 HTTPS/MQTT와 Spring 검증은 [세션 실행 기록](../evidence/m7-stream-session.md)을 따른다.

## 공개 실행 연결 전 필요한 조건

- SERVICE의 artifact/stream port 및 최종 Result 규격을 명시하고 BATCH/STREAM 혼합 DAG의
  시작 조건·입력 스냅샷·분기를 검증한다. 현재 BATCH 입력을 완료하지 않은 채 전달하지 않는다.
- Runner claim이 해당 Run/현재 Attempt에 허용한 generation mapping을 제공해야 한다.
  이 구성 요소에 임의 generation 목록을 전달하는 것을 공개 실행 API로 취급하지 않는다.
- 마지막 consumer ACK 유실이 upstream을 막지 않도록 생산·처리 종료 확인 절차를 마련한다.
  Task 성공이 incoming route를 먼저 fence하는 기존 동작을 그대로 사용해서는 안 된다.
- 외부에 내구성 있게 저장한 checkpoint와 처리 ACK의 경계를 정한다. 로컬 확정 뒤 ACK를
  보내는 현재 journal만으로 볼륨이 사라진 새 Pod의 무손실 복원을 주장하지 않는다.
- 운영 broker·제어 API TLS/CA·S3 checkpoint·공개 API/Swagger/UI·실제 Kubernetes 다중 장치
  BATCH/STREAM·실장비 수용을 연결한다. 기존 공개 STREAM501은 이 단계에서 유지한다.
