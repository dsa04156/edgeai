# M4 실행 구성 요소 — 진행 중

2026-10-02. M0–M3는 구현·검증 완료이며 M4는 아직 미완료다.
이 기록은 실행 규격·Job compiler·S3 adapter·독립 Runner와 실행 상태/결과 확정 서비스의 시험에 한정한다.
배포의 실행 활성화와 실제 Runner 전체 경로 검증 상태는 아래 최신 기록을 따른다.

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

1. 검증된 Runner·MinIO 이미지 배포, 영속 signing key·저장소 bucket 설정과 실행 활성화.
2. 실제 Runner의 BATCH 입력 전달과 취소·리소스 종료·누락/늦은 결과에 대한 종단 검증.
3. Result 공개 API·UI·Swagger와 실제 kind의 scheduler→Runner→MinIO→Result 수용시험.

presigned PUT의 checksum 헤더는 서명하지만 SDK는 Content-Length/Content-Type을 서명에서
제외한다. 현재 내용·크기·형식 검증은 commit 전에 수행하며, 업로드 전 정확한 크기 제한을
보장하지 않는다. 저장소 quota 또는 별도 업로드 제한은 후속 보강 대상이다.

CI에 실제 MinIO artifact 검증·Runner 컨테이너 job·PostgreSQL과 S3를 함께 사용하는 결과 검증을 추가했다.
로컬 Docker 권한 제한은 유지한다. V5 서비스 코드의 CI·새 배포 상태 확인은 아래 기록을 따른다.
`test-kind.sh`와 `demo-workflow.sh`는 전체 실행 경로가 없어 계속 BLOCKED다.
전체 플랫폼의 LOCAL_VERIFIED/FULL_ACCEPTANCE를 주장하지 않는다.

## V5 코드의 CI·배포 확인

`5992cdc`의 Actions 36978182298은 5 jobs success, 다운로드한 4 artifacts의 결과 JSON
13개 PASS/0다. Actions pin `bc061e7`, 실제 API imageID
`sha256:c08052d470d4297016883c63d30a4c01d07dbef9d3ac11e21619a410a4fd7e61` 일치.
`20261002T074736Z-451996e7`: API/UI/PG Ready, PVC Bound, Argo Synced.
기존 Ingress status 제한으로 aggregate health는 Progressing이다.

## 내부 Runner API와 Kubernetes worker

별도 Spring security chain은 Attempt HMAC과 Pod-bound token을 함께 요구한다.
TokenReview의 audience·ServiceAccount·Pod UID와 실제 Pod/Job/Node를 대조한다.
명령 worker는 결정적 Job/Secret·UID 전제 삭제·lease 재시도·전체 목록과 watch/relist를 사용한다.
신규 Run의 root와 결과 확정 후 하위 Runtime 명령을 해당 DB 트랜잭션에서 함께 저장한다.
기존 M3 Run은 자동 실행하지 않는다. 실행 플래그 기본값 false와 실제 배포 비활성을 유지한다.

| testRunId | 확인 범위 | 결과 |
|---|---|---|
| 20261002T074735Z-3758e1db | 단위35, HTTP fixture의 생성 응답 유실·TokenReview·소유권·UID 삭제·watch410 | PASS/0 |
| 20261002T075659Z-ba344c36 | 실제 PG35, 내부 인증·claim/uploads/commit/fail·Basic 거절·취소 fence·본문 제한 | PASS/0 |
| 20261002T075758Z-71ca2adf | 실제 PG41, 새 worker 재시도·실제 종료 확인 전 CANCELLING·늦은 Job 재삭제·누락 Result·deadline·snapshot 경쟁 | PASS/0 |
| 20261002T075817Z-911002f8 | 단위35, projected token audience·유효기간·파일 권한 포함 | PASS/0 |
| 20261002T080018Z-573f6476 | 실제 K8 2개, 제한된 SA/TLS·AUTO/NODE scheduler·Pod TokenReview·unbound token 거절·watch·Job/Pod/Secret 종료 확인 | PASS/0 |
| 20261002T080137Z-48c060c7 | 공개 계약 타입·내부 Runner OpenAPI·MVC·패키징 계약 | PASS/0 |
| 20261002T080335Z-a9f9cfa9 | 호스트 Runner7, 별도 Pod token 요청·비노출·실제 subprocess·실패/timeout | PASS/0 |

실제 K8 시험은 `edgeai-runtimes` 소유 namespace에서 digest 고정 PostgreSQL 이미지의 `sleep`
컨테이너를 실행했다. 생성한 Attempt별 Job/Pod/Secret은 시험 종료 전에 삭제했으며 운영 API/DB는
변경하지 않았다. fixture workload이므로 Runner→MinIO→Result 종단 시험은 아니다.
namespace/SA/RBAC는 후속 실행을 위해 유지한다. 기존 namespace·RBAC 소유 labels를 검사하며
서로 다른 scope의 리소스를 변경하지 않는다. 제어 서버 SA로 TLS API에 접근하고 Pod token은
메모리에서만 사용했다. 시험용 제어 서버 token/CA 파일은 mode600 임시 디렉터리에서 삭제했다.

최초 내부 API 시험 20261002T075046Z-2adb8673은 Mockito 재설정 중 기존 Answer가 null 인수를
받아 실패했다. `doThrow` 방식으로 예외 fixture를 설정해 원래 HTTP 거절 경로를 재검증했다.
실제 K8 gateway 시험 외의 worker/HTTP DB 시험은 Kubernetes 신원·저장소 응답이 fixture다.
Runner·MinIO CI는 시험한 정확한 컨테이너를 GHCR에 발행하고 release.json에 digest와
검증 플랫폼 linux/amd64를 함께 기록하도록 확장했다. 이번 변경의 신규 CI·배포는 아직 확인 전이다.

## Runner 연결 코드 CI·배포와 Result 조회

`9d15fb1`의 Actions36981974775는 최종success, 5 jobs success, 4개 검증 artifact의 결과JSON
13개 PASS/0이다. `4f3d718`이 API/UI/Runner/MinIO digest를 기록했다. Runner와 MinIO manifest
익명 조회200을 확인했다. 처음 전체 artifact 다운로드는 Buildx `.dockerbuild` 파일을 ZIP으로
풀지 못했으며, 네 검증 artifact를 이름으로 지정해 모두 다시 받고 결과JSON을 확인했다.
`20261002T081450Z-85e0a326`: 실제 API/UI imageID와 pin 일치, Ready, PG/PVC, Argo Synced.
`20261002T081450Z-ba7da831`: 실제 Ingress Swagger·Profile·Device·Workflow 회귀 PASS/0.
최초 배포 상태 검사는 rollout 중 이전 imageID를 읽어 실패했으며 rollout 완료 후 같은 검사를 통과했다.
이 시점에는 runtime 실행 설정이 비활성이다. 기존 aggregate health Progressing 제한을 유지한다.

ResultController/Service/DTO와 `GET /api/v1/tasks/{taskId}/results`, Swagger28개, 작업별 결과
화면을 추가했다. Task가 있지만 확정 결과가 없으면200/빈items, 없는Task는404, DB장애는503이다.
결과는 고정 version·실제 검증 checksum/크기/형식 메타데이터이며 인증 토큰·서명URL은 반환하지 않는다.

| testRunId | 범위 | 결과 |
|---|---|---|
| 20261002T080933Z-ef0e6d9d | 실제PG41, 실제 인증/API·결과 전후 조회·404·artifact 메타데이터 | PASS/0 |
| 20261002T081048Z-7c7a4c71 | Result 포함 계약·타입·MVC | PASS/0 |
| 20261002T081438Z-911c64c6 | 단위37, Result 인증·입력400·저장소503 포함 | PASS/0 |
| 20261002T081450Z-31381e93 | UI lint/types/build·PC/모바일16, 결과 대기/확정/오류 UI fixture | PASS/0 |
| 20261002T081525Z-d0c37018 | 실제DB/API·PC/모바일8·Swagger28·Result DB장애503·같은 프로세스 복구 | PASS/0 |
| 20261002T080620Z-bb443558 / 080620Z-6b353bcb | 격리 로컬 MinIO artifact bucket 초기화·재실행 | PASS/0 |
| 20261002T081201Z-aba15b4a | 실제K8 MinIO의 비공개·소유 태그·versioning bucket | PASS/0 |

Result API 첫404시험은 컨트롤러의 예외 처리 범위 누락을 발견해 전용 ResultExceptionHandler로 고쳤다.
UI 첫시험은 Next route announcer와 애플리케이션 alert를 구분하도록 오류 선택자를 수정했다.
Swagger 첫회귀는 실제28개 operation에 기존27개 기대값을 적용한 실패였고 신규 Result 설명 검증과 함께 고쳤다.
PC/모바일 결과 UI screenshot을 직접 확인했다. 결과 표시 UI fixture를 실제 Runner 결과로 주장하지 않는다.

전용 signing/storage Secret은 기존 값을 유지하며 mode600 복구 파일을 기록한다. MinIO는 CI에서
검증한 image digest와 전용5Gi PVC로 실제 클러스터에 초기 배포했다. API key mount/스토리지 주소와
runtime 활성화 GitOps 설정은 server dry-run을 통과했으며 실제 활성화와 full Runner 수용시험은 다음 검증이다.
`smoke-runtime.py`와 실제 버전/내용 검증 스크립트를 준비했다. 아직 실행 성공 증거는 없다.
