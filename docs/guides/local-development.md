# 로컬 빠른 시작

이 가이드는 PostgreSQL·Spring API·Dashboard를 실행하고 관리 화면에 연결하는 과정을 설명합니다.
실제 장비 연결이나 워크플로 배포는 기본 실행을 확인한 뒤 별도로 설정합니다.

## 준비 사항

Git, Node.js 22, Corepack 또는 pnpm 10.34.6, Python 3, curl, JDK 21과 Docker Compose가 필요합니다.
명령은 저장소 루트에서 실행합니다. 버전 기준은 [개발 환경](../reference/configuration.md)을 확인하세요.

Linux x86_64에서 JDK가 없으면 bootstrap이 검증된 Temurin을 `.tools/jdk`에 설치할 수 있습니다.
시스템 권한이나 Kubernetes context는 자동으로 변경하지 않습니다.

## 1. 개발 환경 준비

```bash
make setup
make check
```

`make setup`은 도구와 로컬 `.env`를 준비합니다. `.env.example`의 설명을 확인하고
생성된 `.env`와 `.tools`는 Git에 추가하지 않습니다. API·DB 주소와 포트는
[설정 참고](../reference/configuration.md)에 정리되어 있습니다.

## 2. 기반 서비스 시작

```bash
make up
```

Compose 프로젝트 `edgeai-dev`에서 PostgreSQL과 MQTT를 시작합니다.
Docker를 사용할 수 없는 Ubuntu 24.04 x86_64 환경에서는 `make db-start`로 프로젝트 전용 PostgreSQL을
실행할 수 있습니다. 이 대안은 DB만 제공하며 MQTT·MinIO·컨테이너 실행을 대체하지 않습니다.

## 3. API와 화면 실행

각각 별도 터미널에서 실행합니다.

```bash
# 터미널 1
make backend
```

```bash
# 터미널 2
make dashboard
```

| 확인 대상 | 기본 주소 | 기대 결과 |
|---|---|---|
| Dashboard | http://127.0.0.1:13080 | 운영 현황 화면 |
| 프로젝트 문서 | http://127.0.0.1:13080/docs | 문서 홈 |
| Swagger | http://127.0.0.1:18080/swagger-ui.html | 관리 API 설명 |
| API readiness | http://127.0.0.1:18080/actuator/health/readiness | DB 연결 포함 상태 |

현재 관리 API와 Dashboard는 로그인 계정을 요구하지 않습니다. 쓰기 요청에는 CSRF 세션이 필요하며
화면과 Swagger가 처리합니다. 실제 배포에서는 [접근 경계](../reference/access.md)를 확인하세요.

## 4. 연결 확인

```bash
make health
```

Dashboard의 서버 연결 상태와 API readiness를 함께 확인합니다. 화면이 열려도 API·DB 연결이
실패할 수 있습니다. 인프라 목록이 비어 있는 것은 외부 수집기 미설정일 수도 있습니다.
오류가 있으면 [문제 해결](../operations/troubleshooting.md)을 따릅니다.

## 선택 기능 연결

- 노드·센서 목록: [인프라 연결](infrastructure-inventory.md)
- CPU·GPU·NPU 측정: [Prometheus 연결](node-metrics.md)
- DDS 배포: [워크플로 배포](workflow-editor-integration.md)
- Runner 결과 저장: `make storage`로 MinIO를 준비한 뒤 [실행 조건](../reference/support.md)을 확인

워크플로 화면의 활성화와 Runner·VD·STREAM 실행 활성화는 별도 설정입니다.
`.env`를 바꾸면 해당 프로세스를 다시 실행해야 합니다.

## 종료

API와 Dashboard 터미널에서 각각 `Ctrl+C`로 종료한 뒤 기반 서비스를 종료합니다.

```bash
make down
# Docker 없는 PostgreSQL을 사용했다면
make db-stop
```

`make down`은 개발 컨테이너를 종료하고 데이터 볼륨을 보존합니다.
다음 단계: [첫 프로필 등록](../getting-started/first-profile.md).
