# AI 모델 자원 측정

[AI 모델 관리](ai-model-catalog.md)에 등록한 모델 실행 구성을 **등록 이미지로 띄운 임시 측정 Pod**에서 실제 추론 부하로 측정하고,
결과를 보고 CPU·메모리·GPU/NPU 요구량을 확정합니다. 확정 프로파일이 있는 모델만 **사용 가능(READY)** 으로 표시합니다.
흐름은 `모델 등록 → 측정 시작 → 측정 완료(SUCCEEDED) → 요구량 확정 → 사용 가능`입니다.

## 범위

| 제공 | 하지 않음 |
|---|---|
| 등록 이미지(플랫폼 manifest digest 고정)로 전용 namespace에 소유 Job 1개 실행, 유한 시간 후 회수 | 워커 설치, HAMi·device plugin·VPA/HPA 설치·변경, 시험 중 자동 자원 조절 |
| HTTP 샘플 요청 부하(API 안의 좁은 driver) 또는 이미지 내부 벤치마크 명령 | 범용 Runner·Locust 등 새 부하 도구, 임의 Pod YAML·볼륨·환경 변수·권한 |
| 기존 Prometheus(cAdvisor·kube-state-metrics)·장치 exporter로 그 Pod·구간·`model` 컨테이너만 조회 | GPU 사용률을 GPU 개수로 환산, 단일 측정으로 이론적 최소 요구량·처리량 목표 증명 |
| 실측·추천(고정 정책)·사용자 확정 값을 구분해 불변 revision으로 저장 | SERVICE 자동 발행, 워크플로 연동, 오프로딩 대상 결정(2-1) |

요구량 확정은 실행 측에 전달할 선언이며 자원 예약·분할·증강이나 실행 보장이 아닙니다.

## 측정 방식

### HTTP 추론 부하

모델이 HTTP 추론 서버일 때 사용합니다. 등록한 실행 명령(비우면 이미지 기본값)으로 서버를 띄운 뒤, API가 **Kubernetes가 보고한 소유 Pod IP**의
지정 `port`·`path`로만 샘플 요청을 보냅니다.

- 요청: `GET` 또는 `POST`(`application/json`·`text/plain`은 `body`, `application/octet-stream`은 `bodyBase64`), 본문 64 KiB 이하.
- 부하: 동시 요청 1–16(기본 2)이 각자 응답을 받은 뒤 다음 요청을 보내는 closed-loop. 요청 제한 시간 1–60초(헤더·본문 전체), 응답은 1 MiB까지만 읽고 버리며 redirect는 따르지 않습니다.
- 준비: 첫 2xx 응답이 올 때까지 서버 점검 주기(기본 3초)마다 한 번 요청합니다(준비 대기 30–900초, 기본 300초). 그 시점이 측정 구간 시작입니다.
- 성공 조건: 2xx 비율 95% 이상, 2xx 10건 이상, 구간 끝까지 같은 Pod가 재시작 없이 Running.
- 지연 p50·p95는 5% 간격 고정 구간 상한 근사이며 최대값만 실제 관측값입니다. 요청별 기록은 저장하지 않습니다.
- 부하는 API 프로세스 안에서 돌기 때문에 측정 중 API가 재시작되면 `DRIVER_INTERRUPTED`로 실패합니다(이어서 측정한 것처럼 표시하지 않음). 다른 API 프로세스는 30초 lease가 끝난 뒤에만 중단으로 판정합니다.
- 구간 종료·취소·API 종료 시 진행 중인 요청은 응답 헤더나 본문이 멈춰 있어도 즉시 중단하고 집계하지 않습니다. 중지와 종료는 각각 최대 2초 안에 끝납니다.
- **측정 구간에서 빠지는 것**: 구간은 첫 2xx 응답 이후이므로 서버 기동·모델 적재 중의 메모리·CPU 최대값은 포함하지 않습니다. 적재 시 최대 메모리가 정상 추론 때보다 크면 그 값으로 OOM이 날 수 있습니다(측정 중 OOM은 `OOM_KILLED` 실패로 드러남). 결과 `limitations`에도 같은 문구를 표시합니다.

단순 기동만 하는 서버, 실패 응답만 주는 서버, 연결되지 않는 Pod는 성공이 아닙니다.

### 이미지 내부 벤치마크(COMMAND)

이미지 안에 부하를 스스로 만드는 벤치마크 명령이 있을 때 사용합니다. 측정 Pod는 이 명령·인자로 실행되며 환경 변수
`EDGEAI_PROFILING_DURATION_SECONDS`에 요청 시간이 들어갑니다. 컨테이너가 **요청 시간 이상 실행한 뒤 exit 0(Completed)** 로 끝나야 성공이고,
구간은 컨테이너 시작–종료 시각입니다. 부하 여부는 정상 종료 사실로만 확인하므로 결과에 그 한계를 표시합니다.

## 측정 Pod

| 항목 | 값 |
|---|---|
| namespace·계정 | `edgeai-model-profiling`, Pod 서비스 계정 `edgeai-model-profiler`(토큰 자동 마운트 없음) |
| 이름·소유 | `edgeai-mp-<runId>`, label `app.kubernetes.io/managed-by=edgeai-model-profiler`·`edgeai.io/model-profiling-run`·`edgeai.io/ai-model`, spec digest annotation(모든 중첩 map 키를 정렬한 정규 JSON의 SHA-256, 프로세스와 무관하게 같음) |
| Job | `suspend: true`로 생성(아래 생성 펜스), `backoffLimit 0`, `restartPolicy Never`, `activeDeadlineSeconds = 2×준비 대기 + 측정 시간 + 120`, `ttlSecondsAfterFinished 300` |
| 이미지 | 등록 시 검증한 `host/repository@<플랫폼 manifest digest>`. 실행된 `imageID`가 등록 digest와 다르면 `IMAGE_MISMATCH` |
| 배치 | 선택 노드를 `nodeAffinity matchFields metadata.name`으로 제한하고 스케줄러가 배치(`nodeName` 직접 지정 없음), `kubernetes.io/os`·`arch` nodeSelector |
| 보안 | `runAsNonRoot`(이미지의 숫자 USER), seccomp RuntimeDefault, 권한 상승 금지, capability 전부 제거, 읽기 전용 root FS, `/tmp` emptyDir 256Mi |
| 시험 할당 | CPU 100–8000m, 메모리 64MiB–16GiB, ephemeral-storage 1Gi, 가속기 1–4개. request = limit. 노드 allocatable 이내, 가속기는 서버 allowlist ∩ 노드 allocatable |

**생성 펜스.** DB 조건부 UPDATE는 다른 API 프로세스가 늦게 보낸 Kubernetes 요청을 막지 못합니다(예: Job 생성 직전에 멈춘 프로세스, 그 사이 취소·회수 완료·같은 노드 새 측정 승인).
그래서 Job은 [suspended](https://kubernetes.io/docs/concepts/workloads/controllers/job/#suspending-a-job) 상태로 만들고(Pod 없음), Job UID와 `STARTING`을 DB에 기록한 뒤,
Job을 다시 읽고 → DB에서 run이 아직 그 UID의 `STARTING`인지 확인하고 → 읽은 `uid`·`resourceVersion`을 조건으로 `spec.suspend=false` merge patch를 보냅니다.

- 취소 뒤 늦게 도착한 생성은 Pod 없는 suspended Job만 남기고, 그 run의 상태 기록은 거부되며, 고아 회수가 Job을 지웁니다.
- 늦게 도착한 resume은 Job이 삭제됐으면 404, 삭제 중이거나 바뀌었으면 resourceVersion 불일치 409로 거부됩니다. PATCH는 Job을 다시 만들지 않습니다.
- 회수는 Job과 Pod가 모두 사라져야 끝나므로, 이전 run의 Pod가 새 run과 동시에 실행되는 경우는 없습니다.

**채택.** 같은 이름의 Job이 이미 있으면(재시작·동시 처리) label, digest annotation, 그리고 저장된 `spec` 전체를 의도한 Job과 비교합니다.
quantity는 기본 단위로 비교하고(`2000m`=`2`, `4294967296`=`4Gi`), Kubernetes가 생성 시 채우는 필드
(`completionMode NonIndexed`, `podReplacementPolicy TerminatingOrFailed`, 이 Job UID의 `selector`·template label, `dnsPolicy ClusterFirst`, `schedulerName default-scheduler`,
`terminationMessagePath`·`Policy` 기본값, `suspend`)만 추가로 허용합니다. completions·env·capabilities·seccomp·볼륨·mount·ephemeral-storage·toleration 등 그 밖의 차이는 `OWNERSHIP_CONFLICT`입니다.

**Kubernetes 호출 시간.** 조회·목록·생성·patch·삭제는 응답 본문까지 포함한 요청 전체가 5초 안에 끝나야 하며(헤더 뒤 본문이 멈춰도 취소), 끝나지 않으면 미확인으로 보고 다시 시도합니다.

시험 할당은 측정 조건이며 측정 후 확정하는 요구량과 별개입니다. CPU limit이 걸리므로 관측 CPU는 할당량에서 잘린 값일 수 있습니다.
p95가 할당량의 90% 이상이면 결과의 `notices`가 더 큰 할당량으로 재측정하라고 안내합니다.

## 상태

| state | 의미 |
|---|---|
| `PENDING` | 측정 기록만 저장됨. 서버가 Job을 만듭니다 |
| `STARTING` | Job 생성됨. 스케줄·이미지 받기·컨테이너 시작 대기 |
| `WARMING_UP` | (HTTP) 샘플 요청이 2xx로 응답하기를 기다림 |
| `MEASURING` | 부하 구간 진행 중 |
| `COLLECTING` | 구간 종료. 수집 지연(기본 60초) 후 Prometheus 조회, 실패 시 30초마다 최대 10분 재시도 |
| `SUCCEEDED` / `FAILED` / `CANCELLED` | 종단. 결과는 이후 변경되지 않습니다 |

`cleanup.state`(`NOT_STARTED → PENDING → DONE`)는 측정 상태와 별개입니다. Kubernetes가 Job과 Pod가 모두 사라졌다고 보고해야 `DONE`이며,
그 전까지 모델·노드·전체 측정 슬롯(기본 2)을 계속 차지합니다. 따라서 이전 Pod가 남아 있는 동안 같은 모델이나 같은 노드의 새 측정은 409입니다.
Kubernetes 응답을 확인할 수 없으면 회수 완료로 보지 않고 다시 시도합니다.

실패 코드: `UNSCHEDULABLE`, `IMAGE_PULL_FAILED`, `IMAGE_MISMATCH`, `CONTAINER_CONFIG_ERROR`(root 이미지 등), `CONTAINER_EXITED`, `OOM_KILLED`,
`RESTARTED`, `DEADLINE_EXCEEDED`, `READINESS_TIMEOUT`, `DRIVER_UNREACHABLE`, `LOAD_FAILED`, `TOO_SHORT`, `COMMAND_FAILED`, `DRIVER_INTERRUPTED`,
`INSUFFICIENT_SAMPLES`, `METRICS_UNAVAILABLE`, `KUBERNETES_UNAVAILABLE`(15분), `OWNERSHIP_CONFLICT`, `JOB_MISSING`.
Job의 Complete 조건만으로는 성공 처리하지 않습니다.

## 결과와 추천

- 실측: 측정 Pod UID·구간·`model` 컨테이너로 한정한 CPU(millicores)·메모리 working set의 최소·평균·p95·최대·표본 수. CPU와 메모리가 모두 `AVAILABLE`이고 표본 10개 이상이어야 성공입니다(구간 300–1800초, 기본 300초, step 15초).
- 장치: 시험에 가속기를 할당하면 시험 노드의 기존 exporter 장치 지표를 그대로 붙입니다. 값은 장치 전체이며 귀속은 `TARGET_ASSOCIATED`·`NODE_DEVICE_SHARED`·`NODE_DEVICE_UNKNOWN_OWNER` 표시를 따릅니다. 찾지 못하면 `deviceStatus: UNSUPPORTED`입니다.
- 추천(`edgeai-model-profiling-recommendation-v1`): CPU 요청 = p95×1.15(10m 올림), 최대 = max(관측 최대×1.5, 요청). 메모리 요청 = 관측 최대×1.2(MiB 올림), 최대 = 관측 최대×1.5. **최소값은 추천하지 않습니다.** 가속기는 시험 할당 자원명·수를 `TEST_ALLOCATION`으로 기록합니다.
- 추천은 **시험한 부하에 대한 권장값**이며 최소 자원 증명이 아닙니다. HTTP 방식은 첫 성공 응답 이후 구간만 보므로 모델 적재 시 최대 메모리를 포함하지 않습니다.

## 요구량 확정과 사용 가능

- `request`는 필수, `minimum`·`maximum`은 선택이며 비우면 `null`(미지정)으로 저장합니다. 서버가 최소·최대를 만들어 채우지 않습니다.
- 출처 `RECOMMENDED`는 측정 추천값(요청·최대, 최소 없음)과 정확히 같을 때만, 그 외 값은 `MANUAL`(시험으로 검증되지 않음)입니다. 실측값 자체는 요구량 출처가 아닙니다.
- 가속기는 시험 할당과 정확히 같아야 합니다. 다른 자원명·수는 `ACCELERATOR_NOT_TESTED`(409)이며 그 구성으로 다시 측정해야 합니다.
- 같은 모델의 `SUCCEEDED` 측정만 근거가 됩니다(DB trigger로도 강제). 확정은 불변 revision으로 쌓이고 최신 revision이 현재 프로파일입니다.
- `availability.status`는 확정 revision이 있으면 `READY`, 없으면 `NOT_READY`이며 `reason`은 `NO_MEASUREMENT`·`MEASURING`·`MEASUREMENT_FAILED`·`AWAITING_CONFIRMATION`입니다. 새 측정이 실패해도 이전 확정 프로파일과 READY는 유지됩니다. 등록 시 선언한 요구량(legacy `requirements`)만으로는 READY가 되지 않습니다.
- READY는 `profile.scope`(이미지·실행 명령·부하 정의 digest·시험 노드·아키텍처·할당량) 안에서만 의미가 있습니다.

## API

| operation | 경로 |
|---|---|
| `getAiModelProfilingOptions` | `GET /api/v1/ai-models/{modelId}/profiling/options` — 가능 여부, 한도, 이미지 플랫폼에 맞는 노드(`accelerators[].allocatableCount`는 용량이며 현재 미할당량이 아님) |
| `startAiModelProfilingRun` | `POST /api/v1/ai-models/{modelId}/profiling-runs`(`Idempotency-Key`, CSRF) |
| `listAiModelProfilingRuns` | `GET /api/v1/ai-models/{modelId}/profiling-runs?limit&offset` |
| `getAiModelProfilingRun` | `GET /api/v1/ai-models/{modelId}/profiling-runs/{runId}` |
| `cancelAiModelProfilingRun` | `POST /api/v1/ai-models/{modelId}/profiling-runs/{runId}/cancel`(멱등) |
| `confirmAiModelResourceProfile` | `POST /api/v1/ai-models/{modelId}/resource-profiles`(`Idempotency-Key`, CSRF) |
| `listAiModelResourceProfiles` | `GET /api/v1/ai-models/{modelId}/resource-profiles` |
| `getAiModelResourceProfile` | `GET /api/v1/ai-models/{modelId}/resource-profiles/{profileId}` |

예시(실제 개발 Registry의 digits 모델, HTTP 8080 Open Inference Protocol V2):

```json
{"nodeName": "etri-ser0004-cgnms0",
 "allocation": {"cpuMillicores": 1000, "memoryBytes": 1073741824, "accelerator": null},
 "durationSeconds": 300,
 "load": {"type": "HTTP", "port": 8080, "path": "/v2/models/digits-centroid/infer", "method": "POST",
          "contentType": "application/json", "body": "{\"inputs\": [...]}", "concurrency": 2}}
```

## 운영 설정

1. 클러스터 관리자가 [`deploy/kubernetes/bootstrap/model-profiling-rbac.json`](../../deploy/kubernetes/bootstrap/model-profiling-rbac.json)을 적용합니다: namespace(Pod Security `restricted`), Pod 서비스 계정, ResourceQuota, API 서비스 계정 `edgeai/edgeai-control-plane`에 대한 namespaced Role(`jobs` get·list·create·patch·delete, `pods` get·list). `patch`는 suspended Job의 resume에만 씁니다.
2. overlay에 [`components/model-profiling`](../../deploy/kubernetes/components/model-profiling/kustomization.yaml)을 추가합니다. `EDGEAI_MODEL_PROFILING_ENABLED=true`와 가속기 allowlist를 설정합니다. Kubernetes(`EDGEAI_KUBE_*`)와 Prometheus(`EDGEAI_RESOURCE_PROFILING_PROMETHEUS_URL`)는 기존 설정을 씁니다.
3. 측정 노드의 kubelet cAdvisor가 Prometheus에 수집되어야 합니다. 2026-10-30 확인 기준 `up{metrics_path="/metrics/cadvisor"}`는 서버 노드(ser0001–0004)만 1이고 edge 노드는 0이므로, edge 노드에서는 측정이 `INSUFFICIENT_SAMPLES`·`METRICS_UNAVAILABLE`로 끝납니다.
4. HTTP 방식은 API Pod에서 측정 Pod IP로 연결할 수 있어야 합니다. 연결되지 않으면 `DRIVER_UNREACHABLE`입니다.

그 밖의 설정: `EDGEAI_MODEL_PROFILING_MAX_ACTIVE_RUNS`(기본 2), `SCRAPE_GRACE_SECONDS`(60), `COLLECT_TIMEOUT_SECONDS`(600), `KUBERNETES_TIMEOUT_SECONDS`(900), `POLL_MS`(3000).
저장소는 Flyway `V72__ai_model_profiling.sql`의 `ai_model_profiling_run`(종단 후 결과 불변, 삭제 금지)과 `ai_model_resource_profile`(불변)입니다.
근거와 시험은 [측정 근거](../evidence/ai-model-profiling-20261030.md)에 있습니다.
