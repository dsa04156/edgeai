# M7 자동 체크포인트 SDK·Session 검증

2026-10-03 KST. [ADR0033](../adr/0033-stream-automatic-checkpoint-publisher.md)의 범위다.

| 시험 | 실행 ID | 결과 |
|---|---|---|
| 실제 HTTP checkpoint client·publisher 8개 | 20261003T042134Z-2b5905eb | PASS/0, 4.644초 |
| 자동 EXTERNAL Session 실제 HTTPS/MQTT 신규1개 | 20261003T042415Z-5a5a7757 | PASS/0, 2.919초 |
| 실제 Spring/PG/S3·Python client 최초 연결7개 | 20261003T041724Z-7b20435d | PASS/0 |
| 실제 Spring/PG/S3·Python 자동 publisher 포함7개 | 20261003T042632Z-a38e3d7f | PASS/0 |
| Runner·VD·SDK 전체87개 | 20261003T042633Z-6d75bcd5 | PASS/0, 81.056초 |
| 실제 HTTPS/MQTT·계산·Session 전체39개 | 20261003T042635Z-c27c26d4 | PASS/0, 62.178초 |
| 실제 Spring/PG/TLS broker·Session 회귀22개 | 20261003T042847Z-5b966d84 | PASS/0, 실패/skip0 |

## 직접 확인한 동작

- 실제 HTTP에서 API 자격 파일 회전, S3 요청의 인증 헤더 부재, 고정 version 확정/최신 조회를
  확인했다. redirect·캐시 가능 응답·중복 JSON 키·과대 응답·다른 주체/세대/해시/상태는 거절한다.
  원격 평문 저장소와 임의 Authorization 헤더는 snapshot 전송 전에 거절한다.
- 실제 SQLite의 pending 후보를 uploads/PUT까지 진행해도 출력은 비어 있다. 정확한 commit
  receipt 이후에만9가 허용되고, 다음 상태14는 이전 receipt ID를 참조해 다시 확정한다.
- 실제 HTTP commit 응답을 서버 저장 직후 끊어도 로컬 frontier는 전진하지 않는다.
  journal을 닫고 새 소유자/publisher를 만들면 latest가 같은 후보임을 확인해 추가 PUT 없이
  복구한다. 응답 직후 guard를 만료시키는 시험도 frontier를 전진시키지 않는다.
- 실제 TLS broker·지속 합산 모델·HTTPS fixture에서 checkpoint API만503이면 heartbeat는
  계속되지만 ACK와 출력은 보류된다. 서버 복구 뒤 lost commit reply를 같은 후보로 재전송하고
  실제 sink에9를 전달한다. 같은 볼륨의 Session 재시작 뒤2+3을 더해14까지 이어 간다.
- 실제 Spring·PostgreSQL·MinIO에서는 독립 Python publisher가 현재 Runner 인증으로
  latest/uploads/PUT/commit을 수행한다. 실제 S3 PUT 뒤에도 입력 확인 위치는0이며 서버 확정
  이후에만1이다. Java는 Python receipt ID와 DB 이력이 일치함을 확인한다. 같은 후보의
  upload/commit 재전송과 고정 version 최신 조회도 통과했다.
- Python이 생성한 S3 version을 즉시 private 임시 파일에 남겨 실패 경로에서도 Java가 소유
  object를 정리한다. 자격 파일/임시 디렉터리와 시험 전용 버킷·소유 MinIO도 정리한다.

## 남은 수용

HTTPS/MQTT 시험의 제어 서버와 S3는 명시적 fixture다. 실제 Spring/S3 시험의 Pod gateway와
broker 활성화도 fixture다. 현재 실행 결과를 실제 Kubernetes STREAM 종단 또는 새 Attempt/
세대 복원으로 확대하지 않는다. 공개 STREAM501, M5 잔여와 M7–M10 미완료를 유지한다.
신규 SDK 커밋의 원격 CI/이미지/실제 배포는 별도 확인 대상이다.
