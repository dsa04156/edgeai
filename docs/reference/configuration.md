# 설정 참고

설정은 실행 프로세스의 환경변수로 전달합니다. 로컬 개발 스크립트는 루트 `.env`를 읽으며
IDE·직접 Java 실행·컨테이너는 각 실행 환경에 같은 값을 전달해야 합니다.
설정 파일을 저장하는 것만으로 실행 중인 프로세스가 값을 다시 읽지는 않습니다.

## 기본 실행

| 변수 | 기본값·용도 |
|---|---|
| `EDGEAI_API_PORT` | Spring HTTP 포트, `18080` |
| `EDGEAI_BIND_ADDRESS` | API bind, 기본 `127.0.0.1` |
| `EDGEAI_DASHBOARD_PORT` | 개발/시작 스크립트의 화면 포트, `13080` |
| `EDGEAI_API_BASE_URL` | Dashboard가 접근할 API origin; 생략 시 loopback와 API 포트 |
| `EDGEAI_DB_HOST` / `PORT` | PostgreSQL, 기본 `127.0.0.1:15432` |
| `EDGEAI_DB_NAME` / `USER` / `PASSWORD` | DB 접속; 비밀번호는 로컬 환경 또는 Secret |

현재 관리 접근은 API 계정 로그인 없이 동작합니다. 이전 `.env`에 남은 API 계정 변수와
내부 Runner/VD 인증을 혼동하지 않습니다. [접근 경계](access.md)를 확인합니다.

## 인프라·센서·메트릭

| 변수 | 용도 |
|---|---|
| `EDGEAI_INFRASTRUCTURE_SOURCE` | `disabled` / `kubectl` / `api`; 앱 기본 `disabled` |
| `EDGEAI_INFRASTRUCTURE_CONTEXT` | kubectl 조회 context |
| `EDGEAI_KUBE_ENABLED` | Kubernetes observer 연결, 기본 `false` |
| `EDGEAI_KUBE_API_URL` / `TOKEN_FILE` / `CA_FILE` | Kubernetes API 연결·인증 |
| `EDGEAI_PROMETHEUS_ENABLED` / `URL` | 메트릭 조회; 기본 비활성 |
| `EDGEAI_EDGEX_ENABLED` | EdgeX 목록·측정·명령, 기본 `false` |
| `EDGEAI_EDGEX_METADATA_URL` | Core Metadata, 클러스터 기본 포트 `59881` |
| `EDGEAI_EDGEX_DATA_URL` | Core Data, 기본 포트 `59880` |
| `EDGEAI_EDGEX_COMMAND_URL` | Core Command, 기본 포트 `59882` |

개발 시작 스크립트는 미지정 인프라 설정에 대해 현재 context와 EdgeX Service IP를 탐색할 수 있습니다.
직접 앱 실행에는 이 탐색이 없습니다. [인프라 연결](../guides/infrastructure-inventory.md)을 확인합니다.

## 실행 기능

| 변수 | 기본값 | 의미 |
|---|---|---|
| `EDGEAI_WORKFLOW_ENABLED` | `true` | 워크플로 화면과 공개 API |
| `EDGEAI_RUNTIME_ENABLED` | `false` | 실제 Runtime 구성 |
| `EDGEAI_VD_ENABLED` | `false` | 지속 VD 실행 |
| `EDGEAI_REMOTE_ENABLED` | `false` | 참조 Remote 실행 |
| `EDGEAI_STREAM_ENABLED` | `false` | 스트림 기반 기능 |
| `EDGEAI_STREAM_BINDINGS_ENABLED` | `false` | 스트림 연결 관리 |
| `EDGEAI_STREAM_RUNS_ENABLED` | `false` | 공개 스트림 실행 |
| `EDGEAI_RECOVERY_INSPECT_ONLY` | `false` | 표시가 있는 복원 DB의 조회 전용 점검 |

활성화 값만으로 필요한 키·broker·저장소·ServiceAccount를 생성하지 않습니다.
Runtime 상세는 `.env.example`과 `backend/app/src/main/resources/application.yml`,
Remote는 [설정 안내](../operations/remote.md), 복원은 [복구 안내](../operations/backup-and-recovery.md)를 확인합니다.

## DDS 배포 서비스

`EDGEAI_PLATFORM_SERVICE_URL`의 기본값은 `http://127.0.0.1:18081`입니다.
`EDGEAI_PLATFORM_SERVICE_TOKEN`은 Dashboard와 FastAPI에서 동일하게 설정합니다.
Gitea·Argo CD·Buildx의 `PLATFORM_*` 변수는 [연동 설정](../../platform-service/README.md)을 따릅니다.
컨테이너에서 loopback은 해당 컨테이너 자신이므로 서버 간 접근 가능한 주소를 지정합니다.

## 개발 도구와 설정 원본

JDK 21, Node.js 22, pnpm 10.34.6을 사용합니다. Gradle은 저장소 wrapper를 실행합니다.
실제 라이브러리 버전은 Gradle 설정과 `pnpm-lock.yaml`, 이미지 digest는 배포 manifest가 기준입니다.
`docs/compatibility.yaml`은 기록 날짜의 환경 시험 결과이며 현재 배포 인증표가 아닙니다.
