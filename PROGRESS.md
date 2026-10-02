# 진행 상태

[STATUS]
M0–M4 구현·검증 완료. M5 재시도·명시적 노드 전환은 실제 kind·CI·배포 검증 완료.
실행 측정·자동 전환·Remote 참조 adapter와 RemoteAllocation/결과 연결은 CI·배포까지 검증했다. 전체 플랫폼은 PARTIAL이다.
M5 Remote 자동 worker·공개 실행/전환 API·고정 제공자 binding은 로컬 및 CI37013658656을 통과했다. 후속 CI37016556197의 실제 kind22Run·결과20개도 통과했다. 외부 수용·상태형 복원은 남았다. 상세: docs/evidence/m5-remote-kind.md.
현재 상세: docs/evidence/m5-retry-offload.md. M4 완료: docs/evidence/m4-runtime.md. M3 완료 증거는 docs/evidence/m3-workflow.md에 보존한다.
공개 저장소: https://github.com/dsa04156/edgeai
M4 첫 구성 요소 코드 640e995의 CI 36975219681: scaffold/storage/runner/images/gitops 모두 success, 결과 JSON12개 PASS/0.
https://github.com/dsa04156/edgeai/actions/runs/36975219681
V5 코드5992cdc의 CI36978182298 5 jobs와 결과JSON13개도 통과했다. bc061e7 pin과 실제 imageID를 확인했다.

[IMPLEMENTED]
M5 재시도: V6 정책/예약, RETRY_WAIT, 동일 Task의 새 Attempt/epoch, 이전 종료 확인·취소·예산 소진과 UI.
M5 재시도 CI36990194234의 5 jobs·결과JSON14개 PASS 및 e777233 실제 이미지/Argo Synced 확인.
M5 명시적 offload: ADR0007·V7, source fence/drain, 동일 Task 새 Attempt/target claim, Operation API/화면.
오프로딩 CI36993166041의 5 jobs·결과JSON14개 PASS, kind15개 Run과 f886dd7 실제 배포를 확인했다.
V8/Runner 내부 telemetry API·cgroup 수집·서비스 지연·현재 Attempt 측정 화면을 추가했다.
측정 상세 증거는 docs/evidence/m5-runtime-telemetry.md다. ADR0009 자동 판단은 CI36999672446의
5 jobs/결과JSON14개/실제kind18Run/source951c4bd 배포까지 검증했다. 상세는 docs/evidence/m5-automatic-offload.md다.
ADR0010 Remote 참조 adapter/SQLite 시뮬레이터: 단위57(HTTP/TLS10 포함), 실제 프로세스 통합13,
3개 OpenAPI/MVC18 로컬 통과. 상세는 docs/evidence/m5-remote-adapter.md다.
참조 adapter CI37003825328 5 jobs/결과JSON15개 PASS, source0143094 실제 이미지/Ready/PVC/ArgoSynced 확인.
ADR0011/V10–V11 RemoteAllocation, Pod와 분리한 producer, 관측 revision/lease/늦은 결과 차단,
실제 S3 검증 후 결과 확정·하위 BATCH와 결과 API/화면을 추가했다. 로컬 PG80·단위58·실제 MinIO3 통과.
플랫폼 연결6009136의 CI37008176219 5 jobs/결과JSON15개 PASS, 실제 이미지·Ready/PVC/ArgoSynced 확인.
ADR0012/V12 Remote worker·직접 S3 전송·공개 REMOTE Run/Offload·불변 제공자 설정과 UI를 추가했다.
worker f6dc087 CI37013658656 5 jobs/결과JSON15개 PASS, 기존 실제 kind18Run 통과. GitOps pin은 f6c5a2d다.
실제 API/UI/MinIO imageID 일치·Ready/PVCBound/ArgoSynced도 PASS(20261002T135530Z-df0f3f31).
실제 외부 계약·상태형 복원은 남아 있다.
Profile 불변 버전, Device/Node/Session/Observation, 불변 Workflow DAG,
Idempotency-Key 기반 Run/Task/Attempt 생성·조회·취소·의존성 전파와 실제 Dashboard.
Flyway V1–V16, 계층형 Spring 패키지, 한국어 Swagger39개, GitHub Actions/GHCR/ArgoCD 연결.
M6 첫 구현: 영속 VD 등록/수정/해제·원본 조건/연결 이력·Device 해제 보호 API와 `/virtual-devices` 화면.
V14는 내부 runtime/Operation·명령 lease·교체/drain 상태와 registry hook을 추가한다.
ADR0016의 실제 Pod gateway/worker·HMAC 자격, Pod-bound TokenReview·UID 소유/삭제·watch/relist를
추가했다. ADR0017/V15는 인증된 poll·순번 저장·lease/Ready·idle drain을 연결했다.
ADR0018은 공개 시작·교체·종료·실행 상태4 API, Operation 합집합 조회와 UI를 추가했다.
VD는 기본 비활성이며 실제 Pod/poll 전체 수용과 Run VD Task/Result 연결은 남았다.
상세: docs/evidence/m6-vd-lifecycle.md 및 docs/evidence/m6-vd-gateway.md.
poll 상세: docs/evidence/m6-vd-poll.md. 실제 Python 감독 프로세스→Spring HTTP→PostgreSQL을
검증하며 Kubernetes 신원/Ready는 fixture다. 실제 Task 배정/Result 종단은 아니다.
M3는 실행 요청 저장이며 실제 Runner 실행·검증된 Result는 M4다.
M4 실행 규격·Job compiler·S3 artifact adapter·독립 Runner를 추가했다.
V5의 RuntimeInstance·CREATE/DELETE 명령 lease·봉인된 Result/Artifact와 실행 상태 전이를 추가했다.
Run 잠금으로 claim·결과 확정·BATCH 하위 해제·취소를 직렬화하고 외부 저장소 I/O 후 producer를 다시 검사한다.
Kubernetes Job/Secret worker·watch/reconciliation·내부 claim/uploads/commit/fail HTTP를 구현했다.
Result 공개 API/화면·Swagger28개를 추가했다. 단위37·PG41·UI16·실DB브라우저8·DB장애/복구를 확인했다.
Attempt HMAC과 실제 Pod-bound TokenReview 신원을 함께 검증한다. 전용 runtime namespace/RBAC를 준비했다.
로컬 실행 기능 기본값은 비활성이다. 배포는 MinIO/PVC/버킷/영속 키와 GitOps 실행 활성화를 반영했다.
새 Run/결과 확정과 다음 실행 명령을 같은 DB 트랜잭션에 저장하며 과거 M3 Run은 자동 실행하지 않는다.

[VERIFIED]
M6 등록 로컬: 단위/MVC66·실제PostgreSQL89(VD9)·계약/MVC22·UI28·실DB/브라우저10 모두 PASS.
VD 포함 DB 장애503와 같은 프로세스 복구도 PASS(20261002T144123Z-bf144394).
등록 코드0b4693c의 CI37022079299 5jobs/JSON15개 PASS, b5a9019 pin과 실제 imageID/Ready/PVCBound/ArgoSynced
검증20261002T151449Z-3a9ad7a8 PASS. docs/evidence/m6-vd-registry.md.
지속 supervisor·Pod compiler·내부 poll 계약을 후속 구성 요소로 추가했다. 실제 child Runner 시험과
Java compiler/계약 검증은 docs/evidence/m6-vd-runtime.md를 따른다. 서버·VD Task 연결 완료는 아니다.
ADR0015/V14의 실제 PostgreSQL lifecycle·경합·세대/원본 보호·명령 lease 회수 검증은
docs/evidence/m6-vd-lifecycle.md를 따른다. 실제 gateway 관측과 API 프로세스 재시작 수용은 남는다.
로컬 최종: 단위68·PostgreSQL98(lifecycle9)·계약/MVC22·실DB PC/모바일10 및 DB503/복구 PASS.
이전e442a5c CI37027216582 5jobs/JSON15개·Runner 실제컨테이너27개·기존kind22Run/S3결과20 PASS.
28a6b42 pin의 실제imageID·Ready/PVCBound/ArgoSynced155929Z-50929ad5도 PASS.
V14 b69d805 CI37031788410 5jobs/JSON15개·실제 Runner27·기존kind22Run/S3결과20 PASS.
b1e458c pin의 실제imageID·Ready/PVCBound/ArgoSynced163724Z-f4de6ab6 PASS.
ADR0016 실제 Kubernetes gateway5개(VD3+기존Job2), 단위HTTP경계 및 PostgreSQL worker 시험은
docs/evidence/m6-vd-gateway.md를 따른다. 대기 workload를 사용하므로 실제 감독/poll/Task 종단과 구분한다.
gateway cf499be CI37036686347 5jobs/JSON15개·실제Runner27·kind22Run/S3결과20 PASS.
86ff9bd pin의 실제imageID·Ready/PVCBound/ArgoSynced172048Z-00d56a91 PASS.
V15 poll 로컬: 단위79·실제PG111(poll9,실제감독프로세스/HTTP2 포함)·계약4/MVC23 PASS.
poll d89d2bb CI37040484693 5jobs/JSON15개·실제Runner27·기존kind22Run PASS.
6d46ec4 pin 실제imageID/Ready/PVCBound/ArgoSynced175035Z-94ede97d PASS.
공개 실행 ADR0018 로컬: 단위81·실제PG116(신규5)·계약4/MVC25·UI32 PASS.
실제API/DB PC모바일10·Swagger39·VD execution503/동일프로세스복구175257Z-32f133c1 PASS.
공개 실행 근거는 docs/evidence/m6-vd-public-execution.md다.
실제 Kubernetes VD 수명3개(AUTO+API Pod 재시작, NODE, Unschedulable 시작 실패)는
20261002T182533Z-cfdb17d2 PASS/0. 같은VD의 교체·이전poll차단·drain·자원0개를 확인했다.
docs/evidence/m6-vd-kubernetes.md. 새 kind CI·VD Task 연결·새 코드 CI/배포는 별도이며 미완료다.
공개 실행8981a94 CI37044509505 5jobs/결과JSON15 PASS. pin f6a996e 실제imageID·Ready/PVCBound/ArgoSynced
183056Z-6979449b PASS. 신규 VD kind 게이트와 Task 연결 완료를 뜻하지 않는다.
ADR0019/V16 VD Task 영속 기반: PostgreSQL124(신규8)·단위81·계약4/MVC25·실API/DB PC모바일10과
DB503/복구 PASS. 배정 slot·Run 잠금 순서·생산자 FK를 검증하며 관측/결과 receipt는 fixture다.
공개 VD Run·poll Task 전달·Runner/Result 연결은 남는다. docs/evidence/m6-vd-task-persistence.md.
ADR0012 로컬: 단위60·PostgreSQL80·계약/MVC19·실제 MinIO/DB/provider14·PC/모바일 UI26 PASS.
실제 Spring 스케줄러의 공개 Remote BATCH, 실제 제공자 SIGKILL/재시도, 취소와 출력 변조 차단,
설정 변경 시 전송 차단·재전송, 동시 worker의 단일 실행/결과를 검증했다. NODE 전환 부분은 fixture다.
실DB/API 브라우저8·Swagger·DB 장애503/동일 프로세스 복구도 PASS(20261002T132448Z-5fdba197).
이전6009136 배포: imageID와75c6632 pin 일치, Ready/PVCBound/ArgoSynced(20261002T130758Z-54ea0071).
기존 공유 Ingress status 제한에 따라 Argo aggregate health는 Progressing이다.
ADR0011 로컬: PostgreSQL80·단위/MVC58·계약/MVC19·실제 MinIO/DB3·PC/모바일 UI22 PASS.
실제 Remote 파일→MinIO→DB 결과→고정 입력의 Remote 하위 BATCH까지 계산값·SHA·version을 확인했다.
실DB/API PC·모바일8·Swagger·DB 장애503/동일 프로세스 복구도 PASS(20261002T123523Z-2f3da4ae).
이는 내부 lifecycle 통합 검증이며 Remote 자동 worker/공개 REMOTE 실행 요청 시험은 아니다.
M5 명시적 offload 로컬: 실제 PostgreSQL60·단위/MVC42·계약/MVC18·PC/모바일 UI18 PASS.
실DB/API PC·모바일8·한국어 Swagger30·실제 DB 장애/복구도 PASS(20261002T095243Z-b6c8245c).
실제kind 노드 전환/전환 중 API 재시작/고정 BATCH 입력/취소/시작 제한·cleanup을 통과했다.
실행 측정 로컬: PostgreSQL63·호스트 Runner12·UI20·실DB브라우저8 및 DB 장애/복구 PASS.
측정 CI36996007482 5jobs·artifact14개 PASS, kind15Run source/target 측정·late telemetry401 확인.
61b6caa 실제 배포(20261002T105511Z-be6074de) Ready/PVCBound/ArgoSynced.
자동 전환 정책 V9·API/UI·판단 이력·AUTO 제외 조건 구현. PG69·단위47·UI22·실DB브라우저8·DB장애/복구·실K8s server dry-run을 통과했다.
새 자동 전환의 실제 kind 수용은 CI 확인 대상이다. docs/evidence/m5-automatic-offload.md 참고.
M5 로컬: 단위39·실제PostgreSQL50·계약/MVC15·UI16·실DB PC/모바일8·DB 장애/복구 PASS.
단위/MVC25, 실제 PostgreSQL 통합19, 계약 생성 타입·YAML 일치,
UI lint/types/build·오프라인14·실DB PC/모바일8 및 DB 장애/복구 통과.
신규 CI와 실제 이미지 시험 통과. Kubernetes API/UI/DB Ready, PVC5Gi Bound,
Pod imageID와 CI digest 일치, Argo Synced, 실제 Ingress HTTP·Workflow PC/모바일2개 통과.
새 API의 실제 Node10개 UID/metadata 대조와 합성 장치 연결/해제 통과.
기존 Traefik/Ingress status 문제로 Argo aggregate health는 Progressing이며 공유 설정은 변경하지 않았다.
M4 구성 요소의 단위30·계약·호스트 Runner7·실제 MinIO4·Kubernetes server dry-run과
기존 PostgreSQL·실제 PC/모바일8·DB 장애 복구 회귀를 확인했다.
첫 M4 구성 요소의 CI·Runner 컨테이너·배포 이미지/Ingress를 확인했다.
V5 상태 서비스의 실제 PostgreSQL31·MinIO+DB2·PC/모바일8·계약·DB 장애 복구를 추가 검증했다.
실제3노드 kind에서 AUTO/NODE BATCH·API 재시작·artifact/producer fault·취소 시험을 통과했다.
이후 단위35·실제 PostgreSQL41·내부 API 계약 및 실제 Kubernetes gateway2개를 검증했다.
실제 gateway 시험은 AUTO/NODE 배치·Pod TokenReview·unbound token 거절·watch·UID 삭제를 확인했으며,
대기 컨테이너를 사용하므로 Runner→MinIO→Result 전체 경로 검증과 구분한다.

[EVIDENCE]
docs/evidence/m4-runtime.md, docs/evidence/m3-workflow.md와 docs/evidence/runs/<testRunId>.
M0–M2: docs/evidence/index.md 및 개별 milestone 문서.

[BLOCKED]
M4 범위의 차단 없음. M5–M10은 미완료이며 전체 LOCAL_VERIFIED/FULL_ACCEPTANCE는 아니다.
로컬 Docker 권한 제한은 유지한다. 실제 kind 검증은 권한 있는 GitHub runner에서 수행한다.
외부 Remote API·실장비/모델·성능 수용 기준의 자료 위치를 요청한 상태이며 독립 구현은 계속한다.

[NEXT]
M5 실제 NODE↔REMOTE·API 재시작/취소 kind 게이트4개 Run은 CI37016556197에서 통과했다.
기존18개 포함22Run/실제S3결과20개/원시JSON15개 PASS,861663f GitOps와 실제 imageID/Ready/ArgoSynced도 확인했다.
M6 등록·원본 연결 API/UI를 검증하며 다음은 지속 VD runtime·Operation·실제 Run VD 실행이다.
M4 완료: e4be5ff CI36986090769 5jobs/JSON14개 PASS, kind9Run·aecd457 pin/실제imageID·Ready/PVC/Argo 확인.
M5 Remote worker의 실제 kind 전환/재시작·CI·배포는 확인했다. 외부 실제 계약과 상태형 복원은
M5 완료 전 남은 게이트다. M6 등록 코드의 CI·배포는 확인했고 지속 runtime의 서버 연결을 이어간다.
다음 단계는 M6 VD → M7 다중 장치/STREAM → M8 부하 → M9 운영/복구/보안 → M10 실장비다.
아래는 M4 연결 단계의 이전 진행 기록이다.
현재 연결 코드의 CI 확인과 Runner·MinIO 검증 이미지 발행, 영속 키·bucket 설정 후 배포 실행을 활성화한다.
9d15fb1 CI36981974775 5jobs/JSON13개 통과, 배포 imageID·Ingress 회귀까지 확인했다.
Result/API 코드2cbaf36 CI36983413818 5jobs/JSON13개 통과, eb33bfd pin과 실제 API/UI/MinIO imageID 일치.
첫 실제 Runner 실행은 root Result를 만들었지만 하위 실행 또는 다음 Run의 claim에서 FENCED로 실패했다.
Pending Pod 관측 지연을 재시도하도록 수정했고 Java38·Runner8 회귀 통과. 새 CI/kind·배포 재검증이 남았다.
494ae37 CI36984655502 5jobs/JSON14개 및 실제kind 통과. 실제 root Result PC/모바일·고정S3 대조 PASS.
1c286b2의 실제 imageID/Ready/Argo 확인 후 기존클러스터 AUTO/NODE BATCH·취소·CPU/affinity 부족·
출력누락·작업실패·하위SKIPPED·파일4개 체크섬/계산값 검증 PASS. runtime=true 배포의 Swagger/인증/CRUD 회귀도 PASS.
추가실패조건3개의 다음kind CI 결과를 확인한 뒤 M4 완료 판정.
상세 구현 순서: PLAN.md. 기존 demo-workflow/test-kind의 실제 실행 기준을 유지한다.
개발 재개: bash scripts/dev-up.sh (Docker 대안: bash scripts/dev-postgres-local.sh start)
별도 터미널: bash scripts/dev-backend.sh / bash scripts/dev-dashboard.sh
Workflow UI: http://127.0.0.1:13080/workflows
배포 Workflow: http://edgeai.192.168.0.56.sslip.io/workflows
Swagger: http://edgeai.192.168.0.56.sslip.io/swagger-ui.html
재현: scripts/test-unit.sh, test-contract.sh, test-integration.sh, test-ui.sh,
test-profiles-stack.sh local (Compose는 compose). 인증은 로컬 .env/배포 .tools의 비공개 환경 파일을 사용한다.

[SWAGGER / CI-CD]
Swagger UI `/swagger-ui.html`, 계약 `/openapi.yaml` 추가. 문서 인증과 자동 CSRF 쓰기,
실제 desktop/mobile 등록·조회 검증 완료. 상세 근거: docs/swagger-ui.md.
GitHub Actions CI 연결 상태를 확인했고 Swagger 시험도 기존 CI 브라우저 경로에 포함했다.
최초 Swagger 추가 시점에는 ArgoCD/CD가 미연결이었다. 이후 진행 상태는 아래 CI/CD 기록을 따른다.

Swagger 포함 코드 fd729ca의 CI 36952012074: scaffold/storage success, 결과 JSON 8개 PASS/0.
https://github.com/dsa04156/edgeai/actions/runs/36952012074

[BACKEND PACKAGE LAYOUT — 2026-10-02]
사용자 요청에 따라 Java 소스를 역할별 계층형 패키지로 재배치했다.
app: controller/service/dto/config/exception/support, domain: profile/repository,
adapters: repository. 응답 DTO와 예외 타입을 독립 파일로 분리하고 테스트 선택 경로를 갱신했다.
상세 경로와 책임은 docs/architecture.md, 후속 구현 규칙은 backend/AGENTS.md에 반영했다.
Gradle 모듈 의존성, API·OpenAPI·DB 스키마·JSON 처리 규칙은 유지한다.

로컬 검증: clean 후 단위·MVC 11개, 계약 MVC 7개 및 생성 타입/패키징 YAML 일치,
실제 PostgreSQL 통합 6개, 실행 JAR + Profile/Swagger desktop·mobile 브라우저 4개,
DB 중단 시 503와 동일 API/UI 프로세스의 복구까지 모두 통과했다.
기존 Dashboard 빌드를 사용했으며 프런트엔드 소스는 변경하지 않았다.
원시 근거: docs/evidence/runs/ 아래 다음 실행 결과(JSON exit code 0/PASS).

- unit: 20261002T015706Z-2211bc1b
- contract: 20261002T015811Z-9c7e25cf
- integration: 20261002T015818Z-9cb09f25
- health/Profile/Swagger: 20261002T015826Z-4e61b9e7

[CI/CD 연결 및 API 설명 — 첫 구현 기록]
사용자 요청에 따라 GitHub Actions → GHCR → Git digest 갱신 → ArgoCD 흐름을 구현했다.
기존 context/ArgoCD/Traefik/local-path를 조회했고 전용 edgeai namespace와 Secret을 준비했다.
Kustomize/ArgoCD manifests는 실제 클러스터 server dry-run을 통과했다.
API/DB 주소의 환경 설정, 비루트 컨테이너, Next.js standalone, 실제 컨테이너 HTTP 검증을 추가했다.
Swagger의 모든 operation에 한국어 역할·입력·응답·오류 설명과 예시를 보강했다.

로컬 검증: unit 11개, 계약 타입/패키징 일치, UI lint/typecheck/build 및 8개 검사,
실제 PostgreSQL 통합 6개, Profile/Swagger PC·모바일 4개 및 DB 장애·복구 통과.
근거: unit 20261002T023728Z-2552d567, contract 20261002T023734Z-c96a7d3a,
UI 20261002T023836Z-0ecc2310, integration 20261002T023843Z-e5d47cee,
health 20261002T024516Z-13e1707a (모두 PASS/0).
Swagger 설명 선택자가 두 영역과 일치한 실패는 텍스트 범위로 수정했다.
Profile 브라우저에서 성공 문구 대기 실패가 한 번 발생해 실제 새 버전 POST 201을 먼저 확인하도록 보강했다.
로컬 Docker 권한 제한으로 이미지 빌드·발행은 GitHub runner에서 검증한다.
최초 CI 이미지 발행, GHCR pull 가능 여부 및 ArgoCD 실제 동기화는 이 기록 시점에 아직 미검증이다.

첫 CI 36957209450은 scaffold/storage 및 두 이미지 빌드까지 통과했지만 실제 컨테이너의
CSRF 거절 코드 검증에서 중단됐다. 기본 거절 처리의 /error 재디스패치가 403을 401로 바꾸는
현상을 로컬 실제 서버 로그로 재현했고, 거절 핸들러가 403을 직접 반환하도록 수정했다.
토큰 누락·잘못된 토큰 모두 403, 정상 등록/재등록/충돌/조회는 201/200/409/200으로 확인했다.
deployment-smoke 20261002T025851Z-53df87c0, contract 20261002T025908Z-d79f7da1: PASS/0.
빠른 재실행 시 TIME_WAIT를 활성 서버로 오인하던 포트 검사도 수정했고 활성 listener 차단은 확인했다.

[CI/CD 연결 및 API 설명 — 배포 확인, 2026-10-02]
코드 39eb6fe의 GitHub Actions 36958143060: scaffold/storage/images/gitops 모두 success.
내려받은 원시 결과 JSON 9개 모두 PASS/0. 실제 컨테이너 이미지 시험도 포함한다.
https://github.com/dsa04156/edgeai/actions/runs/36958143060
Actions가 검증한 두 이미지 digest를 912fa23으로 자동 기록했고 익명 GHCR manifest 조회도 200이었다.
ArgoCD edgeai-dev를 등록했으며 실제 Pod의 API/Dashboard imageID가 검증한 digest와 일치한다.
PostgreSQL PVC 5Gi Bound, DB/API/Dashboard 각 1/1 Ready, Git 동기화 Synced를 확인했다.
클러스터의 Docker Hub CDN reset으로 PostgreSQL은 동일 digest의 공식 ECR 미러로 전환했다(41bd732).

실제 Ingress에서 UI/CSS·API 연결·Swagger·인증·CSRF·등록201/재등록200/충돌409/조회200 통과.
두 사설망 주소 모두 인증된 Swagger/한글 계약/API 조회200 확인.
근거: kubernetes-http 20261002T031016Z-1de769da,
gitops-state 20261002T031127Z-a7f88d2d, ingress-swagger 20261002T031127Z-a1448527 (PASS/0).
배포 Swagger: http://edgeai.192.168.0.56.sslip.io/swagger-ui.html
대체 주소: http://edgeai.10.254.192.217.nip.io/swagger-ui.html
계정은 Git에서 제외한 .tools/kubernetes/edgeai-runtime.env에 보관한다.

남은 제한: 기존 Traefik LoadBalancer Service의 status.loadBalancer가 비어 Ingress 상태에도
주소가 게시되지 않는다. 실제 HTTP와 Pod는 정상이지만 ArgoCD aggregate health는 Progressing이다.
공유 Traefik/클러스터 설정은 변경하지 않았다. 작업 중 기존 control-plane 노드의 DiskPressure와
scheduler lease 갱신 실패도 관측했으며 이후 Pod 배치는 재개됐다. 클러스터 전체 안정성은 별도 운영 점검 대상이다.
로컬 검증 API/UI/DB와 일회성 registry probe Pod는 종료·제거했다. 배포 서비스와 PVC는 유지한다.

[M2 DEVICE/NODE — 2026-10-02 로컬 검증]
Device 8개·Node 2개 API, Flyway V3, controller/service/domain/repository 계층,
장치 등록/조회/revision 이름 수정/논리 해제·활성 연결 이력·bootId/epoch session fence·관측을 구현했다.
Profile UUID 참조·DEVICE 종류 FK, 활성 attachment/session partial UNIQUE 및 Device row lock을 적용했다.
실제 Kubernetes Node UID/Ready/allocatable/labels를 CA 검증·토큰 파일 기반 adapter로 관측한다.
실패 snapshot은 캐시를 제거하지 않으며 60초를 넘은 상태는 STALE로 조회한다.
Dashboard /devices와 Profile 메뉴를 연결하고 Swagger의 17개 operation에 역할·오류를 설명한다.

단위19·실제 PostgreSQL 통합13·계약 타입/YAML, UI lint/typecheck/build 및 offline12,
Profile/Device/Swagger PC·모바일6, DB 중단503/복구와 실제 Kubernetes 노드10개 대조를 통과했다.
실노드 연결 시험은 SYNTHETIC Device의 관리 이력이며 물리 장치 데이터 수신 시험이 아니다.
전용 ServiceAccount의 get/list nodes만 허용하는 bootstrap RBAC를 준비했고
nodes patch·secrets list·pods create가 허용되지 않는 것을 확인했다.
M2 코드의 새 GitHub CI, GHCR 이미지 및 클러스터 내 HTTPS/CA/ServiceAccount 경로 검증은 다음 확인 대상이다.
M3–M10은 아직 미완료이며 전체 목표를 M2로 축소하지 않는다.

[M2 CI·배포 확인 — 2026-10-02]
코드 3cfc41b의 GitHub Actions 36966964979 재실행: scaffold/storage/images/gitops 모두 success.
최초 시도는 Maven Central 의존성 다운로드403으로 backend 시험 전에 실패했다.
동일 POM 4개를 HTTP200으로 확인하고 코드 변경 없이 실패 job을 재실행해 통과했다.
다운로드한 platform/storage/image artifact의 result.json 9개 모두 PASS/0이다.
Actions가 d56f673으로 이미지 digest를 기록했고 실제 Pod imageID가 일치한다.
Argo Synced, DB/API/UI 각Ready1/1, PVC Bound. 기존 Ingress status 문제로 aggregate health는 Progressing이다.
Ingress에서 Device 관리·CSRF·session fence·관측 정밀도·해제 및 실제 Node10개 UID/metadata 대조를 통과했다.
전용 ServiceAccount/CA/HTTPS의 실제 cluster 내 노드 읽기 경로도 확인했다.
근거: m2-kubernetes-http 20261002T051528Z-b69dcf3d,
m2-kubernetes-nodes 20261002T051546Z-5657d8c7, m2-gitops-state 20261002T051753Z-35363223.

[M3 WORKFLOW/RUN/TASK — 2026-10-02 로컬 검증]
Workflow4/Run4/Task2 API, Flyway V4, 불변 DAG 봉인·SERVICE FK·동일 버전 FK·활성 Attempt UNIQUE,
Idempotency-Key 기반 원자적 실행 요청과 Run 행 잠금 취소·하위 전파·독립 분기 보존을 구현했다.
/workflows와 Swagger27개 operation, 큰 숫자·PC/모바일·인증/CSRF를 검증했다.
단위/MVC25, 실제 PostgreSQL 통합19, UI 오프라인14/실DB8, 계약 및 DB 장애·복구 모두 통과했다.
상세 실행 ID·실패 원인과 수정·한계는 docs/evidence/m3-workflow.md에 기록했다.
현재 root READY/QUEUED와 하위 WAITING은 요청 저장 상태이며 실제 작업 실행은 M4다.
M3 코드의 신규 CI·이미지·클러스터 배포 검증은 다음 확인 대상이다.

[M3 CI·배포 확인 — 2026-10-02]
코드8d1ae08의 Actions36970385137 네 job과 내려받은 결과JSON9개 모두 성공.
Actions의7a1614e 이미지pin과 실제 Pod imageID 일치, API/UI/DB Ready·PVC Bound·Argo Synced 확인.
Ingress HTTP·Node10개 대조·사설 HTTP Workflow PC/모바일까지 통과했다.
원시 증거: 20261002T055519Z-2f49e2b9 / -c35bcc0a / -7f924d90, 20261002T055555Z-bfc97a18.
전체 플랫폼 범위를 유지하며 M4 실제 실행 경로로 진행한다.
