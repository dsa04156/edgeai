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
desktop/mobile screenshot 직접 확인. 최초 M0 시점에는 MinIO 빌드·runtime을 검증하지 않았으며 후속 감사에서 아래와 같이 보완했다.

수정 이력: 첫 단위시험은 test 전용 password property가 없어 실패했고 설정 분리 후 통과했다.
첫 UI 시험(20261001T074758Z-9a59c73d)은 전역 pnpm shim 부재로 webServer 시작이 실패했다.
Node로 설치된 Next CLI를 직접 호출하도록 고친 뒤 UI 전체 재실행이 통과했다.

Docker 소켓 접근은 현재 호스트에서 차단됐다. 실제 kind·hardware 시험은 미구현이다.
이 결과는 플랫폼 전체의 `LOCAL_VERIFIED` / `FULL_ACCEPTANCE`를 의미하지 않는다.

## GitHub Actions 검증

[Run 36832834758](https://github.com/dsa04156/edgeai/actions/runs/36832834758),
코드 커밋 `f265c04ddf407950baa76f68d83beb59ef87dae8`, 결과 **success** (2분 50초).
Ubuntu 24.04 hosted runner / JDK 21 / Node 22 / Compose PostgreSQL 17 / MQTT.
Artifact `m0-verification-36832834758`의 결과 JSON도 내려받아 확인했다.

| testRunId | 시험 | exit | 결과 |
|---|---|---|---|
| 20261001T075239Z-0d254de4 | PostgreSQL ready + MQTT pub/sub | 0 | PASS |
| 20261001T075302Z-d385d565 | unit | 0 | PASS |
| 20261001T075341Z-f84f9215 | contract | 0 | PASS |
| 20261001T075354Z-4c80f49d | UI lint/types/build + desktop/mobile | 0 | PASS |
| 20261001T075412Z-4eeef430 | PostgreSQL 17 Flyway integration | 0 | PASS |
| 20261001T075427Z-afd2b114 | PostgreSQL → Spring → Next.js health | 0 | PASS |

이후 증거·진행 문서만 갱신하는 커밋은 동일 코드에 대한 위 검증을 재사용한다.

## 초기 환경 완료 감사 보완

- `20261001T080004Z-0963a846`: `test-health-stack.sh local`, exit 0.
  실제 프로젝트 PostgreSQL 중지 → API readiness 503/DOWN → Next health 503/DOWN,
  DB 재시작 후 동일 앱 프로세스가 UP으로 복구되는 경로를 확인했다.
- `20261001T080058Z-3a5d7aab`: `test-storage.sh`, exit 0.
  공식 MinIO release commit의 native build를 실제 실행했다. 인증된 S3 PUT/stat/GET,
  무작위 256 KiB 파일 byte 일치, SHA-256 metadata, 비인증 GET 403을 검증했다.
  시험은 고유 probe bucket/object만 만들고 제거했으며 기존 버킷은 건드리지 않았다.
- MinIO 소스 tar SHA-256을 Dockerfile ADD에 고정했다. native build와 Docker source는 동일 commit이다.
- [CI 36833935958](https://github.com/dsa04156/edgeai/actions/runs/36833935958)의 storage job이
  컨테이너 빌드·기동·실제 S3 시험을 2분 35초에 완료했다.
- 같은 CI의 앱 검사에서는 DB 중지 후 API/UI 503까지 통과했으나 CI Compose가 `start --wait`를
  지원하지 않아 재기동 단계에서 실패했다. 기본 `start`와 기존 bounded health polling을 사용하도록 수정했다.
