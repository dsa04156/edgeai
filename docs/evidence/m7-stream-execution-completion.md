# M7 서버 스트림 실행 배정·공동 완료 검증

2026-10-03 KST. [ADR0038](../adr/0038-stream-execution-completion.md).
M7 전체나 공개 STREAM 실행 완료가 아니다. 공개 요청501은 유지한다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 신규 실제 PostgreSQL·서비스 | 20261003T070620Z-fe989b33 | PASS/0, 8개 |
| 실제 HTTP 인증·DB 포함 신규 시험 | 20261003T070853Z-3f909dea | PASS/0, 9개 |
| 전체 PostgreSQL 회귀·신규 V24 | 20261003T071104Z-bc10261b | PASS/0, 163개·실패/skip0 |
| 단위·그룹 축약 교착/누락/용량 검사 | 20261003T071421Z-f90e9ff0 | PASS/0, 98개·실패/skip0 |
| 실제 SDK/HTTP/PG/MinIO 체크포인트 | 20261003T071716Z-d52e4803 | PASS/0, 14개 |
| 실제 S3/PG·Result/Remote/VD 전체 회귀 | 20261003T071831Z-bdfd6fe1 | PASS/0, 30개·실패/skip0 |
| OpenAPI 5종·MVC/Swagger·패키징 | 20261003T072136Z-5f75cadc | PASS/0 |
| 실제 Spring/PG/TLS broker·DeviceSource 회귀 | 20261003T072216Z-6e5747aa | PASS/0, 23개·실패/skip0 |
| 실제 API/DB·PC/모바일·Swagger | 20261003T072442Z-2ed739dd | PASS/0, 10개·51.2초 |

V24는 각 시험이 소유한 빈 PostgreSQL DB에서 먼저 적용·검증했고 시험 후 해당 DB를 삭제했다.
위 격리 시험 동안 프로젝트 DB를 변경하지 않았다. V1–V23은 Git의 원래 bytes와 일치한다.
V24 SHA-256은 `857e0eab074587bfc4c143f852194cc88e0dfcf3ada3b5d0265d34e7e1266cb1`이다.
JSON Schema와 실제 제공되는 OpenAPI의 6개 실행/완료 스키마가 같으며 내부11개 API임을 검증했다.
PC·모바일에서 새3개 설명을 펼쳐 역할·409 응답을 확인하고 화면도 직접 검토했다.
브라우저 오류·외부 요청·자격 영속 저장·가로 넘침이 없었다. 이번 회차는 DB 재시작 시험을 반복하지 않았다.
격리 검증 후 프로젝트 DB에도 V24를 적용했다. 직접 읽은 Flyway 이력은 success=true,
V24 checksum `-1357529490`이다. V21/V22/V23 checksum은 각각901037868/-2138605945/-1050126656으로 유지된다.
이후 V24도 적용된 불변 migration으로 취급한다.

## 검증한 동작

- 전체 route 고정, SERVICE live 포트의 실제 generation 배정, 동일 Attempt RESTORE 판정.
- 마지막 출력 처리 확인 전 보고 거절, 보고한 checkpoint의 추가 변경 거절.
- 연결된 두 Task와 Device가 모두 확인하기 전 Result 확정 거절. 독립 그룹은 진행 가능하며
  같은 Device fanout은 함께 기다린다. 생산자·소비자 종료 순번 불일치는 grant를 만들지 않는다.
- 동시 보고 6개가 하나의 허가 시각으로 수렴한다. checkpoint 참조나 이미 기록된 허가를
  바꾸거나 지울 수 없다. 취소 후 늦은 완료도 거절한다.
- 실제 HTTP에서 Attempt 범위 토큰과 Pod 증명, Device 세션 토큰을 적용한다. 다른 Attempt,
  Pod 증명 누락·필드 추가·세션 교체를 거절한다. peer 성공·경로 회수 이후 동일 현재 producer의
  FINALIZE 재조회와 Run 성공 뒤 동일 Device 세션의 재조회는 통과한다.
- 실제 Python Journal이 DATA와 END를 받아 만든 checkpoint를 실제 MinIO에 업로드하고,
  서버가 고정 version/SHA/커서를 검증하여 DB에 확정한다. 종료 전 snapshot은 거절하고
  종료 snapshot과 두 Device 확인이 모이면 grant를 기록한다. terminal 보고 후 업로드는409이며
  같은 commit 재전송은200이다. 그 후 Result 준비가 허용된다.

첫 9개 시험의 Runtime 상태·broker receipt·S3 metadata는 명시적 fixture다. HTTP 요청과
인증/DB 트랜잭션은 실제다. 마지막 14개는 실제 SDK·HTTP·PG·S3를 사용하지만 Pod 신원과
broker activation은 fixture이고, 새 완료 허가는 서비스 호출로 검증한다. TLS MQTT·모델·
Kubernetes·전체 Runner를 한 번에 연결한 스트림 종단 시험이라고 주장하지 않는다.

## 실패와 수정

- `070443Z-610f3fbc`: fixture Run 해시가 sha256 접두사 규칙을 어겼다. 기존 digest helper를
  사용하게 고쳤다. 새 V24 route 해시도 같은 helper 형식으로 정의하고 새 빈 DB에서 재검증했다.
- `071312Z-165ecbbd`: 새 순수 계획 시험 fixture에서 Task 원본 Profile ID를 빠뜨렸다.
  도메인 불변 조건에 맞는 fixture로 수정한 뒤 단위98개가 통과했다.
- `071605Z-0dc8b2ab`: Python fixture가 없는 Journal.revision 속성을 사용했다.
  실제 checkpoint().revision으로 수정했다. 같은 실행에서 새 FK 때문에 기존 단독 TRUNCATE가
  보호 trigger 전에 PostgreSQL 0A000으로 거절됐고 Hikari가 연결을 닫아 rollback도 실패했다.
  참조 completion 테이블을 함께 지정하여 불변 이력 trigger를 실제로 검증하도록 고쳤다.
  후속14개 전체가 통과했다. 실패 이력을 성공으로 바꾸지 않는다.

## 선행 Runner 코드의 CI와 배포

source `5f0bae556e089ae9ddbc91a7e7a57341eb72090b`의 CI37103460164는 5 jobs 모두 success,
다운로드한 결과 JSON17개 모두 PASS/0이다. 실제 Runner 컨테이너102개(063347Z-28034b83),
HTTPS/TLS MQTT58개(063545Z-cae4f0e1), kind064636Z-664f9771의 기존 BATCH/Retry/Offload/
TLS Remote/VD·고정 S3결과20+5와 소유 cluster edgeai-ci-6af49150d094 삭제를 직접 확인했다.
GitOps c63dde0과 배포071202Z-53439c81에서 API/dashboard/MinIO 정확한 imageID·Ready·PVCBound·
ArgoSynced·VD활성화를 확인했다. 공유 Ingress로 aggregate health는 Progressing이다.
이는 이번 V24/서버 완료 코드의 CI·배포 증거가 아니다.

DeviceSource 완료 대기와 공개 provisioning·동시 시작·봉인된 최종 상태 복구·운영 TLS·UI·
실제 Kubernetes 다중 장치 수용, M5 잔여와 M8–M10은 계속 남아 있다.
