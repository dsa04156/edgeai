# 빠른 시작

일상 실행은 저장소 루트에서 `make help`를 확인하세요.
`make setup` → `make up` 뒤 별도 터미널에서 `make backend`, `make dashboard`를 실행합니다.
아래는 같은 명령의 스크립트 경로와 상세 설정입니다.

필수: Git, Node 22, Corepack 또는 pnpm 10.34.6, Python 3, curl, JDK 21, Docker Compose.
Linux x86_64에서 JDK가 없으면 bootstrap이 검증된 Temurin 아카이브를 `.tools/jdk`에 설치합니다.
시스템 권한·Docker 그룹·기존 Kubernetes context는 변경하지 않습니다.

Ubuntu 24.04 x86_64에서 Docker 접근이 없으면 `bash scripts/dev/dev-postgres-local.sh start`로
프로젝트 전용 PostgreSQL 16.15를 실행할 수 있습니다. 종료는 같은 명령의 `stop`입니다.
이 경로는 PostgreSQL만 대체하며 MQTT·MinIO·kind의 검증을 대신하지 않습니다.

```bash
bash scripts/dev/bootstrap.sh
bash scripts/dev/preflight.sh compose
bash scripts/dev/dev-up.sh
```

각각 별도 터미널에서 실행합니다.

```bash
bash scripts/dev/dev-backend.sh
bash scripts/dev/dev-dashboard.sh
```

- Dashboard: <http://127.0.0.1:13080>
- Profile 관리: <http://127.0.0.1:13080/profiles> (`.env` 개발 계정으로 연결)
- 장치·노드 관리: <http://127.0.0.1:13080/devices>
- 가상 장치·원본 연결 관리: <http://127.0.0.1:13080/virtual-devices>
- 워크플로·실행 요청 관리: <http://127.0.0.1:13080/workflows>
- 관리 요청 감사 기록: <http://127.0.0.1:13080/audit> (접수·인증 주체·HTTP 결과 및 미확정 구분)
- Dashboard → API → PostgreSQL 상태: <http://127.0.0.1:13080/api/health>
- Swagger UI: <http://127.0.0.1:18080/swagger-ui.html>
- 스트림 내부 API 설명: <http://127.0.0.1:18080/swagger-ui/index.html?contract=streams>
- OpenAPI 계약: <http://127.0.0.1:18080/openapi.yaml>
- API readiness: <http://127.0.0.1:18080/actuator/health/readiness>
- API metadata: `GET /api/v1/platform` (로컬 Basic 인증 필요)
- PostgreSQL: `127.0.0.1:15432`, MQTT: `127.0.0.1:11883`

`.env`의 API 계정·비밀번호로 개발용 인증을 사용합니다. `.env`와 `.tools`는 커밋하지 않습니다.
사용 중인 포트가 있으면 `.env`에서 변경한 뒤 앱을 재시작합니다.
Profile·장치 등 관리 API만 사용할 때 MinIO는 선택 사항입니다. 실제 Runner 결과 저장에는 필요합니다.
로컬 실행: `bash scripts/dev/dev-storage.sh`.
공식 커뮤니티 소스를 빌드하므로 첫 실행은 오래 걸릴 수 있습니다.
기동 후 `bash scripts/test/test-storage.sh`로 실제 S3 업로드·다운로드·metadata·비인증 차단을 확인합니다.

```bash
bash scripts/test/test-health.sh
bash scripts/dev/dev-down.sh  # edgeai-dev 컨테이너만 종료; 데이터 볼륨 유지
```
