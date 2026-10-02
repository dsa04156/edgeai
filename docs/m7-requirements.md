# M7 다중 장치 DAG·Streaming 요구사항

2026-10-03 KST에 Notion 전체 설계/API/ERD/실행 지시를 다시 조회했다. 수정 시각은
[기존 출처](sources.md)의 2026-10-01과 같았다. 이 문서는 다음 구현의 수용 범위이며
STREAM 구현 또는 검증 완료 기록이 아니다. 현재 공개 STREAM Run은 계속501을 반환한다.

## 원문에서 요구하는 동작

- 서로 다른 물리 Device의 입력을 BATCH/STREAM DAG로 연결하고 실제 데이터를 처리한다.
  Device·실행 Node·VD·Runtime의 식별자와 책임을 계속 구분한다.
- MQTT/Streaming 데이터 경로는 센서/adapter→broker→AI Runtime이다. PostgreSQL에는
  관리 상태·메타데이터를 저장하며 고주기 원시 스트림을 모두 적재하지 않는다.
- STREAM 연결에는 DataRoute와 generation이 필요하다. 실행 중 전환은 같은 Task의
  새 Attempt, producer fencing, 필요한 drain/checkpoint 및 route 전환을 유지한다.
- `scripts/demo-multidevice.sh`는 여러 Device가 참여하는 실제 BATCH/STREAM DAG를
  재현해야 한다. 관리 행이나 route 메타데이터 생성만으로 통과 판정하지 않는다.
- 합성/replay/live 출처를 구분한다. 합성 센서·참조 workload의 결과는 실제 장비·모델
  또는 성능 수용을 대신하지 않는다. 외부 장비 계약은 확인 전까지 고정하지 않는다.

근거: [전체 설계 §7/§9/§12](https://app.notion.com/p/3ecbafd382d681b295f4f878aad79160),
[API 도메인·확장 엔티티](https://app.notion.com/p/3ebbafd382d681feb4a5c3610d9d3b3b),
[ERD TaskDependency·DataRoute/Checkpoint](https://app.notion.com/p/3ebbafd382d681cf8568e6d870fe97f3),
[실행 지시 §6/§7](https://app.notion.com/p/3ebbafd382d681bd920ae91452b0463a).

## 현재 코드와의 차이

현재 BATCH는 검증된 선행 S3 결과를 다음 작업의 고정 입력으로 전달한다. Device 등록·session
관측과 VD source binding은 관리 경로이며 센서 payload를 실행 입력으로 전달하는 경로가 아니다.
Run의 실행 정책은 전체 DAG 기본값이고, STREAM edge는 발행할 수 있지만 실행은 거절한다.
따라서 현재 BATCH 또는 VD 수용 성공을 다중 물리 장치 데이터 경로의 완료로 해석하지 않는다.

## 구현 전에 정할 계약과 검증

| 경계 | 정할 계약 | 필요한 증거 |
|---|---|---|
| 장치 입력 | Device/Profile/session과 named port, 출처·schema·시간·sequence, 입력 스냅샷 | 여러 Device 입력이 실제 계산값에 반영, 이전 session/잘못된 형식 거절 |
| 실행 배치 | 각 단계의 실행 대상과 Run 기본값, AUTO/NODE/VD 및 SERVICE 호환성 | 서로 다른 실제 Runtime 간 흐름, scheduler bind와 실제 생산자 대조 |
| 데이터 route | 불변 연결 식별자, 현재 generation, producer/consumer Attempt와 권한 | 교체 전후 세대·이력 보존, 이전 producer 전송이 최종 결과에 영향 없음 |
| 전달 | 메시지 envelope·순서·중복·ack, 최대 payload·buffer·대기 시간 | 중복/재전송·순서 오류·느린 소비자에서 bounded memory/backpressure |
| 복구 | broker·네트워크·API·Runner 재연결, drain/cancel, checkpoint/offset 수명 | 장애 주입 뒤 손실/중복 정책 검증, 같은 Task/새 Attempt와 늦은 메시지 차단 |
| 결과 | 종료/완료 조건과 고정 S3 결과·출처 추적 | 프로세스 종료만으로 성공하지 않음, 실제 bytes·SHA/version·기대 계산값 일치 |
| 공개 관리 | Run/Task의 route/진행/오류 조회, Swagger와 화면 | 실제 API/DB와 PC·모바일 조회, 인증·세대 정보·오류가 일치 |

메시지 wire format, broker별 권한 관리, 전달 보장 수준과 checkpoint 형식은 원문에 상세
정의가 없다. [ADR0021](adr/0021-stream-frame-boundary.md)과
[ADR0022](adr/0022-stream-processing-journal.md)에서 frame/처리 확인·로컬 journal·MQTT adapter를
구체화했다. [ADR0023](adr/0023-stream-route-authority.md)/V19는 DataRoute 영속 상태와 세대 제어를
구현하고 실제 DB에서 검증했다. 인증된 배정·실제 broker 권한 수명·S3 checkpoint는 남았다. M5의 상태형
복원 잔여 조건은 이 결정과 함께 검토하고, 단순 처음부터 재시작을 checkpoint 복원이라 부르지 않는다.

수용은 프로토콜 단위→실제 DB/브로커→Runner/다중 입력→실제 Kubernetes→장애/재연결→
화면/Swagger→CI·배포 순으로 확장한다. M8 규모 시험과 M10 실장비 합격 기준은 별도로 남는다.

## 데이터 전달 계약을 정할 때 반영할 근거

MQTT QoS1에서는 중복 전달이 가능하다. MQTT5 Receive Maximum은 연결별 미확인 QoS1/2
PUBLISH 개수를 제한하며 QoS0와 애플리케이션 내부 버퍼 전체를 제한하지 않는다. 따라서
route/producer/generation·메시지 식별자, 처리 완료 ack와 bounded queue를 별도로 정의하고
broker 수신 확인을 AI 처리 또는 Result 확정으로 해석하지 않아야 한다.
[OASIS MQTT5 §3.3/§4.9](https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html).

Paho Python의 공식 제한 설명은 클라이언트 세션을 메모리에 보관하며 프로세스 재시작 때
미완료 메시지가 유실될 수 있음을 명시한다. 자동 reconnect 또는 QoS 설정만으로 상태 복원과
무손실을 주장하지 않는다. 실제 사용할 버전·MQTT5 경로의 동작을 시험하고 애플리케이션의
checkpoint/재전송/중복 제거 경계를 정의해야 한다.
[Paho Python known limitations](https://eclipse.dev/paho/files/paho.mqtt.python/html/#known-limitations).

현재 Compose MQTT는 loopback 공개 개발용 익명 broker이며 런타임별 권한 격리를 구현하지
않았다. 별도 통합 시험은 private 계정/정확한 topic ACL·TLS를 사용하지만 운영 계정 발급/해제와
실행 배정 연결 완료를 뜻하지 않는다. 실제 실행 전달 경로는 Compose 설정을 그대로 운영 계약으로 사용하지 않는다. Mosquitto
Dynamic Security의 client/role/topic 제어는 [ADR0024](adr/0024-stream-broker-authority.md)에서 채택해
실제 TLS broker adapter로 검증했다. 서버 worker·운영 broker·인증 배정 연결은 남아 있다.
[공식 설명](https://mosquitto.org/documentation/dynamic-security/).
