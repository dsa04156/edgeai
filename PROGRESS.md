# 진행 상태

[STATUS]
M0 초기 개발 환경 구현·완료 감사·검증 완료. 전체 플랫폼은 PARTIAL.
공개 GitHub 저장소 https://github.com/dsa04156/edgeai 생성 및 main push 완료.
최신 코드 커밋 b469f62의 GitHub Actions 36834353000은 scaffold/storage 두 job 모두 success로 완료됐다.

[IMPLEMENTED]
Spring Boot modular monolith, Next.js, OpenAPI 생성 타입, Flyway schema 초기화,
개발용 인증, Compose PostgreSQL/MQTT, 선택적 MinIO source build, 로컬 PostgreSQL 대체 경로,
bootstrap/preflight/test/evidence 스크립트, GitHub Actions, 설계·개발 문서.
기존 루트 AGENTS.md는 보존하고 backend/dashboard/scripts의 차별화된 지침을 추가했다.

[VERIFIED]
test-unit / test-contract / test-integration / test-ui / test-health-stack 모두 exit 0.
실제 PostgreSQL 16.15 → Spring readiness → Next.js health 연결 및 인증 401/200 확인.
Compose config·shell 구문 및 미구현 스크립트의 BLOCKED/2 확인.
GitHub의 새 환경에서 PostgreSQL 17 + MQTT 송수신 + 동일 빌드/시험 + health 경로도 모두 통과했다.
프로젝트 DB 중지 시 API/UI 503, 재시작 후 같은 앱 프로세스의 UP 복구를 확인했다.
MinIO 공식 소스 native/container build 및 실제 S3 PUT/stat/GET·SHA-256 metadata·비인증 403도 통과했다.

[EVIDENCE]
docs/evidence/index.md와 로컬 docs/evidence/runs/<testRunId> 참조.
CI: https://github.com/dsa04156/edgeai/actions/runs/36834353000
초기화 요구사항별 완료 감사: docs/evidence/m0-completion-audit.md

[BLOCKED]
로컬 Docker 소켓 권한은 여전히 제한된다. Docker 의존성은 권한 있는 GitHub CI에서 실제 검증했다.
M1+ 도메인 기능, kind/fault/load/hardware 시험은 아직 미구현.
별도 전체 구현 계약·실장비·2세부 Remote API·성능 수용 기준은 추가 확인 필요.

[NEXT]
다음 구현 단계는 M1 Profile 계약 상세화다.
개발 재개: bash scripts/bootstrap.sh → bash scripts/dev-up.sh
(Docker 미사용 Ubuntu 대안: bash scripts/dev-postgres-local.sh start)
별도 터미널: bash scripts/dev-backend.sh / bash scripts/dev-dashboard.sh
검증 재개: bash scripts/verify-all.sh scaffold
검증용 로컬 API/UI/PostgreSQL/MinIO 프로세스는 종료했으며 DB 데이터는 .tools/pgdata에 보존했다.

[M0 COMPLETION AUDIT]
원래 요청인 초기 개발 환경과 공개 저장소 생성·push의 완료 증거를 재검토했다.
M1~M10 기능 구현은 이후 개발 단계이며 이번 초기화 완료 판정과 구분한다.
DB 장애·복구와 MinIO 실제 S3 경로를 추가 검증했고, 확장 CI 두 job 및 결과 JSON 8개의 PASS/exit 0을 확인했다.
초기 개발 환경과 공개 저장소 생성·push라는 이번 작업 범위의 요구사항은 모두 증거로 확인됐다.
