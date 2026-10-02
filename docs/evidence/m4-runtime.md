# M4 Kubernetes 실행·검증된 결과 — 완료

2026-10-02. M0–M4 구현·검증 완료. 전체 M0–M10 목표는 계속 진행 중이다.
아래는 구성 요소부터 실제 종단 실행까지의 기록이며 최신 완료 판정은 마지막 절을 따른다.

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

1. 실제 Runner의 BATCH 입력 전달과 취소·리소스 종료·누락/늦은 결과에 대한 종단 검증.
2. 실제 kind의 scheduler→Runner→MinIO→Result 및 재시작/producer fault 수용시험.
3. 실행 게이트 통과 후 새 이미지의 실제 배포 회귀.

presigned PUT의 checksum 헤더는 서명하지만 SDK는 Content-Length/Content-Type을 서명에서
제외한다. 현재 내용·크기·형식 검증은 commit 전에 수행하며, 업로드 전 정확한 크기 제한을
보장하지 않는다. 저장소 quota 또는 별도 업로드 제한은 후속 보강 대상이다.

CI에 실제 MinIO artifact 검증·Runner 컨테이너 job·PostgreSQL과 S3를 함께 사용하는 결과 검증을 추가했다.
로컬 Docker 권한 제한은 유지한다. V5 서비스 코드의 CI·새 배포 상태 확인은 아래 기록을 따른다.
`test-kind.sh`와 `demo-workflow.sh`는 아래 최신 기록의 실제 시험 구현으로 교체했으며 성공은 아직 검증 전이다.
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

## Result 배포·실제 Runner 첫 실행과 kind 게이트

`2cbaf36`의 [CI36983413818](https://github.com/dsa04156/edgeai/actions/runs/36983413818)은
5 jobs success, 다운로드한 검증 결과JSON13개 PASS/0이다. pin `eb33bfd`와 실제 API/UI/MinIO
Pod imageID가 일치하고 Ready였다. 소유 namespace/RBAC, 영속 키, private/versioned bucket과
runtime=true 배포 설정이 반영됐다. M4의 완전한 실행 수용시험을 통과했다는 뜻은 아니다.

`20261002T082855Z-4eb06c3e`: 실제 AUTO Run의 root는 SUCCEEDED·Result 확정, child는
producer claim 없이 JOB_FAILED였다. `20261002T082942Z-d3237537` 재실행에서는 root가
`RUNNER_FAILED FENCED`, producer claim 없음·JOB_FAILED였다. 두 실패 모두 해당 리소스는 정리됐다.
삭제 전 고정 Runner 이벤트만 읽도록 observer를 추가했으며 토큰/서명URL/작업 payload는 수집하지 않는다.

Pod 프로세스가 먼저 시작하고 kubelet의 Pending→Running 반영이 늦으면 기존 gateway가 401을
반환하고 Runner가 영구 fence로 종료하는 경로를 코드와 HTTP fixture에서 확인했다. Pending 신원은
503으로 재시도하고 Running에서만 기존 Job/Pod/Node 검사를 통과시킨다. 잘못된 token·삭제 중·종료된
Pod는 계속 거절한다. claim만 기존30초 deadline 안에서 backoff 재시도하며 다른 요청의 횟수는 유지한다.
실제 두 실패의 원인 여부와 수정 효과는 새 이미지의 클러스터 재검증으로 확인해야 한다.

`20261002T083045Z-97e992b4`: Java 단위38개 PASS/0, Pending→Running 재시도와 종료 Pod 거절 포함.
`20261002T083046Z-83cf4467`: Python Runner8개 PASS/0, 연속4회503 후 실제 workload 단1회 실행·commit 포함.
`20261002T082908Z-3ea0cda4`: 로컬 Docker 권한 제한을 재확인, kind preflight BLOCKED/2.

kind용 임시 kubeconfig·별도 Secret 복구 경로·3노드·실제 이미지/S3/DB와 정리 스크립트를 구현했다.
CI는 새 kind 게이트 통과 후에만 API/화면 이미지를 발행한다. API 재시작·같은Job/Attempt 보존,
wrong version/size/hash·동시 commit·다른 Pod 신원·취소 뒤 늦은 commit 시험도 추가했다.
이 변경의 새 CI/kind 및 실제 배포 재검증은 아직 수행 전이다.

`494ae37`에 재시도 수정과 실제 kind CI 게이트를 push했고 Actions36984655502에서 검증 중이다.
`20261002T083220Z-41a1c38e`: 변경 후 OpenAPI/내부 계약·MVC PASS/0.
`20261002T083613Z-1ba364be`: 실제 배포 PC1440/모바일390 화면에서 첫 Run의 성공한 root Result
ID·79byte·SHA·version을 공개 API와 대조하고 수평 넘침이 없음을 확인했다. 같은 고정 버전의 MinIO
파일을 다시 읽어 실제 SHA/크기/계산값(score0.25, features[2,1])도 일치했다. screenshot 두 장을
직접 검토했고 `output/playwright/real-result-{desktop,mobile}.png`에 보존했다.
이 Result는 실패한 전체 DAG 중 성공한 root의 결과다. 하위 실행·전체 Run 성공을 증명하지 않는다.
최초 S3 재조회는 MinIO rollout으로 기존 port-forward 연결이 끝나 실패했다. 종료된 handle과
연결 거절을 확인하고 새 Pod에 재연결한 뒤 동일 object/version 검증을 통과했다.

`20261002T083849Z-3a33a3a2`: 수정 gateway의 실제 Kubernetes2개 회귀 PASS/0.
제한된 SA/TLS·AUTO/NODE 배치·실제 Pod token·watch·UID 기반 Job/Pod/Secret 종료를 다시 확인했다.
대기 컨테이너 시험이므로 새 Runner 전체 경로를 대신하지 않는다. 별도 CI36984655502의 kind 시험이 진행 중이다.

## 실제 kind·기존 클러스터 전체 실행 통과

`494ae37`의 [CI36984655502](https://github.com/dsa04156/edgeai/actions/runs/36984655502)은
5 jobs success, 다운로드한 결과JSON14개 PASS/0이다. kind `20261002T083920Z-edc458f0`의 원시
로그와 `kind-runtime.json`을 직접 확인했다. 임의 이름의 전용3노드 클러스터·별도 DB/S3/키에서:

- AUTO/NODE 각각 2단계 BATCH, 실제 Pod UID/배치 노드·단일 Attempt·Result·리소스 종료.
- 실행 중 API 교체 후 같은 Job UID/Attempt 유지, 나머지 BATCH 완료.
- 실제 S3 version 누락/크기/해시 오류400 ARTIFACT_INVALID, 동시commit201/200으로 같은 Result1개.
- 다른 실제 Pod token으로 claim409, 취소·Pod 종료 뒤 늦은commit401, 결과 생성 없음.
- 실행 중 취소·불가능한 affinity, Job/Pod/Secret 잔여0.
- 실제 고정버전 artifact7개의 길이/SHA/계산값 대조. 시험 후 생성한 kind 클러스터만 삭제.

Actions pin `1c286b2`의 API/UI/MinIO imageID·Ready·PVC Bound·Argo Synced는
`20261002T084513Z-d2d5a6ad` PASS/0. aggregate health Progressing은 기존 공유 Ingress status 제한이다.
`20261002T084526Z-b0e53961`: 기존 Kubernetes1.31.14에서 새 Runner/API를 사용한 실제 AUTO/NODE
BATCH와 artifact4개 내용 검증·실행 취소·affinity 및 CPU 부족·출력 누락·작업 실패·하위SKIPPED,
모든 해당 Run의 리소스 종료 PASS/0. 이전 FENCED 실패 이후 수정 이미지로 전체 실행이 통과했다.
모든 workload는 참조 CPU 계산의 SYNTHETIC 데이터이며 실제 모델·가속기·장치 acceptance가 아니다.

추가한 CPU 부족·출력 누락·프로세스 실패 시험은 기존 클러스터에서 먼저 통과했고 다음 CI kind에
포함한다. `20261002T084654Z-018ce37c`는 runtime=true 배포의 UI/assets·Swagger·인증/CSRF·
Profile/Device/Node·불변 DAG·Run 재전송·분기 및 전체취소 회귀 PASS/0이다. 해당CRUD probe는
의도적으로 스케줄되지 않는 작업으로 관리 동작을 시험하며 실제 계산 증거는 위 종단 시험이 담당한다.
추가3개 실패조건의 다음 CI kind 결과를 확인한 뒤 M4 완료를 판정한다.


## M4 완료 판정 — e4be5ff

[CI36986090769](https://github.com/dsa04156/edgeai/actions/runs/36986090769)의
scaffold/storage/runner/images/gitops 5개 job 모두 success. 내려받은 검증 결과 JSON14개 모두 PASS/0.
실제3노드 kind `20261002T085328Z-3c737299`의 9개 Run 보고서를 확인했다. 기존 종단 실행·재시작·
producer/artifact fault에 CPU 부족·출력 누락·프로세스 실패까지 통과했다. 후자의 두 실패는
root FAILED/child SKIPPED, 하위 Attempt와 Result 없음, 잔여 Job/Pod/Secret 0을 확인한다.

Actions pin `aecd457`을 반영하고 실제 API/UI/MinIO imageID가 source e4be5ff의 digest와 일치하며
Ready, 두 PVC Bound, Argo Synced인 것을 확인했다. Argo aggregate health는 공유 Ingress status
제한으로 Progressing이다. 이는 종단 HTTP/Runner 결과 증거와 구분하며 공유 설정은 변경하지 않았다.

| M4 요구사항 | 직접 증거 |
|---|---|
| PodSpec 요구조건·AUTO/NODE scheduler bind | 실행 규격/Job compiler 단위 + kind AUTO/NODE 실제 Pod/Node UID |
| Job watch/relist·명령 복구 | gateway 통합2 + worker PostgreSQL + kind API 교체 시 같은 Job UID |
| Runner 실제 프로세스·입출력 | 호스트/컨테이너8 + kind BATCH 두 단계 계산 |
| S3 검증 후 원자적 Result·단일 producer | 실제 MinIO/DB + kind version/size/hash 거절·동시 commit201/200 단1개 |
| 선행 결과에 따른 하위 해제 | kind 입력 artifact의 features/score 대조·실패 시 하위 Attempt 없음 |
| 취소·늦은 producer·리소스 정리 | kind 다른 Pod claim409·종료 뒤commit401·취소/실패 후 잔여0 |
| 공개 API/Swagger/UI | 한국어28개 + 실제 배포 CRUD + PC/모바일 Result ID/S3 대조 |

M4의 각 범위를 위 직접 증거로 확인했다. 재시도/실행 중 오프로딩/Remote(M5), VD/STREAM,
부하·운영 복구/보안·실장비 수용시험은 이 완료 판정에 포함하지 않으며 여전히 전체 목표에 남아 있다.
