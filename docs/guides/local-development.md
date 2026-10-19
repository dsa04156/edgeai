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

## Kestra 원본 Vue 운영 콘솔

기존 Dashboard13080을 BFF로 실행한 상태에서 다음 명령은 별도13083에 Kestra v2.0.6 원본 Vue 앱을
실행합니다. 운영 현황·장치·가상 장치·실행 모듈·워크플로·실행 결과·감사·DDS 배포를 기존 서버 API에 연결합니다.

```bash
bash scripts/dev/dev-kestra-native.sh
```

첫 실행은 고정 revision의 공식 소스를 Git에서 제외된 `output/kestra-native/upstream`에 받고,
원본 요구사항에 맞는 Node24·npm11과 lockfile 의존성을 준비합니다. Kestra 서버·DB는 설치하지 않습니다.
브라우저 주소는 `http://127.0.0.1:13083/ui/main/dashboards/default`이며 기존 웹·backend 프로세스를 교체하지 않습니다.

`dashboard/kestra-native/overview.yaml`은 원본 KPI·차트·표 배치를 정의하고, `overview.ts`는
기존13080 BFF의 인프라·node-metrics·가상 장치 전체 페이지·최근 실행20건을 조회합니다.
센서 수는 KubeEdge 등록 목록, VD 수는 REGISTERED 목록이며 실제 실행 준비와 별도입니다.
현재 관측만 표시하며 별도 이력 API가 없는 CPU 그래프를 시계열처럼 만들지 않습니다.
조회 실패·만료된 값은0과 구분해 표시하고, 새로고침 버튼으로 차트와 조회 시각을 함께 갱신합니다.
지연 로딩한 차트도 같은 조회 시점의 스냅샷을 사용합니다. 메모리에는 최근64개 조회 세대만 유지하고,
다른 조회가 많아 해당 세대가 만료되면 새로고침을 요구합니다. CPU 퍼센트는 여러 노드의 합계로 표시하지 않습니다.
노드별 자원 표는 처음에 AI 서버만 표시하고 하단 **더보기**로 전체 노드를 서버 우선으로 펼칩니다.
전체 모드는 고정 높이·페이지 제한 없이 모두 표시하며 **서버만 보기**로 접을 수 있습니다.
CPU·메모리·GPU·NPU 사용률은 한 표에 막대로 표시하고, GPU/NPU 모델을 펼치면 메모리·온도·전력을
확인할 수 있습니다. 더보기는 같은 조회 시점의 표시 범위만 바꾸며 KPI·준비 상태는 전체 기준입니다.
GPU·NPU는 한 열에 함께 표시하며 Intel NPU는 운영 표에서 숨깁니다. 다른 NPU는 실제0이나
미수집이라는 이유로 제외하지 않으며 원본 계측 데이터는 유지합니다.
Kubernetes에 노출된 가속기와 최신 종류·벤더·장착 정보를 결합하며 공유 슬롯을
물리 GPU로 늘려 세지 않습니다. 미수집·만료는 `—`, 실제0은 그대로 표시합니다. Hailo/RKNN처럼
장착은 확인되지만 사용률 계측이 없는 NPU는 별도 계측 안내를 표시합니다.
장치 관리 주소는 `/ui/main/devices`입니다. 원본 메뉴의 **장치 관리**에서 서버·엣지·센서 목록을
검색·정렬·페이지 이동하고 장치명을 눌러 읽기 상세를 확인합니다. 운영 현황 노드명도 해당 상세로
연결합니다. 노드는 `/api/edgeai/overview`의 메타데이터·사용률을 사용하고, 센서 목록·상세는 기존
`managed-devices` GET으로 직접 조회해 등록 직후 인프라의10초 캐시를 기다리지 않습니다. 각각의 조회 시각을
표시하며 조회 오류는 빈 목록의0과 구별합니다. 센서 최신값·단위·관측 시각도 같은 응답에서 표시합니다.
상단 **새 센서 등록**은 원본 KsDrawer·KsForm·KsInput·KsSelect로 RTSP를 포함한 일곱 프로토콜의 연결 설정과
측정 항목 하나를 등록합니다. 저장한 측정 항목을 선택할 수 있고, 실패·취소 후 입력을 유지합니다.
성공하면 센서 목록을 다시 조회하고 해당 센서 상세로 연결합니다. 연결 상태는 실제 서버 응답을 따릅니다.
`device-registration.ts`는 관리 API18080과 워크플로 API18082를 서버에서 정한 origin으로 연결합니다.
EDGEAI_API_BASE_URL·EDGEAI_WORKFLOW_API_BASE_URL로 변경할 수 있습니다. 관리 EDGEAI_SESSION과
워크플로 EDGEAI_WORKFLOW_SESSION·각 CSRF·Idempotency-Key·감사 ID와 lossless JSON을 보존합니다.
DDS는13080 BFF를 통해 서버 전용 자격 증명을 사용합니다. 일반 JSON64KiB,
DDS 배포 정의1MB 제한과 기존 서버의 오류·참조 제한·revision 검사를 유지합니다.
센서의 해당 측정 항목 수정·추가·삭제, 연결 시험과 규격 저장을 지원하며 노드는 자동 관측합니다.

나머지 메뉴는 `/ui/main/data-store`, `/virtual-devices`, `/profiles`, `/workflows`, `/runs`, `/audit`, `/platform-service`입니다
(각 경로 앞에 `/ui/main` 적용). SERVICE 모듈 불변 버전 발행·삭제, eKuiper VD 원본·계산·저장 정책·시작·중지·해제·설정 버전 이력,
그래프/코드 DAG 편집·가져오기·내보내기·발행·실행, Run/Task/Placement/STREAM/결과·취소·위치 전환,
센서 원본 저장소·기간 그래프·RTSP 정지 영상과 WORKFLOW_DATA 원본 선택·입력 종료 후 완료,
감사 ID 조회와 DDS YAML·Buildx·Gitea·Argo CD 작업을 제공합니다. DDS 작업은 연결된
Platform-Service가 필요하며 오류·비활성 상태에서는 실제 준비나 성공을 추정하지 않습니다.
운영 API 계약은 ad3f239의 생성된 `live-api-schema.d.ts`를 사용합니다. 폐기한 public VD 템플릿·Runner VD 실행은 재도입하지 않습니다.
문서는 사이드바의 별도 링크로 기존13080 `/docs`를 엽니다. 기본 웹13080 전환은 자동으로 하지 않습니다.

검증은 저장소 루트에서 API 경계 테스트와 기존 typecheck/lint를 실행하며, 원본 앱용 Vue 검사는
준비된 UI 워크스페이스의 compiler를 사용합니다.

```bash
node --experimental-strip-types --test dashboard/tests/kestra-overview.unit.mjs dashboard/tests/kestra-device-registration.unit.mjs dashboard/tests/kestra-console.unit.mjs
cd output/kestra-native/upstream/ui
npx --yes --package=node@24 --package=npm@11.16.0 -- npm exec -- vue-tsc --project ../../../../dashboard/kestra-native/tsconfig.json --noEmit
```

Kestra 연동 운영 확인(2026-10-16): 관리 API는 ad3f239, 워크플로 API는 별도18082를 사용합니다.
원본 API 타입 재생성은 해당 revision의 OpenAPI를 `output/`에 추출한 뒤 기존 `openapi-typescript`로
`dashboard/kestra-native/live-api-schema.d.ts`를 생성합니다. 루트의 다른 작업용 계약을 덮어쓰지 않습니다.

DDS는 `PLATFORM_BUILDX_BUILDER`로 별도 rootless Kubernetes Buildx를 사용할 수 있습니다.
현재 `edgeai-builds` namespace의 `edgeai-kube-arm64-rootless`는 ARM64만 빌드합니다.
`PLATFORM_IMAGE_PUSH_REPOSITORY`는 **동일 registry의 빌더 접근 주소**가 배포 노드 주소와 다를 때만 지정합니다.
빌드 응답은 배포 노드가 사용하던 `PLATFORM_IMAGE_REPOSITORY@digest`를 반환합니다.

호스트 API에서 TokenRequest 토큰을 사용하는 경우 만료 전에 같은 context·namespace·서비스 계정으로 갱신합니다.
기존 토큰 파일을 지정해 다음 명령을 실행합니다. 자리표시자는 현재 환경의 값으로 바꾸며,
실패하면 기존 파일을 보존합니다. 갱신 주기는 실제 발급된 토큰의 유효기간보다 짧게 설정하세요.

```bash
bash scripts/ops/refresh-host-kubernetes-token.sh CONTEXT KUBECONFIG NAMESPACE SERVICE_ACCOUNT TOKEN_FILE
```

EdgeMesh를 통해 selector 없는 호스트 API Service에 연결할 경우 CoreV1 Endpoints와 EndpointSlice의
주소·포트가 같은 실제 backend를 가리키는지 확인합니다. 두 리소스를 직접 관리한다면
Endpoints의 `endpointslice.kubernetes.io/skip-mirror=true`로 중복 Slice 생성을 막습니다.
TLS CA와 서버 이름 검증은 유지합니다.

InfluxDB v2.9.1에는 DELETE와 TSI compaction이 서로 기다리는 upstream 문제가 있습니다.
원본 중복 정리 DELETE가 몰려 원시 수집이 멈추면 `/health`만으로 정상이라고 판단하지 말고
수집 시각·실제 새 기록·수집 상태를 함께 확인하세요. 같은 StatefulSet/PVC를 보존한 재시작은
회복 조치이며 영구 수정이 아닙니다. [공식 이슈26889](https://github.com/influxdata/influxdb/issues/26889)와
[미병합 수정27696](https://github.com/influxdata/influxdb/pull/27696)을 피하기 위해 원본 수집 경로는
중복 legacy point의 동일 태그·시각에 빈 문서와 `archive_visible=false`를 기록합니다. 원본 조회와 목록은 빈 문서를,
차트는 비표시 항목을 제외하고, 물리 typed field는 기존 bucket 보관 기한에 만료됩니다.
새 원본에는 `archive_visible=true`를 기록하므로 같은 시각이라도 값이 다른 원본은 보존합니다.
raw Influx를 직접 조회하는 소비자도 같은 표시 조건을 적용해야 합니다. 이 변경은 InfluxDB 자체의 DELETE 버그를
수정하는 것은 아니므로 수집 경로에 동기 DELETE를 다시 추가하지 마세요.
수집기는 64개 수신 한도와 수동 ACK를 유지하고, 측정별 대기를 가상 스레드와 공정 잠금으로 분리합니다.
이미 저장 완료한 동일 경로·payload의 최근 4,096개 digest만 메모리에 보관해 재전달의 중복 쓰기를 줄입니다.
저장 실패는 완료 캐시에 넣지 않으며 재시작 후에는 기존 원본 신원으로 저장을 다시 확인합니다.

### 워크플로 초안 저장·복구

워크플로 편집기 위 **초안 저장**은 그래프·노드 위치와 적용 전 JSON 코드를 이 브라우저에 저장합니다.
새로고침하거나 다른 메뉴를 다녀온 뒤 같은 워크플로에서 **저장한 초안 복구**를 누릅니다.
새 워크플로의 초안은 별도 공간을 쓰며 기존 워크플로끼리 섞이지 않습니다.
복구한 적용 전 코드는 코드 탭에서 확인하고 **코드 적용**을 누릅니다. **DAG 버전 발행**은 별도 작업입니다.
**저장한 초안 삭제**는 브라우저의 저장본만 지우고 현재 편집 중인 내용은 유지합니다.
초안은 다른 브라우저·기기에 동기화되지 않으며 브라우저 저장소를 지우면 없어집니다.
공유 PC에서 사용할 때는 작업 후 저장한 초안을 삭제하세요.

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

워크플로 화면의 활성화와 Runner·STREAM 실행 활성화는 별도 설정입니다.
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
