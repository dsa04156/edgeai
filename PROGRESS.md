# 진행 상태

[STATUS]
M0 핵심 개발 환경 구현 및 로컬 health 경로 검증 완료. 전체 플랫폼은 PARTIAL.
공개 GitHub 저장소 https://github.com/dsa04156/edgeai 생성 및 main push 완료.
코드 커밋 f265c04의 GitHub Actions 36832834758은 success로 완료됐다.

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

[EVIDENCE]
docs/evidence/index.md와 로컬 docs/evidence/runs/<testRunId> 참조.
CI: https://github.com/dsa04156/edgeai/actions/runs/36832834758

[BLOCKED]
로컬 Docker 소켓 권한. 선택적 MinIO 실행 검증 미완료.
M1+ 도메인 기능, kind/fault/load/hardware 시험은 아직 미구현.
별도 전체 구현 계약·실장비·2세부 Remote API·성능 수용 기준은 추가 확인 필요.

[NEXT]
다음 구현 단계는 M1 Profile 계약 상세화다.
개발 재개: bash scripts/bootstrap.sh → bash scripts/dev-up.sh
(Docker 미사용 Ubuntu 대안: bash scripts/dev-postgres-local.sh start)
별도 터미널: bash scripts/dev-backend.sh / bash scripts/dev-dashboard.sh
검증 재개: bash scripts/verify-all.sh scaffold
검증용 로컬 API/UI/PostgreSQL 프로세스는 종료했으며 DB 데이터는 .tools/pgdata에 보존했다.
