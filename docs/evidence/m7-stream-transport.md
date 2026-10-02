# M7 스트림 메시지·원자적 처리 기록·실제 MQTT 전달

2026-10-03 KST. ADR0021–0022 구성 요소의 로컬 검증이다. 공개 STREAM 실행은 여전히501이며
전체 M7 또는 M5 상태형 전환 완료로 판정하지 않는다.

## 구현과 검증 범위

- DATA/END의 정확한 bytes·SHA-256·route/generation·Device session/Task Attempt를 검사한다.
  처리 확인은 별도 ACK envelope와 누적 처리 순번이며 broker PUBACK과 구분한다.
- 실제 SQLite journal은 처리한 입력 위치·계산 상태·새 출력 frame을 원자 저장한다.
  단일 소유·전체 binding 일치·private 파일을 요구하고, 재연결/재시작 시 빈 상태로 초기화하지 않는다.
- 입출력 경로별 메시지/bytes 용량을 예약한다. 용량 초과는 전체 트랜잭션을 롤백하며
  journal·Paho queue/inflight를 제한한다. outbox 재전송도 각 경로에 차례를 준다.
- 실제 MQTT5 adapter는 정확한 경로별 topic·QoS1·retain=false·처리 완료 확인/재전송을 사용한다.
  broker 계정/ACL 발급은 아직 없으며 시험이 인증된 배정을 대신해 fixture로 설정한다.
- 일반 endpoint는 검증된 TLS를 요구한다. 비암호화 TCP는 명시한 loopback 시험만 허용한다.
  Paho2.1.0 wheel을 SHA-256으로 고정하고 Runner 이미지/CI에 설치하도록 연결했다.

## 실행 근거

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 첫 codec9개 | 20261002T203728Z-90a8cafd | PASS/0 |
| 실제 SQLite journal11개·두 지점 SIGKILL·경로별 용량 | 20261002T211238Z-e8b44184 | PASS/0 |
| 최종 Runner/VD/codec/journal50개 | 20261002T212713Z-679697f8 | PASS/0 |
| 최종 실제 MQTT·ACL·TLS9개 | 20261002T213209Z-c9a0b768 | PASS/0 |
| TLS 실패/정지·실제 소켓 수·시간 상한2개 | 20261002T212842Z-011c028f | PASS/0, 이후 최종9개에도 포함 |
| Draft202012 schema2개·참조·유효8/무효6 벡터·예시2개 | 20261002T213005Z-4dbfa803 | PASS/0 |

최종50개는 기존 Runner/VD28개, codec10개, journal12개다. journal 시험은 별도 Python 자식을
실제 SIGKILL한다. dirty page/rollback journal이 생긴 트랜잭션 도중에는 이전 입력·상태·출력을,
commit 직후에는 확정 상태와 동일 미전송 출력을 복구했다. 이는 OS 전원 장애 시험이 아니다.

MQTT9개는 실제 별도 Mosquitto2.0.18 프로세스와 네 MQTT client의 TCP/TLS 연결을 사용한다.
두 source의 합성 입력→join 계산→sink를 실제 topic으로 전달하고, END 처리와 처리 확인 후
outbox 정리를 검증한다. 참조 계산과 네 journal/client는 시험 프로세스 안에서 실행되며 실제
Control Plane·Runner workload·물리 센서 프로그램의 종단 연결 시험은 아니다.

느린 consumer 시험은 제한6개의 journal에서 입력20쌍을 처리하며 broker를 SIGKILL 후 재기동한다.
최종 누적 계산값·정확한20회 checkpoint·outbox 정리와 실제 backpressure 발생을 확인한다.
별도로 소비자가 commit 직후 처리 확인 없이 연결을 닫고 같은 볼륨을 다시 열어 중복 처리를 방지했다.
다른 Device topic 발행 거절, 잘못된 세대/retained frame 거절, 잘못된 비밀번호, 신뢰하지 않는 CA와
hostname 거절, TLS 응답 정지 timeout·실제 소켓 정리도 확인했다. 최종 시험 소유 broker 잔여0개를
별도 `/proc` 조회로 확인했다. credentials·인증 헤더·원문 데이터는 evidence에 출력하지 않는다.

## 재현한 실패와 수정

1. 전체 buffer 한도를 공유하면 빠른 입력이 join의 다른 입력 용량을 소진할 수 있었다.
   회귀 `20261002T211206Z-c8c34900` FAIL을 경로별 메시지/bytes 예약으로 수정했다.
2. broker 강제 종료 첫 통합 `20261002T211956Z-a2b1e13c`에서 전송 시 연결 손실을 치명 오류로
   처리했다. 단순 재시험3회는 통과했으므로 그것만으로 해결됐다고 보지 않았다. 실제 TCP write를
   차단한 `20261002T212250Z-47df5813`에서 MQTT_ERR_CONN_LOST(7)를 결정적으로 재현했고,
   출력을 보존한 재연결 경로로 수정한 최종 통합을 통과했다.
3. 초기 TLS 거절 시험의 ResourceWarning과 고정 Paho 소스에서 deferred handshake 실패 시
   소켓 정리 누락을 확인했다. 좁은 버전 의존 TLS wrapper를 보정하고 실제 열린 소켓 수와
   handshake 시간 상한을 확인했다. `/proc`의 socket symlink에는 exists()를 쓰지 않고 readlink로 센다.
4. schema 검사 첫 실행 `20261002T212930Z-18c1ff4f`는 로컬 jsonschema 버전에 없는 referencing
   모듈을 가정해 실패했다. 설치된 validator의 명시적 offline resolver로 검증했고 production 의존성을 추가하지 않았다.

## 재현과 후속 게이트

`bash scripts/test-runner.sh`와 [Runner의 실제 MQTT 시험 명령](../../runner/README.md#스트림-전달-구성-요소-m7-진행-중)을 따른다.
새 CI runner job은 이미지 빌드/기존 실행 회귀와 실제 MQTT9개를 성공해야 이미지를 발행한다.
현재 기록은 로컬 결과이며 새 CI·이미지·배포 결과는 후속 확인한다.

남은 작업: DataRoute 영속 세대·생산자/소비자 배정·broker 계정/ACL 수명, SERVICE/Runner의 스트림
입출력, S3 checkpoint 검증·새 Pod/Node restore, 여러 Device의 실제 BATCH/STREAM DAG,
공개 API/Swagger/화면, 실제 Kubernetes와 `demo-multidevice.sh` 수용이다.
현재 journal 복구는 같은 로컬 볼륨만 다루며 M5 상태형 offload·M10 실장비 수용을 대신하지 않는다.
