# 진행 상태

[STATUS]
M2 Device/Node 구현·로컬·CI·실제 배포 검증 완료. M3 구현 중이며 전체 플랫폼은 PARTIAL.
M0/M1 및 초기 CI/CD 완료 기록은 아래에 보존한다. 현재 상세는 docs/evidence/m2-device-node.md.
공개 저장소: https://github.com/dsa04156/edgeai
코드 20a8d6c의 CI 36950519908: scaffold/storage 모두 success, 결과 JSON 8개 PASS/0.
https://github.com/dsa04156/edgeai/actions/runs/36950519908

[IMPLEMENTED]
DEVICE/SERVICE/VD 등록·목록·버전 조회, OpenAPI 생성 타입, Flyway V2,
JSON 정규화 SHA-256, 중복 재등록·충돌 처리, PostgreSQL 불변 trigger,
Basic+CSRF를 유지한 실제 Profile Dashboard.
키/버전/JSON 상세 형식은 ADR 0002에서 확정했다. spec 실행 호환성은 후속 범위다.

[VERIFIED]
unit, contract, 실제 PostgreSQL integration, UI lint/typecheck/build/offline browser 통과.
실제 DB에 연결한 desktop/mobile 등록·재등록·409·새 버전·이전 버전 조회 통과.
동시 8개 동일 요청은 생성 1개·행 1개, 다른 내용 경쟁은 승자 1개·충돌 1개.
같은 키의 세 종류 분리, DB UPDATE/DELETE/TRUNCATE 차단, 정밀한 숫자 보존을 확인했다.
DB 중지/재시작 시 기존 API/UI 프로세스의 readiness 장애 및 복구를 확인했다.
CI의 새로운 PostgreSQL 17에서도 동일 검증을 통과했다. Flyway history는 edgeai로 고정한다.

[EVIDENCE]
docs/evidence/m1-profile.md와 docs/evidence/runs/<testRunId>.
M0 역사적 완료 감사: docs/evidence/m0-completion-audit.md

[BLOCKED]
M1 로컬 범위의 차단 없음. 로컬 Docker 소켓 권한 제한은 portable PostgreSQL로 대체했다.
전체 플랫폼의 kind/fault/load/hardware 시험과 M2+ 기능은 후속 범위다.
별도 전체 구현 계약·실장비·2세부 API·성능 기준은 필요한 단계에서 확인한다.

[NEXT]
M3 Workflow/Run/Task/Attempt와 Dashboard를 구현·검증한다.
개발 재개: bash scripts/dev-up.sh (Docker 대안: bash scripts/dev-postgres-local.sh start)
별도 터미널: bash scripts/dev-backend.sh / bash scripts/dev-dashboard.sh
Profile UI: http://127.0.0.1:13080/profiles
재현: bash scripts/test-unit.sh; bash scripts/test-contract.sh; bash scripts/test-integration.sh;
bash scripts/test-ui.sh; bash scripts/test-profiles-stack.sh local (Compose는 compose).

검증용 API/UI/PostgreSQL 프로세스는 종료하고 개발 DB 및 원시 evidence는 보존한다.


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
