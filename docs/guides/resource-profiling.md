# 서비스 자원 프로파일

3-3 ② 범위의 웹 기능입니다. 등록된 SERVICE 실행 모듈 버전을 Pod로 실행할 때 선언할 **CPU·메모리·GPU/NPU 요구량과 허용 범위**를 기존 계측과 KRR 추천으로 확인하고, 사용자가 확정한 값을 불변 revision으로 저장해 2-1에 전달합니다.
계산·전송 시간을 다루는 [측정 기반 DAG 배치](operational-scheduling.md)(성능 프로파일)와 별개입니다. 저장한 요구량은 선언이며 자원 예약·분할·증강이나 3-3 전체 완료를 뜻하지 않습니다.

## 구현 상태

| 수준 | 상태 |
|---|---|
| API | 분석·저장·목록·조회·2-1 전달 계약 9개 operation(`ResourceProfile` 태그), Flyway `V70__resource_profile.sql`(배포된 V69 다음) |
| 실측 | 기존 Prometheus의 cAdvisor·kube-state-metrics 범위 조회(고정 PromQL). 장치는 기존 DCGM(NVIDIA GPU)·Mobilint NPU exporter만 기간 조회 |
| 추천 | 오픈소스 [KRR](https://github.com/robusta-dev/krr) `v1.31.1`(`simple` 전략)을 전용 bridge(`profiling-bridge/`)에서 실제 실행하고 JSON 보고서를 대상 식별자로 대조 |
| 웹 | 콘솔 `자원 프로파일`(`/ui/main/resource-profiles`) |
| 시험 | 격리 PostgreSQL 통합 시험, adapter·bridge·화면 단위/브라우저 시험, 읽기 전용 운영 Prometheus·실제 KRR 연동 확인. 근거: [시험·연동 기록](../evidence/resource-profiling-20261030.md) |
| 배포 | 미배포. bridge 이미지 빌드와 `deploy/kubernetes/components/resource-profiling` 적용은 검토 후 별도 수행 |

## 사용 흐름

1. **서비스 선택** — `실행 모듈`에 발행된 SERVICE key와 불변 버전을 고릅니다. 프로파일은 이 버전 ID에 묶입니다.
2. **측정 대상·구간** — 서버가 허용한 네임스페이스에서 **선택한 관측 구간** 동안 관측된 워크로드(Deployment·StatefulSet·DaemonSet·Job·CronJob·소유자 없는 Pod)와 컨테이너를 고릅니다. 구간을 바꾸면 목록을 다시 조회하므로 며칠 전에 끝난 Job도 168시간 구간에서 고를 수 있습니다. 필요하면 구간의 특정 Pod 실행(이름·UID·노드)으로 좁히고, 외부 실행 ID를 참조로 남깁니다. 기존 Runner는 필요하지 않습니다.
3. **분석** — 구간(5분–14일)의 컨테이너 사용량, 선택한 장치의 관측, KRR 추천(이력 1–336시간)을 한 번에 조회해 불변 분석 결과로 저장합니다.
4. **확인·수정** — 표에서 실측(최소/평균/p95/최대·상태·표본·최근 시각), KRR 추천(요청/제한·실제 KRR 이력 구간), 확정할 최소·요청·최대를 비교합니다. `p95 사용`·`추천 사용`은 요청값만 채웁니다.
5. **저장·조회** — 새 revision을 저장하고, 목록에서 revision을 열어 2-1 전달 계약 JSON을 복사·내려받습니다.

실행 이력이 없는 새 모델은 `측정 없이 직접 입력`으로 저장합니다. 이 경우 측정 상태는 `UNMEASURED`, 근거는 `MANUAL`이며 검증된 추천으로 표시하지 않습니다.

## 값과 단위

| 자원 | 단위 | 확정값 | 비고 |
|---|---|---|---|
| CPU | millicores(정수) | `minMillicores ≤ requestMillicores ≤ maxMillicores`, 최대 1,000,000 | 화면 입력은 `250m` 또는 core(`0.25`) |
| 메모리 | bytes(정수) | `minBytes ≤ requestBytes ≤ maxBytes`, 최대 16 TiB | 화면 입력은 bytes 또는 `Ki/Mi/Gi/Ti` |
| GPU/NPU | 확장 자원 단위 | `resourceName`(예 `nvidia.com/gpu`), `unit`=`COUNT`·`MEBIBYTES`·`PERCENT`(≤100), 정수 min/request/max | 사용자 확정값만 |
| 장치 메모리 요구량 | bytes | `deviceMemoryBytes`(선택) | 예약 가능한 Kubernetes 자원이 아님 |

- 소수·문자열·`NaN`·0·음수·순서가 맞지 않는 범위는 400 `INVALID_RESOURCE_PROFILE`이며 저장하지 않습니다. 빈 값을 0으로 채우지 않습니다.
- 최소는 관측 최솟값이 아니라 실행에 필요한 하한입니다. 최대는 허용 상한이며 컨테이너 limit은 2-1이 정합니다.
- **근거(basis)** 는 확정 요청값에 대한 주장이며 서버가 확인합니다. `RECOMMENDED`는 요청값이 인용 분석의 KRR 요청값과, `MEASURED`는 인용 분석의 `AVAILABLE` 실측 p95(정수 올림)와 정확히 같을 때만 허용되고 다르면 400입니다. 화면도 같은 규칙으로 근거를 정하며 수정한 값은 `MANUAL`입니다.
- 저장 측정 상태: 인용 분석의 CPU·메모리 실측이 모두 `AVAILABLE`이면 `MEASURED`, 하나면 `PARTIAL`, 없거나 분석이 없으면 `UNMEASURED`.

## 실측과 장치 귀속

| 상태 | 의미 |
|---|---|
| `AVAILABLE` | 구간 표본 10개 이상, 마지막 표본이 구간 종료 직전까지 있음 |
| `STALE` | 값은 있으나 마지막 표본이 구간 종료보다 max(5분, 3×step) 이상 이전. 실측 근거로 저장 불가 |
| `INSUFFICIENT` | 표본 10개 미만. 값 없음 |
| `NO_DATA` | 해당 시계열 없음 |
| `NOT_FOUND` | 구간에 대상 Pod가 없거나 지정한 Pod UID가 일치하지 않음 |
| `UNSUPPORTED`·`AMBIGUOUS` | 장치 exporter가 기간 조회를 지원하지 않음 / 장치와 지표를 1:1로 대응할 수 없음 |
| `UNAVAILABLE` | Prometheus·KRR 미구성 또는 조회 실패 |

- CPU: `sum by(pod)(rate(container_cpu_usage_seconds_total{namespace,pod=~대상,container}[max(120s,2×step)]))×1000`, 메모리: `max by(pod)(container_memory_working_set_bytes{…})`. Pod는 `kube_pod_owner`·`kube_replicaset_owner`·`kube_job_owner`로 구간 안에서 찾습니다. step은 15초 이상, 시계열당 최대 1,000점입니다. 최대값은 수집·조회 해상도의 값이며 순간 최대를 보장하지 않습니다.
- **복제본 의미:** 요청량은 Pod(컨테이너)당 값입니다. 측정 분포는 모든 복제본의 Pod별 표본을 모은 것이며(`aggregation: PER_POD_CONTAINER`) 최대값은 가장 큰 단일 Pod입니다. 복제본 합계를 하나의 Pod 요구량으로 표시하지 않고, `perPod`에 복제본별 p95·최대를 따로 보여 줍니다. KRR 추천도 컨테이너당 값입니다.
- 같은 cgroup이 여러 scrape job으로 수집되면 `max by(pod,id)`로 하나로 접은 뒤 Pod 안의 컨테이너 cgroup을 더합니다.
- **Pod 실행 지정:** cAdvisor 시계열은 Pod 이름으로 선택합니다. 구간 안에 같은 이름의 UID가 둘 이상이면(StatefulSet 재생성 등) 한 실행으로 섞지 않고 `AMBIGUOUS`로 거절합니다. 구간을 한 실행으로 좁혀 다시 분석합니다.
- 장치 관측은 언제나 **노드의 장치 전체** 값입니다(다른 프로세스·공유가 포함될 수 있음). exporter의 Pod 표시(`exported_namespace`·`exported_pod`)가 대상 Pod만 가리키면 `TARGET_ASSOCIATED`, 다른 Pod도 있으면 `NODE_DEVICE_SHARED`, 표시가 없으면 `NODE_DEVICE_UNKNOWN_OWNER`입니다. `TARGET_ASSOCIATED`도 연관일 뿐 단독 점유나 모델별 사용량이 아니며 `perWorkload`는 항상 false입니다. 현재 운영 DCGM은 Pod 표시가 없어 `NODE_DEVICE_UNKNOWN_OWNER`입니다.
- 장치 사용률을 GPU 개수·분할 요청량으로 변환하지 않고, 모델 파일만으로 요구량을 정하지 않습니다. Jetson·Spark·Intel·Hailo exporter는 이번 범위에서 기간 조회를 연결하지 않아 `UNSUPPORTED`입니다.

## KRR 연동

- 브라우저는 URL·명령·PromQL을 보낼 수 없습니다. API는 검증된 `namespace/kind/name/container`와 `historyHours`만 bridge에 보내고, bridge는 서버 설정의 Prometheus로 다음을 실행합니다.

  ```text
  krr simple --prometheus-url <설정값> --namespace <ns> --resource <Kind> --formatter json \
    --fileoutput <임시 report.json> --history_duration <h> --logtostderr --quiet
  ```

- KRR에는 이름 필터가 없어 네임스페이스·종류 전체를 스캔한 뒤 API가 `object.{namespace,kind,name,container}`가 정확히 같은 scan 하나만 사용합니다. 없으면 `NOT_FOUND`, 둘 이상이면 `AMBIGUOUS`, 값 형식 오류는 `INVALID_REPORT`입니다.
- 단위: KRR CPU 값(core)은 millicore로 올림, 메모리는 bytes 올림. `"?"`는 값 없음(`info`의 `No data`·`Not enough data`·`HPA detected` 사유 유지), `null` CPU limit은 KRR `simple`이 limit을 두지 않는다는 뜻입니다. 둘 다 0으로 바꾸지 않습니다.
- KRR `simple` 기본값: CPU 요청=p95, CPU limit 없음, 메모리 요청·limit=최대+15%, 최소 100점 필요, 하한 CPU 10m·메모리 100MB. 실제 설정·버전·인자·보고서 SHA-256·실행 시각을 `provenance`에 보존합니다.
- 추천 구간은 KRR 실행 시각 기준 `historyHours`입니다. 화면의 측정 구간과 다를 수 있어 둘을 따로 표시합니다. bare Pod는 KRR 지원 대상이 아니어서 `UNSUPPORTED`입니다.
- bridge 실패·시간 초과(기본 120초)·미구성은 `UNAVAILABLE`과 사유로 반환하며 대체 추천을 만들지 않습니다.
- 분석 요청 하나에 기한 하나를 둡니다(기본값, 설정 가능): KRR은 요청 시작부터 병렬로 실행되고(bridge 120초, API의 bridge 대기 130초), 워크로드·장치 Prometheus 조회는 관측 예산 60초를 공유합니다. 예산을 넘긴 조회와 KRR 대기는 중단(interrupt)되어 `UNAVAILABLE`(시간 한도)이 됩니다. KRR 대기는 전체 기한 180초에서 저장 여유(최대 5초)를 뺀 시점까지이고, 문서 저장 직전에 전체 기한을 넘었으면 저장하지 않고 503 `RESOURCE_ANALYSIS_TIMEOUT`을 반환합니다.
- 이 보장은 Prometheus·KRR 대기와 저장 전 확인에 대한 것입니다. 저장(INSERT) 자체의 소요 시간이나, 브라우저가 연결을 먼저 끊었을 때 서버 작업이 취소되는지는 따로 제한하지 않습니다. 기본값 180초는 콘솔 프록시 대기 210초·브라우저 220초보다 짧게 잡았습니다. API는 외부 조회 동안 DB 트랜잭션을 열지 않습니다.

## 2-1 전달 계약

`GET /api/v1/resource-profiles/services/{serviceKey}/versions/{serviceVersion}/revisions/{revision}/handoff`(또는 `/{profileId}/handoff`)는 `edgeai.io/resource-profile-handoff/v1` 문서를 반환합니다.

| 필드 | 내용 |
|---|---|
| `profile` | revision ID·번호·digest·저장 시각·측정 상태 |
| `service` | SERVICE 버전 ID·key·version·digest |
| `compatibility`·`inputConditions` | OS·아키텍처(SERVICE `platform.architectures` 범위)·런타임, 입력 조건 |
| `resources.cpu/memory` | 정수 min/request/max, basis, Kubernetes quantity(`250m`, `512Mi`) |
| `resources.accelerators[]` | 확장 자원 이름·단위·범위·장치 메모리 요구량, `deviceMemoryReservable:false`, `verticalScaling:NOT_SUPPORTED` |
| `mappings.podContainerResources` | 컨테이너 `requests.cpu/memory`·가속기 요청에 대응할 필드 경로. max는 허용 상한이며 limit 결정은 2-1 |
| `mappings.verticalPodAutoscaler` | `controlledResources:[cpu,memory]`, `minAllowed`·`maxAllowed`, 권장 시작 `updateMode: Off` |
| `evidence` | 인용 분석의 대상·구간·실측·장치 관측·KRR 추천 사본 |

Pod YAML 생성, 오프로딩 대상 결정, 워커 할당·자원 제어는 이 기능에 없습니다. revision과 분석은 DB trigger로 수정·삭제할 수 없고, 프로파일이 있는 SERVICE 버전은 삭제할 수 없습니다(409 `PROFILE_IN_USE`).

### 실행 측 호환성(설치는 범위 밖)

| 항목 | 기준 |
|---|---|
| Kubernetes | 현재 클러스터 1.31 |
| VPA | 1.31과 호환되는 1.5.x 계열로 고정(마스터의 1.8 계열 기준 문서를 사용하지 않음). CPU·메모리만, `updateMode: Off`로 시작해 추천을 비교한 뒤 적용 방식 결정 |
| GPU/NPU | VPA 대상 아님. HAMi 등 실행 측 장치별 방식의 지원 범위를 따로 확인 |
| KubeEdge | 엣지 노드에서의 VPA 동작은 검증되지 않음 |

HAMi·VPA 설치와 워커 변경은 이 웹 기능 범위가 아니며 이 저장소 변경으로 수행하지 않습니다.

## 설정과 배포

| 환경 변수(API) | 기본값 | 의미 |
|---|---|---|
| `EDGEAI_RESOURCE_PROFILING_ENABLED` | `true` | API 활성화(저장·조회는 DB만 필요) |
| `EDGEAI_RESOURCE_PROFILING_NAMESPACES` | `edgeai` | 분석 허용 네임스페이스(쉼표 구분) |
| `EDGEAI_RESOURCE_PROFILING_PROMETHEUS_URL` | 비어 있음 | 비어 있으면 실측 `UNAVAILABLE` |
| `EDGEAI_RESOURCE_PROFILING_KRR_BRIDGE_URL` | 비어 있음 | 비어 있으면 추천 `UNAVAILABLE` |
| `EDGEAI_RESOURCE_PROFILING_KRR_TIMEOUT_SECONDS` | `130` | bridge 응답 대기(bridge 120초보다 길게) |
| `EDGEAI_RESOURCE_PROFILING_OBSERVATION_BUDGET_SECONDS` | `60` | 워크로드·장치 Prometheus 조회가 공유하는 예산 |
| `EDGEAI_RESOURCE_PROFILING_TOTAL_BUDGET_SECONDS` | `180` | 분석 전체 기한(최대 200, 콘솔 프록시 210초보다 짧게) |

bridge(`profiling-bridge/`)는 공식 `robustadev/krr:v1.31.1` 이미지(index digest 고정) 위의 표준 라이브러리 HTTP 서버이며 동시에 한 번만 KRR을 실행합니다. 설정은 `KRR_PROMETHEUS_URL`, `KRR_ALLOWED_NAMESPACES`, `KRR_TIMEOUT_SECONDS`입니다.

- 공식 이미지는 `USER`가 없어(root) 이 이미지는 `10001:10001`로 실행하고 `HOME`·`TMPDIR`를 `/tmp`로 둡니다. KRR은 보고서와 matplotlib·설정 캐시만 쓰므로 읽기 전용 root 파일시스템에 `/tmp` emptyDir만 쓰기 가능하면 됩니다.
- 공식 이미지 안의 `krr version`은 `dev`를 출력합니다(버전 문자열은 릴리스 바이너리에만 주입). 그래서 bridge는 `krrVersion`과 함께 고정된 기반 이미지 `krrImage`(`robustadev/krr:v1.31.1@sha256:835bf3b6…`)를 응답·추천 provenance(`image`)에 남깁니다.
- KRR stderr는 64 KiB 단위로 읽고 마지막 8 KiB만 진단용으로 보관합니다.

**이미지 빌드·고정(둘 중 하나).** 둘 다 `FROM`+`COPY`만 있어 에뮬레이션이 필요 없습니다.

1. 클러스터 rootless 빌더(현재 API·콘솔과 같은 경로, 로컬 레지스트리):

   ```bash
   docker buildx build --builder edgeai-kube-arm64-rootless --platform linux/amd64 --provenance=false \
     --label org.opencontainers.image.revision=$(git rev-parse HEAD) \
     -t 10.254.192.47:5000/edgeai/krr-bridge:$(git rev-parse --short HEAD) --push profiling-bridge
   python3 scripts/internal/enable-resource-profiling.py --digest sha256:<푸시된 digest> \
     --registry 10.254.192.47:5000/edgeai/krr-bridge
   ```

2. GitHub Actions `.github/workflows/krr-bridge.yml`: bridge 단위 시험 → `linux/amd64` 빌드 → 사용자 `10001:10001`·읽기 전용 rootfs에서 `robusta_krr` import·`/healthz`의 `krrImage`·허용 밖 요청 400·Prometheus 미연결 시 추천 없는 `FAILED` 확인 → `ghcr.io/dsa04156/edgeai-krr-bridge:sha-<commit>` 발행. 새 GHCR 패키지는 기본 비공개이므로 공개 여부나 pull secret을 확인한 뒤 `enable-resource-profiling.py --digest sha256:<digest>`로 고정합니다.

고정 후 확인과 배포:

```bash
kubectl kustomize deploy/kubernetes/overlays/dev | grep -n 'edgeai-krr-bridge\|EDGEAI_RESOURCE_PROFILING'
```

API(V70 포함)·콘솔 이미지 digest를 같은 커밋에서 갱신하고 Argo CD `edgeai-dev`(자동 동기화·selfHeal)로 반영합니다. `kubectl set image`로 바꾸면 selfHeal이 되돌리므로 overlay 커밋으로 배포합니다. 운영 반영은 검토 후 별도 수행합니다.

컴포넌트는 ServiceAccount·bridge Deployment·Service, `edgeai-api`만 허용하는 NetworkPolicy와 API 환경 변수 patch를 포함합니다. 클러스터 범위 읽기 권한은 기존 배포 규칙에 따라 GitOps 밖의 `deploy/kubernetes/bootstrap/krr-bridge-rbac.yaml`로 준비합니다. Argo CD 프로젝트의 `clusterResourceWhitelist`는 비워 둡니다. KRR의 get/list/watch만 허용하며 워크로드를 변경하는 권한은 없습니다.

컴포넌트 활성화 전에 대상 context와 같은 이름의 리소스가 없는지(있다면 EdgeAI 소유 label인지) 확인하고 bootstrap을 적용합니다. 다른 설치의 리소스는 인수하지 않습니다.

```bash
kubectl --context <대상-context> apply --dry-run=server -f deploy/kubernetes/bootstrap/krr-bridge-rbac.yaml
kubectl --context <대상-context> apply -f deploy/kubernetes/bootstrap/krr-bridge-rbac.yaml
```

로컬 확인은 KRR 릴리스 바이너리로 bridge를 실행해 할 수 있습니다.

```bash
KRR_COMMAND=/path/to/krr KRR_PROMETHEUS_URL=http://<prometheus>:9090 KRR_ALLOWED_NAMESPACES=edgeai \
  KRR_BRIDGE_BIND=127.0.0.1 KRR_BRIDGE_PORT=18471 python3 profiling-bridge/krr_bridge.py
```

KRR은 kubeconfig 또는 in-cluster 서비스 계정으로 워크로드·Pod 목록을 읽고 Prometheus 2.26+에서 `container_cpu_usage_seconds_total`·`container_memory_working_set_bytes`·`kube_replicaset_owner`·`kube_pod_owner`·`kube_pod_status_phase`를 사용합니다.

## 시험

```bash
python3 -m unittest discover -s profiling-bridge/tests
bash scripts/test/test-unit.sh                  # 도메인·adapter(KRR 실제 보고서 fixture, 가짜 Prometheus HTTP) 포함
bash scripts/test/test-integration.sh           # ResourceProfileIntegrationTest: 격리 PostgreSQL
bash scripts/test/test-ui.sh                    # resource-profile.unit.ts, 콘솔 경로 경계
EDGEAI_DASHBOARD_PORT=<port> npx playwright test --config dashboard/playwright.profiles.config.ts tests/resource-profile.integration.spec.ts
```

## 남은 범위

- 운영 배포·운영 DB 적용은 하지 않았습니다.
- DCGM의 Pod 소유 정보 연결(kubelet pod-resources)이 없어 GPU 관측은 장치 전체 값입니다. 다른 장치 exporter의 기간 조회는 연결하지 않았습니다.
- KRR은 네임스페이스·종류 단위로 스캔하므로 큰 네임스페이스에서는 실행 시간이 길어질 수 있습니다(bridge 제한 120초).
- 실행 측 ①(HAMi·device plugin 분할)과 ③(VPA 증강)의 실제 연동 근거는 3-3 전체 완료 판단에 별도로 필요합니다.
