# EdgeAI 소개

EdgeAI는 서로 다른 엣지 장비를 한 화면에서 관측하고, 장치 규격과 서비스 실행 구성을 관리하는 플랫폼입니다.
Kubernetes의 노드·실행 환경, Prometheus의 자원 측정, EdgeX의 센서 정보를 연결합니다.

## 할 수 있는 일

- 엣지 AI 서버와 엣지 디바이스를 구분하고 CPU·메모리·GPU·NPU 관측값을 조회합니다.
- EdgeX 센서의 등록 상태와 측정 이력을 보고 장치가 지원하는 명령을 실행합니다.
- 장치·서비스·가상 장치 프로필을 버전으로 발행하고 등록된 장치에 연결합니다.
- 가상 장치의 원본 연결과 실행 수명을 관리합니다.
- DDS 배포 구성을 작성해 Buildx·Gitea·Argo CD로 전달하거나, Spring DAG 경로에서 작업 실행과 결과를 조회합니다.

## 구성 요소

| 구성 요소 | 역할 |
|---|---|
| Dashboard | 관리 화면과 문서, 서버 측 API 프록시 |
| Spring API | 프로필·장치·가상 장치·DAG·실행·감사 메타데이터 관리 |
| PostgreSQL | 관리 상태, 불변 버전, 연결과 실행 이력 저장 |
| Kubernetes | 노드 관측과 실행 Pod의 배치·수명 관리 |
| Prometheus | 기존 exporter의 노드·가속기 지표 조회 |
| EdgeX | 센서 목록, 저장 측정값, 장치 명령 제공 |
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
센서 UP, 노드 Ready, 가상 장치 Ready, 작업 성공도 서로 다른 상태입니다.
[지원 범위](../reference/support.md)와 [상태 해석](../concepts/observability.md)을 기준으로 판단합니다.

다음 단계: [로컬 빠른 시작](../guides/local-development.md).
