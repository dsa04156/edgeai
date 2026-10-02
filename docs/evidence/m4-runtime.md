# M4 실행 구성 요소 — 진행 중

2026-10-02. M0–M3는 구현·검증 완료이며 M4는 아직 미완료다.
이 기록은 실행 규격·Job compiler·S3 adapter·독립 Runner와 실행 상태/결과 확정 서비스의 시험에 한정한다.
Dashboard의 Run 요청은 아직 실제 Kubernetes workload를 시작하지 않는다.

## 구현

- SERVICE 실행 JSON Schema와 엄격한 소비 parser: digest 이미지, command/args, 입출력,
  자원/QoS, Linux amd64/arm64, node selector/tolerations/runtime class, 시간·작업 공간 제한.
- 순수 Kubernetes Job compiler: AUTO 요구조건, NODE required affinity, scheduler bind 유지,
  Attempt별 결정적 이름, backoff 0, timeout, 비루트·읽기 전용 root·token 자동 마운트 금지.
- MinIO SDK 9.0.3 기반 artifact adapter: 고정 bucket/key, 필수 versioning, 서명 PUT,
  실제 object version의 길이·형식·내용 SHA-256 검증, version 고정 다운로드, 오류 내용 비식별화.
- 독립 Python Runner: claim/입력 검증/실제 자식 프로세스/출력 전송/commit 요청,
  timeout·SIGTERM 취소, 고정 오류 코드, 큰 정수·소수 보존, 누락·링크 출력 거절.
- 별도 내부 OpenAPI와 참조 Runner 이미지. SERVICE 예시의 0 digest는 자리표시자다.
  CPU 참조 계산은 SYNTHETIC이며 학습 모델·실장비 성능 증거가 아니다.
- V5 RuntimeInstance·명령 lease·봉인된 Result/Artifact, Run 잠금 기반 상태 전이와 producer fencing.
  저장소 I/O 전후의 분리된 트랜잭션, 멱등 결과 확정, BATCH 하위 해제·실패/취소 전파.

## 확인한 로컬 증거

모든 행은 `docs/evidence/runs/<testRunId>/result.json`의 PASS/exit 0이다.
원시 로그는 Git에 올리지 않으며 검증 환경에 보존한다.

| testRunId | 범위 |
|---|---|
| 20261002T062558Z-d9d7ed6c | 단위/MVC 30개, 실행 규격·자원·AUTO/NODE compiler 포함 |
| 20261002T063221Z-b085584c | 공개 OpenAPI 생성 타입·패키징 일치, 내부 Runner OpenAPI 파싱 |
| 20261002T063221Z-0f99b9cf | 호스트 Python Runner 7개: 실제 subprocess와 격리 HTTP fixture |
| 20261002T062841Z-2e4cca4b | 실제 Kubernetes API의 AUTO/NODE Job server dry-run |
| 20261002T064010Z-1aeb05aa | 실제 MinIO 4개: 버전 고정·변조·형식·위조 metadata·체크섬 거절 |
| 20261002T064042Z-fbbfc1a4 | 새 SDK 의존성을 포함한 기존 실제 PostgreSQL 통합 회귀 |
| 20261002T064241Z-4f1b6aad | 실제 DB/API·PC/모바일 8개와 DB 중단 503·동일 앱 프로세스 복구 |

server dry-run은 단위 시험이 생성한 Job JSON을 소유 label이 확인된 edgeai namespace에
제출했다. 리소스는 생성하지 않았으며 실제 scheduler bind나 workload 실행의 증거가 아니다.
Runner HTTP fixture의 claim 거절·commit 재전송은 실제 Control Plane의 fencing·멱등성을
증명하지 않는다. 별도 PostgreSQL 시험이 서비스/DB 경계의 fencing과 멱등성을 검증하며,
실제 Kubernetes 신원 검증과 내부 HTTP 인증 연결은 후속 작업이다.

## 첫 구성 요소의 CI·배포

코드 `640e995`의 [CI 36975219681](https://github.com/dsa04156/edgeai/actions/runs/36975219681)은
scaffold/storage/runner/images/gitops 모두 success다. 다운로드한 4개 artifact의 결과 JSON
12개가 모두 PASS/0이며 실제 Runner 이미지 시험 `20261002T064708Z-448290c4`를 포함한다.
Actions의 `3a406b4`가 검증한 digest를 기록했고 실제 Pod imageID와 일치했다.
`20261002T070650Z-e5ac612e`: API/UI/PG Ready, PVC 5Gi Bound, Argo Synced.
`20261002T070803Z-c9028df2`: 실제 Ingress Profile/Device/Workflow·Swagger·인증·CSRF 회귀 PASS/0.
기존 Traefik Ingress status 문제로 Argo aggregate health는 Progressing이다.

위 CI·배포는 V5 실행 상태/결과 확정 서비스가 추가되기 전 구성 요소 코드의 증거다.
새 서비스의 로컬 검증과 후속 CI는 아래에 구분한다.

## 실행 상태와 실제 결과 확정

`20261002T071722Z-c1c1cdda`: 실제 PostgreSQL 31개(기존19 + Runtime12) PASS/0.
동시 계획 1개·Pod claim 단일 소유자·명령 lease 만료/회수·이전 소유자의 완료 거절,
중복 결과 1개·봉인 뒤 변경/추가 거절·하위 Attempt 1개·DB 쓰기 실패 rollback,
외부 I/O 중 취소·늦은 결과 차단·독립 분기 보존·NODE UID/이름·만료를 확인했다.
취소 뒤 DELETE가 Job 부재를 관측한 후 늦은 CREATE 응답이 도착하는 경합도 시험했다.
그 경우 관측 상태를 SUBMITTED로 되돌리고 삭제 명령을 다시 열어 삭제 요청 유실을 막는다.
필수 입력 누락·포트 크기 불일치·실행 규격이 아닌 기존 Profile은 Runtime/dispatch 상태를 만들기 전에 거절한다.

`20261002T071417Z-a83d781c`: 실제 MinIO+PostgreSQL 통합 2개 PASS/0.
서명 업로드·실제 byte 검증·DB 결과 봉인·중복 commit·고정 버전의 하위 입력 다운로드·두 작업 완료 후
Run SUCCEEDED를 확인했다. 위조 metadata를 실제 내용 검증으로 거절하고, 실제 S3 읽기 후 취소가
먼저 반영되면 결과를 저장하지 않는 것도 확인했다. Job/Pod는 이 시험에서 신원 fixture다.
`20261002T071606Z-b5e93c5e`: 공개/내부 OpenAPI 및 현재 MVC 계약 PASS/0.
`20261002T071756Z-ee0a87b9`: 새 V5·현재 JAR의 실제 DB/API·PC/모바일 8개,
DB 중단 503과 동일 앱 프로세스 복구 PASS/0. 프런트엔드 소스가 같아 기존 빌드를 사용했다.

PostgreSQL 단독 시험의 Pod 신원과 artifact receipt는 fixture다. 별도 실제 MinIO+PostgreSQL
시험은 저장소에서 읽은 byte 검증과 결과 확정, 고정 version의 하위 입력 전달,
위조 metadata·파일 검증 중 취소를 다룬다. 시험 전용 S3 bucket/version은 종료 때 제거하므로
보존된 시험 DB 행은 운영 artifact가 아니다.
추가 오류 입력 시험의 최초 fixture는 빈 spec을 사용해 registry 단계에서 거절됐다.
등록 가능한 비어 있지 않은 임의 JSON으로 고쳐 실행 소비 단계의 거절과 QUEUED 보존을 검증했다.

## 실패 원인과 수정

- MinIO SDK 9.0.3의 API와 import/버전 설정/업로드 입력/close 계약을 맞추고 dependency lock을 갱신했다.
  최초 저장소 실행은 lock 누락으로 실패했고 이후 빌드·실제 저장소 시험이 통과했다.
- 최초 presigned PUT은 `x-amz-meta-sha256`을 서명하지 않아 400 AccessDenied였다.
  해당 헤더와 SHA-256 checksum 헤더를 서명한 뒤 실제 PUT/검증/다운로드가 통과했다.
- 변조 PUT은 처음부터 HTTP 400으로 거절됐으나 시험이 `BadDigest`를 예상해 실패했다.
  실제 응답의 비밀정보 없는 코드 `XAmzContentChecksumMismatch`를 확인해 assertion을 수정했다.
  최종 4개 시험에서 정상 업로드와 변조 거절을 함께 확인했다.

## 남은 M4 작업과 제한

1. 전용 runtime namespace·RBAC, 영속 명령을 소비하는 실제 Job/Secret 생성·UID 대조·watch/relist·재시작 reconciliation.
2. 내부 인증·실제 Pod UID 확인·claim/업로드/결과 HTTP API와 구현한 트랜잭션 서비스 연결.
3. 실제 Runner의 BATCH 입력 전달과 취소·리소스 종료·누락/늦은 결과에 대한 종단 검증.
4. Result 공개 API·UI·Swagger와 실제 kind의 scheduler→Runner→MinIO→Result 수용시험.

presigned PUT의 checksum 헤더는 서명하지만 SDK는 Content-Length/Content-Type을 서명에서
제외한다. 현재 내용·크기·형식 검증은 commit 전에 수행하며, 업로드 전 정확한 크기 제한을
보장하지 않는다. 저장소 quota 또는 별도 업로드 제한은 후속 보강 대상이다.

CI에 실제 MinIO artifact 검증·Runner 컨테이너 job·PostgreSQL과 S3를 함께 사용하는 결과 검증을 추가했다.
로컬 Docker 권한 제한은 유지한다. V5 서비스 추가 이후의 CI·새 배포 검증은 아직 진행 전이다.
`test-kind.sh`와 `demo-workflow.sh`는 전체 실행 경로가 없어 계속 BLOCKED다.
전체 플랫폼의 LOCAL_VERIFIED/FULL_ACCEPTANCE를 주장하지 않는다.
