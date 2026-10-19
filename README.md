# EdgeAI

엣지 AI 서버·엣지 디바이스·측정 센서를 관리하고 연속 가상 센서와 서비스 실행을 구성하는 플랫폼입니다.
Kestra 원본 Vue 콘솔, Spring Boot, Next.js BFF와 PostgreSQL을 기반으로 Kubernetes·Prometheus·KubeEdge Mapper와 연결합니다.

## 시작하기

- [EdgeAI 소개](docs/getting-started/overview.md): 구성 요소와 사용 경로
- [로컬 빠른 시작](docs/guides/local-development.md): 준비·실행·연결 확인
- [첫 프로필 등록](docs/getting-started/first-profile.md): 등록·조회·정리 실습
- [연속 가상 센서](docs/guides/vd-processing.md): 입력·계산·출력 등록과 운영 활성화 조건
- [문서 안내](docs/README.md): 개념·사용·운영·API·기여 문서

Kestra 원본 콘솔은 `bash scripts/dev/dev-kestra-native.sh`로 실행하며
`http://127.0.0.1:13083/ui/main/dashboards/default`에서 사용합니다. Kestra 서버·DB 설치 없이
기존 EdgeAI API를 연결합니다. 아래 기본 웹13080은 BFF·문서와 기존 화면을 제공하며 자동 교체하지 않습니다.
실행 조건·메뉴별 기능은 [로컬 빠른 시작](docs/guides/local-development.md#kestra-원본-vue-운영-콘솔)에 있습니다.

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
새 콘솔은 Kestra의 표·드로어·폼·그래프/코드 패널을 사용합니다. 센서는 측정 항목별 Device·DeviceStatus CRD로 분리하고, 중앙 eKuiper의 연속 VD는 여러 입력과 출력별 결과를 관리합니다. 구형 VD BATCH·supervisor와 DEVICE 신규 발행은 종료됐습니다. 연속 처리 기본값은 비활성이며 eKuiper 설치만으로 실제 계산·이력 저장을 시작하지 않습니다.
현재 일반 관리 API는 로그인 없이 접근하며 쓰기에는 CSRF가 필요합니다.
[접근 경계](docs/reference/access.md)와 [지원 범위](docs/reference/support.md)를 확인합니다.

최신 개발 상태는 [PROGRESS.md](PROGRESS.md), 단계별 잔여 범위는 [PLAN.md](PLAN.md)에 있습니다.
개별 기능 구현과 전체 플랫폼 실장비·성능·복구 수용을 구분합니다.

기여하려면 [개발 참여](docs/contributing/development.md)와 [문서 작성 기준](docs/contributing/documentation.md)을 읽으세요.
