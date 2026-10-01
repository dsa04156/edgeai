# EdgeAI

설계 문서를 기준으로 처음부터 만드는 Edge AI Control Plane입니다.
Spring Boot modular monolith + Next.js + PostgreSQL을 기반으로 하며,
최종 Kubernetes 노드 선택은 kube-scheduler가 담당합니다.

현재 범위는 **M0 개발 환경**입니다. Profile·Device·Workflow·Runner 등 도메인 기능은 아직 구현하지 않았습니다.
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
- Dashboard → API → PostgreSQL 상태: <http://127.0.0.1:13080/api/health>
- API readiness: <http://127.0.0.1:18080/actuator/health/readiness>
- API metadata: `GET /api/v1/platform` (로컬 Basic 인증 필요)
- PostgreSQL: `127.0.0.1:15432`, MQTT: `127.0.0.1:11883`

`.env`의 API 계정·비밀번호로 개발용 인증을 사용합니다. `.env`와 `.tools`는 커밋하지 않습니다.
사용 중인 포트가 있으면 `.env`에서 변경한 뒤 앱을 재시작합니다.
MinIO는 M4 결과 저장을 위한 선택적 구성입니다: `bash scripts/dev-storage.sh`.
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
bash scripts/test-health-stack.sh compose # 앱을 테스트 전용으로 띄우고 프로젝트 DB 장애·복구까지 확인
bash scripts/test-storage.sh      # MinIO 실행 필요; 고유 probe bucket만 생성·제거
```

`verify-all.sh local|full`은 미구현 kind/fault/hardware 시험을 숨기지 않고 nonzero를 반환합니다.
모든 테스트는 실행 환경과 함께 기록하며 `docs/evidence/runs/`의 원시 로그는 Git에서 제외합니다.
GitHub Actions는 Linux/JDK 21/Node 22/Compose PostgreSQL 17 환경에서 M0를 검증합니다.

## 개발 기준

- [개발 지침](DEVELOPMENT.md), [계획](PLAN.md), [현재 진행 상태](PROGRESS.md)
- [설계 출처](docs/sources.md), [아키텍처](docs/architecture.md), [API 범위](docs/api-scope.md), [ERD](docs/erd.md)
- [구현 계약](docs/implementation-contract.md), [검증 기준](docs/verification-matrix.md)
- [OpenAPI](contracts/openapi/platform-api.yaml), [환경 호환성](docs/compatibility.yaml)
- [검증 증거](docs/evidence/index.md), [결정 기록](docs/adr/0001-greenfield-foundation.md)

로컬 개발용 인증·MQTT 설정은 운영 배포 구성이 아닙니다. 운영 identity/RBAC/TLS 및 실제 장비 검증은 후속 단계입니다.
