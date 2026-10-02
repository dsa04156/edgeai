# EdgeAI

설계 문서를 기준으로 처음부터 만드는 Edge AI Control Plane입니다.
Spring Boot modular monolith + Next.js + PostgreSQL을 기반으로 하며,
최종 Kubernetes 노드 선택은 kube-scheduler가 담당합니다.

**M0–M4 구현·검증을 완료**했습니다. Profile·장치/노드·Workflow 관리부터
실제 Kubernetes Runner 실행, MinIO 파일 검증과 결과 저장까지 연결했습니다.
로컬 실행은 기본 비활성이고 전용 클러스터 배포는 활성화되어 있습니다.
현재 M5 재시도 정책·새 Attempt 구현을 검증 중이며, 실행 중 오프로딩·Remote는 후속 작업입니다.
[M4 완료 근거](docs/evidence/m4-runtime.md)와 [M5 진행 기록](docs/evidence/m5-retry-offload.md)을 참고하세요.
전체 플랫폼의 `LOCAL_VERIFIED` 또는 `FULL_ACCEPTANCE` 상태를 의미하지 않습니다.

## 빠른 시작

필수: Git, Node 22, Corepack 또는 pnpm 10.34.6, Python 3, curl, JDK 21, Docker Compose.
Linux x86_64에서 JDK가 없으면 bootstrap이 검증된 Temurin 아카이브를 `.tools/jdk`에 설치합니다.
시스템 권한·Docker 그룹·기존 Kubernetes context는 변경하지 않습니다.

Ubuntu 24.04 x86_64에서 Docker 접근이 없으면 `bash scripts/dev-postgres-local.sh start`로
프로젝트 전용 PostgreSQL 16.15를 실행할 수 있습니다. 종료는 같은 명령의 `stop`입니다.
이 경로는 PostgreSQL만 대체하며 MQTT·MinIO·kind의 검증을 대신하지 않습니다.

```bash
bash scripts/bootstrap.sh
bash scripts/preflight.sh compose
bash scripts/dev-up.sh
```

각각 별도 터미널에서 실행합니다.

```bash
bash scripts/dev-backend.sh
bash scripts/dev-dashboard.sh
```

- Dashboard: <http://127.0.0.1:13080>
- Profile 관리: <http://127.0.0.1:13080/profiles> (`.env` 개발 계정으로 연결)
- 장치·노드 관리: <http://127.0.0.1:13080/devices>
- 워크플로·실행 요청 관리: <http://127.0.0.1:13080/workflows>
- Dashboard → API → PostgreSQL 상태: <http://127.0.0.1:13080/api/health>
- Swagger UI: <http://127.0.0.1:18080/swagger-ui.html>
- OpenAPI 계약: <http://127.0.0.1:18080/openapi.yaml>
- API readiness: <http://127.0.0.1:18080/actuator/health/readiness>
- API metadata: `GET /api/v1/platform` (로컬 Basic 인증 필요)
- PostgreSQL: `127.0.0.1:15432`, MQTT: `127.0.0.1:11883`

`.env`의 API 계정·비밀번호로 개발용 인증을 사용합니다. `.env`와 `.tools`는 커밋하지 않습니다.
사용 중인 포트가 있으면 `.env`에서 변경한 뒤 앱을 재시작합니다.
Profile·장치 등 관리 API만 사용할 때 MinIO는 선택 사항입니다. 실제 Runner 결과 저장에는 필요합니다.
로컬 실행: `bash scripts/dev-storage.sh`.
공식 커뮤니티 소스를 빌드하므로 첫 실행은 오래 걸릴 수 있습니다.
기동 후 `bash scripts/test-storage.sh`로 실제 S3 업로드·다운로드·metadata·비인증 차단을 확인합니다.

```bash
bash scripts/test-health.sh
bash scripts/dev-down.sh  # edgeai-dev 컨테이너만 종료; 데이터 볼륨 유지
```

## 검증

```bash
corepack pnpm --filter @edgeai/dashboard exec playwright install chromium
bash scripts/verify-all.sh scaffold
bash scripts/test-integration.sh  # 실제 PostgreSQL 필요
bash scripts/test-infra.sh        # Compose의 PostgreSQL·MQTT 필요
bash scripts/test-health.sh       # DB + API + Dashboard 실행 필요
bash scripts/test-profiles-stack.sh compose # 실제 Profile/Device/Workflow/Swagger UI + DB 장애·복구; 로컬 PG는 local
bash scripts/test-node-inventory.sh <context> # 기존 context는 변경하지 않고 실제 Node 목록만 읽음
bash scripts/test-storage.sh      # MinIO 실행 필요; 고유 probe bucket만 생성·제거
bash scripts/test-runtime-storage.sh # 실제 MinIO 버전·SHA-256·변조 거절
bash scripts/test-runtime-results.sh # PostgreSQL + MinIO: 실제 결과 확정·취소 경쟁
bash scripts/test-runner.sh       # 실제 Python 자식 프로세스 + 격리 HTTP fixture
```

`verify-all.sh local|full`은 미구현 fault/load/hardware 시험을 숨기지 않고 nonzero를 반환합니다.
모든 테스트는 실행 환경과 함께 기록하며 `docs/evidence/runs/`의 원시 로그는 Git에서 제외합니다.
GitHub Actions는 Linux/JDK 21/Node 22/Compose PostgreSQL 17 환경에서 M0–M4와 추가된 재시도 회귀를 검증합니다.
저장소·Runner 컨테이너와 실제3노드 kind 종단 시험이 이미지 발행 게이트에 포함됩니다.
실제 Kubernetes 노드 관측은 별도 클러스터 검증이며 CI fixture 시험과 구분합니다.

## Swagger UI

백엔드를 실행한 후 `/swagger-ui.html`을 엽니다. 브라우저 인증 창에는 `.env`의
`EDGEAI_API_USER` / `EDGEAI_API_PASSWORD`를 입력합니다. 문서와 API 모두 인증을 요구합니다.
Swagger의 **Try it out → Execute**로 API를 호출할 수 있으며, 쓰기 요청의 CSRF 토큰과
세션 쿠키는 자동으로 연결합니다. `Authorize`의 csrfToken 입력칸은 비워 두어도 됩니다.
문서의 예시는 연습용이며 POST를 실행하면 실제 개발 DB에 Profile이 발행됩니다.

화면은 `contracts/openapi/platform-api.yaml`을 빌드할 때 그대로 포함해 표시합니다.
포트를 바꾸면 같은 호스트의 API를 사용하며, Swagger 자산은 JAR에 포함되어 외부 CDN이나
온라인 validator에 연결하지 않습니다. 인증 정보는 Swagger 브라우저 저장소에 영속 저장하지 않습니다.

## CI / CD

- **CI 연결됨:** GitHub Actions가 push/PR마다 빌드, 단위·계약·PostgreSQL 통합,
  Dashboard/Swagger 브라우저 시험, DB 장애·복구, MQTT 및 MinIO 검증을 실행합니다.
- **이미지·GitOps:** 검증한 backend/Dashboard 이미지를 GHCR에 발행하고 digest를 Git에 기록합니다.
  ArgoCD `edgeai-dev`가 `deploy/kubernetes/overlays/dev`를 감시하도록 구성했습니다.
- 실제 연결·배포 검증 상태는 [진행 상태](PROGRESS.md), 초기 연결과 접속 주소는
  [CI/CD 문서](docs/cicd.md)를 확인하세요. GitHub 검사 성공과 Kubernetes 배포 건강 상태는 별도로 확인합니다.

## Profile 사용

`/profiles`에서 DEVICE / SERVICE / VD를 선택하고 키, `1.0.0` 형태의 버전,
비어 있지 않은 JSON 규격을 입력합니다. 같은 내용의 재등록은 기존 버전을 반환하고,
같은 버전의 다른 내용은 409로 거절합니다. 변경은 새 버전으로 발행합니다.
목록에서 버전을 누르면 저장된 내용과 digest를 조회할 수 있습니다.

규격은 현재 JSON 문서로 보관합니다. 장치 프로토콜·이미지·서비스 참조의 실행 호환성은
각 소비 기능을 구현할 때 검증합니다. 비밀번호·토큰은 규격에 넣지 않습니다.
직접 API를 호출할 때는 Basic 인증으로 `GET /api/v1/csrf`를 먼저 호출하고,
응답의 `EDGEAI_SESSION` 쿠키와 토큰(`X-CSRF-TOKEN`)을 POST에 함께 보냅니다.
상세 계약: [OpenAPI](contracts/openapi/platform-api.yaml), [M1 결정](docs/adr/0002-profile-registry.md).

Profile 통합 시험은 고유 `test-*`/`browser*` 키를 사용합니다. 발행 불변성 때문에
시험 행도 개발 DB에 보존합니다. 반복 시험에는 전용 개발 DB를 사용하세요.

## 장치·노드 사용

`/devices`에서 DEVICE Profile 버전 UUID를 선택하고 장치 키·이름·데이터 출처를 등록합니다.
Profile UUID는 `/profiles`의 상세에서 확인할 수 있습니다. 장치 등록 후 연결 상태는 보고 없음입니다.
장치 에이전트는 `/api/v1/devices/{id}/sessions`에 재접속마다 새 `bootId`를 보내고,
발급된 sessionId와 증가 sequence로 `/observations`에 상태를 보고합니다.
실제·재생·합성 데이터는 sourceMode로 구분합니다. Swagger에서 각 입력·응답·오류를 확인하세요.
API/UI 실행 후 `bash scripts/demo-device-lifecycle.sh`는 합성 장치를 등록·보고·재접속·해제합니다.

로컬 Node 관측은 기본 비활성입니다. Kubernetes 배포에서는 전용 ServiceAccount로
15초마다 실제 Node 목록을 읽습니다. 목록은 UID·Ready 상태·CPU/메모리 allocatable·아키텍처를
보여주며 마지막 관측이 60초를 넘으면 만료로 표시합니다. CPU/메모리는 현재 잔여량이 아닙니다.
로컬 설정은 `.env.example`의 `EDGEAI_KUBE_*`, 권한 구성은 [배포 문서](deploy/kubernetes/README.md),
장치/세션/이력 규칙은 [ADR 0003](docs/adr/0003-device-node-observation.md)을 따릅니다.

## 워크플로·실행 요청 사용

`/workflows`에서 워크플로 키·이름을 등록하고 SERVICE Profile 버전 UUID를 참조하는 DAG를
발행합니다. 작업 간 포트 연결과 BATCH/STREAM 모드를 정의하며 순환·잘못된 참조·중복 입력 포트는
거절합니다. 같은 버전의 같은 내용은 기존 버전을 반환하고, 내용 변경은 새 버전이 필요합니다.

발행한 버전을 선택해 AUTO 또는 관측된 Node UUID의 NODE 정책으로 실행 요청을 저장합니다.
`Idempotency-Key`는 요청을 재전송해도 실행을 중복 생성하지 않게 합니다. 다른 실행을 만들 때는
**새 실행 키 만들기**를 누릅니다. 작업별 Attempt와 상태를 조회하고 작업 또는 실행을 취소할 수 있습니다.
작업 취소는 그 결과를 기다리는 하위 작업을 건너뛰고 별도 분기는 유지합니다.

실행 기능이 비활성이면 root 작업은 READY/QUEUED, 나머지는 WAITING으로 요청을 보관합니다.
활성 배포에서는 실제 Runner가 작업을 수행하고 검증된 결과만 하위 작업에 전달합니다.
Run 생성의 선택적인 `retry`로 최대 시도 횟수·대기 시간·허용 기간·오류를 지정합니다.
재시도는 같은 Task에서 새 Attempt/epoch를 만들며 상세 계약은 Swagger RetryPolicy를 따릅니다.
STREAM 실행은 M7이며 현재 요청은 501입니다.
상세 계약은 [ADR 0004](docs/adr/0004-workflow-run-task.md)와 Swagger의 Workflow/실행/작업 태그를 따릅니다.

## 개발 기준

- [개발 지침](DEVELOPMENT.md), [계획](PLAN.md), [현재 진행 상태](PROGRESS.md)
- [설계 출처](docs/sources.md), [아키텍처](docs/architecture.md), [API 범위](docs/api-scope.md), [ERD](docs/erd.md)
- [구현 계약](docs/implementation-contract.md), [검증 기준](docs/verification-matrix.md)
- [OpenAPI](contracts/openapi/platform-api.yaml), [환경 호환성](docs/compatibility.yaml)
- [검증 증거](docs/evidence/index.md), [결정 기록](docs/adr/0001-greenfield-foundation.md)
- [초기 개발 환경 완료 감사](docs/evidence/m0-completion-audit.md)

로컬 개발용 인증·MQTT 설정은 운영 배포 구성이 아닙니다. 운영 identity/RBAC/TLS 및 실제 장비 검증은 후속 단계입니다.
