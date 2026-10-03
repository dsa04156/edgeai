# M7 VD 스트리밍 제어·완료·복구

2026-10-04. ADR0055/V31–V32의 서버·DB·Swagger·화면을 연결했다.
서버·DB 시험에 이어 실제 VD supervisor/자식 Runner·TLS broker·S3 시험5개와
전체 실제 저장소 회귀45개를 통과했다. 후속 실제 Kubernetes6개/VD Pod14개·Node Pod1개/
고정 S3결과15개도 통과했다. 이어 VD 교체·Pod 유실·최종 처리 복구3개를 실제 Kubernetes에서
검증했다. 새 이미지 CI·배포는 남으며 최신 선행 CI의 저장소 시험은 실패했다.

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
- 이 ADR0055 시점에는 Remote 스트리밍과 VD 그룹 위치 전환을 지원하지 않았다.
  후속 수동 NODE 전환은 [ADR0056 검증](m7-vd-stream-group-offload.md)을 따른다. Remote STREAM은 남는다.

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
| 실제 VD supervisor/자식 STREAM | 20261003T214323Z-d9e57d1d | PASS5개. 같은 VD의 두 작업·다른 VD의 체인, 양쪽 그룹 재시도, 같은 VD 취소. 실제 Device SDK·Spring/PG/TLS MQTT/MinIO·VD poll·자식 프로세스와 고정 S3 결과28/37 |
| 전체 실제 저장소 회귀 | 20261003T214448Z-27d21841 | PASS45개, 실패/오류/skip0. 신규 VD5개와 기존40개를 같은 실행에서 확인 |
| 실제 Kubernetes VD STREAM | 20261003T215558Z-8e800c29 | PASS6개. 같은/다른 VD·VD→Node→VD·API Pod 교체·같은/다른 VD 자식 SIGKILL 후 상태 인계·취소, 실제 VD14Pods/Node1Pod/S3결과15개와 소유 자원 정리 |
| 기존 Kubernetes AUTO 회귀 | 20261003T220351Z-8031f85d | 변경된 공통 driver/fixture의 실제 AUTO·API 교체·Node3Pods/S3결과3개·소유 자원 정리 PASS |
| VD 교체·Pod 유실·최종 처리 복구 | 20261003T222247Z-bfb805ce | PASS3개/VD10Pods/S3결과9개. 같은 VD 공개 REPLACE·다른 VD의 sink Pod 삭제 후 PROVISION·완료 허가 뒤 sink 자식 SIGKILL. 이전 실행 종료 후 다음 세대, 상태 인계, 원본 완료 허가/체크포인트 보존과 소유 자원 정리 확인 |
| 실패 분류 수정 후 PostgreSQL 전체 | 20261003T222557Z-1fe8caf0 | PASS220개/21 suites, 실패·오류·skip0. summary.json에 집계 보존 |
| VD drain 결함 재현 | 20261003T221934Z-e7f14442 | 수정 전 회귀가 실제 RUNTIME_LOST 대신 CANCELLED를 관측해 실패. 실제 Kubernetes 교체 실패221255Z-1d1b7754와 같은 분류 경계 |
| VD drain 수정 후 회귀 | 20261003T222037Z-8f5b8d10 | STREAM/VDTask/route 실제 PG68개 PASS. 물리 종료 전 재시도 금지·같은 늦은 보고 재전송·사용자 취소 차단 확인 |
| 수정 후 단위·실행 JAR | 20261003T222142Z-fe49ce8b | PASS105개/bootJar. 추가 Kubernetes3개가 이 JAR을 사용 |
| 수정 후 전체 실제 저장소 | 20261003T222248Z-3e62e39b | PASS45개, 실패/오류/skip0. 선행 CI의 공유 VD 재시도 실패는 이 로컬 실행에서 재현되지 않음 |

추가 Kubernetes3개는 수정 JAR SHA
`af0096deaaba97cffbcf3296c049dd85c675278337ac2d4c12c6e7ff3ab45821`을 사용했다.
교체/Pod 유실 두 경우 모두 이전 supervisor의 물리 종료와 generation+1 순서를 확인하고
각 작업의 상태 인계2개를 검증했다. 같은 장치 SDK 객체가 각각2회 연결됐다.
최종 처리 복구는 기존 완료 허가·체크포인트 이력을 유지하며 새 계산을 열지 않고 sink만
새 Attempt에서 재개했다. 장치는 각각1회 연결을 유지했다. 모든 시험 소유 자원의 잔여 수는0이다.

소스b144c8b CI37157334661은 scaffold/runner 성공, storage 실패, images/gitops skipped다.
실패한 공유 VD 재시도 시험의 원인은 아직 확정하지 않았다. 아래 VD drain 실패 분류 결함과
같은 원인이라고 판단하지 않으며, 다음 CI에는 비밀값을 제외한 대기 단계·호출 위치·작업 상태를 남긴다.

DB 시험의 Pod 신원·프로세스 종료 보고·broker/S3 receipt는 명시적 fixture다. 그룹 retry 시험은
새 Runner가 `HANDOVER`를 요구하고 원본 체크포인트를 보존하는 경계까지 검증한다. 새 VD의
실제 상태 bytes 전송이나 실제 Kubernetes Pod 실행을 이 결과로 주장하지 않는다.
모바일의 추가 VD 선택 화면을 직접 확인했고 가로 넘침이 없는 것을 브라우저에서 검사했다.

새 supervisor 시험은 실제 `runner/vd.py`가 자식 Runner를 시작하고 HTTP poll로 종료를 보고한다.
그룹 실패 주입은 서버 lifecycle 호출이며 이전 자식 종료/slot 회수는 실제 프로세스와 poll로
확인한다. 두 장치의 같은 SDK 객체·센서 cursor가 유지되고 S3 상태9를 복원한 뒤 추가 입력으로
14/14 또는14/23과 하위 BATCH28/37을 만든다. 실제 고정 object version의 bytes/SHA/내용과
VD runtime/Pod provenance를 대조한다. 취소는 Result/하위 Attempt를 만들지 않으며 정상 종료 후
자식 디렉터리와 slot을 회수한다. 이 시험의 Kubernetes 제출·Pod 신원은 명시적 fixture다.

Kubernetes 후속 시험은 실제 TokenReview·VD supervisor·자식 프로세스를 사용한다. 첫 체크포인트의
상태9를 확인한 뒤 `/proc`의 정확한 Runner 명령/cwd를 대조하고 해당 시험의 sink 자식에만
SIGKILL을 보낸다. 실제 poll의 PROCESS_EXIT, 이전 두 runtime의 TERMINATED와 broker 세대
종료를 확인하고 같은 VD에서 새 Attempt의 불변 상태 인계2개를 대조한다. 최종값은 fanout28,
chain37이며 합성 입력이다. API Pod 교체 중에도 VD Pod·자식 프로세스를 유지한다.
전체 작업 종료 후 빈 자식 디렉터리/닫힌 slot과 아직 살아 있는 supervisor를 확인하고, 공개 drain
뒤 Pod/claim 제거를 확인한다. 이 시험은 현재 JAR SHA
`1a2eec760f3d49d61fc88b098904413b9a227abe67efca749f7937948c9e9616`을 기존 CI 이미지의
JRE에서 실행했다. 후속 VD 교체/최종 처리3개를 포함해 새 API 이미지 자체의 CI 기본 게이트는20개다.

## 시험으로 발견한 수정

- 실제 공개 VD 교체 중 supervisor가 자식을 종료하면 자식은 `CANCELLED`를 보고할 수 있다.
  이를 사용자 취소로 분류하면 `RUNTIME_LOST` 재시도가 발동하지 않아 스트림이 종료됐다.
  drain 중인 VD 실행의 해당 보고만 `RUNTIME_LOST`로 정규화했다. 실제 사용자 Run 취소는
  기존 producer 차단을 유지한다. 수정 후 전체 PG220개와 실제 Kubernetes 교체/복구3개가 통과했다.
- 최초 컴파일의 Result accessor/와일드카드 List 검증 오류를 실제 타입에 맞췄다.
- 재시도 fixture가 인계 없이 새 체크포인트를 삽입해 DB 제약에 거절됐다. 제약을 유지하고
  `HANDOVER` 요구와 원본 보존을 확인하도록 시험을 수정했다. 취소 경로에는 실제 production
  authority worker가 수행하는 route reconciliation을 명시적으로 실행했다.
- VD 최종 처리 재시도는 V26의 `AUTO/NODE` DB 제한으로 실제 실패했다
  (`20261003T211159Z-54bdc791`). 이미 격리 DB에 적용한 V31을 수정하지 않고 V32로 보완했다.
- 브라우저 시험은 option의 native disabled 속성을 검사하고, 오류 영역은 Next.js 전역
  알림을 제외한 main 내부로 한정했다. 제품의 비활성 조건이나 오류 표시는 바꾸지 않았다.
- 새 실제 VD 시험의 첫5개 중4개가 실패했다(213628Z-fd3be215). fanout 서비스에 연결하지
  않는 출력이 선언돼 요청이400이었고, 대기 helper가 성공한 retry 명령을 두 번 호출했다.
  fanout은 출력 없는 SERVICE로 선언하고 조건 결과를 한 번만 평가하도록 수정해5개/전체45개를 통과했다.
- Kubernetes 시험 초기 설정이 VD lease 상한60을 초과해 시작에 실패했다(215318Z-2d8a42ad).
  60초로 맞춘 다음 실행은 계산/자식 회수까지 통과했지만 VD 상세 응답의 `vd` 래퍼를 누락한
  drain 코드에서 실패했다(215421Z-a45d1e42). API 계약대로 수정한 최종6개가 모두 통과했다.
  실패한 시험의 VD Pod/claim 제거도 직접 확인했다.

V31 SHA-256: `f7c0ed45000bbe6da2d6783b0a7244d3422e0f25daf04f956321a60f7b45c6f2`.
V32 SHA-256: `222fcd6f35d8e7af131511a2b735cdd0f3ede38ff3e2386d4ec0e5bd58ad03d3`.
로컬 Flyway checksum은 V31 `-1662476967`, V32 `1965438612`다.
적용된 V1–V32는 변경하지 않는다.

## 남은 수용 범위

VD supervisor Pod 교체·유실 뒤 복원과 VD 최종 처리 실패의 실제 Kubernetes 수용은 위3개로
확인했다. 선행 CI의 공유 VD 재시도 실패 원인 확인과 새 수정의 이미지 CI/배포는 남는다.
Remote STREAM, VD 그룹 전환, 외부 장치 수용 및 M5 잔여/M8–M10도 남는다.
