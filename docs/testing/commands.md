# 테스트 실행 안내

변경 범위와 실행 환경에 맞는 검사를 선택합니다. 아래 명령은 저장소 루트에서 실행합니다.
프로세스·DB·클러스터를 생성하거나 변경하는 검사는 각 스크립트의 대상과 정리 범위를 먼저 확인합니다.

## 코드·계약·문서

| 명령 | 확인 범위 |
|---|---|
| `make test` | 기본 단위 테스트 |
| `bash scripts/test/test-contract.sh` | 생성 타입·OpenAPI·controller 계약 |
| `bash scripts/test/test-docs.sh` | 문서 링크·목차·주요 목록·API 참고 동기화 |
| `corepack pnpm --filter @edgeai/dashboard lint` | 화면 코드 정적 검사 |
| `corepack pnpm --filter @edgeai/dashboard build` | 타입·production build·문서 패키징 |
| `bash scripts/test/test-platform-service.sh` | 모의 외부 API 기반 DDS 배포 연동 |
| `bash scripts/test/test-edgex.sh` | EdgeX 배포 구성 정합성 |

## 서비스와 통합

| 명령 | 필요한 환경·영향 |
|---|---|
| `make health` | 실행 중인 DB·API·Dashboard 조회 |
| `bash scripts/test/test-integration.sh` | 설정된 PostgreSQL; 시험 DB 범위 확인 |
| `bash scripts/test/test-registry-deletion.sh` | 별도 DB 생성·제거, 미사용 등록 삭제 검사 |
| `bash scripts/test/test-infra.sh` | Compose PostgreSQL·MQTT |
| `bash scripts/test/test-storage.sh` | MinIO; 시험 bucket 생성·정리 |
| `bash scripts/test/test-profiles-stack.sh local` | 실제 DB/API·브라우저; 별도 프로세스·장애 주입 |
| `bash scripts/test/test-ui.sh` | 화면 빌드·브라우저 검증; 서버 실행 여부 확인 |

브라우저가 필요한 환경은 `corepack pnpm --filter @edgeai/dashboard exec playwright install chromium`으로 준비합니다.
이미 실행 중인 사용자 서버를 검사할 때 서버 시작·종료가 포함된 전체 wrapper를 무조건 실행하지 않습니다.

## 실행·부하·복구 심화 검사

Runner·STREAM·Remote는 `test-runner.sh`, `test-stream.sh`, `test-remote.sh`,
`test-runtime-results.sh` 등에서 별도 실행 주체와 저장소를 검사합니다.
실제 cluster mutation은 명시한 시험 context·namespace·소유 범위에서 수행합니다.

- [부하 시험](load-testing.md): 합성 장치 관리 HTTP 측정과 수용 기준
- [백업·복구](../operations/backup-and-recovery.md): 각 절차에 해당하는 격리 검증
- [단계별 검증표](verification-matrix.md): 초기 단계부터의 구성 요소별 검사 범위
- [스크립트 안내](../../scripts/README.md): 전체 진입점과 내부 구현

## 결과 기록

성공은 실행한 환경과 범위 안에서만 보고합니다. 미실행·차단·성능 기준 미정은 성공으로 표시하지 않습니다.
`verify-all.sh local|full`은 미완료 범위를 nonzero로 반환할 수 있습니다.
정확한 CI 실행 집합은 workflow 파일의 조건을 기준으로 하며 기본 배포 gate와 수동 전체 검증을 구분합니다.

원시 로그의 비밀·개인정보는 공개 문서에서 제외합니다. 근거는 `docs/evidence/`,
현재 상태는 `PROGRESS.md`, 재사용할 사용법은 해당 가이드에 기록합니다.
