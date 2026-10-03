# M7 인증 Attempt·세대 체크포인트 인계 검증

2026-10-03 KST. [ADR0035](../adr/0035-stream-verified-checkpoint-handover.md)의 구성 요소 범위다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| Java streaming 원본/인계 파일 검사 | 20261003T045719Z-16d14ac8 | PASS/0, 4개 |
| 실제 HTTP SDK·명시적 handover·복원 | 20261003T050054Z-c86c26d1 | PASS/0, 13개·8.015초 |
| 실제 Spring/PG/MinIO 초기 인계·회귀 | 20261003T050212Z-16bc2f0c | PASS/0, 11개 |
| 실제 계산 프로세스·Device 경계·DDL 제약 포함 | 20261003T050354Z-25097419 | PASS/0, XML13개·실패/skip0 |
| OpenAPI5개·MVC/Swagger·패키징 계약 대조 | 20261003T050538Z-cca12d5f | PASS/0 |
| 전체 Java 단위/MVC | 20261003T050622Z-97d924d2 | PASS/0, XML89개·실패/skip0 |
| 전체 Runner | 20261003T050538Z-2fd65eeb | PASS/0, 92개·85.209초 |
| 실제 HTTPS/MQTT | 20261003T050538Z-396def03 | PASS/0, 42개·70.827초 |
| 프로젝트 PostgreSQL 회귀 | 20261003T051440Z-7698c3fd | PASS/0, XML153개·실패/skip0 |
| 실제 S3/Remote/VD·checkpoint 회귀 | 20261003T051541Z-091d7101 | PASS/0, XML29개·실패/skip0 |
| 실제 Spring/PG/TLS broker·SDK 회귀 | 20261003T051701Z-5e613e1d | PASS/0, XML22개·실패/skip0 |
| 최종 OpenAPI·nonnull 인계 응답·MVC/Swagger | 20261003T051942Z-17463a57 | PASS/0 |
| 실제 API/DB·PC/모바일·Swagger·DB 중단/복구 | 20261003T052013Z-1ff09d15 | PASS/0, 브라우저10개·43.8초 |

## 실제 검증한 경로

- 실제 Execution/RuntimeLifecycle 재시도 상태 전이로 동일 Task의 Attempt2/epoch2를 만든다.
  이전 runtime 종료 관측과 broker 회수 receipt는 명시적 fixture다. 다음 Pod proof로 인증한
  latest는 인계 전409이며 handover가 실제 S3 파일을 읽고 새 고정 version을 작성·검증한다.
- 독립 Python SDK가 새 Attempt의 receipt를 확인하고 새 journal에 상태9·커서1을 복원한다.
  실제 `stream_sum.py` 프로세스에 복원 상태와 새 순번의2·3을 전달해14 응답을 얻는다.
  이를 journal과 실제 S3/DB에 확정해 revision2·커서2를 대조한다. 원본+인계+후속 확정3행이며
  handover_from_id 이력은1개다. 이전 producer의 API 요청은 거절된다.
- 같은 Attempt의 세대 변경에 두 HTTP handover를 동시에 보내도 같은 receipt/한 행만 확정한다.
  snapshot serial만1 증가하고 계산 revision은 유지한다. 재호출도 멱등이다.
- 원본 없음·실행 digest 변경·잘못된 Pod/claim은 거절한다. Device Session을 실제로 교체한
  새 경로는409 DEVICE_STREAM_HANDOVER_REQUIRED이며 과거 checkpoint를 덮지 않는다.
- 실제 S3 원본 읽기 직후를 latch로 대기시켜도 다른 트랜잭션의 Run 취소가 완료된다.
  대기 작업을 풀면 현재 권한 재검사에서 거절되고 과거 확정본만 남는다.
- 실제 DB의 rollback 시험에서 정상 인계 레코드는 허용되지만 marker 누락·serial 건너뛰기·
  상태 해시/END/용량 제한 변경·현재 generation 불일치는 모두 거절된다. 직접 DB 시험의
  object는 제약 확인용 fixture이며 실제 저장소 확정과 구분한다.
- Java parser는 미확인 출력 frame의 producer/generation만 새 Attempt로 바꾸고 payload·순번·
  SHA·mediaType·커서·계산 상태를 그대로 보존하는지 대조한다. 변조 원본은 거절한다.

V23은 프로젝트 DB에 적용하기 전에 새 임시 PostgreSQL DB와 소유 MinIO에서 검증했다.
각 실행 뒤 DB·MinIO 프로세스·파일과 테스트 버킷의 모든 version을 정리했다.
이후 프로젝트 DB의 V23 적용 성공과 Flyway checksum `-1050126656`을 직접 조회했다.
V23 파일 SHA-256은 `275d9b29521662994cf0949fee727062c34b3ad9a6690bc037dadb45993d5505`다.
V21·V22 파일 해시와 적용 checksum은 이전과 같으며 V1–V22를 수정하지 않았다.
전체 S3 회귀도 소유 MinIO 프로세스와 임시 저장소 정리까지 통과했다.

실제 Swagger는 공개40개·내부 스트림8개 API의 계약을 그대로 표시한다. 인계 API의 한국어
역할·상태/순번 보존 설명·409 응답을 PC/모바일에서 펼쳐 확인했고 생성된 두 화면도 직접 검토했다.
가로 넘침·브라우저 오류·외부 요청·브라우저 저장소 기록은 없었다. 같은 API/UI 프로세스가
프로젝트 DB 중단 중503을 반환하고 DB 재시작 뒤 정상 복구했다.

## 발견한 실패와 수정

`045608Z-1191bb01`은 Java 기대값 Map의 Integer와 JSON 파싱 숫자 타입 차이로 비교가 실패했다.
실제 producer의 정규 JSON을 대조하도록 수정하고4개를 통과했다.
`050021Z-8c36078f`의11개 동작 시험은 통과했지만 버킷 정리가 실패했다. 새 서버가 작성한
파일도 정리하려고 version 목록을 조회하면서 recursive를 빠뜨려 하위 key를 누락한 원인이다.
소유 버킷 전체 version을 재귀 조회·수집한 뒤 삭제하고 비어 있음을 확인하도록 수정했다.
해당 실행의 외부 MinIO와 임시 DB는 finally에서 정리됐다.
재실행 `050134Z-5f3bea2e`는 설정된 loopback 포트 bind에서 실패했다. 실제 listener가 없음을
확인하고 임시 시험의 API/console 포트를 각각 OS가 배정하도록 바꿨다. 기존 서비스를 중단하지 않았다.
이후 위11개 및13개 실행에서 종료 정리까지 통과했다.

## 남은 전체 수용

실제 데이터 저장·HTTP 인증·재시도 DB 상태·독립 참조 계산 프로세스 시험이다. 실제 Kubernetes
Pod 종료/생성이나 운영 MQTT ACL 회수의 증거는 아니다. 출력 frame 변경은 Java parser 시험이며
새 세대의 실제 MQTT 재전송·Device/인접 Task journal 전환·공개 STREAM 종단은 후속이다.
합성 참조 계산은 실장비/실제 모델 수용을 대신하지 않는다. 이 변경의 원격 CI·배포는 별도 확인한다.
