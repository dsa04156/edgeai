# ADR0026 — 인증된 주체에만 스트림 배정 정보 제공

상태: 구현·실제 HTTP/DB/TLS broker 연결 및 인증 경계 로컬 검증 완료. 공개 STREAM 실행 연결은 별도 게이트다.

검증 범위·실행 ID·fixture 경계는 [인증 배정 검증](../evidence/m7-stream-bindings.md)을 따른다.

관리자는 기존 Device 세션을 연 뒤 해당 세션의 stream-token을 발급한다. 별도256-bit 서명 키로
Device ID/session ID/epoch/boot ID에 HMAC을 적용하며 토큰은 DB에 저장하지 않는다.
세션 교체·장치 해제 뒤 토큰은 내부 인증에서 거절한다. 토큰 보유만으로 다른 Device/세대 또는
관리 API에 접근할 수 없다. 초기 물리 장비에 자격을 안전하게 설치하는 절차는 M10 외부 계약이다.

Runner는 기존 Attempt HMAC과 Pod-bound TokenReview를 그대로 사용한다. 배정 서비스는
route의 VD→Device→Run→Route 잠금 뒤 현재 주체·세대·lease를 검사하고, 같은 Run 잠금 안에서
현재 runtime producer·offload 상태·VD allocation/lease도 검사한다. 인증 필터의 조회만으로
현재 실행 권한을 확정하지 않는다. MQTT 자격과 배정 정보는 no-store 응답으로만 반환한다.

응답은 route/generation/전체 producer, consumer Attempt/epoch, 방향, 정확한 topic,
media type/최대 payload, broker 접속 정보·공개 CA와 해당 주체의 MQTT 자격을 제공한다.
관리 비밀번호·HMAC 키·다른 주체의 자격은 반환하지 않는다. CA 파일은 X509로 파싱한
인증서만 PEM으로 다시 인코딩하므로 임의 파일 내용을 응답하지 않는다.

배정은 이미 ACTIVE인 세대의 현재 lease까지 유효한 snapshot이다. 이 API는 세대를 생성하거나
lease를 자동 연장하지 않는다. Runner 응답의 유효 시각은 runtime/VD lease·drain deadline까지
추가로 제한한다. 후속 SDK는 serverTime/leaseUntil과 요청 경과 시간을 이용해 수신·계산·발행을
중단해야 하며, 양쪽 주체의 생존 확인과 갱신 프로토콜은 별도 연결한다. 현재 단계에서
무기한 스트림 실행이나 API 장애 중 정확한 broker TTL을 보장한다고 주장하지 않는다.

원격 MQTT는 TLS를 요구하고 literal127.0.0.1만 개발용 평문을 허용한다. 설정된 client endpoint는
동일 broker/CA로 연결되는 주소여야 한다. 기본 비활성, 공개 STREAM501은 계속 유지한다.
권한 worker의 응답과 마찬가지로 고정 broker digest를 비교하여 이전 설정의 세대에 새 자격을
발급하지 않는다. 운영 TLS/API endpoint·Device SDK와 Runner 실제 흐름은 후속 수용 범위다.
