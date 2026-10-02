# ADR0022 — 처리 상태·입력 위치·출력의 원자적 스트림 기록

상태: 구성 요소 구현·로컬 실제 프로세스/MQTT 검증. M7 공개 실행·DataRoute 영속 상태·실장비 수용 완료는 아니다.
[메시지 형식](0021-stream-frame-boundary.md)과 [M7 요구사항](../m7-requirements.md)을 따른다.

## 처리와 전달 경계

MQTT broker PUBACK은 네트워크 전달 확인이다. consumer의 처리 완료나 Result 확정으로
사용하지 않는다. producer는 처리 확인을 받을 때까지 실제 출력 bytes와 순번을 보관한다.
consumer는 받은 메시지를 순서대로 보관하고, workload가 계산한 상태·처리한 각 입력 순번·
새 출력 메시지를 한 SQLite 트랜잭션으로 확정한 뒤 처리 watermark를 발행한다.
중간 상태에서 종료되면 같은 볼륨의 마지막 완료 트랜잭션을 복구한다.

PostgreSQL은 route/세대/상태 같은 관리 메타데이터의 저장소다. 이 로컬 journal은 고주기
데이터를 중앙 DB에 모두 적재하지 않기 위한 제한된 data-plane 저장소이며 DataRoute를 대체하지 않는다.
실제 제어 서버의 인증된 배정으로 받은 전체 route/generation/producer와 저장한 manifest가
같아야 다시 열 수 있다. 볼륨이 없거나 신원이 바뀌면 빈 상태로 자동 복구하지 않는다.

입력은 연속된 순번만 받는다. 아직 처리하지 않은 중복은 동일한 전체 frame이어야 한다.
이미 처리한 순번은 계산 없이 버린다. 과거 전체 payload/hash를 무제한 보관하지 않으므로
처리 완료된 옛 메시지의 변조 여부까지 재검증하는 계약은 아니다. gap은 버리고 producer의
미확인 출력 재전송을 기다린다. END는 선행 DATA가 모두 처리된 뒤 완료할 수 있고 후속 DATA는 거절한다.

처리 확인은 `edgeai.stream-ack/v1`과 전체 binding/누적 sequence로 표현한다. sequence0은
아직 처리한 입력이 없다는 뜻이다. producer가 생성하지 않은 미래 순번, 다른 세대·출처의 확인은
거절한다. 확인 신뢰는 broker의 consumer별 topic ACL과 제어 서버 배정에서 얻어야 한다.
이 codec·journal이 MQTT 계정·ACL을 스스로 인증하거나 생성하지 않는다.

## 상한과 복구

기본 journal은 총128개 frame·16MiB wire bytes·256KiB 계산 상태로 제한한다. 입출력 경로마다
메시지 수와 bytes 용량을 균등 예약한다. 한 장치가 전체 buffer를 차지해서 join의 다른 입력이나
출력을 막을 수 없게 한다. 제한 초과 시 트랜잭션 전체를 롤백하고 upstream에 대기를 요구한다.
DB page 수와 SQLite cache도 제한한다. DELETE journal/synchronous EXTRA와 private0700/0600
디렉터리·파일, 단일 프로세스 소유 잠금을 사용한다. journal 파일을 외부에서 교체하지 않는다.

MQTT5 adapter는 정확한 경로별 `frames`/`acks` topic, QoS1·retain=false와 bounded inflight/queue를
사용한다. 재연결마다 구독을 다시 만들고 로컬 미확인 출력을 재전송한다. 수신 gap/용량 부족은
네트워크 차원에서 확인해 다른 topic을 막지 않지만 처리 watermark는 올리지 않는다. producer의
로컬 출력은 계속 남는다. QoS0/retained 메시지는 처리하지 않는다. TCP 비암호화는 명시한 loopback
시험에만 허용하고 일반 endpoint는 CA/hostname 검증 TLS를 요구한다.

Paho MQTT2.1.0 wheel hash를 고정한다. MQTT Receive Maximum만으로 애플리케이션 전체 메모리가
제한되거나 Paho 세션이 프로세스 종료 후 복원된다고 가정하지 않는다.
[MQTT5 규격](https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html),
[Paho manual_ack/queue 계약](https://eclipse.dev/paho/files/paho.mqtt.python/html/client.html),
[SQLite 원자적 commit과 저장장치 전제](https://www.sqlite.org/atomiccommit.html)를 확인했다.

고정 버전 Paho의 TLS deferred handshake가 실패 시 SSLSocket을 명시적으로 닫지 않는 것을
실제 인증서 거절 시험과 소스에서 확인했다. `_ssl_wrap_socket`만 좁게 재정의해 Python의
즉시 handshake/실패 정리·connect timeout을 사용한다. 이 버전 의존 경로는 CA/hostname 거절·
TLS 응답 정지의 실제 소켓 수/시간 상한 시험으로 검증하며 라이브러리 변경 시 다시 확인한다.

## 아직 충족하지 않은 범위

이는 같은 로컬 볼륨에서의 프로세스 복구다. S3 checkpoint export/검증·새 Pod/Node의 restore,
DataRoute generation 전환과 broker 계정/ACL 수명은 후속 구현이다. 따라서 M5 상태형 offload
완료로 판정하지 않는다. 임의 workload의 외부 파일·HTTP 부작용도 이 트랜잭션으로 원자화되지 않는다.
workload는 checkpoint 확정 전 외부 부작용을 만들지 않거나 별도 멱등 계약을 가져야 한다.
다중 입력의 시간 정렬·join 의미는 SERVICE 계약에서 정하며 단순 순번 zip을 모든 센서의 의미로
고정하지 않는다. 실제 시험의 정수 합산은 합성 참조 계산이다.
