# 센서 수집과 가상디바이스 처리 소프트웨어 기술자료

**TDP-01** · 2026-10-20

과제명: (기입) · 작성자: (기입) · 검토자: (기입)
기준 소스: `1170c1e88667ea22bba2b21844456c18821e0213`

## 구성

센서 연동, 원본 저장, eKuiper 연속 계산의 소스와 인터페이스, 설치·운영·시험 절차를 정리한다. 기술 설명은 [TM-01](TM-01_이기종_센서_연동.md), [TM-02](TM-02_가상디바이스_연속_처리.md), [TM-03](TM-03_센서_데이터_저장_안정화.md)에 있다.

| 기능 | 구성 |
|---|---|
| 센서 관리 | KubeEdge 측정 항목, 공유 Mapper, 프로토콜 설정, 연결 시험 |
| 원본 수집 | 노드 MQTT 데이터 수신, 식별 정보 유지, InfluxDB 저장 |
| 연속 계산 | 입력 연결, 계산식 검사, Agent·eKuiper 실행 관리 |
| 관리 화면 | 센서·VD 등록, 상태·원본·결과 이력 조회 |

### 소스 위치

| 경로 | 내용 | 확인할 사항 |
|---|---|---|
| `mappers/internal/driver` | 프로토콜 드라이버와 데이터 변환 | 실제 장치의 값·매핑 |
| `mappers/internal/dmi` | DMI 통신과 보고 주기 관리 | EdgeCore·노드 설정 |
| `mappers/internal/runtime` | 설정·세션·수집 실행 | 저장된 카운터·source generation |
| `mappers/cmd` | Mapper·Processing Agent 실행 진입점 | 빌드 대상·옵션 |
| `deploy/kubeedge` | RBAC·장치 예제·배포 설정 | 실제 주소·장치 정보 |
| `backend/app` | 센서·VD 관리와 저장 전달 | 설정·활성화 조건 |
| `backend/adapters` | KubeEdge·InfluxDB·PostgreSQL 연동 | 연결·권한 |
| `dashboard/kestra-native` | 센서·VD 관리 화면 | BFF·API 연결 |

공통 모델과 migration을 함께 사용하므로 기준 저장소 전체를 준비한다. KubeEdge, NanoMQ, eKuiper, PostgreSQL, InfluxDB는 기존 소프트웨어를 사용하며 원본 라이선스와 고지를 유지한다.

## 요구사항과 구성요소의 연결

인수 단위는 센서 등록부터 원본 조회까지의 수집 경로와, 등록된 입력에서 VD 최신 결과·저장 이력까지의 계산 경로다. DMI/Twin의 최신 상태와 MQTT·InfluxDB의 관측 이력을 따로 확인한다. VD 출력을 SERVICE에 연결하는 단계는 [TDP-02](TDP-02_워크플로_실행과_통합_운영.md)에서 다룬다.

| 요구사항 | 설계·구현 담당 | 연결 시험 |
|---|---|---|
| R01-01·02 프로토콜 정규화·항목 격리 | DeviceProfileCompiler·ManagedDeviceService·Mapper | AT01-01·02 |
| R01-03 동시 변경 보존 | resourceVersion을 사용한 CRD 갱신 | AT01-02 |
| R02-01·03 제한 수식·윈도우 격리 | ContinuousInput·ContinuousRuleCompiler·Agent | AT01-05 |
| R02-02 현재 실행 결과만 반영 | ProcessingEngineService·ContinuousRepository | AT01-06 |
| R02-04 계산과 이력 저장 분리 | ResultStoragePolicy·ProcessingHistoryDelivery | AT01-07 |
| R03-01·02 저장 후 ACK·중복 처리 | SensorArchiveCollector·MqttObservationSubscriber | AT01-03·04 |
| R03-03·05 원본 식별·조회 조건 | InfluxTelemetryRepository | AT01-04 |

R01~R03은 관련 TM의 요구사항 식별자다. AT01은 이 자료의 인수 시험 식별자이며 기관의 공식 등록번호를 뜻하지 않는다.

### 상태와 보존 대상

| 대상 | 변경·실행 상태 | 보존할 정보 |
|---|---|---|
| 센서 | 이름·UID·설정·resourceVersion | 실제 연결과 항목 정의, 이력에서 참조하는 UID |
| 수집 세션 | source generation·session·sequence | 세션 카운터와 원본 신원 |
| VD 정의 | revision·ProfileVersion | 당시 입력·계산·저장 정책과 정의 이력 |
| VD 실행 | STOPPED·STARTING·RUNNING·WAITING_INPUT·STOPPING·ERROR | generation·규칙 소유권·gap 정보 |
| 원본·결과 이력 | 관측 시각·저장 상태·보관 기간 | InfluxDB 데이터·PostgreSQL 저장 대기열 |

등록 해제와 이력 삭제는 같은 작업이 아니다. VD 등록 해제 시 물리 센서와 공유 Mapper, 저장된 과거 이력을 함께 지우지 않는다. 보관 기한이 지난 이력은 등록 정보만으로 복구할 수 없다.

## 실행 환경과 설정

관리 API는 JDK 21과 PostgreSQL, 기존 BFF는 Node 22·pnpm 10.34.6을 사용한다. Kestra native UI는 Node 24·npm 11로 별도 실행한다. Mapper는 `mappers/go.mod`의 Go 버전과 대상 노드 아키텍처를 따른다. [2][3]

기존 시험에 사용한 버전은 eKuiper 2.4.2 full, InfluxDB OSS 2.9.1이며, 센서 노드의 NanoMQ는 0.25.6이었다.

| 설정 | 용도 |
|---|---|
| `EDGEAI_RAW_HISTORY_ENABLED` | 원본 MQTT 수집·저장 활성화 |
| `EDGEAI_INFLUX_URL` | API에서 접근할 InfluxDB 주소 |
| `EDGEAI_INFLUX_ORG` | InfluxDB 조직 |
| `EDGEAI_INFLUX_TOKEN_FILE` | bucket 읽기·쓰기 토큰 파일 |
| `EDGEAI_CONTINUOUS_ENABLED` | 연속 계산 활성화, 기본 false |
| `EDGEAI_RAW_HISTORY_BROKER_NODE_OVERRIDES` | 필요한 노드의 broker 주소 보정 |

토큰은 서버에서 파일로 읽는다. 브라우저나 Git에 전달하지 않는다. 원본 bucket의 기본 보관 기간은 30일이며 결과 bucket은 1~30일이다. 저장 용량은 데이터 크기·주기·보관 기간을 기준으로 계산한다.

## 설치와 실행

### 기본 서비스

기준 소스를 준비하고 `.env.example`과 설정 가이드를 확인한다. 새 개발 환경에서는 다음 명령을 사용한다. [3]

```bash
make setup
make check
make up
make data-store-start
```

`make up`의 개발 MQTT는 실제 노드 NanoMQ와 별개다. Docker 없이 `make db-start`를 사용할 수도 있지만 이 명령은 PostgreSQL만 준비한다. 로컬 InfluxDB는 기본 18086을 사용하며 생성한 연결 정보와 토큰 파일을 API 설정에 연결한다.

관리 API와 BFF는 각각 별도 터미널에서 `make backend`, `make dashboard`로 실행한다. native UI는 `bash scripts/dev/dev-kestra-native.sh`로 실행한다.

### 센서와 연속 계산

1. 대상 노드의 Ready 상태, EdgeCore DMI, 장치 경로를 확인한다.
2. 아키텍처에 맞는 Mapper 이미지와 RBAC, 노드·프로토콜 설정을 준비한다.
3. 보드 출력에 맞는 Property 규격을 저장하고 측정 항목을 등록한다.
4. 연결 시험과 실제 MQTT 데이터의 시각·값을 확인한다.
5. Mapper의 `/capabilities`, 검증된 데이터 형식, 세션 순서를 확인한다.
6. Agent·eKuiper·저장소·권한을 준비한 뒤 연속 계산을 켠다.

Kubernetes 배포는 `deploy/kubernetes/ekuiper`와 기존 운영 가이드를 따른다. 실제 InfluxDB PVC·전용 토큰·네트워크는 따로 준비해야 한다. eKuiper 설치 후에는 규칙이 생성되고 결과가 나오는지 확인한다. [2]

## 인터페이스와 데이터

### 주요 경로

| 구분 | 경로 | 기능 |
|---|---|---|
| 웹 센서 관리 | `/api/devices` | 측정 항목 관리 |
| 웹 규격 관리 | `/api/device-profiles` | 저장된 항목 규격 |
| 서버 원본 목록 | `/api/v1/data-store` | 저장된 센서 목록 |
| 서버 원본 이력 | `/api/v1/data-store/sensors/{uid}/{property}/history` | 기간·cursor로 원본 조회 |
| 서버 VD 관리 | `/api/v1/virtual-devices` | 연속 VD 목록·상세 |
| 서버 VD 등록 | `/api/v1/virtual-devices/continuous` | 입력·계산·저장 정책 등록 |
| 서버 VD 출력 | `/api/v1/virtual-devices/{id}/outputs` | 현재 결과·저장 상태 |

웹 프록시와 서버 API의 경로는 다르다. HTTP 메서드·요청 형식은 해당 컨트롤러와 OpenAPI를 따른다. KubeEdge 센서 관리와 일반 UUID Device API를 구분하고, 쓰기 요청의 CSRF·revision·멱등 키 조건을 유지한다. [5]

### 데이터 식별과 저장 상태

원본 데이터는 UID, property, 수집 설정, generation, session, sequence, `observedAt`을 유지한다. 같은 시각이라도 sequence가 다르면 별도로 저장한다. 구형 형식은 ‘설정 미검증’으로 표시한다.

VD 결과는 VD·output·revision·실행 generation으로 구분한다. NONE·SELECTED·ALL은 계산과 별개인 저장 정책이다. 결과 상태는 저장 제외 `SKIPPED`, 대기 `PENDING`, 완료 `STORED`, 대기 중 만료 `EXPIRED`로 표시한다.

유효 관측의 QoS 1 ACK는 저장이 끝난 뒤 보낸다. retained·경로 외·형식 오류 메시지는 저장 대상에서 제외하면서 ACK할 수 있다. 일반 STREAM의 처리 ACK와는 다르다. InfluxDB를 직접 조회할 때도 `archive_visible`과 빈 문서 제외 조건을 적용해야 한다.

## 센서 등록 API 상세

아래는 관리 서버 기준 경로다. `{id}`는 KubeEdge 등록 이름이며 일반 실행 Device의 UUID와 다르다. native 프록시의 `managed-devices` 경로와도 구분한다. 본문은 JSON이고 쓰기는 관리 서버의 세션·CSRF 조건을 충족해야 한다.

| 메서드·경로 | 본문·성공 응답 | 동작 |
|---|---|---|
| POST `/api/devices` | 등록 본문 → 201·등록 객체 | 측정 항목 하나를 가진 센서 생성 |
| GET `/api/devices/{id}` | 본문 없음 → 200·등록 객체 | 실제 CRD·관측 상태 조회 |
| POST `/api/devices/{id}/properties` | 측정 항목 객체 → 201·등록 객체 | 같은 연결의 별도 측정 Device 생성 |
| PUT `/api/devices/{id}/properties/{property}` | 측정 항목 객체 → 200·등록 객체 | 지정 항목만 수정 |
| DELETE `/api/devices/{id}/properties/{property}` | 본문 없음 → 204 | 참조·UID·resourceVersion 조건으로 해제 |
| POST `/api/device-connections/test` | 연결 시험 본문 → 200·시험 결과 | 실제 프로토콜 연결 확인 |

| 등록 필드 | 형식·필수 조건 | 기본값·제약 |
|---|---|---|
| `name` | 문자열, 필수 | DNS 형식·63자 이내·이름 중복 금지 |
| `targetNode` | 문자열, 필수 | 대상 노드 이름과 아키텍처 확인 |
| `protocol` | 문자열, 필수 | 지원 프로토콜 식별자 |
| `host`, `port` | 접속 정보, 프로토콜별 조건부 | port 1~65,535, 프로토콜별 기본값 |
| `protocolConfig` | 객체, 프로토콜별 조건부 | timeout·Unit ID·장치 경로 등 |
| `collectionInterval` | 정수 밀리초, 선택 | 기본 1,000, 범위 100~60,000 |
| `properties` | 배열, 필수 | 현재 신규 등록은 항목 정확히 하나 |

property에는 `name`, `dataType`과 프로토콜별 값 위치를 지정한다. `unit` 기본값은 빈 문자열, `scale`은 1, `offset`은 0이다. Modbus register, OPC-UA NodeId, JSON 값 경로 등 위치 필드는 프로토콜에 따라 다르다. 항목 수정 API에는 단일 property 객체를 보내며 host 등 연결 필드를 섞지 않는다. 전체 필드·허용값은 [DeviceProfileCompiler](../../backend/app/src/main/java/io/edgeai/app/support/DeviceProfileCompiler.java), 상태와 변경 순서는 [ManagedDeviceService](../../backend/app/src/main/java/io/edgeai/app/service/ManagedDeviceService.java)를 기준으로 한다.

다음은 Modbus 등록 요청 예시다. 주소는 문서용이며 실제 장비에 전송하지 않았다. 노드·장비 주소와 register·단위는 실제 값으로 바꾼다.

```json
{
  "name": "temp-sensor",
  "targetNode": "edge-a",
  "protocol": "modbus-tcp",
  "host": "192.0.2.31",
  "properties": [
    {"name": "temperature", "dataType": "float32", "register": 40001, "unit": "C"}
  ]
}
```

기본 port 502, Unit ID 1, timeout 1,000ms, holding 함수, 바이트 순서 ABCD로 정규화된다. register 40001은 해당 함수의 wire 주소 0이다. 장비의 자료형·register 개수·바이트 순서를 대조해야 한다.

등록 객체는 `id`, `uid`, `profile`, `model`, `targetNode`, `state`, `observedState`, `stateReason`, `lastSeen`, `properties`, `mapper`, `resourceVersion`, `configurationUpdatedAt`을 제공한다. 검증된 설정 경로에는 `configurationSha256`도 포함된다. `mapper.name`과 `mapper.ready`를 관측 상태와 함께 해석한다. 생성 201이어도 실제 값이 들어오기 전에는 ONLINE으로 단정하지 않는다.

| 상태·오류 코드 | 발생 조건 | 호출자의 처리 |
|---|---|---|
| 400 `INVALID_DEVICE_PROFILE` | 필드·자료형·설정 오류 | 본문 수정 |
| 404 `DEVICE_PROPERTY_NOT_FOUND` | 항목 없음 | 최신 설정 조회 |
| 409 `DEVICE_ALREADY_EXISTS` | 이름 중복 | 기존 등록과 비교 |
| 409 `DEVICE_PROPERTY_ALREADY_EXISTS` | 항목 추가 충돌 | 기존 항목 확인 |
| 413 `PAYLOAD_TOO_LARGE` | 본문 크기 초과 | 규격과 크기 확인 |
| 503 `DEVICE_CONTROL_UNAVAILABLE` | 제어 계층 사용 불가 | 연결·권한 복구 후 재시도 |

오류 본문은 `code`, `message`다. 동시 수정 충돌은 최신 설정을 다시 조회해 변경 범위를 확인하고 강제로 덮어쓰지 않는다. [관리 예외 처리](../../backend/app/src/main/java/io/edgeai/app/exception/ManagedDeviceExceptionHandler.java)와 [컨트롤러](../../backend/app/src/main/java/io/edgeai/app/controller/ManagedDeviceController.java)에 응답이 정의되어 있다.

## 연속 VD API 상세

### 등록·수정·실행 명령

| 메서드·경로 | 필수 입력 | 성공·재요청 처리 |
|---|---|---|
| POST `/api/v1/virtual-devices/continuous` | `key`, `displayName`, `sources`, `continuous` | 신규 201, 같은 key·digest 200 |
| PATCH `/api/v1/virtual-devices/{id}/continuous` | `revision`, `displayName`, `sources`, `continuous` | 현재 revision으로 수정 |
| POST `/api/v1/virtual-devices/{id}/provision` | UUID `Idempotency-Key`, `revision` 본문 | 신규 Operation 202·재요청 200 |
| POST `/api/v1/virtual-devices/{id}/drain` | UUID `Idempotency-Key`, `revision` 본문 | 중지 Operation, 완료는 후속 조회 |
| GET `/api/v1/operations/{id}` | Operation UUID | 명령 진행·완료·실패 |
| GET `/api/v1/virtual-devices/{id}/processing-versions` | `limit`, `offset` 선택 | 과거 정의·SQL 템플릿 |

등록은 key와 등록 내용 digest로 중복을 검사한다. provision·drain의 명령 멱등 키와는 다른 계약이다. `sources`의 각 항목은 `sourceKey`, `deviceId`, `properties`로 alias와 센서를 연결하고, `maxAgeSeconds`는 기본 30초·범위 1~300초다. 연속 등록은 alias마다 property 정확히 하나를 선택한다.

`continuous`에는 `intervalSeconds`, `retentionDays`, `outputs`가 필수이며 `resultStorage` 생략 시 ALL이다. 아래는 등록된 온도 센서의 최근 3개 frame 이동 평균을 1초마다 계산해 7일 보관하는 요청 예시다. 실제 등록 결과나 실제 측정 결과는 아니다.

```json
{
  "key": "temperature-mean",
  "displayName": "온도 이동 평균",
  "sources": [
    {"sourceKey": "temperature", "deviceId": "temp-sensor", "properties": ["temperature"], "maxAgeSeconds": 30}
  ],
  "continuous": {
    "intervalSeconds": 1,
    "retentionDays": 7,
    "outputs": [
      {"name": "mean_temperature", "expression": "temperature", "operation": "movingAverage", "sampleCount": 3, "unit": "C"}
    ],
    "resultStorage": {"mode": "ALL"}
  }
}
```

입력·출력은 각각 1~16개, 주기는 1~3,600초, 보관은 1~30일이다. 이동 평균·최솟값·최댓값은 `sampleCount` 1~1,024가 필수다. threshold와 condition은 boolean 식이어야 하며 boolean을 수치 윈도우에 넣지 못한다. 공개 API는 임의 SQL이나 sink를 받지 않는다.

SELECTED 정책은 정의된 출력의 이름과 계산 결과 `value`에 조건을 지정한다. 아래는 `resultStorage` 값만 발췌한 예시다. 계산식 filter와 달리 이력 저장 여부만 바꾼다.

```json
{
  "mode": "SELECTED",
  "outputs": [{"name": "mean_temperature", "condition": "value >= 30"}]
}
```

명령 본문은 `{"revision":1}`처럼 현재 조회한 revision을 넣는다. 등록 응답은 `id`, `key`, `displayName`, `profileVersionId`, `serviceProfileVersionId`, `state`, `revision`, `placement`, `createdAt`, `updatedAt`, `executionMode`를 제공한다. 시작 접수 뒤에도 실행과 출력을 별도로 조회해야 한다.

### 최신값·이력·오류

출력 응답은 `vdId`, `executionMode`, `asOf`, `intervalSeconds`, `retentionDays`, `historyEnabled`, `resultStorage`, `items`를 제공한다. 출력별 `quality`는 NOT_AVAILABLE·VALID·STALE이다. `latest`가 있어도 현재 엔진 session, generation·실행 상태와 결과 나이 검사를 통과하지 못하면 STALE이다. `asOf`는 조회 시각, `evaluationAt`은 계산 시각이다.

결과에는 VD·outputId·generation·sequence·evaluationId·evaluationAt·value·type·unit과 입력 proof가 포함된다. 최신 유효성과 `historyStored`·저장 상태를 함께 확인한다. 출력 이력은 `/api/v1/virtual-devices/{id}/outputs/{outputId}/history`에서 읽는다. 기본 최근 1시간, limit 기본 1,000·최대 2,000, 최대 기간 31일이다. 계산 공백은 `gaps`로 제공한다. `bucketSeconds`를 사용한 표시는 원본 행 조회와 목적이 다르므로 같은 조건으로 비교한다.

비활성 등록은 503 `CONTINUOUS_DISABLED`, 잘못된 입력은 400 `INVALID_CONTINUOUS_INPUT`, key·revision 충돌은 409 `VD_CONFLICT`다. 실행 수·저장 예산 초과는 `CONTINUOUS_CAPACITY_EXCEEDED`, `CONTINUOUS_STORAGE_CAPACITY_EXCEEDED`로 구분한다. 전체 스키마는 [공개 OpenAPI](../../contracts/openapi/platform-api.yaml), 현재 검증은 [연속 컨트롤러](../../backend/app/src/main/java/io/edgeai/app/controller/ContinuousDeviceController.java)·[출력 서비스](../../backend/app/src/main/java/io/edgeai/app/service/ContinuousOutputService.java)·[저장 정책](../../backend/app/src/main/java/io/edgeai/app/support/ResultStoragePolicy.java)을 따른다.

## 원본 조회 API와 데이터 식별

`GET /api/v1/data-store`는 엔진·설정 여부·보관 기간·조회 시각, `collector` 상태와 센서 목록을 반환한다. `storedDeliveries`는 중복을 제거한 신규 원본 행 수와 다를 수 있으므로 `rejectedDeliveries`, 최신 관측 시각과 이력 증가를 함께 본다. 미설정 DISABLED, 성공한 빈 목록과 저장소 장애를 구분한다.

| 원본 이력 인자 | 형식·기본값 | 제약 |
|---|---|---|
| `uid`, `property` | 경로의 UUID·측정 이름 | 등록 이름 대신 원본의 UID 사용 |
| `from`, `to` | ISO 8601, 생략 시 최근 1시간 | 양의 기간·최대 31일 |
| `limit` | 기본 1,000 | 1~2,000 |
| `order` | 기본 asc | asc 또는 desc |
| `cursor` | 이전 응답의 다음 위치 | 최대 256자, 같은 조건으로 사용 |

응답은 `items`, `truncated`, 필요할 때의 `nextCursor`, `deviceUid`, `property`, `from`, `to`, `retentionDays`를 포함한다. 원본은 `observedAt`, `receivedAt`, `value`, `rawPayload`, `quality`, `timeBasis`와 형식별 식별자를 유지한다. qualified는 generation·설정 digest·session·sequence로 구분한다. 다른 UID·기간의 cursor를 섞지 않는다.

`/chart`는 개수에 따라 ORIGINALS 또는 SELECTED_ORIGINALS로 표시한다. 후자는 FIRST_MIN_MAX_LAST로 실제 관측을 선택하고 `selection`에 방법과 구간을 제공한다. 평균·보간 측정값을 만들지 않으며 차트 점 수를 원본 전체 수로 사용하지 않는다. 조회 오류는 400 `INVALID_CONTINUOUS_INPUT`, 저장소 장애는 503 `HISTORY_STORE_UNAVAILABLE`다. [원본 조회 서비스](../../backend/app/src/main/java/io/edgeai/app/service/DataStoreService.java)와 [Influx 어댑터](../../backend/adapters/src/main/java/io/edgeai/adapters/repository/InfluxTelemetryRepository.java)가 상세 기준이다.

## 사용과 장애 대응

센서는 프로토콜·대상 노드·연결·측정 항목을 지정해 등록한다. 같은 연결의 항목 추가와 단일 항목 수정을 지원한다. 수정 후 다른 항목과 연결 설정이 유지되는지 확인한다.

VD는 입력, 계산식, 출력, 저장 정책을 선택한다. 조합 10종은 같은 단위의 서로 다른 숫자 센서를 사용한다. 시작 후 현재 generation의 유효 결과와 저장 상태를 확인한다. 설정 변경은 새 revision으로 남기며 등록 해제 시 원본 센서와 기존 이력은 보존한다. [2]

| 현상 | 점검할 내용 |
|---|---|
| 등록 후 OFFLINE | 노드·Mapper·장치 경로·DMI 상태 |
| 값이 바뀌지 않음 | 측정 시각·heartbeat·MQTT 실제 수신 |
| 원본 저장 정체 | collector 상태·저장 건수 증가·대기량·Influx 쓰기 |
| VD 결과 없음 | 입력 유효 시간·Agent 규칙·lease |
| 결과 저장 대기 | PENDING·PostgreSQL 대기열·보관 기한 |

InfluxDB DELETE·TSI 대기로 수집이 멈추면 기존 데이터와 PVC를 보존하는 복구 절차를 따른다. 수집 경로는 중복 데이터를 조회에서 제외하는 방식을 사용하며 동기 DELETE를 다시 넣지 않는다. [3]

중지·되돌리기는 연속 계산 flag를 false로 바꾸고 lease 만료 후 소유 규칙이 정리됐는지 확인한다. 다른 사용자 규칙, 센서 CRD, VD 정의, 이력은 유지한다. 원본 수집은 연속 계산과 독립적으로 켜고 끈다.

## 시험 절차와 기존 결과

격리 환경에서 `bash scripts/test/test-processing.sh`와 `make test-data-store`를 사용한다. 실제 broker·eKuiper full·InfluxDB가 필요하다. [2]

| 시험 | 입력·조치 | 확인할 결과 |
|---|---|---|
| 센서 연결 | 실제 항목 등록 | 연결 시험과 실제 값 일치 |
| 항목 수정 | 한 항목의 설정 변경 | 다른 항목·연결 유지 |
| 중복 수신 | 같은 식별 정보로 재전송 | 조회에 같은 원본이 중복되지 않음 |
| 동시각 데이터 | 다른 sequence로 같은 시각에 전달 | 두 데이터의 식별 정보·값 보존 |
| 세션 변경 | session·generation 교체 | 이전 데이터가 새 결과를 덮지 않음 |
| 조합 계산 | 결과를 아는 입력 전달 | 수식·단위 일치 |
| 저장 정책 | NONE·SELECTED·ALL 적용 | 최신값과 이력에 정책별 동작 반영 |
| 저장소 장애 | 시험 저장소 중지·복구 | 저장 전 ACK 금지, 복구 후 재전달 |

기존 시험에서는 2026-10-14 실센서 발행·저장 3,055건을 3분 동안 대조했다. 10월 16일 복구 후에는 짧은 구간에서 COLLECTING, rejected 0, 약 0.65~1.02초 지연을 확인했다. 10월 19일 조합·이력 UI와 20일 native API 시험 27개도 통과했다. 세부 조건은 시험 기록을 참고한다. [1]

24시간 손실·중복 검증, 최대 동시 처리량, 전체 재시작·복구는 남아 있다. 모든 원본의 STREAM 전달, 중단된 윈도우 복원, 과거 데이터 전체 재생, 임의 보드·인증 방식도 별도 검증이 필요하다. [6]

## 인수 시험과 운영 이관

### 준비와 증거 보존

운영 센서와 다른 이름·UID·broker·bucket을 사용하는 격리 환경에서 시험한다. 기준 소스, 장치·Mapper·Agent·eKuiper 버전과 이미지 digest, 대상 아키텍처, 적용 migration, 계산 주기·보관 정책을 기록한다. 토큰 값은 기록하지 않고 파일의 역할·권한 확인 결과만 남긴다.

노드 Ready, DMI 지원, Mapper capability·qualified 발행, Agent·eKuiper full 연결, InfluxDB 읽기·쓰기와 PostgreSQL 대기열을 확인한 뒤 연속 처리를 켠다. 실제 관측이 없으면 등록 성공만으로 계산 인수를 진행하지 않는다. 상세 설정은 [설정 참고](../reference/configuration.md)와 [운영 가이드](../guides/vd-processing.md)를 따른다.

### 시험별 절차와 판정

| 시험 ID | 전제·입력·조치 | 기대 결과 |
|---|---|---|
| AT01-01 연결 | 알려진 장비 등록, 원시 출력과 API 값 대조 | 값·자료형·단위·시각 일치, DMI·Mapper 정상 |
| AT01-02 변경 | 공유 연결에서 항목 추가·지정 항목 수정·충돌 주입 | 별도 Device 생성, 다른 항목·연결 보존, 충돌 덮어쓰기 없음 |
| AT01-03 재전송·장애 | 유효 메시지 반복, 격리 저장소 쓰기 실패·복구 | 실패를 완료 캐시에 넣지 않음, 복구 후 관측 보존 |
| AT01-04 식별 | 같은 시각·값의 다른 sequence와 legacy를 순서별 발행 | 다른 sequence 별도 보존, 대응 legacy만 제외 |
| AT01-05 계산 | 알려진 숫자·윈도우, 잘못된 boolean 연산 지정 | 수식·단위·표본 수 일치, 잘못된 타입 거절 |
| AT01-06 세대 변경 | Mapper session·VD generation 교체 후 이전 데이터 전달 | 이전 세대 거절·윈도우 분리, 현재 실행만 VALID |
| AT01-07 저장 정책 | NONE·SELECTED·ALL, 저장소 장애 중 수신·복구 | 최신값과 이력 분리, 정책별 저장·대기·만료 |
| AT01-08 해제 | VD 중지·해제 뒤 센서·과거 이력 확인 | 소유 규칙 정리, 원본·정의·이력 보존 |

결과에는 수행 시각·환경, 실제 입력과 digest, 기대값·관측값, API 응답·로그 위치를 남긴다. 위 표는 인수 절차이며 수행 전 시험을 통과로 표시하지 않는다. 새 환경의 장애 주입은 미실행으로 두고 해당 환경에서 따로 수행한다.

| 확보한 근거 | 결과 | 범위 |
|---|---|---|
| 이번 개정 단위 시험 | collector 3개·rule compiler 2개 통과 | 중복·재시도·항목 잠금·규칙 생성·타입 검사 |
| 2026-10-14 과거 실센서 기록 | 3분 3,055건 발행·저장·내용 대조 | 당시 센서·설정·구간 |
| 2026-10-16 과거 복구 기록 | COLLECTING·rejected 0·약 0.65~1.02초 지연 | 짧은 복구 구간, p95·p99 아님 |
| 기존 통합 시험 정의 | legacy/qualified·동시각 sequence·장애·저장 정책 | 이번 개정에서 실제 InfluxDB 재실행 안 함 |

원본 24시간 대조, 장시간 저장 장애 뒤 누락·중복 판정, 최대 동시 부하와 전체 재시작은 남아 있다. 단위 시험 통과로 이 인수를 대체하지 않는다. [collector 시험](../../backend/app/src/test/java/io/edgeai/app/service/SensorArchiveCollectorTest.java), [컴파일러 시험](../../backend/app/src/test/java/io/edgeai/app/support/ContinuousRuleCompilerTest.java), [실제 Influx 통합 시나리오](../../backend/app/src/test/java/io/edgeai/app/integration/InfluxDataStoreIntegrationTest.java)에 재현 입력과 assertion이 있다.

### 중지와 복구

정상 중지는 정의 변경을 멈춘 뒤 해당 VD의 현재 revision으로 drain을 요청하고 Operation·실행 상태를 확인한다. 전체 중지는 기능 flag, lease 만료와 소유 규칙 정리를 함께 확인한다. 원본 수집은 연속 계산과 독립적으로 중지한다.

복구 전 DB·InfluxDB 볼륨을 보존한다. DELETE·TSI 대기에는 기존 우회 설정과 논리 비표시 경로가 유지되는지 확인한다. 재시작 뒤 새 관측 시각·저장 증가·rejected·결과 generation·대기열 전달을 확인한다. 중단된 윈도우는 자동 복원된 것으로 표시하지 않는다. 인계 시 소스·이미지, 연결·단위의 근거, 운영·복구 순서, 실제 결과와 미검증 항목을 함께 전달한다.

## 참고 자료

1. [구현·시험 기록](../../PROGRESS.md)
2. [연속 계산·저장 운영 가이드](../guides/vd-processing.md)
3. [개발 환경과 저장소 복구](../guides/local-development.md)
4. [센서 등록](../guides/sensor-registration.md), [Mapper 구조](../../deploy/kubeedge/README.md)
5. [API 참고](../reference/api.md)
6. [남은 개발·검증 항목](../../PLAN.md)
