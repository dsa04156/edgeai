# EdgeAI

Spring Boot + Next.js + PostgreSQL 기반 Edge AI 관리 플랫폼입니다.
Profile·장치·노드·가상 장치를 관리하고, 시각 편집기로 워크플로를 구성해 Kubernetes에 실행합니다.

## 여기서 시작하세요

| 할 일 | 문서 |
|---|---|
| 처음 클론해서 실행 | [로컬 개발 안내](docs/guides/local-development.md) |
| 어디까지 개발됐는지 확인 | [현재 상태](PROGRESS.md) |
| 코드와 DB 구조 이해 | [아키텍처](docs/architecture/architecture.md) · [ERD](docs/architecture/erd.md) |
| GitHub Actions / ArgoCD 확인 | [배포 안내](docs/operations/cicd.md) |

## 자주 쓰는 명령

저장소 루트에서 실행합니다. `make help`로 명령 목록을 볼 수 있습니다.

```bash
make setup       # 개발 도구와 .env 준비
make up          # 로컬 PostgreSQL·MQTT 시작 (Docker Compose)
make backend     # Spring Boot — 별도 터미널
make dashboard   # Next.js — 별도 터미널
```

| 접속 | 기본 주소 |
|---|---|
| 화면 | http://localhost:13080 |
| Swagger | http://localhost:18080/swagger-ui.html |
| PostgreSQL | localhost:15432 / edgeai |

환경변수는 `.env.example`을 기준으로 `.env`에 설정합니다.
워크플로 편집·실행을 사용하려면 API와 Dashboard에 `EDGEAI_WORKFLOW_ENABLED=true`를 설정합니다.
새 `.env`에는 활성화돼 있으며, 이전 `.env`를 사용한다면 해당 값을 바꾸고 두 서버를 재시작합니다.
실제 작업 결과 저장에는 MinIO가 필요하며 `make storage`로 시작합니다.
Docker 없이 PostgreSQL만 실행하려면 `make db-start`를 사용합니다.
자세한 준비 조건과 종료 방법은 [로컬 개발 안내](docs/guides/local-development.md)를 확인하세요.

## 저장소 구조

```text
backend/     Spring Boot API·도메인·저장소
dashboard/  Next.js 화면
runner/      작업 실행·스트림 SDK
simulator/   장치·외부 시스템 시뮬레이터
contracts/   OpenAPI·프로필 규격
deploy/      Compose·Kubernetes·ArgoCD
scripts/     개발·검증·운영 명령과 내부 구현
docs/        용도별 문서
```

[개발 규칙](DEVELOPMENT.md) · [개발 단계](PLAN.md) · [전체 문서 안내](docs/README.md) · [스크립트 안내](scripts/README.md)

과거 설계 결정과 검증 기록은 `docs/adr`, `docs/evidence`, `docs/history`에 있습니다.
현재 개발 상태는 **PROGRESS.md 한 곳**에서 확인합니다.

Dashboard는 로그인 입력 없이 API에 연결합니다. Dashboard 서버에도 `EDGEAI_API_USER`와
`EDGEAI_API_PASSWORD`를 전달해야 하며, Kubernetes에서는 기존 runtime Secret을 참조합니다.
브라우저에 비밀번호를 전달하지 않습니다. 이 환경의 Dashboard에 접근 가능한 사용자는 관리 기능을 사용할 수 있습니다.
Profile → DeviceManager / Virtual Device → Workflow Builder → Run·Task·Placement 순서로 사용합니다.
당분간 Runner/STREAM 고도화는 중단하고 관리 화면과 API 완성을 우선합니다.
