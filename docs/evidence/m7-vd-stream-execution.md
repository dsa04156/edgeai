# M7 VD 스트리밍 제어·완료·복구

2026-10-04. ADR0055/V31–V32의 서버·DB·Swagger·화면을 연결했다.
아래 구성 요소 검증과 실제 VD 자식 Runner의 스트림 종단 수용은 구분한다.
새 VD 스트리밍의 실제 broker/S3/supervisor·Kubernetes·CI·배포 검증은 아직 남는다.

## 구현한 경계

- 공개 STREAM Run의 기본/작업별 AUTO·NODE·VD를 함께 사용한다. SERVICE 버전이 맞고
  Ready인 VD를 선택해야 한다. Task의 최초 대상과 retry의 직전 Attempt 대상을 유지한다.
- 하나의 스트림 그룹이 같은 VD에 요구하는 작업 수가 `maxConcurrentTasks`를 넘으면
  `409 VD_STREAM_CAPACITY`로 전체 생성 트랜잭션을 되돌린다. 실제 빈 slot을 보장하지는 않는다.
- Runner 인증 경로는 자기 VD → Device → Run 순서로 잠근다. Run 기본 VD나 다른 peer의
  VD를 추가로 잠그지 않는다. 완료 판단의 peer 검사는 Run 잠금 아래 기존 배정·세대·lease를
  읽는다. 각 작업의 자격 발급/체크포인트/Result 확정은 자기 VD 권한을 다시 확인한다.
- 같은 supervisor Pod의 두 작업도 다른 Attempt/토큰/slot을 갖는다. 다른 Attempt 경로에
  토큰을 보내면401이다. 성공 Result에는 실제 해당 VD runtime/Pod를 기록한다.
- 그룹 재시도는 이전 Node 실행과 VD 자식 프로세스가 모두 끝나고 broker 권한이 회수된 뒤
  새 Attempt를 만든다. 저장된 상태가 있으면 `HANDOVER`를 요구한다.
- 완료 허가 뒤 실패한 VD 작업은 기존의 봉인된 체크포인트로 최종 처리를 재시도한다.
  완료한 peer는 재실행하지 않는다. V32는 V26의 해당 모드 제한만 확장한다.
- Remote 스트리밍과 VD가 포함된 그룹의 위치 전환은 아직 지원하지 않는다.

## 직접 확인한 시험

원시 결과는 무시된 `docs/evidence/runs/<runId>/`에 보관한다.

| 검증 | runId | 확인 범위 |
|---|---|---|
| STREAM/route PostgreSQL | 20261003T211330Z-f3b2545d | PASS53개. 새 VD5개: 다른 VD 공동 완료·peer 잠금, 같은 Pod의 토큰 분리/용량 거절, VD↔Node 그룹 재시도, 자식 종료 전 취소 대기, VD 최종 처리 재시도 |
| PostgreSQL 전체 | 20261003T211440Z-6754e4a7 | PASS219개, 실패/오류/skip0. 클래스별 집계·migration SHA 보존 |
| 서버 단위·실행 JAR | 20261003T211754Z-703c1656 | PASS105개/bootJar. summary.json에 실제 JAR SHA 보존 |
| OpenAPI·MVC·Swagger | 20261003T211855Z-d093d089 | 계약5개/MVC26개 PASS, 생성 타입·패키징된 YAML 일치 |
| 실제 저장소·스트리밍 회귀 | 20261003T212010Z-a74a7f3a | 기존40개 PASS. 실제 Spring/PG/MinIO/TLS MQTT/SDK·독립 Runner와 BATCH VD/Remote. 새 VD 스트리밍 종단은 포함하지 않음 |
| 화면 lint/type/build·기존 회귀 | 20261003T211659Z-bba7fb82 | lint/type/build와 기존42개 PASS. 신규2개는 테스트의 option disabled 판정 오류로 실패 |
| 새 VD 스트리밍 PC/모바일 | 20261003T211914Z-0bb52701 | 수정 후2개 PASS. VD 선택·Remote 거절·서버 용량 오류 표시·동일 요청 키/입력 유지. 명시적 HTTP fixture |
| 실제 API/DB·Swagger·PC/모바일 | 20261003T212323Z-80951e84 | PASS10개. 한국어 VD STREAM/용량 제한 설명·패키징된 계약·실제 CSRF, 기존 관리 흐름. 이 서버는 실행 비활성 설정 |
| 로컬 V30→V32 업그레이드 | 20261003T212706Z-46c55521 | PASS. 기존 Task9,371개의 신원·정의·최초 대상 보존, 성공한 migration32개 |

DB 시험의 Pod 신원·프로세스 종료 보고·broker/S3 receipt는 명시적 fixture다. 그룹 retry 시험은
새 Runner가 `HANDOVER`를 요구하고 원본 체크포인트를 보존하는 경계까지 검증한다. 새 VD의
실제 상태 bytes 전송이나 실제 Kubernetes Pod 실행을 이 결과로 주장하지 않는다.
모바일의 추가 VD 선택 화면을 직접 확인했고 가로 넘침이 없는 것을 브라우저에서 검사했다.

## 시험으로 발견한 수정

- 최초 컴파일의 Result accessor/와일드카드 List 검증 오류를 실제 타입에 맞췄다.
- 재시도 fixture가 인계 없이 새 체크포인트를 삽입해 DB 제약에 거절됐다. 제약을 유지하고
  `HANDOVER` 요구와 원본 보존을 확인하도록 시험을 수정했다. 취소 경로에는 실제 production
  authority worker가 수행하는 route reconciliation을 명시적으로 실행했다.
- VD 최종 처리 재시도는 V26의 `AUTO/NODE` DB 제한으로 실제 실패했다
  (`20261003T211159Z-54bdc791`). 이미 격리 DB에 적용한 V31을 수정하지 않고 V32로 보완했다.
- 브라우저 시험은 option의 native disabled 속성을 검사하고, 오류 영역은 Next.js 전역
  알림을 제외한 main 내부로 한정했다. 제품의 비활성 조건이나 오류 표시는 바꾸지 않았다.

V31 SHA-256: `f7c0ed45000bbe6da2d6783b0a7244d3422e0f25daf04f956321a60f7b45c6f2`.
V32 SHA-256: `222fcd6f35d8e7af131511a2b735cdd0f3ede38ff3e2386d4ec0e5bd58ad03d3`.
로컬 Flyway checksum은 V31 `-1662476967`, V32 `1965438612`다.
적용된 V1–V32는 변경하지 않는다.

## 남은 수용 범위

실제 VD supervisor의 자식 Runner에서 TLS 배정·체크포인트 업로드·상태 인계·공동 완료·결과를
연결하고, 같은 VD/다른 VD/Node 혼합을 실제 Kubernetes에서 검증해야 한다. API/VD 교체,
물리 자식 종료·취소·그룹 복구·고정 S3 bytes/SHA/version·소유 자원 정리와 새 이미지 CI/배포가
필수다. Remote STREAM, VD 그룹 전환, 외부 장치 수용 및 M5 잔여/M8–M10도 남는다.
