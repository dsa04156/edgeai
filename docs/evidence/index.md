# 검증 증거

2026-10-01 M0 초기 환경 검증. 로컬 Linux x86_64 / JDK 21 / Node 22 / PostgreSQL 16.15.
원시 로그·JSON은 `docs/evidence/runs/<testRunId>/`에 있으며 자격 증명을 제거하고 Git에서는 제외한다.
공개 저장소에는 아래 결과 요약만 보존한다. CI 원시 증거는 해당 Actions run artifact에서 확인한다.

| testRunId | 명령 | exit | 결과 |
|---|---|---|---|
| 20261001T074249Z-49975f51 | bash scripts/test-unit.sh | 0 | API 비인증 401/인증 metadata, 2 tests |
| 20261001T074758Z-ef139f25 | bash scripts/test-integration.sh | 0 | 실제 PostgreSQL 16.15 + Flyway, 1 test |
| 20261001T074854Z-0b774af7 | bash scripts/test-contract.sh | 0 | OpenAPI 생성 타입 일치 + API tests |
| 20261001T074850Z-b27fea4b | bash scripts/test-ui.sh | 0 | lint/typecheck/build + desktop/mobile 2 tests, 연결 실패 503 |
| 20261001T074945Z-eabe3444 | bash scripts/test-health-stack.sh | 0 | DB→Spring→Next.js UP, 인증 200/비인증 401 |

추가 확인: shell 구문, Compose config, 비밀/로컬 파일 Git 제외, 미구현 스크립트 8개의 exit 2,
desktop/mobile screenshot 직접 확인. 선택적 MinIO 빌드·runtime은 아직 검증하지 않았다.

수정 이력: 첫 단위시험은 test 전용 password property가 없어 실패했고 설정 분리 후 통과했다.
첫 UI 시험(20261001T074758Z-9a59c73d)은 전역 pnpm shim 부재로 webServer 시작이 실패했다.
Node로 설치된 Next CLI를 직접 호출하도록 고친 뒤 UI 전체 재실행이 통과했다.

Docker 소켓 접근은 현재 호스트에서 차단됐다. 실제 kind·hardware 시험은 미구현이다.
이 결과는 플랫폼 전체의 `LOCAL_VERIFIED` / `FULL_ACCEPTANCE`를 의미하지 않는다.
