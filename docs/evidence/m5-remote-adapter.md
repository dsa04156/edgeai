# M5 Remote 참조 경계 — 구성 요소 검증

범위는 ADR0010, domain.remote, ReferenceRemoteGateway, remote-reference-api.yaml,
simulator/remote_server.py다. 실제 별도 Python 프로세스와 HTTP/SQLite/파일을 사용한다.
이 adapter 단계에서는 공개 실행 경로에 연결하지 않았으며 M5 전체 완료가 아니다.
후속 영속 모델·결과 API/화면 연결의 검증은 `m5-remote-runtime.md`에 따로 기록한다.
실제 2세부 API가 제공되지 않았으므로 참조 프로토콜의 검증을 외부 시스템 수용으로 확대하지 않는다.

| testRunId (2026-10-02) | 직접 확인한 범위 |
|---|---|
| 20261002T114814Z-8bddd232 | 단위/MVC57 PASS/0. 이 중 실제 HTTP/TLS transport10개 |
| 20261002T115302Z-a05a2f22 | 별도 Python provider+SQLite+HTTP+파일 통합13개 PASS/0/skipped0 |
| 20261002T115228Z-376c1d31 | public/Runner/Remote 참조 OpenAPI 타입 생성, MVC18, 패키징된 Swagger 계약 일치 PASS/0 |

## 확인한 동작

- 동일 reserve/start 동시8개가 한 번만 계산한다. SQLite executions와 실제 출력score8.0을 확인한다.
  SERVICE에 선언한 고정 입력이 parameters의 다른 features보다 우선하며 SHA/bytes/mediaType을 검증한다.
- 예약 DB commit 후 응답만 지연해 client timeout을 실제 발생시키고 같은 identity의 GET/PUT으로
  복구한다. 할당/Attempt를 새로 만들거나 중복 실행하지 않는다.
- reserve보다 먼저 도착한 cancel은 영속 tombstone이다. 다른 Attempt/epoch 재사용과 늦은 start를
  거절한다. 실행 중 cancel은 실제 계산 중단 후 CANCELLED이며 마감 만료는 FAILED/LEASE_EXPIRED다.
- 프로세스를 강제 종료 후 같은 SQLite로 재시작했다. 완료 상태/파일/tombstone은 유지하며 진행 중
  계산은 PROVIDER_RESTART로 실패하고 자동 재실행하지 않는다. 예약 commit 전 남은 디렉터리도 복구한다.
- 잘못된 bearer는 상태를 만들지 못한다. 서버 재시작 없이 파일 자격 교체 후 요청이 성공한다.
  계산8개가 활성일 때9번째 start는503이고 ALLOCATED를 유지하며 drain 이후 재시작할 수 있다.
- 서버 디스크의 입력을 업로드 후 변조하면 start가 거절되고 실제 계산 횟수는0이다. 출력 크기 또는
  같은 크기의 내용 변조도 다운로드 SHA 검증에서 거절한다. 임시파일과 잘못된 결과를 남기지 않는다.
- 다운로드 중 대상 경로가 다른 파일로 생성되면 보존한다. 같은 파일시스템의 hard link 생성으로
  덮어쓰기 없는 발행을 원자적으로 수행하며, 이를 지원하지 않는 파일시스템에서는 실패로 처리한다.
- 자체 시험 인증서/실제 HTTPS 서버에서 전용 CA 신뢰는 성공하고 미신뢰 CA/다른 hostname은 실패한다.
  redirect는 따라가지 않으며 인증 헤더를 두 번째 요청으로 전달하지 않는다. 제공자 오류 본문을
  예외 message/cause에 포함하지 않는다. 매 요청 자격 파일을 다시 읽는다.
- metadata256KiB 초과, 중복 키, 뒤따르는 JSON, 다른 identity/sourceMode, 불가능한 상태/출력,
  안전 정수 범위 초과를 거절한다. 큰 숫자·유효 한국어/emoji는 보존하고 비정상 surrogate/NUL은 거절한다.
- HTTP 헤더 이후 본문이 멈춘 다운로드에도 RPC 전체250ms 제한을 적용하고 부분 파일을 제거한다.

## 발견·수정

첫 통합시험 실행은 테스트 함수형 인자 타입으로 compile 실패했다(113835Z-9ebc5ba3).
fixture 확장 중 wildcard Map 타입 오류도 수정했다(114038Z-89e9a4fd).
별도 회귀시험114349Z-032070d6에서 orphan directory 재예약500과 미선언 입력 허용을 재현했다.
소유 디렉터리의 멱등 생성 및 spec/input 대조 후13개 전체 통과했다.
단위시험114449Z-7ab1efc5는 잘못된 surrogate가 정규화에서 손실되는 문제를 검출했다.
Unicode 쌍 검증 후57개 전체 통과했다. 검증 기준을 줄이거나 실패 시험을 제외하지 않았다.

## 이 단계 이후의 필수 연결

1. 새 migration의 RemoteAllocation/runtime kind 및 명확한 producer identity. Pod/Node UID를 위조하지 않는다.
2. Run/Task/Offload API·UI의 REMOTE 선택, 영속 command/lease/reconciliation과 cancel-before-create 처리.
3. 고정 S3 입력 전송, 실제 Remote 출력→S3 및 현재 Attempt/epoch 재검사 후 원자적 Result 확정.
4. 원격 장애·취소·전환·재시도 경쟁/늦은 결과 차단을 실제 PostgreSQL·MinIO와 종단 시험.
5. 실제 제공자 endpoint/auth/계약 정합화와 실장비·모델 수용. 합성 실행을 이 증거로 대체하지 않는다.

1·3·4의 내부 모델/결과 경로는 후속 로컬 검증을 진행했으며 자동 worker/공개 API 종단은 아직 남아 있다.

## CI와 실제 배포 확인

CI scaffold/local/full에 test-remote를 포함했다. 코드0143094e8a90cb2cdd629815235a6d52c366dc91의
[Actions37003825328](https://github.com/dsa04156/edgeai/actions/runs/37003825328)은
scaffold/storage/runner/images/gitops 5 jobs 모두 success다. 내려받은 결과JSON15개 모두 PASS/0이며
참조 Remote13개, 실제 kind18Run·자동 CPU/MEMORY/LATENCY 전환·late401·cleanup0도 포함한다.
kind 증거는 20261002T120515Z-b7f6cbc2다. 이 kind 시험은 Kubernetes 회귀이며 Remote 자동 worker 시험은 아니다.

20261002T122443Z-92195a03에서 GitOps pin fe1f252와 API/Dashboard/MinIO 실제 imageID의 검증 digest 일치,
Ready·PVC Bound·ArgoSynced를 확인했다. 기존 공유 Traefik/Ingress status 문제로 aggregate health는
Progressing이다. V10–V11의 신규 플랫폼 연결 코드가 배포됐다는 뜻은 아니다.
기존 자동 전환 코드951c4bd의 실제 CI/배포 근거는 m5-automatic-offload.md에 별도로 기록한다.
