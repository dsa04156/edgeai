# API 참고

프로필·UUID 앱 장치·VD·실행 관리 API는 `/api/v1`, KubeEdge Device Manager는 `/api/devices` 등 별도 경로를 사용합니다.
Swagger에서 요청·응답·필드 제한과 오류를 확인합니다.
정의 원본은 `contracts/openapi/platform-api.yaml`이며 이 페이지의 목록은 해당 계약에서 생성합니다.

## 어떤 API를 사용할까

| 계약 | 대상 |
|---|---|
| `platform-api.yaml` | 프로필, 장치, 인프라, 센서, VD, Spring DAG·실행, 감사 |
| `platform-service-api.json` | DDS 구성의 Buildx·Gitea·Argo CD 연결 |
| `runner-api.yaml` | 내부 Runner 프로토콜 |
| `vd-runtime-api.yaml` | 내부 VD supervisor 프로토콜 |
| `stream-api.yaml` | Device·Runner의 스트림·checkpoint 프로토콜 |
| `remote-reference-api.yaml` | 참조 Remote 제공자 계약 |

브라우저는 허용된 관리 프록시를 사용합니다. 내부 프로토콜은 해당 실행 주체용이며 화면 API로 취급하지 않습니다.

## 장치 API 선택

| 경로 | 관리하는 리소스 |
|---|---|
| `/api/devices`, `/api/devices/{id}` | CRD 이름으로 식별하는 Mapper 장치의 등록·조회·수정·삭제 |
| `/api/devices/{id}/properties` | 기존 연결에 측정 항목 하나 추가 (`POST`) |
| `/api/devices/{id}/properties/{property}` | 항목 하나 삭제 (`DELETE`); 마지막 항목이면 장치 등록도 삭제 |
| `/api/device-profiles` | 프로토콜별 저장 Property 규격 |
| `/api/device-connections/test`, `/api/protocols` | 대상 Mapper 연결 테스트, 지원 프로토콜 목록 |
| `/api/v1/devices`, `/api/v1/profiles` | UUID 앱 Device·Session, 버전형 DEVICE/SERVICE/VD 프로필 |
| `/api/v1/virtual-devices` | 원본·실행 이력을 유지하는 UUID VD |

기존 `/api/v1/sensors/*`는 410 `SENSOR_API_REPLACED`로 종료됐습니다. 아래 생성 목록에는
호환 응답을 위한 deprecated operation도 포함됩니다. 이전 이력·명령 기능이 유지된다는 뜻은 아닙니다.
새 장치 경로와 기존 UUID 앱 등록은 자동으로 병합되지 않습니다.

## 요청 규칙

- 현재 일반 관리 API는 계정 로그인을 요구하지 않습니다. 쓰기에는 CSRF 토큰과 같은 세션 쿠키가 필요합니다.
- 페이지 조회는 endpoint의 `limit`·`offset` 계약을 따릅니다. 변경 중 목록은 고정 스냅샷이 아닙니다.
- 프로필·워크플로 발행 버전은 불변입니다. 같은 버전의 다른 내용은 충돌합니다.
- Run 재전송은 원래 `Idempotency-Key`를 유지합니다. revision 충돌은 최신 상태를 읽은 뒤 처리합니다.
- 202는 접수이며 실제 완료는 대상 Run·Task·Operation 또는 외부 서비스를 조회합니다.

## 주요 응답

| 상태 | 해석 |
|---|---|
| 200 / 201 / 204 | endpoint 계약에 따른 조회·저장·삭제 성공 |
| 202 | 비동기 요청 접수 |
| 400 | 입력·명령·규격 검사 실패 |
| 403 | CSRF 또는 조회 전용 모드 등으로 변경 거절 |
| 404 | 대상 없음 또는 비활성 워크플로 경로 |
| 409 | 버전·revision/resourceVersion 충돌, 중복 장치·항목 이름, 사용 중인 등록 등 |
| 410 | 종료된 센서 API; 새 Device Manager 경로로 전환 |
| 502 / 503 | 원본 서비스 실패, 기능 비활성, 의존 서비스 또는 저장소 사용 불가 |

정확한 오류 코드는 각 operation의 schema를 확인합니다. [Swagger 사용](../guides/swagger-ui.md)에 직접 호출 절차가 있습니다.

## 관리 operation 목록

<!-- BEGIN GENERATED OPERATIONS -->
계약에 정의된 operation은 **66개**입니다.

| 메서드 | 경로 | operationId |
|---|---|---|
| `GET` | `/api/devices` | `listManagedDevices` |
| `POST` | `/api/devices` | `createManagedDevice` |
| `GET` | `/api/devices/{id}` | `getManagedDevice` |
| `PUT` | `/api/devices/{id}` | `updateManagedDevice` |
| `DELETE` | `/api/devices/{id}` | `deleteManagedDevice` |
| `POST` | `/api/devices/{id}/properties` | `addManagedDeviceProperty` |
| `PUT` | `/api/devices/{id}/properties/{property}` | `updateManagedDeviceProperty` |
| `DELETE` | `/api/devices/{id}/properties/{property}` | `deleteManagedDeviceProperty` |
| `POST` | `/api/device-connections/test` | `testDeviceConnection` |
| `GET` | `/api/device-profiles` | `listManagedDeviceProfiles` |
| `POST` | `/api/device-profiles` | `createManagedDeviceProfile` |
| `GET` | `/api/protocols` | `listDeviceProtocols` |
| `GET` | `/api/v1/platform` | `getPlatform` |
| `GET` | `/actuator/health/readiness` | `getReadiness` |
| `GET` | `/api/v1/csrf` | `getCsrfToken` |
| `POST` | `/api/v1/profiles/{kind}` | `publishProfile` |
| `GET` | `/api/v1/profiles/{kind}` | `listProfiles` |
| `GET` | `/api/v1/profiles/{kind}/{key}/versions/{version}` | `getProfileVersion` |
| `DELETE` | `/api/v1/profiles/{kind}/{key}/versions/{version}` | `deleteProfileVersion` |
| `GET` | `/actuator/health/liveness` | `getLiveness` |
| `POST` | `/api/v1/devices` | `registerDevice` |
| `GET` | `/api/v1/devices` | `listDevices` |
| `GET` | `/api/v1/devices/{deviceId}` | `getDevice` |
| `PATCH` | `/api/v1/devices/{deviceId}` | `updateDevice` |
| `DELETE` | `/api/v1/devices/{deviceId}` | `releaseDevice` |
| `DELETE` | `/api/v1/devices/{deviceId}/registration` | `deleteDeviceRegistration` |
| `PUT` | `/api/v1/devices/{deviceId}/attachments/{nodeId}` | `attachDevice` |
| `POST` | `/api/v1/devices/{deviceId}/sessions` | `openDeviceSession` |
| `POST` | `/api/v1/devices/{deviceId}/observations` | `reportDeviceObservation` |
| `GET` | `/api/v1/infrastructure` | `getInfrastructure` |
| `GET` | `/api/v1/node-metrics` | `getNodeMetrics` |
| `GET` | `/api/v1/nodes` | `listNodes` |
| `GET` | `/api/v1/nodes/{nodeId}` | `getNode` |
| `POST` | `/api/v1/workflows` | `createWorkflow` |
| `GET` | `/api/v1/workflows` | `listWorkflows` |
| `GET` | `/api/v1/workflows/{workflowId}` | `getWorkflow` |
| `POST` | `/api/v1/workflows/{workflowId}/versions` | `publishWorkflowVersion` |
| `POST` | `/api/v1/workflow-runs` | `createWorkflowRun` |
| `GET` | `/api/v1/workflow-runs` | `listWorkflowRuns` |
| `GET` | `/api/v1/workflow-runs/{runId}` | `getWorkflowRun` |
| `GET` | `/api/v1/workflow-runs/{runId}/placements` | `getRunPlacements` |
| `GET` | `/api/v1/workflow-runs/{runId}/streams` | `listRunStreamRoutes` |
| `POST` | `/api/v1/workflow-runs/{runId}/cancel` | `cancelWorkflowRun` |
| `GET` | `/api/v1/tasks/{taskId}` | `getTask` |
| `POST` | `/api/v1/tasks/{taskId}/cancel` | `cancelTask` |
| `GET` | `/api/v1/tasks/{taskId}/results` | `getTaskResults` |
| `POST` | `/api/v1/tasks/{taskId}/offload` | `offloadTask` |
| `GET` | `/api/v1/operations/{operationId}` | `getOperation` |
| `POST` | `/api/v1/virtual-devices` | `createVirtualDevice` |
| `GET` | `/api/v1/virtual-devices` | `listVirtualDevices` |
| `GET` | `/api/v1/virtual-devices/{vdId}` | `getVirtualDevice` |
| `PATCH` | `/api/v1/virtual-devices/{vdId}` | `updateVirtualDevice` |
| `DELETE` | `/api/v1/virtual-devices/{vdId}` | `releaseVirtualDevice` |
| `POST` | `/api/v1/virtual-devices/{vdId}/provision` | `provisionVirtualDevice` |
| `POST` | `/api/v1/virtual-devices/{vdId}/replace` | `replaceVirtualDeviceRuntime` |
| `POST` | `/api/v1/virtual-devices/{vdId}/drain` | `drainVirtualDeviceRuntime` |
| `GET` | `/api/v1/virtual-devices/{vdId}/execution` | `getVirtualDeviceExecution` |
| `POST` | `/api/v1/devices/{deviceId}/sessions/{sessionId}/stream-token` | `issueDeviceStreamToken` |
| `GET` | `/api/v1/audit-requests` | `listManagementAuditRequests` |
| `GET` | `/api/v1/audit-requests/{auditId}` | `getManagementAuditRequest` |
| `GET` | `/api/v1/sensors/registration-options` | `getSensorRegistrationOptions` |
| `POST` | `/api/v1/sensors/registrations` | `registerSensor` |
| `GET` | `/api/v1/sensors/readings` | `getSensorReadings` |
| `GET` | `/api/v1/sensors/commands` | `getSensorCommands` |
| `POST` | `/api/v1/sensors/command` | `executeSensorCommand` |
| `DELETE` | `/api/v1/virtual-devices/{vdId}/registration` | `deleteVirtualDeviceRegistration` |
<!-- END GENERATED OPERATIONS -->

기능별 사용 조건은 [지원 범위](support.md)를 확인합니다.
