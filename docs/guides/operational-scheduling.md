# 측정 기반 DAG 배치

발행된 SERVICE BATCH 워크플로에 대해 **운영 artifact 경로의 측정값**을 등록하고, 그 값으로 작업별 최초 실행 노드를 미리 보거나 기존 Run 생성 요청에서 서버가 선택하게 합니다.
결과는 측정값에 근거한 **예측 계획**입니다. 실제 실행 시간·실행 위치는 Run·Task·`/placements` 조회로 따로 확인합니다.

## 구현 상태

| 수준 | 상태 |
|---|---|
| 코드 | 프로파일 등록·조회, 미리보기, `scheduling.profileId` Run 생성, 결정 조회 구현 |
| 관련 시험 | 격리 PostgreSQL 통합 시험(`OperationalSchedulingIntegrationTest`)과 기존 Workflow·Retry 회귀 통과 |
| 운영 배포 | 미수행. 클러스터 API에 배포되기 전에는 이 기능을 사용할 수 없습니다 |
| 실측 프로파일 | 자동 profiler·부하 생성은 포함하지 않습니다. 실제 BATCH 실행에서 측정한 값만 등록합니다 |
| 성능 효과 | 실장비 비교는 수행하지 않았습니다. 지연 개선 수치를 주장하지 않습니다 |

## 비용 모델

Runner는 입력을 순서대로 내려받고 workload를 실행한 뒤 출력을 순서대로 올리고 commit합니다. 다음 작업은 모든 부모 Result가 commit된 뒤 시작합니다. 같은 경로를 다음 식으로 계산합니다.

```text
release(t) = max(commit(p) for p in parents(t)), 루트는 0
commit(t,n) = release(t) + Σ downloadMs[port][n] + computeMs[n] + Σ uploadMs[port][n]
예측 E2E = max(commit(t))
```

| 항목 | 경계 |
|---|---|
| `computeMs` | Runner workload 시작부터 종료까지. 입력은 이미 로컬에 있고 출력 파일 작성까지 포함 |
| `downloadMs` | DAG의 BATCH 의존으로 **연결된 입력 포트**마다 저장소→노드 다운로드 1회 |
| `uploadMs` | SERVICE의 **모든 출력 포트**마다 노드→저장소 업로드 1회(소비 여부와 무관) |
| 제외 | 큐 대기, 노드·링크 동시 경합, Pod 시작, 이미지 pull, kube-scheduler 지연 |

- 업로드는 생산 작업에서, 다운로드는 소비 작업에서 각각 한 번만 더합니다. 같은 노드에 둬도 저장소를 거치므로 전달 비용을 0으로 가정하지 않습니다.
- 연결되지 않은 선택 입력 포트는 Runner가 내려받지 않으므로 `downloadMs`에 넣지 않습니다. 루트 작업의 Run `parameters`는 workload 입력이며 `computeMs`에 포함됩니다.
- 작업의 비용은 자기 노드만으로 정해지고 현재 계약에는 후보 간 자원 예약·경합 제약이 없습니다. 그래서 작업마다 적합 후보 중 합계가 가장 작은 노드를 고르면(동률은 노드 UUID 사전순) 이 모델 안의 최소 critical path가 됩니다. 노드 조합을 전부 탐색하거나 HEFT를 쓰지 않습니다.
- 직접 통신 경로나 공유 자원 예약이 추가되면 이 분리 조건이 깨지므로 공동 배치 문제를 다시 정의해야 합니다.

## 후보 조건

미리보기와 Run 생성은 현재 노드 관측으로 후보를 다시 거릅니다.

| 사유 코드 | 조건 |
|---|---|
| `NOT_READY` | READY가 아니거나 마지막 관측이 60초보다 오래됨 |
| `OS` | Linux가 아님 |
| `ARCHITECTURE` | SERVICE `platform.architectures`에 없음 |
| `NODE_SELECTOR` | SERVICE `nodeSelector`가 노드 label에 없음 |
| `ALLOCATABLE_CPU` / `ALLOCATABLE_MEMORY` | 요청량이 노드 allocatable보다 큼 |
| `ALLOCATABLE_RESOURCE` | 확장·ephemeral 자원이 부족하거나 allocatable 값이 없거나 해석 불가 |
| `NOT_PROFILED` | 프로파일에 이 작업의 측정값이 없는 노드 |

allocatable은 노드 전체 할당 가능량이며 남은 자원이나 예약을 보장하지 않습니다. 노드 목록에 없는 taint·예약 정보는 판단하지 않습니다. 선택된 노드는 required node affinity로 전달되고 최종 admission은 kube-scheduler가 결정합니다.

## 프로파일 등록

`POST /api/v1/scheduling-profiles`에 `Idempotency-Key`(UUID)와 함께 보냅니다. 요청은 UTF-8 JSON 1 MiB 이하입니다.

```json
{
  "workflowVersionId": "<workflow-version-uuid>",
  "measuredAt": "2026-10-28T01:00:00Z",
  "validUntil": "2026-11-28T00:00:00Z",
  "provenance": {"kind": "MEASURED_BATCH_ARTIFACT", "evidenceId": "runner-batch-2026-10-28", "sampleCount": 12},
  "parameters": {"scale": 2},
  "tasks": {
    "split": {
      "computeMs": {"<node-a>": 10, "<node-b>": 40},
      "downloadMs": {},
      "uploadMs": {"output": {"<node-a>": 5, "<node-b>": 5}}
    },
    "left": {
      "computeMs": {"<node-a>": 50, "<node-b>": 60},
      "downloadMs": {"input": {"<node-a>": 30, "<node-b>": 5}},
      "uploadMs": {"output": {"<node-a>": 20, "<node-b>": 5}}
    }
  }
}
```

- `tasks`는 발행된 DAG의 작업 키와 정확히 같아야 합니다. 위 예시는 두 작업만 보여 줍니다.
- 포트별 노드 집합은 그 작업의 `computeMs` 노드 집합과 같아야 합니다. 빠진 값을 0으로 채우지 않습니다.
- 값은 유한한 비음수 ms, `sampleCount`는 1 이상, `measuredAt`은 현재 이하, `validUntil`은 `measuredAt` 이후입니다.
- `provenance`는 호출자가 제출한 증빙입니다. 서버는 실측 여부를 검증하지 않으므로 연구 fixture나 추정치를 등록하지 않습니다.
- strict `edgeai/v1` SERVICE의 BATCH DAG만 지원합니다. STREAM 의존·`stream` SERVICE(WORKFLOW_DATA 포함)는 409 `SCHEDULING_UNSUPPORTED`입니다.
- 같은 키·같은 문서는 200으로 기존 프로파일을 반환하고, 다른 문서는 409 `IDEMPOTENCY_CONFLICT`입니다. 등록한 프로파일은 수정·삭제할 수 없습니다.

`GET /api/v1/scheduling-profiles/{profileId}`로 저장된 문서와 `digest`를 조회합니다.

## 배치 미리보기

`POST /api/v1/scheduling-profiles/{profileId}/placement-preview`에 `{"parameters": {...}}`를 보냅니다. 저장하지 않습니다.

응답의 `placements`는 작업별 선택 노드, `candidates`는 노드별 `costMs`·`eligible`·`reasons`, `predictedCommitMs`와 `predictedCriticalPathMs`·`criticalPath`는 예측값입니다. `predicted: true`, `admission: kube-scheduler`, `costModel`이 함께 반환됩니다.

위 예시 전체(4작업 fork/join)의 손계산은 다음과 같습니다. 통합 시험의 고정 사례와 같습니다.

| 작업 | node-a 합계 | node-b 합계 | 선택 | commit |
|---|---|---|---|---|
| split | 10+5=15 | 40+5=45 | node-a | 15 |
| left | 30+50+20=100 | 5+60+5=70 | node-b | 85 |
| right | 5+20+5=30 | 5+80+5=90 | node-a | 45 |
| join | 10+10+10+4=34 | 1+1+10+4=16 | node-b | 101 |

`left`는 compute만 보면 node-a가 빠르지만 다운로드·업로드를 더하면 node-b가 작습니다. 예측 critical path는 `split → left → join`, 101 ms입니다.

## 측정 기반 Run 생성

기존 `POST /api/v1/workflow-runs` 요청에 `scheduling`을 추가합니다.

```json
{
  "workflowVersionId": "<workflow-version-uuid>",
  "parameters": {"scale": 2},
  "execution": {"mode": "AUTO"},
  "scheduling": {"profileId": "<profile-uuid>"}
}
```

- 서버는 프로파일·현재 노드를 다시 확인하고, 선택한 작업별 NODE 배치를 Run의 `taskExecutions`에 저장합니다. 이 값으로 Task 최초 위치·최초 Attempt·required node affinity가 정해지며 Retry·Offload는 기존 동작을 따릅니다.
- 측정 기반 Run의 응답 `taskExecutions`는 사용자 입력이 아니라 **서버가 선택한 최초 배치**입니다. 응답의 `scheduling.profileId`가 사용자가 보낸 값입니다.
- 멱등 digest는 사용자 요청(`scheduling` 포함)으로 계산합니다. 같은 키의 재전송은 계획 전에 기존 Run을 반환하므로 프로파일 만료나 노드 상태 변화 이후에도 같은 Run이 돌아옵니다.
- Run·Task·결정은 한 트랜잭션에서 저장됩니다. 아래 거절은 아무것도 저장하지 않습니다.

| 응답 | 조건 |
|---|---|
| 400 `SCHEDULING_CONFLICT` | `taskExecutions`와 함께 지정 |
| 404 `SCHEDULING_PROFILE_NOT_FOUND` | 프로파일 없음 |
| 409 `SCHEDULING_UNSUPPORTED` | `execution.mode`가 AUTO가 아님, `streamInputs`, `device` |
| 409 `SCHEDULING_PROFILE_MISMATCH` | 다른 DAG 버전 또는 정규화 `parameters` 불일치 |
| 409 `SCHEDULING_PROFILE_EXPIRED` | `validUntil` 이후 새 결정 |
| 409 `SCHEDULING_NO_CANDIDATE` | 측정되고 현재 조건에 맞는 노드가 없는 작업 존재 |

`scheduling`이 없는 기존 AUTO/NODE/REMOTE·`taskExecutions` 요청과 digest는 바뀌지 않습니다.

## 결정 조회

`GET /api/v1/workflow-runs/{runId}/scheduling-decision`은 Run 생성 시점의 미리보기 문서와 `runId`, `decidedAt`, `effectiveTaskExecutions`를 반환합니다. 이 결정은 불변이며 재시도·offload 이후 실제 Attempt 위치는 `GET /api/v1/workflow-runs/{runId}/placements`에서 확인합니다. 측정 기반으로 만들지 않은 Run은 404 `SCHEDULING_DECISION_NOT_FOUND`입니다.

## 호출 예

CSRF 토큰·세션 쿠키 발급은 [Swagger와 API 호출](swagger-ui.md)의 순서를 따릅니다. 명령은 위 형식으로 작성한 `profile.json`(측정 프로파일)과 `run.json`(Run 요청)이 있는 디렉터리에서 실행합니다. 쿠키 파일 `jar`와 응답 파일도 이 디렉터리에 만들어집니다. `API`는 호출할 관리 API의 기준 주소로 바꿉니다. 로컬 개발 서버는 `http://127.0.0.1:18080`이고, 클러스터에 배포된 API는 해당 배포의 Ingress·포트 포워딩 주소를 사용합니다.

프로파일 등록 응답의 `id`가 미리보기 경로와 `run.json`의 `scheduling.profileId` 값이 되고, Run 생성 응답의 `id`가 결정 조회 경로의 Run ID가 됩니다. 아래 예는 `jq`로 두 값을 읽어 넣으므로 `<profile-uuid>`·`<run-uuid>` 같은 자리표시자를 직접 고치지 않아도 됩니다. 손으로 호출할 때는 각 응답의 `id`로 바꿉니다.

```bash
API=http://127.0.0.1:18080   # 호출할 관리 API 기준 주소로 바꿉니다.
curl --fail-with-body -c jar -b jar "$API/api/v1/csrf" > csrf.json
TOKEN="$(jq -r .token csrf.json)"
curl --fail-with-body -c jar -b jar -H "X-CSRF-TOKEN: $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' --data @profile.json "$API/api/v1/scheduling-profiles" > profile-response.json
PROFILE_ID="$(jq -r .id profile-response.json)"
curl --fail-with-body -c jar -b jar -H "X-CSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
  --data '{"parameters":{"scale":2}}' "$API/api/v1/scheduling-profiles/$PROFILE_ID/placement-preview"
jq --arg id "$PROFILE_ID" '.scheduling.profileId = $id' run.json > run-scheduled.json
curl --fail-with-body -c jar -b jar -H "X-CSRF-TOKEN: $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' --data @run-scheduled.json "$API/api/v1/workflow-runs" > run-response.json
RUN_ID="$(jq -r .id run-response.json)"
curl --fail-with-body -c jar -b jar "$API/api/v1/workflow-runs/$RUN_ID/scheduling-decision"
```

### 웹 콘솔 경유 호출

배포된 웹 콘솔(13080)은 전용 화면 없이 같은 API를 워크플로 세션으로 전달합니다. 콘솔 주소를 `WEB`으로 두고 위 명령의 경로를 아래처럼 바꿉니다. 쓰기 요청에는 `Origin: $WEB` 헤더가 필요합니다. 프로파일 등록 본문은 1 MiB, 미리보기 본문은 64 KiB까지 전달합니다.

| 직접 API (`$API`) | 웹 콘솔 (`$WEB`) |
|---|---|
| `GET /api/v1/csrf` | `GET /api/control-plane/workflow-csrf` |
| `POST /api/v1/scheduling-profiles` | `POST /api/control-plane/scheduling-profiles` |
| `GET /api/v1/scheduling-profiles/{profileId}` | `GET /api/control-plane/scheduling-profiles/{profileId}` |
| `POST /api/v1/scheduling-profiles/{profileId}/placement-preview` | `POST /api/control-plane/scheduling-profiles/{profileId}/placement-preview` |
| `POST /api/v1/workflow-runs` | `POST /api/control-plane/workflow-runs` |
| `GET /api/v1/workflow-runs/{runId}/scheduling-decision` | `GET /api/control-plane/workflow-runs/{runId}/scheduling-decision` |

## 제한

- 초기 범위는 AUTO 실행의 SERVICE BATCH입니다. STREAM·Remote·device·camera·WORKFLOW_DATA 입력은 지원하지 않습니다.
- 프로파일 목록 조회와 UI는 없습니다.
- 예측은 측정 조건(`parameters`, 입력 크기, 노드 상태)이 같다는 가정에 기댑니다. 조건이 달라지면 다시 측정해 새 프로파일을 등록합니다.
- 저장소 migration은 `V67__operational_scheduling.sql`입니다.
