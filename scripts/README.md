# 명령 안내

평소에는 저장소 루트에서 **`make help`**를 사용하세요.

| 작업 | 명령 |
|---|---|
| 처음 준비 | `make setup` |
| 로컬 DB·MQTT 시작/종료 | `make up` / `make down` |
| API / 화면 실행 | `make backend` / `make dashboard` |
| 원본 Platform-Service 배포 API 실행 | `make platform-service` ([설정](../platform-service/README.md)) |
| MinIO 시작 | `make storage` |
| Docker 없는 로컬 PostgreSQL | `make db-start` / `make db-stop` |
| 개발 환경 점검 | `make check` |
| 실행 중인 앱 연결 확인 | `make health` |
| 기본 단위 테스트 | `make test` |
| 문서 링크·목차·API 목록 검사 | `bash scripts/test/test-docs.sh` |
| Mapper 단위/race 시험 | `bash scripts/test/test-mapper.sh unit` |

## 직접 스크립트를 수정할 때

| 경로 | 용도 |
|---|---|
| `dev/` | 개발 환경 준비·앱 실행·도구 설치 |
| `test/` | 목적별 검증 실행 명령; 필요한 의존성은 [검증 안내](../docs/testing/commands.md) 참조 |
| `demo/` | 워크플로·VD·다중 장치 실행 데모 |
| `ops/` | 백업·복원·권한 회수·복구 등 명시적 운영 명령 |
| `internal/` | 위 명령과 CI가 호출하는 Python/Node 구현·시험 보조 코드 |
| `lib.sh` | 공통 환경 로딩과 저장소 루트 설정 |
| `collect-evidence.sh` | 명령의 실제 종료 코드와 검증 기록 수집 |

예: `bash scripts/test/test-runner.sh`, `bash scripts/ops/backup-postgres.sh --help`.
Platform-Service 모의 연동 검사는 `bash scripts/test/test-platform-service.sh`다.
Profile/Device/VD 삭제 검사는 `bash scripts/test/test-registry-deletion.sh`다.
설정된 PostgreSQL에 별도 임시 DB를 만들고 검사 후 제거하며 앱 DB는 변경하지 않는다.
Python 구현과 `.sh` 진입점은 서로 다른 기능 두 개가 아닙니다.
`bash scripts/test/verify-all.sh full`은 구현된 검사를 모아서 실행합니다. 클러스터·저장소 등
각 검사에 필요한 환경을 요구하며, 미구현 종합 장애·실장비 수용 범위 때문에 nonzero를 반환합니다.
일상 개발의 기본 명령으로 실행하지 않습니다. 검증 로그는 Git에서 제외한 `output/verification/`에 생성합니다.

기존 `scripts/<파일>` 경로는 위 폴더로 옮겼습니다. CI·저장소 내부 호출도 새 경로를 사용합니다.

- `test/test-vd-sensor.sh`: Sense HAT 온도를 실제로 읽어 기존 Python Runner와 S3 Result까지 검증합니다. 센서 쓰기 없이 별도 DB·시험 bucket을 정리하며 Pod 인증은 fixture입니다.
- `test/isolated-db.sh`: 통합 시험이 개발 DB에 데이터를 남기지 않도록 createdb/dropdb와 종료 trap을 제공하는 내부 helper입니다. 직접 실행하지 않습니다.
