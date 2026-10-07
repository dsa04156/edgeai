# CPU·메모리·가속기 메트릭 연결

이미 설치된 수집기를 사용한다. API가 고정 PromQL로 조회하고 Dashboard는
`/api/control-plane/node-metrics`를 통해 `GET /api/v1/node-metrics` 응답을 표시한다.
브라우저에서 Prometheus에 직접 접근하지 않으며 DB 변경도 없다.

## 실행 설정

로컬 API를 실행할 때 `.env`에 다음을 설정한다. Dashboard 설정은 추가하지 않는다.

```dotenv
EDGEAI_PROMETHEUS_ENABLED=true
EDGEAI_PROMETHEUS_URL=http://<API에서-접근할-Prometheus>:9090
```

예시의 주소를 API 프로세스가 접근할 수 있는 origin으로 바꿉니다. URL에 사용자정보·경로·쿼리를 넣지 않습니다.
클러스터의 기본 서비스 주소는 `.env.example`과 API manifest를 확인합니다.
기본값은 비활성이며 설정을 전달한 뒤 API를 다시 실행해야 합니다.

## 확인 방법

인프라 관리에서 실제 노드 이름과 측정값을 확인합니다. 값이 없으면 해당 exporter의 `up`,
원본 시각과 아래 연결 라벨을 확인합니다. 웹 요청은 Spring의 고정 PromQL 조회를 사용합니다.

## 지표와 노드 연결

| 대상 | 기존 지표 | 연결 기준 |
| --- | --- | --- |
| CPU | `node_cpu_seconds_total` idle의 최근 두 수집값 irate (`[2m]` 탐색 범위) | node_uname_info의 nodename → kube_node_info의 node |
| CPU 온도 | `node_thermal_zone_temp`의 cpu-thermal/x86_pkg_temp (복수 센서는 최대 온도) | node-exporter hostname 매핑 |
| 메모리 | `node_memory_MemAvailable_bytes`, `node_memory_MemTotal_bytes` | 동일 node-exporter target |
| 가속기 장착 정보 | `node_accelerator_info` | node/device/kind/vendor/model; 사용률 수집 여부와 독립적 |
| NVIDIA GPU | DCGM 사용률·FB 메모리·온도·전력 | namespace/pod → kube_pod_info의 node, PCI 주소로 장착 정보와 연결 |
| Jetson GPU | `jetson_gpu_utilization_ratio` | node 라벨 |
| Spark GPU | `spark_gpu_utilization_percent` 및 온도·전력 | node/gpu 라벨 |
| Mobilint NPU | `mobilint_npu_utilization_ratio` 및 메모리·온도·전력 | node/device 라벨 |
| Intel NPU | `hairp_npu_busy_seconds_total`의1분 rate, memory_used_bytes | node/device 라벨 |

DCGM의 `Hostname`에 exporter Pod 이름이 들어오는 것을 확인했다. 이를 노드 이름으로
취급하지 않는다. node-exporter 호스트 이름은 대소문자를 정규화해 Kubernetes 이름과
일치시킨다. 모호하거나 연결할 수 없는 시계열은 노드에 임의 배정하지 않는다.

## 상태와 표시

- 물리 디바이스 목록은 Prometheus의 노드 관측으로 만든다. 실제 Node Ready 조건을
  `kube_node_status_condition{condition="Ready"}`에서 읽어 `Ready`/`NotReady`/`Unknown`으로
  표시한다. 원본 시각과 kube-state-metrics의 up을 검사하며 만료·조회 실패는 Ready로 표시하지 않는다.
  이 조건은 kubectl get nodes가 사용하는 Kubernetes Ready 조건과 같고 Prometheus 수집 지연이 있다.
  과거 execution_node DB의 REMOVED 값으로 최신 Ready 조건을 덮어쓰지 않는다.
- 데이터 입력 장치는 별도 Device 등록 목록이다. 물리 노드를 Device 레코드로 복제하지 않는다.
- `node_accelerator_info`와 `node_hardware_inventory_success`의 원본 시각·up·수집 성공을
  검사해 장착 정보를 표시한다. Hailo처럼 사용률이 없는 장치도 목록·NPU 필터에 포함하고
  사용률은 `—`로 둔다. 만료된 장착 정보만으로 장치가 현재 있다고 표시하지 않는다.
- UUID·PCI 주소는 내부 연결용으로만 사용하며 화면 본문과 툴팁에 표시하지 않는다.
  장치 모델명을 표시하고 같은 이름이 여러 개면 순번으로 구분한다. 모델의 PCI 제품 코드도 생략한다.
  DCGM/Intel은 PCI 주소 정규화로 연결한다. 주소가 없는 Jetson/Spark/Mobilint는
  같은 노드·종류·벤더의 장착 장치와 해당 exporter의 측정 장치가 각각 하나인 경우에만 연결한다.
- API는 `AVAILABLE`, `DISABLED`, `UNAVAILABLE`을 구분한다. 정상 응답에 지표가 없으면 빈 목록이다.
- 화면은5초마다 조회하며 백엔드는 동시 조회 결과를5초간 공유한다.
- GPU 사용률은 수집된 최신 gauge다. 개발 환경에서 확인한 GPU exporter 수집 간격은15초이며,
  프론트 갱신만으로 수집 주기보다 빠른 실제 변화를 얻을 수는 없다.
- CPU는 최근 두 수집값의 `irate`로 계산한다. 개발 환경에서 확인한 node-exporter는 약30초 간격이며,
  `[2m]`은 샘플 탐색 범위일 뿐2분 평균이 아니다. 수집 주기보다 빠른 실제 변화를
  보여주려면 수집기 설정도 변경해야 한다. Intel NPU는1분 평균이다.
  메모리는 가용 메모리를 제외한 비율이다.
- 원본 시계열의 수집 시각과 exporter `up`, 전용 collector 성공 상태를 함께 검사한다.
  90초 경과·수집기 실패·조회 실패의 값은 화면에서 `—`로 표시한다. 확인된0은 그대로 표시한다.
- 개요와 노드 목록은 실제 사용률과 Kubernetes allocatable 총량을 따로 표시한다.
  노드 목록에는 수집된 가속기 메모리·온도·전력도 표시한다. 미수집 지표를0으로 채우지 않는다.
- Prometheus 실패·부분 경고 응답은 `UNAVAILABLE`로 처리한다. 마지막 성공 값을 새 값처럼 재사용하지 않는다.

PromQL 시간 처리 기준: [Prometheus HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/),
[rate·timestamp 함수](https://prometheus.io/docs/prometheus/latest/querying/functions/).
검증 범위는 [메트릭 검증 근거](../evidence/node-metrics-2026-10-06.md)에 기록했다.

## 장비별 수집 범위

온도·전력·가속기 메모리는 해당 장비와 exporter가 제공하는 항목만 표시합니다.
사용률이 없어도 신뢰할 수 있는 장착 정보는 목록에 표시할 수 있습니다.
DeepX 등 새로운 가속기는 호스트 인식과 inventory/exporter 분류를 먼저 확인합니다.
특정 개발 장비의 관측 결과는 [가속기 검증 기록](../evidence/accelerator-inventory-2026-10-06.md)에 보존합니다.

다음 단계: [관측값 해석](../concepts/observability.md), [문제 해결](../operations/troubleshooting.md).
