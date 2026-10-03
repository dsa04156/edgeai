# ADR 0043: Device 세션의 Run 경로 조회

2026-10-03. 그룹 재연결 시 Device SDK가 관리자 자격 없이 현재 경로 세대를 찾는 기반이다.
이 변경 자체가 그룹 복구·자동 journal 전환을 수행하지는 않는다.

## 인증과 조회 범위

`POST /internal/v1/devices/{deviceId}/sessions/{sessionId}/streams/routes`는 현재 Device 세션의
Bearer 토큰만 받는다. 관리 계정이나 Runner claim으로 접근할 수 없다. 본문은 정확히
`epoch`, `runId`, `limit`, `offset`이며 Run 생성자가 해당 장치에 runId를 전달한다.
장치의 전체 Run 목록이나 다른 장치의 경로는 제공하지 않는다.

조회는 현재 ACTIVE 장치·세션·epoch와 V25의 불변 `stream_device_binding`을 대조한다.
해당 장치에 고정된 경로가 없는 Run은 존재 여부와 관계없이404다. 현재 세션 토큰이 유효해도
Run에 고정된 이전 세션과 다르면409이며 이전 세션의 토큰은 인증 단계에서401이다.
세션 교체로 이전 Run을 자동 승계하지 않는다.

## 메타데이터와 실제 전송 권한

응답은 `edgeai.device-routes/v1`, Run/Device/Session/epoch, Run 상태와 본인 경로의 포트·
sourceMode·payload 제한·최신 세대 ID/번호/상태/leaseUntil을 담는다. 세대가 아직 없으면null이다.
종료된 Run이나 닫힌 세대도 같은 현재 세션이 읽을 수 있다. MQTT 주소·비밀번호·토큰·
서명 URL은 포함하지 않으며 `Cache-Control: no-store`와262144 bytes 제한을 적용한다.

이 응답은 전송 권한이 아니다. 세대를 만들거나 lease를 갱신하지 않는다. SDK는 실제
DeviceSource를 시작하기 전에 기존 streams 배정 API로 현재 권한과 기한을 확인한다.
재연결 시 이전 owner 종료와 journal handover 선택도 기존 명시적 절차를 유지한다.

## 페이지와 일관성

limit은1–100, offset은0–1,000,000이다. 고정된 논리 route UUID 문자열 오름차순으로
페이지를 나누고 마지막 nextOffset은null이다. REPEATABLE_READ 트랜잭션 안에서 현재 세션과
불변 binding·Run·최신 세대 상태를 읽는다. 논리 경로 집합은 Run 생성 시 고정되지만 페이지를
넘기는 사이 세대 상태는 바뀔 수 있으므로 메타데이터만으로 전체 경로의 동시 유효성을 판단하지 않는다.

SDK `BindingClient.device_routes`는 응답의 정확한 필드·주체·타입·페이지·정렬·중복·세대 상태를
검증하며 기존 TLS·private token file·리다이렉트 금지·응답 크기와 캐시 제한을 재사용한다.
실제 장치 송신기는 이 응답에서 route/generation을 얻는다. 관리 경로 조회는 시험의 독립 대조에만 쓴다.

DB schema 변경은 없고 V1–V25 bytes와 기존 공개 STREAM opt-in·AUTO/NODE 제한을 유지한다.
검증 범위는 [진행 기록](../evidence/m7-device-route-discovery.md)을 따른다.
