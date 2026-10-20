# 엣지 워크플로 실행과 통합 운영 소프트웨어 기술자료

**TDP-02** · 2026-10-20

과제명: (기입) · 작성자: (기입) · 검토자: (기입)
기준 소스: `1170c1e88667ea22bba2b21844456c18821e0213`

## 구성

SERVICE DAG 실행, 센서·VD 입력 연계, 결과 검증, Kestra 운영 콘솔의 소스와 설치·운영·시험 절차를 정리한다. 설계 설명은 [TM-04](TM-04_분산_작업_실행과_결과_검증.md), [TM-05](TM-05_실시간_워크플로_입력_연계.md), [TM-06](TM-06_엣지_자원_관측과_운영_콘솔.md)에 있다. 센서와 eKuiper 준비는 [TDP-01](TDP-01_센서_수집과_가상디바이스_처리.md)을 따른다.

| 구성 | 기능 |
|---|---|
| 실행 제어 | 모듈·DAG 버전, Run·Attempt, 배치·노드 이동 |
| Runner | 입력 검사, 계산, 출력 업로드, 결과 확정 |
| 실시간 입력 | 센서·VD 최신 유효값, 입력 식별 정보 고정, 정상 종료 |
| 운영 콘솔 | 자원 모니터링, 편집, 실행·결과·감사 조회 |

### 소스 위치

| 경로 | 내용 |
|---|---|
| `backend/domain` | 실행 모델·정책·외부 연동 인터페이스 |
| `backend/app` | DAG·Run·Task·위치 전환·입력 서비스 |
| `backend/adapters` | DB·Kubernetes·S3·메트릭 연동 |
| `runner/edgeai_runner` | claim·실행·STREAM·journal |
| `contracts/openapi` | 공개 API·내부 Runner API |
| `contracts/profiles` | SERVICE 실행·데이터 입력 예제 |
| `dashboard/kestra-native` | EdgeAI용 Vue 화면·API 연동 |
| `scripts/dev/dev-kestra-native.sh` | Kestra 원본 UI 준비·실행 |

Kestra는 v2.0.6 원본 revision `b59444f09be2e44a3b0738452b2e02fc4fe89505`를 사용한다. 실행 제어는 EdgeAI API가 담당하며 Kestra 서버·DB는 설치하지 않는다. 외부 소스의 라이선스와 고지를 유지한다.

## 요구사항과 책임 분담

Kubernetes는 실제 Pod 배치를, 결과 저장소는 파일 보관을 담당한다. EdgeAI 서버는 실행 의도와 권한·상태를 관리하고 Runner가 제출한 파일을 검증한다. Kestra UI는 이 상태를 조회·편집하는 화면이다. 화면 표시, 요청 접수, 실제 프로세스 실행, 파일 업로드와 검증된 결과 확정을 각각 확인해야 한다.

| 요구사항 | 구현·설계 근거 | 인수 시험 |
|---|---|---|
| R04-01·02 중복 요청·불변 버전 | WorkflowService·ExecutionService·DB seal 제약 | AT02-01·02 |
| R04-03·04 현재 실행·파일 검증 | RuntimeLifecycleService·Runner·ResultService | AT02-03·04 |
| R04-05 전환 중 실행 권한 격리 | OffloadService·Runtime 명령·epoch | AT02-05 |
| R05-01·02 typed 입력·신원 고정 | WorkflowSourceInput·WorkflowDataInputService | AT02-06 |
| R05-03~05 유효 표본·중복·종료 | reader·EXTERNAL journal·STREAM 완료 | AT02-07 |
| R06-01·02 관측 의미·장비 식별 | NodeMetricsService·Prometheus 어댑터 | AT02-08 |
| R06-03~05 세션·편집·조회 일관성 | native 프록시·Vue 편집기·이력 화면 | AT02-09·10 |

R04~R06은 관련 TM의 요구사항 식별자다. AT02는 아래 인수 절차와 연결한다. 구현과 시험이 확인된 범위, 과거 기록, 새 환경에서 아직 수행하지 않은 항목을 구분해 관리한다.

### 데이터와 파일의 인계 단위

ProfileVersion·WorkflowVersion은 실행 정의, Run·Task·Attempt·Runtime은 실행 이력, Result·Artifact는 확정 결과다. 브라우저 초안은 이 영속 실행 이력과 별개다. 재시도·전환은 Task를 새로 등록하는 대신 같은 Task에 새 Attempt·epoch를 남긴다. 완료한 파일 결과는 고정 object version과 SHA-256으로 인계한다.

| 정보 | 보존·확인 방법 |
|---|---|
| SERVICE | Profile UUID·버전·이미지 digest·입출력·자원·실행 시간 |
| DAG | WorkflowVersion UUID·발행 버전·digest·Task·포트 연결 |
| 실행 | Run·Task·Attempt·epoch·배치 노드·Operation |
| 결과 | Result ID·manifest digest·포트·bucket/key/version·bytes·SHA-256 |
| 실시간 입력 | typed 선택·고정 원본 신원·sampleId·journal·최종 checkpoint |
| 콘솔 | 사용한 소스·lockfile·API 목적지·세션 구분·편집 파일 형식 |

## 실행 환경과 설정

| 구성 요소 | 로컬 기본 포트 | 역할 |
|---|---|---|
| 기존 웹·BFF | 13080 | API 프록시·문서 |
| Kestra native 콘솔 | 13083 | Vue 운영 화면 |
| 관리 API | 18080 | 센서·VD·저장 관리 |
| 별도 워크플로 API | 18082 | 모듈·DAG·실행 제어 |
| InfluxDB | 18086 | 원본·VD 결과 이력 |

관리 API는 JDK 21, 기존 BFF는 Node 22·pnpm 10.34.6을 사용한다. Kestra UI는 Node 24·npm 11로 별도 실행한다. Runner 의존성은 Dockerfile과 `requirements-stream.txt`, UI 의존성은 lockfile을 따른다. [2][5]

관리·워크플로 API를 분리하면 같은 control DB를 쓰되 원본 수집은 관리 인스턴스 한 곳에서만 실행한다. 세션은 `EDGEAI_SESSION`과 `EDGEAI_WORKFLOW_SESSION`으로 구분하고 각각 CSRF를 사용한다.

| 설정·조건 | 용도 |
|---|---|
| `EDGEAI_API_BASE_URL` | BFF가 연결할 관리 API 주소 |
| `EDGEAI_WORKFLOW_API_BASE_URL` | 별도 워크플로 API 주소 |
| `EDGEAI_RUNTIME_ENABLED` | 실제 작업 실행 활성화 |
| `EDGEAI_PROMETHEUS_ENABLED`, `EDGEAI_PROMETHEUS_URL` | 메트릭 조회 활성화·대상 주소 |
| Runner TLS·Pod 인증 | 현재 Attempt의 실행·결과 제출 권한 |
| 결과 저장소 versioning | 고정 객체 버전·digest 검증 |

대상 노드와 이미지의 아키텍처, broker·결과 저장소를 준비해야 한다. 예제 SERVICE의 0 digest는 실제 이미지 digest로 바꾼다.

## 설치와 실행

새 개발 환경에서는 소스를 준비하고 `.env.example`, Makefile, 설정 가이드를 확인한다. [2]

```bash
make setup
make check
make up
make storage
```

아래 명령은 각각 별도 터미널에서 실행한다.

| 대상 | 명령 |
|---|---|
| 관리 API | `make backend` |
| 기존 BFF | `make dashboard` |
| native 콘솔 | `bash scripts/dev/dev-kestra-native.sh` |

워크플로 API를 분리할 경우 포트·세션 이름·DB 연결·원본 수집 비활성 설정을 확인해 별도로 실행한다. 기본 명령만으로 18082 인스턴스가 자동 생성되지는 않는다.

`make health`와 API readiness로 DB·서버 연결을 확인한다. `http://127.0.0.1:13083/ui/main/dashboards/default`에서 실제 노드·센서·실행 목록을 조회한다. 원본 UI 준비 스크립트는 revision과 lockfile을 확인하며 다른 checkout을 덮어쓰지 않는다.

## API와 데이터

### 주요 서버 경로

| 경로 | 기능 |
|---|---|
| `/api/v1/profiles/SERVICE` | 실행 모듈의 고정 버전 |
| `/api/v1/workflows` | 워크플로·DAG 버전 |
| `/api/v1/workflow-runs` | 실행 요청·목록·상세 |
| `/api/v1/workflow-runs/{id}/drain` | 새 입력을 끝내는 정상 종료 |
| `/api/v1/tasks/{id}` | Task·Attempt·전환 이력 |
| `/api/v1/tasks/{id}/results` | 검증된 결과 |
| `/api/v1/workflow-data-sources` | 센서·VD 입력 목록 |
| `/api/v1/node-metrics` | 노드 자원 메트릭 |

메서드·요청 형식·revision·멱등 조건은 OpenAPI와 컨트롤러를 따른다. 브라우저는 `/api/control-plane/`의 허용된 경로로 접근한다. lossless JSON으로 큰 정수를 보존하고, 관리·워크플로 API의 세션과 CSRF를 따로 사용한다. [2][4]

### 실행과 파일

Run은 WorkflowVersion과 입력을 고정한다. Task는 논리 작업이며 재시도·전환마다 Attempt를 새로 만든다. 결과는 현재 실행 권한, object version, 크기, SHA-256을 검사한 뒤 확정한다.

편집 파일은 `edgeai.workflow-editor/v1` 형식으로 DAG와 좌표를 저장한다. 브라우저 초안은 워크플로별 로컬 저장이며 발행 버전과 별개다. 가져오기에 실패하거나 취소해도 현재 작업을 유지한다.

실시간 입력은 센서 UID·설정·generation 또는 VD revision·실행 generation을 Run에 고정한다. 1초마다 최신 유효값을 읽고 sampleId 중복을 제거한다. 모든 중간 측정값의 전달·재생을 보장하지 않는다.

## 워크플로·실행 API 상세

공개 API는 관리 세션·CSRF 정책을 따르고, 내부 Runner API는 현재 Attempt와 Pod 신원을 요구한다. 두 인증을 서로 대체하지 않는다. 아래 요청 예시의 UUID는 설명용이다. 실제 발행 ProfileVersion·WorkflowVersion·노드 ID로 바꾸고, 새 실행의 멱등 키도 별도로 발급한다.

### 생성과 불변 버전 발행

| 메서드·경로 | 입력 | 성공 응답 |
|---|---|---|
| POST `/api/v1/profiles/SERVICE` | SERVICE의 key·version·spec | 신규 201·같은 버전/내용 200, ProfileVersion |
| POST `/api/v1/workflows` | `key`, `displayName` | 신규 201·동일 생성 입력 200, Workflow |
| POST `/api/v1/workflows/{id}/versions` | `version`, `tasks`, `dependencies` | 신규 201·동일 발행 200, WorkflowVersion |
| GET `/api/v1/workflows/{id}` | `version`, `limit`, `offset` 선택 | Workflow·버전 목록·nextOffset |

같은 key 또는 발행 버전에 다른 내용을 보내면 기존 내용을 덮어쓰지 않고 409로 충돌을 반환한다. SERVICE 실행 스키마는 [service-execution.schema.json](../../contracts/profiles/service-execution.schema.json)이 소유하며, 원시 PodSpec을 받는 API가 아니다. 이미지 digest, command/args, 입출력 포트, 자원·아키텍처·timeout을 해당 스키마에 맞춰 지정한다.

Workflow 생성 본문은 다음 형식이다.

```json
{"key": "live-input-example", "displayName": "실시간 입력 시험"}
```

아래는 데이터 입력 SERVICE와 후속 집계 SERVICE를 연결하는 DAG 발행 예시다. 각 ProfileVersion UUID와 포트는 실제 발행한 규격에 맞춰야 한다. `dataSource`의 상세 선택 규칙은 [TM-05](TM-05_실시간_워크플로_입력_연계.md)에 있다.

```json
{
  "version": "1.0.0",
  "tasks": [
    {
      "key": "source",
      "serviceProfileVersionId": "11111111-1111-4111-8111-111111111111",
      "parameters": {
        "dataSource": {"kind": "SENSOR", "deviceId": "temperature", "property": "value"}
      }
    },
    {
      "key": "sample",
      "serviceProfileVersionId": "22222222-2222-4222-8222-222222222222",
      "parameters": {}
    }
  ],
  "dependencies": [
    {"fromTask": "source", "toTask": "sample", "fromPort": "output", "toPort": "input", "mode": "STREAM"}
  ]
}
```

Task는 `key`, `serviceProfileVersionId`, `parameters`를, 연결은 `fromTask`, `toTask`, `fromPort`, `toPort`, `mode`를 사용한다. DAG는 Task 1~128개·edge 최대 512개·JSON 64 KiB 이내다. 알 수 없는 필드, 순환·자기 연결, 없는 Task, 중복 edge와 같은 입력 포트의 복수 producer를 거절한다. 버전은 MAJOR.MINOR.PATCH 형식이다. 편집 중 좌표는 이 DAG 정의의 실행 내용과 구분한다. [WorkflowInput](../../backend/app/src/main/java/io/edgeai/app/support/WorkflowInput.java)과 [WorkflowService](../../backend/app/src/main/java/io/edgeai/app/service/WorkflowService.java)가 정규화·발행 규칙을 구현한다.

### Run 접수와 재요청

`POST /api/v1/workflow-runs`는 UUID 형식의 `Idempotency-Key`와 다음 본문을 요구한다. 이 예시는 AUTO 배치이며 실제 실행 가능 여부는 SERVICE 규격·기능 flag·저장소·노드 조건에 따라 검사한다.

```json
{
  "workflowVersionId": "33333333-3333-4333-8333-333333333333",
  "execution": {"mode": "AUTO"},
  "parameters": {}
}
```

| 필드 | 필수·형식 | 의미·제약 |
|---|---|---|
| `workflowVersionId` | 필수 UUID | 발행·봉인한 버전 참조 |
| `execution` | 필수 객체 | AUTO, NODE+nodeId 또는 지원하는 REMOTE+providerKey |
| `parameters` | 필수 객체 | Task 매개변수에 최상위 키 단위로 덮어쓰기 |
| `taskExecutions` | 선택 객체 | 실제 Task key별 배치, 최대 128개 |
| `retry` | 선택 객체 | 최초 실행 포함 재시도 예산·간격·대상 오류 |
| `offload` | 선택 객체 또는 null | 관측 임계값·연속 횟수·대기·최대 전환·timeout |
| `streamInputs`, `device` | 선택, 각 계약에 따름 | 기존 입력 경로이며 WORKFLOW_DATA 선택과 별도 |

서버가 소유하는 `workflowSourceBindings`를 parameters로 주입하면 거절한다. Run parameters에 `dataSource`만 넣어 발행된 입력 Task의 선택을 임의로 바꾸는 방식도 사용하지 않는다. WORKFLOW_DATA의 선택은 발행 DAG의 해당 Task parameters에 있으며, Run 생성 시 실제 신원을 추가로 고정한다.

새 요청은 201, 같은 키·정규화한 내용의 재요청은 기존 Run을 200으로 반환한다. 키가 같아도 내용이 다르면 409 `IDEMPOTENCY_CONFLICT`다. 이전 Run이 취소됐다고 같은 키의 재요청이 새 Run이 되지는 않는다. 목록·버전 조회는 limit 기본 20·범위 1~100, offset 기본 0을 사용하고 `nextOffset`이 null이면 다음 페이지가 없다.

Run 응답은 `id`, `workflowVersionId`, `mode`, `nodeId`, `parameters`, `retry`, `offload`, `state`, `createdAt`, `updatedAt`, `remoteTarget`, `vdId`, `taskExecutions`를 제공한다. 대상 모드에 따라 nodeId·remoteTarget 등은 null일 수 있다. 이는 접수 상태이며 실제 배치는 placements·Task·Attempt에서 확인한다. 상세는 [ExecutionService](../../backend/app/src/main/java/io/edgeai/app/service/ExecutionService.java)와 [WorkflowRunResponse](../../backend/app/src/main/java/io/edgeai/app/dto/WorkflowRunResponse.java)에 있다.

### 취소·정상 종료·위치 전환

| 메서드·경로 | 본문·조건 | 확인할 결과 |
|---|---|---|
| POST `/api/v1/workflow-runs/{id}/cancel` | 빈 JSON 객체 `{}` | 취소·실제 자원 종료, 완료 Result 덮어쓰기 금지 |
| POST `/api/v1/tasks/{id}/cancel` | `{}` | 지정 Task와 관련 후속 분기 상태 |
| POST `/api/v1/workflow-runs/{id}/drain` | `{}`, 입력 binding이 있는 Run | DRAINING 접수 후 END·FINALIZE·Result |
| POST `/api/v1/tasks/{id}/offload` | 멱등 키·현재 Attempt·대상·제한 시간 | 신규 202·동일 재요청 200, Operation 조회 |
| GET `/api/v1/operations/{id}` | Operation UUID | DRAINING·STARTING·완료·실패 상태 |

위치 전환 요청 본문 예시는 다음과 같다. sourceAttemptId는 화면에서 오래전에 읽은 값이 아닌 현재 RUNNING Attempt여야 한다. targetNodeId는 다른 호환 노드를 지정한다.

```json
{
  "sourceAttemptId": "44444444-4444-4444-8444-444444444444",
  "targetNodeId": "55555555-5555-4555-8555-555555555555",
  "drainTimeoutSeconds": 60,
  "startTimeoutSeconds": 60
}
```

RESTART 지원 또는 해당 STREAM 전환 조건을 충족해야 한다. 이전 실행 권한을 차단하고 실제 종료·미완료 CREATE 정리 뒤 새 Attempt를 만든다. Operation의 SUCCEEDED는 새 target 실행 시작의 확인이다. Task의 최종 결과 성공은 별도 Result로 판정한다. [OperationController](../../backend/app/src/main/java/io/edgeai/app/controller/OperationController.java)와 [전환 설계](../adr/0007-running-offload.md)에 상세가 있다.

### 오류 응답과 재시도 판단

공개 오류 본문은 `code`, `message`를 사용한다. 네트워크 timeout에는 응답 유실 가능성이 있으므로 새 키를 만들기 전에 원래 멱등 키로 결과를 확인한다. 409를 모두 재시도 가능한 일시 장애로 취급하지 않는다.

| HTTP·코드 | 조건 | 처리 |
|---|---|---|
| 400 `INVALID_WORKFLOW` | 필드·UUID·DAG·페이지·키 형식 오류 | 요청 수정 |
| 404 `WORKFLOW_NOT_FOUND`·`RUN_NOT_FOUND`·`TASK_NOT_FOUND` | 참조 대상 없음 | 발행·선택 대상 재확인 |
| 409 `WORKFLOW_CONFLICT` | 같은 key·버전의 내용 충돌 | 기존 내용 비교·새 버전 발행 |
| 409 `IDEMPOTENCY_CONFLICT` | 같은 키에 다른 Run 요청 | 요청 의도 확인·새 실행이면 새 키 |
| 409 `WORKFLOW_SOURCE_UNAVAILABLE`·`WORKFLOW_SOURCE_CHANGED` | 입력 미준비 또는 고정 원본 변경 | 유효 입력 준비·필요 시 새 Run |
| 413 `PAYLOAD_TOO_LARGE` | Workflow JSON 64 KiB 초과 | 요청 크기 확인 |
| 503 `RUNTIME_DISABLED` | 실제 실행 비활성 | worker·저장소·권한을 준비한 뒤 활성화 |
| 503 `WORKFLOW_STORE_UNAVAILABLE` | 실행 저장소 사용 불가 | 저장소 복구 후 원래 요청 재확인 |

전체 응답 스키마는 [공개 OpenAPI](../../contracts/openapi/platform-api.yaml)를 기준으로 하고, 현재 예외 처리는 [WorkflowExceptionHandler](../../backend/app/src/main/java/io/edgeai/app/exception/WorkflowExceptionHandler.java)에 있다. 내부 인증 실패·실행 fence는 공개 요청의 오류 처리와 분리한다.

## 결과·실시간 입력·메트릭의 데이터 명세

### 검증된 결과와 내용 조회

`GET /api/v1/tasks/{id}/results`는 `taskId`, `items`를 반환한다. 결과가 없으면 빈 items일 수 있으므로 HTTP 200만으로 계산 성공을 판정하지 않는다. 결과 객체는 `id`, `taskId`, `attemptId`, `runtimeId`, `epoch`, `producerPodUid`, `manifestDigest`, `createdAt`, `artifacts`와 Remote·VD 식별 필드를 포함한다. artifact는 `port`, `bucket`, `objectKey`, `objectVersion`, `sha256`, `bytes`, `mediaType`이다.

`objectVersion`은 검증한 고정 버전이며 현재 key의 최신 파일과 다를 수 있다. ETag나 사용자가 넣은 metadata를 내용 digest로 대신하지 않는다. 공개 응답에는 실행 token·서명 업로드 URL을 넣지 않는다. JSON 결과 내용은 `/api/v1/tasks/{id}/results/{port}/content`로 조회한다. 메타데이터와 byte 검증은 [ResultController](../../backend/app/src/main/java/io/edgeai/app/controller/ResultController.java)·[TaskResultsResponse](../../backend/app/src/main/java/io/edgeai/app/dto/TaskResultsResponse.java)를 따른다.

### 입력 목록과 내부 reader

`GET /api/v1/workflow-data-sources`의 응답은 `items`, `truncated`, `samplingIntervalSeconds`, `asOf`다. 항목은 `selection`, `label`, `unit`, `type`, `available`을 제공한다. VD 목록 탐색은 최대 500개로 제한되며 `truncated`를 확인한다. 목록에서 선택한 뒤에도 Run 생성 시 입력 신원을 다시 검사한다.

내부 reader는 `POST /internal/v1/attempts/{attemptId}/streams/input`이다. Runner claim과 Pod 신원을 모두 요구하고 호출자의 원본 주소를 받지 않는다. 요청은 공통 `StreamExecutionRequest` 스키마를 사용한다. DATA 응답은 `state`, 64자리 16진수 `sampleId`, `sample`로 구성하며 sample은 `value`, `unit`, `type`, `observedAt`, `quality=VALID`, 서버가 고정한 `source`를 포함한다. sample 크기는 4,096byte 이하다.

유효 입력이 없으면 WAITING, 정상 종료가 기록됐으면 END다. 원본 신원 변경은 409이며 기존 Run에서 다른 원본으로 대체하지 않는다. 조회는 1초 주기이고 센서·결과의 나이와 자료형을 검사한다. reader 성공은 lease 연장이 아니므로 heartbeat·journal·STREAM 상태도 유지해야 한다. 요청·인증·응답의 전체 정의는 [STREAM OpenAPI](../../contracts/openapi/stream-api.yaml)와 [입력 서비스](../../backend/app/src/main/java/io/edgeai/app/service/WorkflowDataInputService.java)에 있다.

### 노드 메트릭

`GET /api/v1/node-metrics`는 `status`, `fetchedAt`, `maxAgeSeconds`, `items`, `unmappedSeries`를 제공한다. 각 노드는 `nodeName`, `measurements`, `readiness`, `accelerators`다. measurement는 `key`, `device`, `value`, `unit`, `observedAt`, `fresh`이며 readiness와 accelerator에도 원본 시각·fresh가 있다.

응답 전체의 조회 시각과 원본 관측 시각을 구분한다. 신선한 값 0은 정상값, 누락·만료는 미수집 또는 만료다. 가속기 장착 목록과 사용률도 따로 해석한다. 세부 형식은 [NodeMetricsResponse](../../backend/app/src/main/java/io/edgeai/app/dto/NodeMetricsResponse.java), 단위·exporter별 연결은 [메트릭 가이드](../guides/node-metrics.md)를 따른다.

## 사용과 운영

### 실행과 정상 종료

1. SERVICE의 입출력, 이미지 digest, 실행 규격을 확인한다.
2. DAG에 모듈을 추가하고 포트를 연결한다.
3. 센서·VD 입력을 선택하고 후속 작업·연결을 확인한다.
4. 새 버전을 발행하고 배치 정책을 지정해 실행한다.
5. Run·Task·Attempt와 실제 배치를 확인한다.
6. 정상 종료는 drain으로 요청하고 DATA·END 처리와 FINALIZE 후 Result를 확인한다.

같은 요청을 다시 보낼 때는 같은 멱등 키를 사용한다. 강제 취소와 정상 종료는 구분한다. 입력 식별 정보가 달라지면 기존 Run에 새 원본을 자동 연결하지 않는다. [3]

### 자원과 이력

운영 현황은 서버 우선 목록과 더보기, CPU·메모리 막대, GPU·NPU 공통 열을 제공한다. 실제 0과 미수집·만료를 구분하고 장착 정보와 사용률도 따로 확인한다. 공유 슬롯을 물리 GPU 여러 개로 세지 않는다. Intel NPU는 운영 표에서 숨기되 원본 지표는 유지한다.

실행 목록은 3초, 감사 목록은 5초마다 갱신한다. 펼친 STREAM 첫 페이지는 3초마다 갱신하며 접기·일시정지·과거 페이지에서는 멈춘다. 조회에 실패하면 마지막 기록과 오류를 함께 표시한다.

### 오류 점검

| 오류 | 확인할 내용 |
|---|---|
| claim 거절 | Attempt·epoch·Pod 인증 |
| 결과 검증 실패 | 고정 object version·크기·digest |
| 위치 전환 실패 | 이전 실행 종료·권한 회수, 새 Attempt·Operation |
| 입력 중단 | 데이터 유효 시간, 고정한 UID·revision·generation |

전환 Operation의 `SUCCEEDED`와 최종 Result 성공은 별개다. 합성 데이터와 Remote 참조 계산은 실장비 시험과 구분해 표시한다.

## 시험 절차와 기존 결과

기본 시험은 `make test`, Runner는 `bash scripts/test/test-runner.sh`, PostgreSQL 통합은 `bash scripts/test/test-integration.sh`를 사용한다. 통합 시험에는 격리 DB를 사용한다. [2][5]

native API 시험은 다음 명령으로 실행한다.

```bash
node --experimental-strip-types --test \
  dashboard/tests/kestra-console.unit.mjs \
  dashboard/tests/kestra-device-registration.unit.mjs \
  dashboard/tests/kestra-overview.unit.mjs
```

| 시험 | 확인할 결과 |
|---|---|
| DAG·버전 | 잘못된 포트·순환 거절, 발행 버전 유지 |
| 중복 요청 | 같은 키·내용으로 중복 Run을 만들지 않음 |
| Runner 결과 | 파일 byte와 Result digest 일치 |
| 권한·취소 | 이전 Attempt의 claim·commit 거절 |
| 실시간 입력 | 고정한 입력, sampleId, 선택 표본 건수 일치 |
| 정상 종료 | 마지막 DATA·END, FINALIZE, Result 확인 |
| 이력 갱신 | 선택·과거 페이지 유지, 조회 오류 후 복구 |
| 메트릭 | 0·미수집·만료·장착 정보 구분 |

2026-10-14 센서 39건·VD 85건의 전달과 정상 종료, 결과 파일을 대조했다. 10월 16일에는 NODE 계산 결과 24와 센서 60건·VD 8건의 연계 종료를 확인했다. 10월 20일 Vue 타입·native API 시험 27개·문서 검사와 수정 후 리뷰가 통과했다. [1]

실제 조회한 작업에는 전환 이력이 없어 최신 전환 화면의 상태 변화는 HTTP fixture로 시험했다. 이 결과와 실제 노드 이동·모델 실행 시험은 구분한다.

## 인수 절차와 검증 기록

### 사전 조건

실제 실행 시험은 격리 namespace·DB·broker·결과 bucket을 준비한다. 실행 flag, Runner·SERVICE 이미지 digest·아키텍처, 전용 namespace 권한, Pod token audience, 내부 TLS·신뢰 인증서와 결과 bucket versioning을 확인한다. 예제의 0 digest는 유효한 실행 이미지가 아니다. 실시간 입력 시험에는 유효 센서 또는 RUNNING VD와 최신 결과가 추가로 필요하다.

관리·워크플로 API를 나눌 때는 같은 control DB 사용과 별개로 원본 collector가 중복 실행되지 않게 한다. 두 API의 로그인·CSRF와 실제 목적지, 콘솔 포트도 함께 확인한다. 개발 포트가 열린 것만으로 Kubernetes 실행 권한이나 결과 저장소의 구성이 완료된 것은 아니다.

### 시험별 판정 기준

| 시험 ID | 입력·조치 | 기대 결과·확인 자료 |
|---|---|---|
| AT02-01 DAG | 정상 DAG와 순환·중복 producer·큰 정수 입력 | 잘못된 정의 거절, 큰 정수·버전 digest 보존 |
| AT02-02 재요청 | 같은 키·같은 내용 반복, 다음으로 내용만 변경 | 동일 Run ID, 다른 내용 409, 중복 Attempt 없음 |
| AT02-03 정상 계산 | 알려진 입력·가중치·bias로 NODE 실행 | Task 성공·Result 존재·파일의 계산값 일치 |
| AT02-04 잘못된 결과 | 입력 손상·출력 누락/링크·이전 Attempt commit | 정상 Result로 확정하지 않음, 오류·자원 정리 확인 |
| AT02-05 전환 | RESTART 지원 작업의 실제 target 변경·응답 유실 | 이전 권한 차단·실제 종료·새 Attempt·Operation 확인 |
| AT02-06 입력 고정 | SENSOR/VD 선택, 실행 중 UID·설정·generation 변경 | 선택 형식 검사, 원본 변경 차단 |
| AT02-07 정상 종료 | 최신 표본 전달 후 drain | source/처리 건수·마지막 값·END·FINALIZE·Result 대조 |
| AT02-08 메트릭 | 정상 0·누락·만료·공유 슬롯·미수집 NPU | 물리 대수와 수집 상태를 올바르게 표시 |
| AT02-09 API 경계 | 두 세션·CSRF·큰 정수·멱등 키·금지 경로 | 목적지·본문·헤더 보존, 금지 요청 차단 |
| AT02-10 화면 | 파일 교체 취소·오류, 선택 중 지연 응답·과거 페이지 | 기존 초안·선택·좌표·확대 유지, 이전 응답 격리 |

AT02-05·07은 실제 실행 환경의 인수다. 화면 fixture의 성공으로 실제 전환·센서 전달 인수를 완료했다고 적지 않는다. 각 결과에 소스·이미지·Run/Task/Attempt ID, 대상 노드, 요청과 응답, 기대값·실제값, Result·파일 digest와 수행 시각을 남긴다. 서명 URL과 token은 증거에서 제외한다.

### 이번 문서 개정의 재검증

| 대상 | 실행한 시험 | 결과·범위 |
|---|---|---|
| DAG·입력·자동 전환 정책 | WorkflowDagTest 3개·WorkflowSourceInputTest 1개·OffloadPolicyTest 4개 | 총 8개 통과, 구조·자료형·정책 단위 검사 |
| Runner | `test_runner.py` 11개 | 실제 자식 프로세스·로컬 HTTP fixture, 입력/출력·timeout·취소·재전송 검사 |
| native API | 기존 콘솔·등록·현황 시험 27개 | 로컬 upstream으로 세션·CSRF·숫자·메트릭 해석 검사 |

위 시험은 2026-10-20 개정 중 다시 실행했다. 기존 suite의 연동 경계 검사를 포함하며 DDS 자체의 개발 성과를 의미하지 않는다. 실제 Kubernetes·센서·전환을 새로 실행한 결과는 아니다. 단위·fixture와 실장비를 구분해야 한다.

과거 실제 실행의 대표 기대값은 `[2,3]·[4,5]+1=24`다. 2026-10-16 기록에서 결과 24를 확인했다. 10월 14일 센서 39건·VD 85건과 고정 object version 4개의 byte 검증, 16일 센서 60건·VD 8건 전달은 [PROGRESS](../../PROGRESS.md)의 당시 기록을 인용한다. 이번에는 그 원시 로그와 파일을 새로 내려받아 다시 집계하지 않았다.

### 배포·장애 후 확인

실행과 전환 중에는 새 작업 시작, 이전 producer 차단, 실제 자원 종료, 최종 Result를 순서대로 확인한다. API 재시작 후에는 영속 Run·Attempt·명령·Operation이 외부 Job UID와 맞는지 확인한다. UI 표시만 보고 작업을 새로 만들어 중복 실행하지 않는다.

되돌리기 전에는 신규 실행을 멈추고 활성 Run의 drain 또는 취소·실제 종료를 확인한다. DB migration은 적용된 파일을 고치거나 이전 바이너리에 맞춰 역수정하지 않는다. 배포 버전과 schema의 호환성을 확인하고, 결과 bucket의 고정 파일 버전을 보존한다. 전체 백업·복구는 [운영 복구 절차](../operations/backup-and-recovery.md)에 따라 별도로 검증한다.

인계 자료에는 실행 스키마·OpenAPI, 소스·이미지·설정 식별자, 정상·오류·종료 절차, 실제 시험 결과와 미검증 범위를 포함한다. 사용자별 권한, 모든 상태형 모델 복원, 실 GPU·NPU 모델, 장기 부하와 전체 복구는 다음 절의 잔여 작업으로 유지한다.

## 배포 확인과 남은 작업

배포할 때는 EdgeAI·Kestra 소스 버전, lockfile, 실제 이미지 digest·아키텍처, TLS, 결과 저장소 versioning을 확인한다. 적용한 migration은 수정하지 않는다. 소스와 실행 규격, 설정·오류 대응·시험 결과를 함께 관리한다.

모든 측정값의 STREAM 전달, 임의 상태형 모델의 checkpoint 복원, 실제 외부 Remote·GPU·NPU 모델 실행, 사용자별 인증·권한, 장기 성능·전체 장애 복구는 남아 있다. 전체 과거 UI의 기능 비교, 기본 포트 통합, 자동 시작도 별도 작업이다. [6]

## 참고 자료

1. [구현·시험 기록](../../PROGRESS.md)
2. [개발 환경과 native 콘솔](../guides/local-development.md)
3. [DAG·실시간 입력 사용법](../guides/dag-execution.md)
4. [API 참고](../reference/api.md), [노드·가속기 메트릭](../guides/node-metrics.md)
5. [Runner 구현과 시험](../../runner/README.md)
6. [남은 개발·검증 항목](../../PLAN.md)
