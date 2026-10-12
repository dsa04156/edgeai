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
| `bash scripts/test/test-mapper.sh unit` | Go 1.24.2 toolchain의 프로토콜·DMI·동시성 race 시험 |

## 서비스와 통합

| 명령 | 필요한 환경·영향 |
|---|---|
| `make health` | 실행 중인 DB·API·Dashboard 조회 |
| `bash scripts/test/test-integration.sh` | PostgreSQL createdb/dropdb; 매번 별도 DB 생성·종료 시 삭제 |
| `bash scripts/test/test-registry-deletion.sh` | 별도 DB 생성·제거, 미사용 등록 삭제 검사 |
| `bash scripts/test/test-infra.sh` | Compose PostgreSQL·MQTT |
| `bash scripts/test/test-storage.sh` | MinIO; 시험 bucket 생성·정리 |
| `bash scripts/test/test-profiles-stack.sh local` | 실제 DB/API·브라우저; 별도 프로세스·장애 주입 |
| `bash scripts/test/test-ui.sh` | 화면 빌드·브라우저 검증; 서버 실행 여부 확인 |

브라우저가 필요한 환경은 `corepack pnpm --filter @edgeai/dashboard exec playwright install chromium`으로 준비합니다.
이미 실행 중인 사용자 서버를 검사할 때 서버 시작·종료가 포함된 전체 wrapper를 무조건 실행하지 않습니다.

Mapper 검사는 `test-mapper.sh unit`으로 실행합니다. 실행 중인 장치의 연결 상태와 최신값은
[센서 화면](../guides/sensor-registration.md)에서 확인합니다. 특정 시험 장치·주소·항목 수를
가정했던 일회성 검증 스크립트는 일반 테스트 명령에서 제외합니다.

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
`verify-all.sh`은 구현된 검사의 실패는 exit 1, 미실행·미구현으로 차단된 검사만 있으면 exit 2로 반환합니다.
`local|full`의 종합 장애·실장비 수용은 미구현이므로 다른 검사가 통과해도 전체 성공으로 표시하지 않습니다.
정확한 CI 실행 집합은 workflow 파일의 조건을 기준으로 하며 기본 배포 gate와 수동 전체 검증을 구분합니다.

원시 로그의 비밀·개인정보는 공개 문서에서 제외합니다. 일회성 로그·스크린샷·보고서는 Git에서 제외한 `output/`에 보관합니다.
현재 상태는 `PROGRESS.md`, 재사용할 사용법은 해당 가이드에 기록합니다.

VD 센서 BATCH의 실장비 입력 수용은 `bash scripts/test/test-vd-sensor.sh`로 실행합니다.
선택한 kubectl context의 `edgeai-devices/sensehat-001`을 읽고, 별도 PostgreSQL DB와
MinIO 시험 bucket에서 실제 Python supervisor·Runner·온도 변환·Result 검증을 수행합니다.
센서 CRD는 읽기만 하며 Kubernetes Pod 인증은 fixture입니다. 실제 Pod 배포 수용과 구분합니다.
MinIO endpoint는 `EDGEAI_STORAGE_URL`(기본 로컬 19000), 자격은 `.env`를 사용합니다.
`test-integration.sh`와 `test-runtime-results.sh`도 별도 DB를 자동 생성·삭제합니다.
이 PostgreSQL 계정에는 CREATEDB 권한이 필요합니다. 같은 worktree의 Gradle 작업은 순차 실행하세요.
