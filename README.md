# EdgeAI

엣지 AI 서버·엣지 디바이스·센서·가상 장치를 관리하고 서비스 배포와 실행 상태를 조회하는 플랫폼입니다.
Spring Boot, Next.js, PostgreSQL을 기반으로 Kubernetes·Prometheus·EdgeX와 연결합니다.

## 시작하기

- [EdgeAI 소개](docs/getting-started/overview.md): 구성 요소와 사용 경로
- [로컬 빠른 시작](docs/guides/local-development.md): 준비·실행·연결 확인
- [첫 프로필 등록](docs/getting-started/first-profile.md): 등록·조회·정리 실습
- [문서 안내](docs/README.md): 개념·사용·운영·API·기여 문서

로컬 기본 화면은 `http://127.0.0.1:13080`, 문서는 `/docs`, Swagger는
`http://127.0.0.1:18080/swagger-ui.html`입니다.

```bash
make setup
make up
# 각각 별도 터미널에서 실행
make backend
make dashboard
```

사전 도구·종료·Docker 없는 DB 경로는 빠른 시작을 확인합니다.
기본 관리 실행과 실제 장비·워크플로 실행의 준비 조건은 다릅니다.

## 구조와 지원 범위

| 경로 | 역할 |
|---|---|
| `backend/` | Spring 관리 API·도메인·adapter |
| `dashboard/` | 관리 화면과 웹 문서 |
| `platform-service/` | DDS 편집·Buildx·Gitea·Argo CD 배포 API |
| `runner/`, `simulator/` | 작업 실행과 참조 구현 |
| `contracts/` | OpenAPI와 규격 |
| `deploy/`, `scripts/` | 배포 구성·개발·검증·운영 명령 |
| `docs/` | 사용자 안내와 설계·검증 기록 |

DDS 배포와 Spring DAG는 [서로 다른 경로](docs/concepts/workflows.md)입니다.
현재 일반 관리 API는 로그인 없이 접근하며 쓰기에는 CSRF가 필요합니다.
[접근 경계](docs/reference/access.md)와 [지원 범위](docs/reference/support.md)를 확인합니다.

최신 개발 상태는 [PROGRESS.md](PROGRESS.md), 단계별 잔여 범위는 [PLAN.md](PLAN.md)에 있습니다.
개별 기능 구현과 전체 플랫폼 실장비·성능·복구 수용을 구분합니다.

기여하려면 [개발 참여](docs/contributing/development.md)와 [문서 작성 기준](docs/contributing/documentation.md)을 읽으세요.
