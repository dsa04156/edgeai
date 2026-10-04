# ADR0068 — 브로커 회수가 heartbeat보다 빠른 장치 재연결

2026-10-04. [ADR0046](0046-device-run-reconnect.md)의 동일 Device Session·LOCAL journal·
인증된 새 배정 계약을 유지한다. 새 HTTP 계약이나 DB migration은 없다.

실제 VD 복구 회귀 중 Device SDK의 MQTT 거절로 복구가 중단됐다. 별도의 실제 TLS 브로커
시험에서 기존 사용자 권한을 먼저 회수하고 다음 HTTP heartbeat를 지연시켜 순서를 고정했다.
첫 재현은 Paho 2.1.0의 소켓 유실 후 timeout 설정 오류로, 그 경계를 고친 재현은 MQTT
연결 거절이 owner를 종료하는 문제로 실패했다. 원래 간헐적 VD 실패의 세부 거절 코드는
기록되지 않아 이 두 재현을 그 실행의 모든 원인이라고 단정하지 않는다.

소켓이 없는 재접속에서는 Paho의 공개 disconnect API로 연결 유실 상태를 마친 뒤, 남은
배정 시간으로 connect timeout을 제한한다. lease 검사와 TLS 검증은 유지한다. 유효한 같은
배정으로 브로커만 재시작한 경우 기존 source/journal과 미확인 순번을 그대로 재사용한다.

CONNECT/SUBSCRIBE/PUBLISH의 브로커 거절은 `MqttBrokerRejected`로 구분한다. 상위
DeviceRunSource는 먼저 닫힌 이전 DeviceSource의 세대 ID를 같은 Device Session으로
다시 조회한다. 인증된409 회수 응답이면 backoff 후 모든 선택 route와 새 배정을 다시 확인한다.
API가503 등으로 일시적으로 불가능해도 이전 transport는 닫힌 상태로 기존 대기 절차를 따른다.
현재 배정이 모두 여전히 유효하면 원래 브로커 거절을 종료 오류로 전달한다.401/403/404,
TLS·잘못된 응답·신원 오류를 재연결로 바꾸지 않는다. 각 조회 사이에 취소와 전체 timeout을
확인하며 새 배정 없이 MQTT를 재개하거나 journal을 바꾸지 않는다.

브로커 거절 자체는 새 권한이나 완료 증거가 아니다. 기존 처리 ACK·미확인 DATA/END·센서
커서와 완료 의도 계약을 유지하고, 다른 세션이나 누락된 볼륨으로 자동 전환하지 않는다.
[검증 근거](../evidence/m7-broker-first-reconnect.md)를 따른다.
