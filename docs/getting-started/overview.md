# EdgeAI 소개

EdgeAI는 서로 다른 엣지 장비를 한 화면에서 관측하고, 장치 규격과 서비스 실행 구성을 관리하는 플랫폼입니다.
Kubernetes의 노드·실행 환경, Prometheus의 자원 측정, KubeEdge Mapper의 센서 수집을 연결합니다.

## 할 수 있는 일

- 엣지 AI 서버와 엣지 디바이스를 구분하고 CPU·메모리·GPU·NPU 관측값을 조회합니다.
- 지원 프로토콜로 센서를 등록하고 연결 상태·측정 항목별 최신값·수집 시각을 조회합니다.
- 기존 센서의 측정 항목을 추가·삭제하고 저장한 항목 규격을 재사용합니다.
- 원본 수집과 저장소 연결이 준비되면 센서의 MQTT 원본을 InfluxDB에 보관하고 기간별 이력·그래프를 조회합니다.
- SERVICE 실행 모듈을 불변 버전으로 발행하고 워크플로에서 재사용합니다.
- 선택한 센서 측정값을 연속 가상 센서로 조합하고 출력별 결과·그래프·이력을 조회하는 관리 기능을 제공합니다. 실제 계산에는 연속 처리 활성화가 필요합니다.
- Spring 워크플로에서 센서 원본 또는 가상디바이스 계산 결과를 입력으로 선택해 최신값을 계속 처리합니다. 실행·스트림 구성과 선택한 입력의 수집 또는 계산 경로가 필요합니다.
- DDS 배포 구성을 작성해 Buildx·Gitea·Argo CD로 전달하거나, Spring DAG 경로에서 작업 실행과 결과를 조회합니다.

## 구성 요소

| 구성 요소 | 역할 |
|---|---|
| Dashboard | 관리 화면과 문서, 서버 측 API 프록시 |
| Spring API | SERVICE·DAG·연속 VD·실행·감사 관리와 Device Manager의 장치 CRD 구성 |
| PostgreSQL | 관리 상태, 불변 버전, 연결과 실행 이력 저장 |
| InfluxDB | 센서 MQTT 원본과 저장 정책에 해당하는 연속 계산 결과의 이력 보관 |
| Kubernetes | 노드 관측과 실행 Pod의 배치·수명 관리 |
| Prometheus | 기존 exporter의 노드·가속기 지표 조회 |
| KubeEdge | DeviceModel/Device CRD 설정과 DeviceStatus·DeviceTwin 관측 |
| Mapper | 대상 엣지 노드에서 프로토콜별 장치를 읽고 DMI 상태·별도 MQTT 데이터를 보고 |
| NanoMQ | 센서 노드의 MQTT 출력 broker; 중앙 연속 처리 입력과 연결 |
| eKuiper·processing Agent | 공유 계산 엔진과 입력·규칙·출력·세대 조정; 운영 활성화 별도 |
| Platform-Service | DDS 편집 결과를 이미지·Git·Argo CD 배포로 연결하는 별도 API |
| Runner·MinIO·MQTT | 선택적으로 활성화하는 작업 실행, 결과 저장, 스트림 전달 |

전체 연결은 [아키텍처](../architecture/architecture.md)에서 확인합니다.

## 사용 경로 선택

**화면과 관리 기능을 먼저 사용하려면** [로컬 빠른 시작](../guides/local-development.md)을 따릅니다.
클러스터나 센서를 연결하지 않아도 프로필 관리와 문서 열람을 시작할 수 있습니다.
연결되지 않은 외부 자원은 실제 장비처럼 표시하지 않습니다.

**이미 운영 중인 클러스터를 연결하려면** [인프라 연결](../guides/infrastructure-inventory.md),
[메트릭 연결](../guides/node-metrics.md), [배포 안내](../operations/cicd.md)를 읽습니다.

**서비스를 배포하거나 실행하려면** 먼저 [두 워크플로 경로](../concepts/workflows.md)를 확인합니다.
DDS 배포와 Spring DAG는 입력 형식, 실행 주체, 결과 조회 방식이 서로 다릅니다.

## 지원 범위 이해하기

화면에서 장비가 보인다는 것은 해당 장비의 모델 추론·성능·복구가 모두 검증됐다는 뜻이 아닙니다.
센서 ONLINE, 노드 Ready, 작업 성공도 서로 다른 상태입니다.
[지원 범위](../reference/support.md)와 [상태 해석](../concepts/observability.md)을 기준으로 판단합니다.

센서 화면은 측정 항목별 행과 Device·DeviceStatus CRD의 최신값을 조회합니다. 센서 원본 이력은 별도 MQTT collector와 InfluxDB 연결, 원본 수집 활성화가 필요하며 연속 계산 flag와 독립적으로 동작합니다. 연속 가상 센서의 계산 결과 이력은 별도 저장 경로이며 실제 계산·저장에는 processing Agent·eKuiper·InfluxDB와 연속 처리 활성화가 필요합니다.

워크플로의 명시적 `WORKFLOW_DATA` 입력은 센서 원본 저장소 또는 유효한 가상디바이스 결과의 최신값을 1초마다 읽거나, `ALL_OBSERVATIONS`로 실행 시작 이후 수락된 관측을 모두 전달합니다([지원 범위](../reference/support.md)). 센서 등록만으로 이루어지는 자동 STREAM 연결은 제공하지 않습니다. 장치 명령 쓰기는 제공하지 않으며, 구형 VD supervisor와 별도 UUID 앱 장치 신규 등록은 종료됐습니다.

등록 버튼은 모달을 열며 긴 입력 내용만 모달 안에서 스크롤합니다. 실패 시 입력을 유지하고 ESC·취소·닫기로 목록에 돌아갑니다. [연속 가상 센서 가이드](../guides/vd-processing.md)에서 원본 등록과 계산 등록의 관계를 확인하세요.

다음 단계: [로컬 빠른 시작](../guides/local-development.md).
