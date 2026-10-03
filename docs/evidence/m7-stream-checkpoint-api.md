# M7 인증 체크포인트 API·DB·S3 검증

2026-10-03 KST. [ADR0032](../adr/0032-stream-verified-checkpoint-api.md)의 범위다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 HTTP·SDK snapshot·PostgreSQL·MinIO 최초5개 | 20261003T035017Z-67852fd2 | PASS/0, 실패/skip0 |
| 정상 후속 체크포인트·과거 receipt·고정 version 포함6개 | 20261003T035221Z-6328534a | PASS/0, 5.708초·실패/skip0 |
| OpenAPI 생성·MVC·정확한 JAR 계약 패키징 | 20261003T035550Z-e0898d51 | PASS/0 |
| 단위·MVC 전체 | 20261003T035641Z-11896a1d | PASS/0, 88개·실패/skip0 |
| 실제 PostgreSQL 기존 통합 회귀 | 20261003T035704Z-1a3355e4 | PASS/0, 153개·실패/skip0 |
| 실제 S3·PostgreSQL·Remote/VD/Result·checkpoint 전체 | 20261003T035746Z-9ba087ae | PASS/0, 22개·실패/skip0 |
| 실제 DB/API PC·모바일 Swagger/관리 UI·DB 중단/복구 | 20261003T040311Z-388d25fe | PASS/0, 브라우저10개 |
| 실제 Spring·PostgreSQL·TLS broker/SDK 회귀 | 20261003T040552Z-19662541 | PASS/0, 22개·실패/skip0 |

## 직접 확인한 동작

- 독립 Python SDK가 실제 두 Device binding으로 snapshot을 만든다. 실제 HTTP uploads →
  signed PUT → version 지정 commit201 → 멱등200 → latest/download를 통과하고 원본 bytes와
  다운로드를 대조한다. 서비스 객체를 다시 만들어도 DB의 같은 receipt를 반환한다.
- 실제 두 HTTP 동시 commit은201/200 한 쌍과 DB 한 행을 만든다. UPDATE/DELETE/TRUNCATE는
  모두 거절된다. 해당 변경 거절 시험은 rollback 트랜잭션 안에서만 수행한다.
- 실제 SDK의 다음 계산 상태9→14와 revision1→2를 후속 확정한다. 이후 과거 확정 요청은409다.
  같은 파일을 중복 PUT한 두 version 중 최초 commit의 version만 receipt에 남는다.
- 같은 계산 revision의 상태 변경, 커서 후퇴, 다른 실행 설정 digest, 부분/다른 Task의
  generation 집합은 거절한다. 다른 Pod proof와 미인증 요청은401이다.
- 같은 길이의 변조 파일에 올바른 SHA metadata를 위조해도 실제 내용 검증으로400을 반환한다.
  결과 레코드는 생성되지 않는다.
- 실제 S3 검증 직후를 latch로 지연시킨 동안 Run 취소가 완료된다. S3 I/O가 Run 잠금을
  점유하지 않고, 지연된 commit은 두 번째 권한 검사에서 거절되며 이력도 생기지 않는다.
- 문서 파서는 실제 SDK 예제와 비정규 JSON·중복 키·잘못된 세대/SHA/순번/END/media/커서를
  검사한다. DB summary에는 raw stateBase64나 frames가 없다.
- 시험 전용 버킷의 object versions·버킷과 private key/임시 파일을 정리하고 소유 MinIO를
  종료했다. 기존 프로젝트 PostgreSQL 데이터는 유지한다.

최초 `20261003T034216Z-7d4b7a80`은 테스트 import와 BrokerReceipt 인자 오류로
compileTestJava에서 실패했다. 실제 API/DB가 실행되기 전이며 원인을 수정한 뒤 위 실제
통합시험을 통과했다. 검증기 단위시험의 최초 문자열 assertion은 `frames`와 `max_frames`를
구분하지 못했으며 정확한 JSON 키 검사로 수정했다.
Swagger 최초 `20261003T040145Z-e54f8416`은 설명 selector가 operation과 request 설명 두 영역을
동시에 선택하여 실패했다. operation별 의미 문구로 범위를 좁힌 뒤 PC/모바일10개와 DB 장애/
동일 프로세스 복구를 통과했다. 두 실제 screenshot에서7개 operation과 줄바꿈을 직접 확인했다.

V1–V21의 파일 bytes가 HEAD와 같음을 확인했다. 실제 DB의 V22 성공 checksum은
`-2138605945`, V22 파일 SHA-256은
`38d8feca5e6997a93710cc7b6b4864708e68ac37e3999ab17db49c4415d7d38c`다.

## 수용 경계

Pod 신원 gateway와 broker 활성화 receipt는 명시적 fixture다. Session의 자동 checkpoint
client나 실제 Kubernetes 다중 장치 실행의 증거는 아니다. 같은 binding 복원과 새 Attempt/
generation handover도 구분한다. 후자는409로 차단하며 후속 구현이 필요하다.
신규 서버 코드의 CI·이미지 배포는 별도 확인한다. 공개 STREAM 실행501은 유지한다.
