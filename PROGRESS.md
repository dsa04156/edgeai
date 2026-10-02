# 진행 상태

[STATUS]
M1 Profile 구현·로컬·CI 검증 완료. 전체 플랫폼은 PARTIAL.
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
M2 Device/Node/Observation: ProfileVersion 참조·장치/Node 분리·세션/관측 계약부터 구현.
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

[CI/CD 연결 및 API 설명 — 진행 중]
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
