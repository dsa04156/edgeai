# 노드 과부하 시 edge→worker 전환

AI 작업을 EdgeCore가 관리하는 edge 노드에서 먼저 실행하고, 그 노드의 과부하가 지속되면
중앙 Spring API가 호환되는 서버 worker 하나를 골라 같은 Task를 새 Attempt로 재시작합니다.
기존 Run `offload` 계약의 선택 필드 `nodePlacement`로 명시적으로 켭니다. 생략하면 기존 작업 측정
기반 자동 전환이 그대로 동작합니다.

이 정책은 배포된 [오프로딩 테스트 화면](offload-lab.md)에서 CPU 부하(2026-10-29)와 Jetson GPU 부하(2026-10-30)의 edge→worker→edge 왕복으로 검증했습니다. 아래 일반 정책의 모든 조합·장치·성능 수용을 검증한 것은 아닙니다. `NPU_USAGE` 기반 전환은 아직 지원하지 않습니다.

## 사용 조건

- API가 [Prometheus 노드 메트릭](node-metrics.md)을 읽을 수 있어야 합니다(`EDGEAI_PROMETHEUS_ENABLED=true`).
- 실행 worker가 켜져 있어야 합니다(`EDGEAI_RUNTIME_ENABLED=true`, `EDGEAI_RUNTIME_WORKER_ENABLED=true`).
  판단 주기는 `edgeai.runtime.offload-poll-ms`(기본 1000ms)이며 노드 메트릭은 5초간 공유 조회됩니다.
- 모든 SERVICE가 `recovery.mode=RESTART`를 선언해야 합니다. STREAM Run은 `OFFLOAD_NODE_STREAM_UNSUPPORTED`로 거절합니다.
- Run의 모든 작업을 `NODE`로 최근 READY·linux인 edge 노드에 지정해야 합니다. `AUTO`, `REMOTE`, worker 노드 지정은
  실행 전에 `OFFLOAD_INITIAL_PLACEMENT_INVALID`(409)입니다.
- 역할은 라벨 값이 아니라 존재로 판단합니다. edge는 `node-role.kubernetes.io/edge`, 후보 worker는
  `node-role.kubernetes.io/worker`가 있고 edge·`node-role.kubernetes.io/control-plane` 라벨이 없는 노드입니다.

라벨은 조회만으로 확인합니다.

```bash
kubectl get nodes -L node-role.kubernetes.io/edge,node-role.kubernetes.io/worker,node-role.kubernetes.io/control-plane,kubernetes.io/arch
```

## 실행 요청

`<version-id>`는 발행한 DAG 버전, `<edge-node-id>`는 인프라 관리의 edge 노드 ID로 바꿉니다.

```json
{
  "workflowVersionId": "<version-id>",
  "execution": {"mode": "NODE", "nodeId": "<edge-node-id>"},
  "parameters": {},
  "offload": {
    "cpuPercent": null,
    "memoryPercent": null,
    "latencyMicros": null,
    "consecutiveSamples": 3,
    "maxSampleAgeSeconds": 120,
    "maxGapSeconds": 60,
    "minRunningSeconds": 60,
    "cooldownSeconds": 300,
    "maxTransfers": 1,
    "drainTimeoutSeconds": 120,
    "startTimeoutSeconds": 300,
    "nodePlacement": {
      "strategy": "EDGE_TO_WORKER",
      "sourceCpuPercent": 85,
      "sourceMemoryPercent": 90,
      "sourceGpuPercent": null,
      "targetMaxCpuPercent": 60,
      "targetMaxMemoryPercent": 70,
      "targetMaxGpuPercent": 50
    }
  }
}
```

위 내용을 `run.json`으로 저장하고 [CSRF 절차](swagger-ui.md#변경-요청과-csrf)대로 전송합니다.

```bash
api=http://127.0.0.1:18080
token=$(curl -s -c jar -b jar "$api/api/v1/csrf" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
curl --fail-with-body -c jar -b jar -H "X-CSRF-TOKEN: $token" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' --data @run.json "$api/api/v1/workflow-runs"
```

## 판단 규칙

| 항목 | 의미 |
|---|---|
| `nodePlacement`가 있을 때 | `cpuPercent`·`memoryPercent`·`latencyMicros`는 모두 null. 작업 측정 임계값과 섞을 수 없습니다. |
| source 임계값 | CPU·메모리·GPU 사용률 중 하나 이상. GPU는 장치마다 별도 계열이며 어느 한 GPU가 연속 초과하면 충족합니다. 같은 지표가 연속 `consecutiveSamples`개 모두 이상이어야 합니다. 동시 충족 시 GPU→메모리→CPU 순으로 `NODE_GPU`/`NODE_MEM`/`NODE_CPU`를 기록합니다. |
| 표본 | Prometheus 원본 시각이 증가한 관측만 새 표본입니다. 같은 표본의 반복 조회는 다시 세지 않고, 다른 GPU의 갱신이 변하지 않은 GPU 표본을 새 표본으로 만들지 않습니다. 만료(`fresh=false`)·미래(5초 초과)·범위 밖·누락 관측, source 노드 미관측과 메트릭 조회 실패는 연속성을 끊어 처음부터 다시 셉니다. 모든 표본은 `maxSampleAgeSeconds` 이내, 인접 간격은 `maxGapSeconds` 이하여야 합니다. |
| 범위 | `nodePlacement`일 때 `maxSampleAgeSeconds` 10–180, `maxGapSeconds` 5–120. node-exporter 수집 주기(개발 환경 약30초)보다 넉넉히 잡습니다. |
| 대기 | 현재 Attempt 시작 후 `minRunningSeconds`, 이전 전환 후 `cooldownSeconds` 이후 표본만 인정합니다. |
| 한도 | 자동 전환 `maxTransfers`(1–8, 복귀 포함)와 수동·자동 합계 8회. worker에서 실행 중인 작업은 다른 worker로 자동 이동하지 않습니다. |
| 미관측 | Prometheus 비활성·조회 실패, source 노드 미관측이면 아무 작업도 중단하지 않습니다. |

후보 worker는 최근 READY·linux, SERVICE `platform.architectures`·`nodeSelector` 호환, CPU·메모리·확장 자원 요청이
노드 전체 allocatable 이하(현재 남은 용량이나 예약이 아님), 신선한 CPU·메모리 사용률이 각각 target 상한 미만이어야 합니다. SERVICE가 GPU 확장 자원
(이름에 `gpu`가 포함된 자원)을 요청하면 장착된 모든 GPU의 신선한 사용률이 있어야 하고 최댓값이 `targetMaxGpuPercent`
미만이어야 합니다. 관측이 없으면 여유로 보지 않습니다. 남은 후보 중 max(CPU, 메모리, GPU) 사용률이 가장 낮은 노드,
동률이면 이름순 첫 노드를 고릅니다. 최적 배치나 네트워크 지연 예측이 아닙니다.
적합한 worker가 없으면 source를 중단하지 않고 다음 주기에 다시 판단합니다.

## 원래 edge로 자동 복귀 (선택)

`nodePlacement.returnToEdge`를 지정하면 worker에서 실행 중인 작업을 **처음 지정한 edge(Task의 initial NODE)**로만 되돌립니다.
다른 edge나 worker로 가지 않습니다. 생략하면 이전과 같은 단방향 정책입니다.

```json
"returnToEdge": {"cpuPercent": 40, "memoryPercent": null, "gpuPercent": null}
```

| 항목 | 의미 |
|---|---|
| 히스테리시스 | 설정한 source 임계값마다 그보다 **작은** 복귀 임계값이 필요합니다(예: 과부하 85% 이상 → 복귀 40% 미만). 둘 사이 값에서는 어느 방향으로도 움직이지 않습니다. |
| 조건 | 원래 edge의 설정한 **모든** 지표가 연속 `consecutiveSamples`개 서로 다른 신선한 관측에서 복귀 임계값 미만. GPU는 모든 GPU 계열이 미만이어야 하며 GPU 관측이 없으면 복귀하지 않습니다. |
| 미관측 | 누락·만료·조회 실패는 유휴로 보지 않고 연속성을 끊습니다. worker 자신의 부하는 판단에 쓰지 않습니다. |
| 대상 확인 | 원래 edge가 최근 READY·linux, arch·nodeSelector 호환, 요청 자원이 allocatable 이하일 때만. 아니면 worker에서 계속 실행합니다. |
| 대기·한도 | 전환 후 `cooldownSeconds` 이후 표본만 인정하고, `maxTransfers`는 이동과 복귀를 모두 셉니다(왕복 1회 = 2). 한도에 닿으면 더 움직이지 않아 흔들림이 계속되지 않습니다. |

복귀도 같은 경로입니다. worker Attempt를 fence하고 Pod 종료(물리 종료 장벽)를 확인한 뒤 원래 edge에 새 Attempt를 만듭니다.
Operation `trigger=NODE_RETURN`, `decision.strategy=WORKER_TO_ORIGINAL_EDGE`, 표본은 `decision.targetNode.samples`(원래 edge)에 있습니다.

## 전환 과정과 확인

선택은 Run 잠금 안에서 현재 inventory로 다시 계산한 직후 source를 차단합니다. 이후는 수동 NODE 전환과 같은 경로입니다.
source Attempt를 OFFLOADED로 fence하고 실제 종료와 CREATE 완료를 확인한 뒤 선택한 worker를 고정한 OFFLOAD Attempt를
만듭니다. 다른 노드의 Pod claim은 거절되며 새 producer의 claim 확인 시 Operation이 SUCCEEDED가 됩니다.
SUCCEEDED는 전환 성공이며 작업 결과 성공과 다릅니다.

`GET /api/v1/operations/{operationId}` 또는 Task 상세의 `offloads`에서 다음을 확인합니다.

- `trigger`: `NODE_CPU`/`NODE_MEM`/`NODE_GPU`, `targetNodeId`: 선택한 worker
- `decision.sourceNode.samples`: 판단에 사용한 지표별 관측값과 원본 시각
- `decision.candidates`: 평가한 worker(최대32개, 선택된 worker는 항상 포함)의 사용률과 `ELIGIBLE`, `NOT_SCHEDULABLE`, `RESOURCE_UNAVAILABLE`,
  `UNOBSERVED`, `GPU_UNOBSERVED`, `NO_HEADROOM` 사유
- `decision.sampling=IN_MEMORY_SINCE_API_START`: 표본 이력은 API 메모리에 있어 API 재시작 시 초기화됩니다. 재시작 후에는 새 관측이
  `consecutiveSamples`개 쌓여야 판단합니다. 저장된 Operation의 판단 근거는 유지됩니다.

## 제한

- Kubernetes 자원을 예약하지 않습니다. 실제 bind는 kube-scheduler가 결정하며 시작 deadline을 넘으면 Task를 실패 처리합니다.
- drain·start deadline은 기존처럼 fail-closed입니다. 연결이 끊긴 edge는 물리 종료를 확인할 수 없어 drain timeout으로 실패하며,
  실패를 성공한 전환으로 바꾸지 않습니다.
- `returnToEdge`를 생략하면 자동 복귀가 없습니다. rollback은 없으며 RESTART 관리 작업만 대상이고 임의 standalone Pod나 상태형 작업은 이동하지 않습니다.
- 확장 자원 이름을 서로 변환하지 않습니다. 예를 들어 edge의 `nvidia.com/gpu.shared`를 요청한 SERVICE는 `nvidia.com/gpu`만 있는
  worker로 이동할 후보가 없습니다. 양쪽에서 실행되는 multi-arch 이미지와 자원 요청이 필요합니다.
- 노드 사용률은 노드 전체 관측이며 이 작업의 사용량이 아닙니다. 다른 작업 때문에 과부하여도 이 작업이 이동할 수 있습니다.
