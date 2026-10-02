# 진행 상태

[STATUS]
M1 Profile 구현 및 로컬 검증 완료. 전체 플랫폼은 PARTIAL.
공개 저장소: https://github.com/dsa04156/edgeai
첫 CI의 fresh DB Flyway history 위치 문제를 재현·수정했다. 수정본 CI를 확인한다.

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
