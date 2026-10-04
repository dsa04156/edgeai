# EdgeAI

설계 문서를 기준으로 처음부터 만드는 Edge AI Control Plane입니다.
Spring Boot modular monolith + Next.js + PostgreSQL을 기반으로 하며,
최종 Kubernetes 노드 선택은 kube-scheduler가 담당합니다.

**M0–M4 구현·검증을 완료**했습니다. Profile·장치/노드·Workflow 관리부터
실제 Kubernetes Runner 실행, MinIO 파일 검증과 결과 저장까지 연결했습니다.
로컬 실행은 기본 비활성이고 전용 클러스터 배포는 활성화되어 있습니다.
M5 재시도와 명시적 실행 중 노드 전환은 실제 kind·CI·배포 검증을 통과했습니다.
실행 측정·자동 전환·Remote 참조 adapter와 RemoteAllocation·결과 확정은 CI·배포까지 검증했습니다.
Remote 자동 worker·공개 실행/전환·제공자 설정 고정은 실제 PostgreSQL·MinIO·참조 제공자로 로컬 검증했습니다.
worker와 실제 kind Kubernetes↔Remote 전환·API 재시작/취소까지 CI·배포 검증을 통과했습니다.
외부 실제 시스템 수용과 상태형 복원은 남아 있습니다. M6 VD 등록·원본 연결·실행 관리와
VD Task 배정·Runner·결과 API/화면을 연결했습니다. 실제 Kubernetes의 Task·결과·API 재시작·
활성 작업 중 교체·취소·재시도와 새 이미지의 CI·배포·실제 PC/모바일 결과 화면까지 통과했습니다.
[M6 완료 범위와 근거](docs/evidence/m6-completion-audit.md)를 확인하세요. 현재 M7 다중 장치·스트리밍을 구현 중입니다.
MQTT 전달·로컬 journal·DataRoute 세대 관리와 [broker 권한 발급/회수](docs/evidence/m7-stream-broker.md)를
구성 요소별로 검증했습니다. [권한 worker](docs/evidence/m7-stream-worker.md)의 실제 DB/TLS broker
자동 조정은 CI·배포까지 검증했습니다. 현재 Device 세션 토큰과 Runner/Pod 인증을 확인하는
[스트림 배정 API](docs/evidence/m7-stream-bindings.md)를 실제 HTTP/DB/TLS broker로 로컬 검증했으며,
[공개 STREAM 실행 요청·그룹 배정](docs/evidence/m7-public-stream-runs.md)을 선택적으로 활성화할 수 있습니다.
기본값은 비활성입니다. 격리된 실제 Kubernetes의 TLS 스트림 실행은 검증했으며,
운영 배포·그룹 복구를 포함한 전체 수용은 진행 중입니다.
SDK의 배정 검증·lease 만료/MQTT 종료·journal rollback과
[양쪽 heartbeat 갱신](docs/evidence/m7-stream-heartbeat.md)을 구현했습니다. 실제 Spring→Python→TLS MQTT에서
기한 갱신 뒤 계산·상태 저장·처리 확인을 검증했고, 한쪽 부재·재전송으로 기한이 늘어나지 않습니다.
[지속 계산 프로세스·watchdog](docs/evidence/m7-stream-workload.md)을 실제 TLS MQTT·Spring·SQLite로
검증했습니다. 모델 재사용·출력 적체·로컬 상태 복원·기한 만료 종료를 포함합니다.
[자동 세션 실행 루프](docs/evidence/m7-stream-session.md)는 인증 배정과 heartbeat 순번 재개·
응답 유실/일시 오류 재시도·계산 정리를 연결하며 실제 Spring 인증 경로로 검증했습니다.
[외부 체크포인트 SDK](docs/evidence/m7-stream-checkpoint.md)는 확인 전 ACK/출력 제한과
동일 binding의 새 볼륨 복원, 실제 S3 고정 버전·TLS MQTT/모델 재개를 검증했습니다.
[인증된 체크포인트 확정 API](docs/evidence/m7-stream-checkpoint-api.md)는 현재 실행 주체·전체 경로와
실제 S3 내용을 검증하고 불변 DB 이력을 저장합니다.
[Session 자동 저장](docs/evidence/m7-stream-checkpoint-publisher.md)은 같은 후보 재시도와 인증된 확정 응답 적용을 연결합니다.
[인증된 새 볼륨 복원](docs/evidence/m7-stream-checkpoint-recovery.md)은 서버 latest의 고정 S3 파일을
검증하고 동일 Attempt·경로의 상태와 미확인 출력/END를 이어갑니다.
[서버 검증 인계](docs/evidence/m7-stream-checkpoint-handover.md)는 이전 실행 종료와 경로 권한 회수를 확인하고
계산 상태를 새 Attempt·세대의 고정 S3 파일로 옮깁니다. 장치 세션 교체·인접 Task journal을 포함한
그룹 복구와 운영 broker 수용은 남아 있습니다.
[Device 송신 journal 인계](docs/evidence/m7-device-source-handover.md)는 같은 장치 세션의 미확인
샘플·순번을 보존하며 실제 broker 권한 회수 뒤 새 경로로 재전송하고 계산을 이어갑니다.
[SERVICE·Runner 스트림 실행](docs/evidence/m7-service-stream-runner.md)은 지속 계산과 최종 파일
생성을 구분하고, 외부 체크포인트와 서버 완료 허가 후 결과를 만듭니다. Runner 소비 경로를
검증했습니다. 후속 [서버 배정·공동 완료](docs/evidence/m7-stream-execution-completion.md)는
경로 고정과 참여자별 종료 확인을 영속화하고 허가 전 Result 확정을 막습니다.
[DeviceSource 공동 완료](docs/evidence/m7-device-source-completion.md)는 실제 Spring/PG/S3/TLS MQTT의
종료·결과 저장과 경로 회수 후 장치 재시작을 연결합니다.
[Runner 최종 상태 복구](docs/evidence/m7-finalizer-recovery.md)는 현재 실행의 완료 허가를 확인하고
MQTT 없이 S3 체크포인트에서 최종 파일을 생성합니다. 실제 Spring/S3/Runner를 연결했으며,
공개 실행 생성·그룹 동시 배정은 ADR0041에서 연결했습니다.
[다중 Runner DAG](docs/evidence/m7-stream-dag.md)는 두 장치→독립 STREAM Runner 두 개→고정 S3
결과→BATCH Runner의 실제 데이터 계산과 중간 작업 취소를 검증했습니다. Pod 생성·신원은 시험용 대역입니다.
[실제 Kubernetes 스트림](docs/evidence/m7-kubernetes-stream.md)은 TLS API/S3/broker, 실제 scheduler와
Pod 신원으로 AUTO/NODE DAG·API 교체·취소·고정 S3 파일6개를 검증했습니다.
현재 JAR 실행과 빌드 이미지·CI 게이트의 검증 범위를 해당 기록에서 구분합니다.
상세는 [Remote worker 검증 기록](docs/evidence/m5-remote-worker.md)을 따릅니다.
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
bash scripts/test-runtime-results.sh # PostgreSQL + MinIO + Mosquitto/Paho: 결과·Remote/VD·스트림 공동 완료
bash scripts/test-runner.sh       # 실제 Python 자식 프로세스 + 격리 HTTP fixture
bash scripts/test-stream.sh       # 고정 Paho Python 의존성 + 실제 Mosquitto: 다중 입력·복구·ACL·TLS
bash scripts/test-stream-broker.sh # 실제 PostgreSQL + Mosquitto dynamic security: 권한 수명·세대 전환
bash scripts/test-remote.sh       # 실제 Remote 참조 프로세스/HTTP/SQLite·파일·장애 시험
bash scripts/test-load.sh --measure-only # 별도 API/전용 PG DB: 100→300→1,000대 관리 부하 측정
bash scripts/test-load-acceptance.sh # 실제 API/DB로 측정·미정·실패·부분 규모 판정 회귀
bash scripts/test-postgres-backup.sh # 전용 DB/API의 실제 백업·새 DB 복원; Compose PG는 --transport compose
bash scripts/install-minio-client.sh # object version을 유지하는 백업용 공식 mc 설치·SHA 검증
bash scripts/test-storage-backup.sh # 격리 TLS MinIO 두 개: 버전 복제·원본 유실·백업 재시작·거절 시험
bash scripts/test-recovery-references.sh # 실제 DB 복원·전체 결과/checkpoint 참조 대조·원본 유실·누락 거절
python3 scripts/test-recovery-kubernetes.py # 복원 DB/실행 목록 분류·페이지 경계 회귀
bash scripts/test-recovery-kubernetes-live.sh --context <시험-context> # 실제 복원 DB와 별도 Kubernetes namespace
python3 scripts/test-recovery-stop.py # 종료 상태·신원·불확실한 종료 거절 판정
bash scripts/test-recovery-stop-live.sh --context <시험-context> # 실제 부모/자식 종료·생성 차단·timeout/재개
bash scripts/test-recovery-database-fence.sh # 격리 DB/API의 연결 차단·다른 DB 보존·미완료 쓰기 rollback
bash scripts/test-recovery-mqtt-fence.sh # 격리 TLS broker의 관리자 교체·기존 연결 차단·중단/재개·재시작
bash scripts/test-recovery-storage-fence.sh # 격리 TLS MinIO의 root/URL 차단·실제 진행 요청 소진·버전 보존25개
bash scripts/install-age.sh # 고정 공식 age 배포 파일·실행 파일 checksum 확인
bash scripts/test-private-material.sh # 합성 키의 실제 암호화·복원·손상/경로/덮어쓰기 거절
bash scripts/test-management-audit.sh # 격리 API/DB의 감사 접수·결과 저장 실패·재시작·비밀값 배제
```

`verify-all.sh local|full`은 미구현 fault/hardware 시험과 부하 성능 기준 미정을 숨기지 않고 nonzero를 반환합니다.
관리 부하의 측정 범위·실행 방법·합격 판정은 [부하 시험 문서](docs/load-testing.md)를 따릅니다.
DB 백업과 새 DB로의 복원은 [백업 실행 문서](docs/postgres-backup.md)를 따릅니다.
관리 API의 변경 접수와 HTTP 결과는 [감사 기록 안내](docs/management-audit.md)를 따릅니다.
Swagger의43개 관리 operation에 감사 목록/UUID 조회를 포함하며 HTTP 응답과 실제 작업 완료를 구분합니다.
고정 S3 버전 복제와 독립 검증은 [MinIO 백업 문서](docs/storage-backup.md)를 따릅니다.
정적 Secret·CA 파일의 암호화 백업과 새 경로 복원은 [키 파일 백업 문서](docs/private-material-backup.md)를 따릅니다.
복원 DB의 모든 결과/checkpoint 참조 대조는 [DB/S3 복원 검증](docs/recovery-references.md)을 따릅니다.
복원 DB의 일반 API 기동은 차단하며, 조회는 [복구 점검 모드](docs/recovery-inspection.md)를 사용합니다.
DB에 없는 실행까지 찾는 조회 전용 [Kubernetes 복구 점검](docs/recovery-kubernetes.md)을 제공합니다.
관측한 실행의 [생성 차단과 종료 확인](docs/recovery-producer-stop.md)은 전용 namespace에서 수행합니다.
[원본 DB 연결 차단](docs/recovery-database-fence.md)은 명시한 원본 DB의 새 연결을 막고 기존 연결 종료를 확인합니다.
[원본 MQTT 차단](docs/recovery-mqtt-fence.md)은 기존 API의 관리 자격을 회수하고 Device/Task 접속을 차단합니다.
[원본 S3 root 차단](docs/recovery-storage-fence.md)은 파일 버전을 보존하면서 원래 자격과 기존 URL의 새 요청을 차단합니다.
[진행 중 S3 요청 확인](docs/recovery-storage-drain.md)은 차단 전에 인증된 요청까지 끝났는지 단일 MinIO에서 별도로 검증합니다.
[참조 Remote 복구 차단](docs/recovery-remote-fence.md)은 새 작업과 늦은 입력을 막고 기존 계산 스레드 종료를 확인합니다.
MinIO 파일·외부 인증 키·실행 중 작업을 포함한 [M9 전체 복구](docs/m9-requirements.md)는 별도 검증이 필요합니다.
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
상단의 **스트림 배정 API**는 `contracts/openapi/stream-api.yaml`의 내부 API 13개를 표시합니다.
배정 조회·heartbeat·체크포인트 저장/복원/인계·완료 후 상태 복구·실행 배정·Task/Device 공동 완료의 역할과
순번·재전송·기한 갱신/거절 조건을 설명합니다. Device 세션 토큰과
Runner Attempt/Pod 인증의 차이, 입출력·권한·lease 조건을 설명합니다. 관리자가 Device의
현재 세션에 발급하는 `stream-token`은 관리 API 문서의 Device 그룹에 있습니다.
Device 전용 경로 조회는 해당 Run에 고정된 본인 경로·최신 세대만 반환하며 전송 자격 발급과는 별개입니다.
[Device 경로 조회](docs/evidence/m7-device-route-discovery.md)의 페이지·세션 교체·SDK 사용 범위를 따릅니다.
배정은 `EDGEAI_STREAM_ENABLED=true`와 `EDGEAI_STREAM_BINDINGS_ENABLED=true`, 별도 장치 서명 키가
필요합니다. [서버 배정·공동 완료](docs/evidence/m7-stream-execution-completion.md)는 구성 요소
검증을 통과했습니다. 공개 실행에는 추가로 `EDGEAI_STREAM_RUNS_ENABLED=true`와 runtime 활성화가 필요합니다.
Run 태그의 스트림 경로 조회를 포함한 관리 API는41개이며 운영 기본 설정은 공개 STREAM 비활성입니다.
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

발행한 버전을 선택해 AUTO, 관측된 Node UUID의 NODE 또는 서버에 설정된 제공자의 REMOTE 정책으로 실행 요청을 저장합니다.
Remote 활성화·제공자·파일 설정은 [Remote 실행 문서](docs/remote.md)를 따릅니다.
`Idempotency-Key`는 요청을 재전송해도 실행을 중복 생성하지 않게 합니다. 다른 실행을 만들 때는
**새 실행 키 만들기**를 누릅니다. 작업별 Attempt와 상태를 조회하고 작업 또는 실행을 취소할 수 있습니다.
작업 취소는 같은 스트림 그룹과 후속 의존 그룹도 정리하며 별도 분기는 유지합니다.

BATCH 실행에서는 **작업별 실행 위치**로 AUTO/NODE/VD/REMOTE 최초 대상을 지정할 수 있습니다.
API는 `taskExecutions`에 발행된 DAG 작업 키를 사용합니다. 예:
`{"decode":{"mode":"AUTO"},"infer":{"mode":"NODE","nodeId":"<Node UUID>"}}`.
생략한 작업은 Run의 `execution`을 따릅니다. BATCH 하위 작업과 STREAM 그룹 모두 적용되며,
Task의 `initialMode`/`initialNodeId`/`initialVdId`/`initialRemoteTarget`은 대기 중인 작업의
최초 배치도 보여 주며 Attempt는 현재 실행 위치를 보여 줍니다. VD는 해당 작업과 같은 SERVICE
버전의 Ready VD UUID, Remote는 서버에 설정된 제공자 키를 사용합니다. Run 기본 정책을
바꿔도 작업별 선택을 유지하며 **기본 실행 정책 따름**을 선택한 작업만 Run 정책을 따릅니다.
전환 뒤 재시도는 전환된 위치를 유지합니다. STREAM은 AUTO/NODE만 지원하며 VD/REMOTE를
포함한 실행의 자동 전환은 지원하지 않습니다.
상세 계약·검증은 [혼합 배치](docs/adr/0054-mixed-task-targets.md)를 따릅니다.

실행 기능이 비활성이면 root 작업은 READY/QUEUED, 나머지는 WAITING으로 요청을 보관합니다.
활성 배포에서는 실제 Runner가 작업을 수행하고 검증된 결과만 하위 작업에 전달합니다.
Run 생성의 선택적인 `retry`로 최대 시도 횟수·대기 시간·허용 기간·오류를 지정합니다.
재시도는 같은 Task에서 새 Attempt/epoch를 만들며 상세 계약은 Swagger RetryPolicy를 따릅니다.
실행 중인 작업을 선택하면 **실행 위치 전환**에서 NODE 또는 Remote를 요청할 수 있습니다. 일반 SERVICE는
`recovery.mode=RESTART`를 선언하고 이전 실행 종료 뒤 고정 입력으로 다시 시작합니다. CHECKPOINT
STREAM은 NODE 전환을 지원하며 연결된 그룹 전체가 외부 체크포인트에서 재개합니다.
전환 상태는 Task 상세와 `GET /api/v1/operations/{operationId}`에서 확인합니다.
전환 성공은 새 실행 시작을 의미하며 결과 성공은 별도로 확인합니다([ADR 0007](docs/adr/0007-running-offload.md)).
최신 Runner의 측정은 선택한 작업의 **실행 측정**에서 확인합니다. CPU·메모리의 제한이 없거나 측정하지
못한 값은 미확인/미수집으로 표시하고, 새 Attempt에 이전 값이 이어지지 않습니다. 서비스 지연 보고
방식은 [Runner 문서](runner/README.md)를 따릅니다. 현재 Remote는 자원·지연 측정을 지원하지 않습니다.
STREAM은 기본 비활성501이며 운영 broker·TLS·runtime·bindings를 설정한 환경에서 별도로 활성화합니다.
실행 폼의 **장치 스트림 입력 추가**로 장치 ID·출력 포트·받는 작업/포트·메시지 한도를 지정합니다.
활성 세션은 Run에 고정되며 같은 그룹은 모든 BATCH 선행 결과를 받은 뒤 함께 배정됩니다.
AUTO/NODE에서 선택적인 `retry`를 설정하면 계산 중에는 연결된 그룹 전체를 재시도하고,
완료 허가 뒤에는 실패한 작업의 최종 처리만 복구합니다. 장치는 같은 세션·송신 볼륨을 유지해야 합니다.
최대 재시도 횟수는 최초 실행을 포함합니다. STREAM의 REMOTE/VD 실행은 아직 거절합니다.
[공개 재시도 계약과 검증 범위](docs/evidence/m7-public-stream-retry.md)를 확인하세요.
실행 상세의 **스트림 경로 조회**로 실제 경로·고정 세션·출처·세대 상태를 확인합니다.
연결 ACTIVE와 작업/Result 성공은 별도 상태입니다([ADR0041](docs/adr/0041-public-stream-runs.md)).
상세 계약은 [ADR 0004](docs/adr/0004-workflow-run-task.md)와 Swagger의 Workflow/실행/작업 태그를 따릅니다.

## 개발 기준

- [개발 지침](DEVELOPMENT.md), [계획](PLAN.md), [현재 진행 상태](PROGRESS.md)
- [설계 출처](docs/sources.md), [아키텍처](docs/architecture.md), [API 범위](docs/api-scope.md), [ERD](docs/erd.md)
- [구현 계약](docs/implementation-contract.md), [검증 기준](docs/verification-matrix.md)
- [OpenAPI](contracts/openapi/platform-api.yaml), [환경 호환성](docs/compatibility.yaml)
- [검증 증거](docs/evidence/index.md), [결정 기록](docs/adr/0001-greenfield-foundation.md)
- [초기 개발 환경 완료 감사](docs/evidence/m0-completion-audit.md)

로컬 개발용 인증·MQTT 설정은 운영 배포 구성이 아닙니다. 운영 identity/RBAC/TLS 및 실제 장비 검증은 후속 단계입니다.

자동 전환은 Run 생성의 선택적인 `offload` 정책으로 켭니다. Workflow 화면에서도 CPU/메모리 사용률·
서비스 지연 기준과 연속 표본·대기 시간·전환 한도를 설정할 수 있습니다. 일반 작업은 RESTART,
STREAM 작업은 CHECKPOINT 선언이 필요합니다. 최초 NODE 지정 이후에도 다른 호환 노드로 이동할 수 있습니다. 이전 노드를 제외하고
Kubernetes가 새 위치를 선택합니다. 작업 상세에서 결정에 쓴 정책·측정과 전환 이력을 확인합니다.
미수집·오래된·누락 표본은 판단에 쓰지 않습니다. [ADR0009](docs/adr/0009-automatic-offload-policy.md).
STREAM 자동 전환은 모든 구성원의 현재 체크포인트·대기 시간·전환 예산을 확인합니다. 함께 재개하는
작업도 횟수를 사용하며 선택된 작업 외의 배치 정책은 유지합니다. 실제 Kubernetes에서 모델의
메모리 부하·노드 이동·체크포인트/결과 보존·동료의 전환 한도·취소를 검증했습니다.
전체9개 시나리오와 이미지/CI별 범위는 [ADR0052와 실행 근거](docs/evidence/m7-stream-automatic-offload.md)를 확인하세요.


## 가상 장치 등록·원본 연결·실행 (M6)

`/virtual-devices`에서 VD Profile 버전과 원본 Device를 연결합니다.
Profile 형식은 [VD 규격](contracts/profiles/vd-profile.schema.json)과
[예시](contracts/profiles/vd-profile.example.json)를 따릅니다. 예시 UUID는 실제 발행된
DEVICE/SERVICE Profile ID로 바꿔야 하며 SERVICE는 실행 규격을 충족해야 합니다.

원본 교체·표시 이름·배치 의도 수정은 revision을 검사하며, VD ID와 연결 이력을 보존합니다.
활성 VD 원본으로 쓰는 장치는 바로 해제할 수 없습니다. 먼저 원본 연결을 바꾸거나 VD를 해제하세요.
VD 해제는 원본 Device를 삭제하지 않고, 생성 재전송도 해제된 VD를 다시 활성화하지 않습니다.

등록 상태 REGISTERED/RELEASED와 실제 runtime의 Ready 상태는 구분합니다. 기동·교체·종료는
Operation으로 추적하며 Pod의 실제 종료를 확인한 후 다음 세대를 시작합니다.
`/workflows`에서 VD 정책과 Ready VD ID를 선택하면 같은 SERVICE 버전의 작업들을 해당 VD의
빈 실행 자리에 배정합니다. retry·하위 작업은 VD ID를 유지하고, 작업 취소는 해당 자식 실행만 중단합니다.
기능은 `EDGEAI_RUNTIME_ENABLED=true`, `EDGEAI_VD_ENABLED=true`와 실행·저장소 설정을 요구합니다.
CPU·메모리는 공유 VD 컨테이너 측정이므로 작업별 자동 offload는 허용하지 않습니다.
검증 범위와 남은 수용 게이트는 [M6 작업 실행 기록](docs/evidence/m6-vd-task-execution.md)을 따릅니다.

`bash scripts/demo-vd.sh <명시적 Kubernetes context>`는 실제 VD 수명과 자식 작업·S3 결과를
검증합니다. 실행이 활성화된 API의 `EDGEAI_SMOKE_API_URL`, `EDGEAI_API_USER`,
`EDGEAI_API_PASSWORD`와 결과 저장소의 `EDGEAI_STORAGE_URL`, `EDGEAI_MINIO_USER`,
`EDGEAI_MINIO_PASSWORD`를 환경에 설정하세요. 이 명령은 시험 VD만 정리하며 기존 데이터를
삭제하지 않습니다. API Pod 재시작 시험은 별도 격리 `test-vd-kubernetes.py`/CI kind에서 수행합니다.
이전 producer 차단 검사에 내부 API도 필요하므로 API origin은 필요 시 별도 loopback
`kubectl port-forward service/edgeai-api`로 연결하세요. `EDGEAI_SMOKE_PROXY_URL`에 공개
Dashboard origin을 주면 CRUD/Run은 실제 Next.js proxy를 통과하고 내부 신원 검사는 직접 API를 씁니다.
