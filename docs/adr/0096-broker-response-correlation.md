# ADR 0096: MQTT 관리 응답의 요청별 수명

상태: 채택, 실제 broker 로컬 검증 완료. 새 CI/배포는 후속이다. 2026-10-05.

branch CI37221887410의 실제 Mosquitto 권한 경합 시험이 REVOKED 대신 INVALID_RESPONSE로
실패했다. 콜백이 `expected.get()`을 두 번 읽는 사이 호출 스레드가 null로 지울 수 있었다.
실제 TLS 경합80회와 임시 예외 종류/코드 위치 진단에서154행 NullPointerException을 관측했다.
이 로컬 실행의 JUnit 결과는 PASS였지만, 종료 직후의 콜백 예외가 별도로 발생했다.

각 명령은 불변 correlation과 전용 용량1 응답 큐를 가진다. 콜백은 현재 요청을 한 번만 읽고
해당 요청의 큐에만 응답을 전달한다. 다음 명령이 시작돼도 지연 콜백이 이전 응답을 새 큐에
넣을 수 없다. 호출 측은 command와 correlation을 모두 대조하며, compareAndSet으로 자기
요청만 종료한다. 같은 연결을 잘못 중첩 호출하면 CONFLICT로 거절한다.

권한 내용·세대 tombstone·정확한 topic·TLS 검증·응답 크기와 timeout은 유지한다.
잘못된 broker 응답을 REVOKED로 바꾸거나 성공으로 처리하지 않는다.
실제 grant/revoke 경합 회귀는 독립 세대12회로 늘리고 실패 시 enum과 코드 위치만 남긴다.
임시 콜백 진단 출력은 최종 코드에서 제거한다.

[재현·검증 근거](../evidence/m9-broker-response-correlation.md). 새 CI/배포는 후속 검증한다.
