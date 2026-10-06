# M7 SDK 배정·lease 검증

2026-10-03 KST. [ADR0027](../adr/0027-stream-client-lease.md)의 Python 클라이언트와 기존 MQTT/
journal 연결을 구현했다. 여기의 제어 API는 실제 HTTPS 서버로 실행하는 명시적 계약 fixture다.
실제 Spring 인증/DB 검증은 [선행 배정 시험](m7-stream-bindings.md)이고, 두 시험을 연결한 실제
Spring→Python→Kubernetes 스트림 workload 수용은 남아 있다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 배정 검증·실제 HTTP·회전하는 Pod/claim 파일8개 | 20261003T001936Z-73120d78 | PASS/0 |
| 실제 HTTPS→TLS MQTT·lease·SQLite rollback·기존 broker 회귀13개 | 20261003T001937Z-b2bf8083 | PASS/0,16.259초 |
| Runner·VD·codec·journal·새 SDK58개 | 20261003T001630Z-1d5e5c30 | PASS/0,69.423초 |

전체58개 이후 변경한 projected claim 읽기와 권한 검사는 최종 SDK8개 및 MQTT13개에 포함된다.
기존 Runner/VD 실행 코드는 변경하지 않았다. Python `ResourceWarning`을 오류로 설정한 최종
SDK8개/MQTT13개 로그에 warning/예외가 없었다.
종료 후 시험 소유 Mosquitto 프로세스 잔여0을 `/proc`에서 확인했다.

## 실제로 확인한 동작

- HTTPS API에서 받은 CA·메모리 자격으로 실제 TLS Mosquitto에 연결했다. Device source→consumer
  payload7 전달·SQLite commit·처리 ACK→source outbox 정리를 확인했다. 신뢰하지 않는 API CA는
  거절됐다. journal 파일에 MQTT 비밀번호 bytes가 없는 것을 검사했다.
- 실제2초 lease가 만료한 뒤 다음 step에서 socket이 닫히고, broker ACL을 그대로 둔 상태에서도 Link가 재전송하지
  않았다. 새 journal commit은 거절되고 이전 revision·미확인 출력·consumer 입력은 보존됐다.
- SQLite가 checkpoint를 수정한 뒤 commit 직전에 시험 monotonic clock을 만료시켰다. 예외와 함께
  상태/출력이 모두 롤백됐고 revision0·빈 상태·출력0개를 유지했다. 이는 실제 DB 트랜잭션 시험이며
  시간 변화만 명시적 fixture다.
- 다른 journal route·주체·generation·topic·방향을 거절했다. media type/최대 payload 검증을
  확인했고4097byte 출력은4096byte route에서 전달되지 않고 로컬 미확인 출력으로 남았다.
- Device와 Runner HTTP의 실제 path/body/인증 헤더, claim 재읽기와 Pod projected token 교체를
  확인했다. Device token의 symlink/공개 권한은 거절하고 Runner의 신뢰된 projected 경로는 읽는다.
- redirect를 따라가지 않고401/403/404/409·503, 캐시 가능한 응답·과대 응답·중복 필드·NaN·깊은
  JSON·잘못된 counter/UUID·평문 원격 endpoint를 거절했다. 응답 대기 시간은 lease를 소모한다.
- 기존 두 합성 장치 join→sink·END, backpressure·SIGKILL 재연결·출력 재전송·ACL·TLS CA/hostname·
  정지한 TLS handshake의 timeout/소켓 정리 시험도 함께 통과했다.

## 실패와 수정

`20261003T001341Z-9ccde29d`는 HTTP 헤더를 대소문자를 구분하는 dict로 검사한 새 시험에서
실패했다. HTTP fixture가 헤더 이름을 소문자로 정규화하도록 수정했다. 실제 요청 인증은 바꾸지 않았다.

`20261003T001629Z-ea734574`에서는 새 timeout 조정이 매 step마다 연결된 Paho의 설정을 변경해
실패했다. 고정 Paho2.1.0 소스의 setter가 열린 연결을 거절하는 것을 확인했다. 새 연결 직전에만
기존 timeout과 남은 기한 중 짧은 값을 적용하도록 수정했다. 이전 정지 TLS 시험의0.15초 설정도
보존한다. 최종13개에서 원래 실패와 기존 소켓 정리/재연결을 다시 검증했다.

## 남은 경계

이 초기 검증 당시에는 갱신이 없었다. 후속 [양쪽 heartbeat](m7-stream-heartbeat.md)에서 실제
Spring→Python·양쪽 갱신·live Link refresh를 검증했다. Runner 계산 프로세스 watchdog은 남는다.
journal의 로컬 ACK를 새 Pod에서도 복구 가능한 S3 checkpoint에 연결하는 작업도 남는다.
운영 broker/TLS 설정, Device 입력·SERVICE 스트림 workload, 실제 Kubernetes 다중 장치 DAG,
공개 실행/조회/Swagger/UI와 M8 부하·M9 운영·M10 실장비 수용은 별도다. 공개 STREAM501은 유지한다.

재현: `bash scripts/test/test-runner.sh`, 고정 Paho 환경과 실제 Mosquitto/OpenSSL에서
`bash scripts/test/test-stream.sh`. 새 SDK/MQTT 시험은 기존 CI 명령에 자동 포함된다.
