# ADR0027 — 인증 배정 기반 SDK와 유효기간 제한

상태: Python SDK 구성 요소 구현·실제 HTTPS/TLS MQTT·로컬 journal 검증. 실제 Spring→Python→
Kubernetes workload 및 공개 STREAM 실행은 아직 연결 전이다.

[ADR0026](0026-stream-authenticated-bindings.md)의 배정 snapshot을 `BindingClient`로 조회한다.
Device는 Device/session/epoch, Runner는 Attempt/epoch/Pod 신원으로 요청하고 응답의 전체
주체·generation ID·방향·정확한 topic·media type·최대 payload를 검증한다. 중복 JSON 필드,
비정상 counter/UUID·알 수 없는 필드·256KiB 초과 응답·다른 주체·임의 topic은 거절한다.

제어 API는 검증된 HTTPS origin이며 redirect·환경 proxy를 사용하지 않는다. 개발용 평문은
명시한 literal127.0.0.1만 허용한다. 기존 Kubernetes 내부 HTTP Runner를 이 SDK로 자동 전환하지
않는다. 운영 API TLS endpoint와 서비스 실행 계약을 후속 단계에서 함께 연결해야 한다.
Device bootstrap 파일은 소유자 전용 일반 파일이다. Runner의 고정 Secret과 회전하는 Pod token은
신뢰된 배포 설정의 projected 파일을 매번 다시 읽는다. 그룹 쓰기·다른 사용자 권한은 거절한다.
MQTT 자격과 공개 CA는 응답에서 메모리로만 전달하며 별도 파일이나 journal에 저장하지 않는다.

클라이언트 기한은 `요청 시작 monotonic + (leaseUntil - serverTime)`이다. 요청 전체 경과 시간을
보수적으로 차감하므로 장치 wall clock 차이나 늦은 응답으로 시간을 더 받지 않는다. 만료되거나
120초를 넘는 기한을 거절한다. 이 조회는 서버의 generation lease를 갱신하지 않는다.
snapshot의 유효기간은 프로세스 메모리에만 유지한다. 재시작 후 새 인증 조회 없이 복원하지 않는다.

`Link.from_assignments`는 journal의 모든 입출력 route와 정확히 일치하는 배정을 요구한다.
한 MQTT 연결은 동일한 주체와 broker 자격만 사용하며, 여러 경로 중 가장 이른 만료를 따른다.
step·연결/구독 callback·수신·발행마다 기한을 확인하고, 만료 시 socket을 먼저 닫아 대기 중인
Paho 패킷이 DISCONNECT 과정에서 추가 전송되지 않게 한다. 새 연결의 timeout도 남은 기한으로
제한하되 이미 연결된 Paho 설정은 변경하지 않는다.

journal은 배정이 연결되면 쓰기 트랜잭션 시작과 commit 직전에 같은 guard를 확인한다. 만료가
트랜잭션 도중 발생하면 입력/계산 상태/출력 변경을 모두 롤백한다. 기존 journal 데이터는 보존한다.
진단용 읽기는 가능하지만 만료된 연결로 계산 결과를 확정하거나 다시 발행할 수 없다.
일반 `Link`/`Endpoint`는 기존 저수준 시험 경로이며 인증/lease가 필요한 실행은 반드시
`from_assignments`를 사용해야 한다. 임의 사용자 코드가 직접 socket/DB에 접근하는 것을 막는
보안 sandbox로 주장하지 않는다.

이 구성 요소는 단일 thread의 step/트랜잭션 경계에서 동작한다. 별도 watchdog이나 실제 계산
프로세스 강제 종료·생존 확인/lease 갱신은 후속 Runner 통합의 책임이다. 이미 네트워크/OS에
전달한 패킷을 회수하거나 broker ACL 회수 지연 동안 악성 client의 전송을 막는 기능은 아니다.
consumer의 현재 binding/기한 검사와 broker 권한 worker를 함께 사용해야 한다.

검증: [SDK 배정·lease 기록](../evidence/m7-stream-client.md). S3 checkpoint/새 Pod 복원,
다중 장치 공개 API·UI·실제 Kubernetes 실행과 M5 잔여/M8–M10 수용 범위는 유지한다.
