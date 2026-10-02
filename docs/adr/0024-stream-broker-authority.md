# ADR0024 — Mosquitto의 세대별 권한과 회수 이력

상태: adapter·실제 TLS broker와 PostgreSQL 세대 전환 검증 완료. 서버 worker·배정·운영 broker 배포 연결은 남았다.

ADR0023의 영속 권한 세대를 Mosquitto Dynamic Security로 구현한다. 동적 권한은
[공식 API](https://github.com/eclipse-mosquitto/mosquitto/blob/v2.0.18/plugins/dynamic-security/README.md)를
사용하며 Java Paho MQTT5 1.2.5를 버전/Gradle lock에 고정한다. domain은 순수 gateway/권한
타입을 정의하고 adapters가 네트워크·암호·broker 응답 처리를 담당한다.

## 주체와 최소 권한

Device의 현재 session/epoch와 Task Attempt/epoch를 서로 다른 MQTT 사용자로 구분한다.
사용자와 client ID를 같은 식별자로 고정하며 한 소비자는 여러 입력 route의 역할을 함께 가질 수 있다.
브로커는 전용 인스턴스와 publish/send·receive·subscribe·unsubscribe의 기본 deny를 요구한다.
adapter는 이를 실제 조회하고 잘못된 기본값이면 권한을 만들지 않는다.

각 RouteGeneration UUID에 producer/consumer 역할을 하나씩 만든다. producer는 정확한
`edgeai/streams/{routeId}/{generation}/frames` 발행과 `acks` 수신/구독만, consumer는 그 역방향만
허용한다. wildcard 구독·다른 route 발행·브로커 관리 topic 접근 권한을 주지 않는다.
정확한 ACL 네 항목·policy digest·주체 client ID·설정 digest·역할 연결을 조회하여 확인한다.

## 늦은 발급과 회수의 경합

grant는 ACL을 포함한 createRole만 사용하며 이미 존재하는 역할의 권한을 추가/변경하지 않는다.
revoke는 역할이 없으면 빈 역할을 먼저 만들고, 두 역할의 ACL을 빈 배열로 바꾼 뒤 실제 조회한다.
빈 역할은 삭제하지 않고 회수 이력으로 남긴다. 따라서 revoke가 먼저 도착하거나 grant의 응답이
유실돼 재시도하더라도 늦은 createRole/addClientRole이 회수된 세대를 되살릴 수 없다.
broker의 확인 뒤에도 Control Plane은 현재 실행 주체·세대·lease를 재검사해야 한다.

같은 주체의 다른 route는 다른 역할을 사용하므로 그 권한을 제거하지 않는다. 닫힌 역할의
보존은 제어 요청이 계속 지연될 수 있다는 전제에 필요하다. 이력/빈 client의 용량과 안전한 정리
정책은 M8/M9에서 검증해야 하며 무조건 deleteRole하는 GC는 허용하지 않는다.
정상 파일 저장 후 broker 프로세스 SIGKILL/재시작을 검증했다. 전원 손실·스토리지 손상 복구를
검증한 것은 아니므로 관리 설정의 영속 볼륨/backup·복구 시 재동기화 게이트는 별도로 필요하다.

## 자격 증명과 실패 처리

MQTT 관리 비밀번호와 별도256-bit 주체 파생 키는 비공개 일반 파일에서 읽는다. Device/Attempt별
비밀번호는 broker digest와 주체에 HMAC-SHA256을 적용해 재시도 때 같게 발급하며 PostgreSQL에
원문 비밀번호를 저장하지 않는다. 파생 키의 fingerprint를 broker client metadata에 포함하고,
동일 설정에서 키가 달라지면 기존 client의 비밀번호를 몰래 바꾸지 않고 충돌로 처리한다.
실제 배정 API는 자격을 반환하기 전에 Device/Pod 인증과 현재 세대 검사를 반드시 수행해야 한다.
현재 credential 메서드는 그 후속 연결을 위한 내부 메서드이며 공개 API가 아니다.

원격 연결은 신뢰 CA·hostname 검증 TLS를 요구한다. 비암호화 예외는 literal127.0.0.1의
명시적 개발 endpoint뿐이다. 요청 correlation UUID와 명령명, 최대 메시지 크기/큐, connect/응답
timeout을 검사한다. broker 응답이나 자격을 예외/로그에 노출하지 않는다.
Paho1.2.5의 String[]/int[]/listener[] subscribe overload는 재귀 호출 결함이 있어
[정상 MqttSubscription[] overload](https://github.com/eclipse-paho/paho.mqtt.java/blob/v1.2.5/org.eclipse.paho.mqttv5.client/src/main/java/org/eclipse/paho/mqttv5/client/MqttClient.java)를 사용한다.
broker의 addClientRole 중복은 Internal error로 응답할 수 있다. 이 응답만으로 성공 판정하지 않고
정확한 역할/priority 연결을 다시 읽어 멱등 완료를 확인한다.

## 남은 연결

Dynamic Security는 세대 lease의 자동 만료 기능을 제공하지 않는다. 이 adapter는 발급 시
만료를 거절하지만, 실제 수신/계산은 Runner/Device의 lease deadline과 제어 서버의 회수 worker로
연결해야 한다. stock broker가 DB lease 만료 즉시 권한을 회수한다고 주장하지 않는다.
영속 상태→worker→broker→인증 배정→실제 STREAM workload의 종단 시험 전에는 공개 STREAM501을 유지한다.
검증 범위와 명령은 [증거](../evidence/m7-stream-broker.md)를 따른다.
