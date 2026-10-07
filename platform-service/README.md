# Platform-Service 연동

`jinuk/Platform-Service`의 편집기 → FastAPI → Gitea → Argo CD 구조를 유지한다.
원본은 변경하지 않고 EdgeAI 대시보드 `/workflows`에 연결했다. 기존 Spring DAG API는
이 배포 경로에 관여하지 않는다.

## 흐름

1. 원본 센서·카메라·Discovery 템플릿으로 구성하고 노드를 더블클릭해 설정한다.
2. **배포 전 Buildx 빌드·푸시**를 선택하면 서버에 고정된 Dockerfile/context로
   이미지를 빌드·푸시한다. 반환된 `repo@sha256:…`를 해당 DDS 이미지에 적용한다.
   직접 지정한 다른 저장소의 이미지는 유지한다. 실패하면 Git 저장을 시작하지 않는다.
3. **Deploy to GitOps**는 원본 `save-and-push` 요청으로 `deployment.yaml`을 로컬에
   저장하고 Gitea `<app>/deployment.yaml`을 생성/갱신한다. Argo 앱이 없으면 만들고
   있으면 hard refresh한다. Argo의 자동 동기화·prune·selfHeal 설정은 원본과 같다.
4. 화면은 Argo의 실제 sync/health를 조회한다. API 수락을 실행 성공으로 표시하지 않는다.
   Git 저장 후 Argo 단계가 실패하면 Git 저장 완료와 Argo 실패를 함께 표시한다.
5. **Delete App**은 확인 후 Argo cascade 삭제 → Gitea YAML 삭제 → 로컬 삭제 순서다.
   삭제 요청과 리소스 종료는 별개이며 앱 삭제 상태를 계속 조회한다.

원본 Sensor Pub은 시험 데이터를 발행한다. EdgeX 물리 센서를 자동으로 연결하지 않는다.
캔버스 연결선은 편집 정보이며 실행 순서나 DDS 연결을 생성하지 않는다. DDS 프로필,
토픽, 환경 변수, nodeSelector가 실제 통신·배치에 적용된다. YAML 파일은 노드 좌표와
연결을 annotation에 보존한다. 기본 Discovery IP와 `environment=edge` 배치는 원본
방식이므로 배포할 클러스터에 맞게 편집한다. 같은 호스트의 Discovery 11811 포트
충돌 여부와 대상 노드의 이미지 아키텍처는 실제 배포 전에 확인해야 한다.

## 실행 준비

저장소 루트에서 환경을 준비한다. 실행 환경마다 가상환경과 로컬 `.env`를 준비한다. 이 절차의 서버 기동과 실제 빌드·Git 저장·Argo 배포는 별도 단계다.

```bash
python3 -m venv .tools/platform-service-venv
.tools/platform-service-venv/bin/pip install -r platform-service/requirements-test.txt
```

`.env.example`의 Platform-Service 항목을 로컬 `.env`에 설정한다.

| 설정 | 용도 |
|---|---|
| `EDGEAI_PLATFORM_SERVICE_URL` | Dashboard 서버가 접근할 FastAPI 주소, 기본 `http://127.0.0.1:18081` |
| `EDGEAI_PLATFORM_SERVICE_TOKEN` | Dashboard와 FastAPI 사이의 동일한 임의 토큰 |
| `PLATFORM_GITEA_URL`, `TOKEN`, `OWNER`, `REPO`, `BRANCH` | 원본 Gitea 저장 대상 |
| `PLATFORM_ARGOCD_URL`, `TOKEN` 또는 `USER`/`PASSWORD` | Argo API 인증 |
| `PLATFORM_ARGOCD_PROJECT`, `NAMESPACE` | 기본값은 원본처럼 `default` |
| `PLATFORM_WORKSPACE` | 로컬 YAML과 빌드 로그를 보관할 쓰기 가능한 절대 경로 |
| `PLATFORM_BUILD_CONTEXT` | 이 디렉터리 아래 `dds-k8s-project`의 절대 경로 |
| `PLATFORM_IMAGE_REPOSITORY` | 태그를 제외한 이미지 저장소 |
| `PLATFORM_BUILD_PLATFORMS` | 기본 `linux/arm64`; 필요하면 `linux/amd64,linux/arm64` |
| `PLATFORM_BUILDX_BUILDER` | 선택: 이미 준비된 Buildx builder 이름 |

표의 `TOKEN`, `OWNER` 등 축약 항목에도 같은 접두사(`PLATFORM_GITEA_` 등)를 붙인다.
인증 정보는 서버 환경에서만 읽으며 브라우저 응답에 포함하지 않는다. Argo가 해당 Git
저장소를 읽을 수 있어야 하며, Buildx builder에는 레지스트리 접속 설정이 필요하다.
원본의 HTTP 레지스트리용 `buildkitd.toml`을 포함했지만 builder를 자동 생성하지 않는다.

설정을 준비한 뒤 별도 터미널에서 실행한다:

```bash
make platform-service
# 다른 터미널: 기존 Dashboard를 종료한 뒤 환경 설정을 다시 읽어 실행
make dashboard
```

FastAPI는 loopback 18081, 단일 worker로 실행한다. 동시에 들어온 빌드·배포·삭제는
409로 거절한다. 시작만으로 빌드·배포하지 않으며 GET 접근 로그는 출력하지 않는다.
컨테이너 등 별도 호스트에서 실행할 경우 서버 간 주소와 토큰을 해당 환경에 설정해야 한다.

실제 Buildx를 쓰려면 Docker에 접근 가능한 계정으로 API를 실행해야 한다.
소켓 접근이 거절되면 실행 계정과 builder의 연결을 확인한다. 빌드 로그는 `PLATFORM_WORKSPACE/builds/<id>/build.log`,
제한 시간은 15분이다. 브라우저 연결이 끊겨도 이미 시작된 서버 작업은 진행될 수 있으므로
재시도 전에 Git·Argo·빌드 로그를 확인한다.

## 검증

```bash
bash scripts/test/test-platform-service.sh
```

외부 호출을 대체한 Python 11개 검사, OpenAPI/생성 타입 일치, Next.js 프록시 검사를
수행한다. Docker 빌드나 운영 저장소·클러스터 쓰기는 하지 않는다.
FastAPI 변경 후 계약 갱신:

```bash
.tools/platform-service-venv/bin/python scripts/internal/export-platform-service-contract.py
corepack pnpm contract:generate
```

브라우저 검증 범위와 실제 환경의 남은 확인은
[검증 기록](../docs/evidence/platform-service-integration-2026-10-06.md)에 정리했다.
