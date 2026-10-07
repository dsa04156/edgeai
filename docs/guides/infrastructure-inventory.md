# 노드와 센서 연결

인프라 관리 화면(`/devices`)은 서버·엣지 노드, EdgeX 센서, 앱 등록 장치를 구분합니다.
이 가이드는 기존 Kubernetes·EdgeX 목록을 연결하는 절차입니다. 장치 등록은 [관리 가이드](platform-usage.md)를 따릅니다.

## 준비 사항

API 프로세스가 Kubernetes와 EdgeX 주소에 접근할 수 있어야 합니다.
노드 조회에는 선택한 context 또는 ServiceAccount의 node get/list 권한이 필요합니다.
센서는 EdgeX Core Metadata에 등록되어 있어야 합니다. 지원 센서는 [웹 센서 등록](sensor-registration.md)을 사용할 수 있습니다.

## 1. 조회 경로 선택

로컬 `.env`의 예시입니다. context와 주소를 실제 환경으로 바꿉니다.

```dotenv
EDGEAI_INFRASTRUCTURE_SOURCE=kubectl
EDGEAI_INFRASTRUCTURE_CONTEXT=<조회할-context>
EDGEAI_EDGEX_ENABLED=true
EDGEAI_EDGEX_METADATA_URL=http://<Core-Metadata-host>:59881
EDGEAI_EDGEX_DATA_URL=http://<Core-Data-host>:59880
EDGEAI_EDGEX_COMMAND_URL=http://<Core-Command-host>:59882
```

`make backend`의 개발 스크립트는 명시된 설정을 우선합니다. source가 미지정이면
`EDGEAI_KUBE_ENABLED=true`일 때 API 모드를 사용하고, 그 외에는 사용 가능한 kubectl의 현재 context를
프로세스 시작 시 고정합니다. kubectl 모드에서 EdgeX URL이 비어 있으면 `edgex-system`의 Service IP를 조회합니다.
직접 IDE·Java 실행에는 이 자동 탐색이 없으므로 환경변수를 전달해야 합니다.

클러스터의 API Pod는 `EDGEAI_INFRASTRUCTURE_SOURCE=api`와 연결 설정을 사용합니다.
EdgeX 기본 DNS는 `edgex-core-metadata.edgex-system.svc`, Data·Command도 같은 namespace의 서비스입니다.
주소 설정 뒤 API를 다시 실행합니다. 실행 중 프로세스는 `.env` 변경을 자동으로 읽지 않습니다.

## 2. 서버와 엣지 구분

| 분류 | 라벨 기준 |
|---|---|
| 엣지 AI 서버 | `environment=cloud` 또는 `gpu.platform=server` |
| 엣지 디바이스 | `node-role.kubernetes.io/edge`, `environment=edge`, `edge.device/class` |
| 분류 불명 | 기준이 없거나 서버·엣지 조건이 충돌 |

장비 이름과 CPU 아키텍처만으로 역할을 판단하지 않습니다. 라벨 변경은 클러스터 관리 절차에서 수행합니다.

## 3. 화면 확인

인프라 관리의 각 탭에서 노드와 센서 목록을 확인합니다. 목록 API는 `/api/v1/infrastructure`이며
화면은 서버 측 `/api/control-plane/infrastructure` 프록시를 사용합니다.
노드·센서 각각 AVAILABLE, UNAVAILABLE, DISABLED를 표시하며 한쪽 실패로 다른 쪽의 정상 목록을 지우지 않습니다.
목록은 10초 주기로 조회하고 60초를 넘긴 관측은 현재 목록으로 사용하지 않습니다.

센서의 원본은 **EdgeX Device와 Device Profile**입니다. 채널별 논리 장치 수와 물리 보드 수는 다를 수 있습니다.
UP/DOWN은 EdgeX 운영 상태입니다. 실제 수신은 [센서 측정 이력](sensor-controls-and-vd-deletion.md)으로 확인합니다.

## 경계와 다음 단계

앱 Device와 EdgeX registry는 별도입니다. 한쪽의 등록·삭제를 다른 쪽에 자동 복제하지 않습니다.
자원 사용량은 [Prometheus 연결](node-metrics.md), 실제 센서 배포는 [EdgeX 배포 안내](../../deploy/edgex/README.md)를 따릅니다.
