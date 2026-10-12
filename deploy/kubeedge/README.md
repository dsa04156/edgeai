# KubeEdge 장치 실행 계층

Device Manager의 기본 경로는 Profile → DeviceModel/Device CRD → DeviceController/DeviceTwin → DMI → Mapper다.
EdgeX 실행 리소스와 활성 어댑터는 2026-10-07 제거했다. 기존 Serial Arduino와 Sense HAT I²C 드라이버를 새 Mapper로 연결했다. 테스트 Device 6개와 테스트 서버는 제거했으며 현재 실제 보드 2개가 등록돼 있다.

**Modbus TCP vertical slice를 먼저 검증한 뒤 OPC-UA·MQTT 입력·Virtual 드라이버를 확장했다.** `/api/protocols`에서 Modbus TCP·OPC-UA·MQTT·Virtual·Serial JSON·Sense HAT을 지원한다. OPC-UA는 Anonymous / SecurityMode None, MQTT 입력은 인증 없는 TCP broker의 정확한 topic, Virtual은 HTTP JSON property endpoint를 제공하는 Container/Emulator/AI Service를 지원한다. Serial JSON은 `device_id`·`sensor`가 있는 줄별 JSON과 Arduino의 읽기 전용 `READ_NOW` 명령을 사용한다. Sense HAT은 ARM64 Raspberry Pi의 v1 보드와 `/dev/i2c-1`을 지원한다. 일반 I²C 장치를 자동 해석하지 않으며 인증·TLS 확장은 아직 없다. 모든 임의 프로토콜을 자동 해석하지 않는다.

## 등록

Dashboard의 인프라 관리 → 센서 → 센서 등록에서 연결과 property 규격을 입력한다. API도 같은 검증/컴파일을 사용한다.

```json
{
  "name": "motor-vibration-01",
  "kind": "physical",
  "protocol": "modbus-tcp",
  "host": "192.168.0.31",
  "port": 502,
  "protocolConfig": {"unitId": 1, "timeoutMs": 1000},
  "collectionInterval": 1000,
  "targetNode": "etri-dev0002-raspi5",
  "properties": [
    {"name": "temperature", "register": 40001, "dataType": "float32", "unit": "C"},
    {"name": "vibration_x", "register": 40003, "dataType": "float32"},
    {"name": "vibration_y", "register": 40005, "dataType": "float32"}
  ]
}
```

`POST /api/devices`는 먼저 CSRF 토큰(`/api/v1/csrf`)과 세션 쿠키가 필요하다. 연결 상태는 비동기 DMI 보고로 확인한다. 등록 성공은 장치 연결 성공을 뜻하지 않는다.

`GET/PUT/DELETE /api/devices/{id}`, `GET /api/devices`, `POST /api/device-connections/test`, `GET/POST /api/device-profiles`, `GET /api/protocols`를 제공한다. 여기서 `id`는 CRD 이름이다. 연결 테스트도 대상 Edge Node의 Mapper에서 실제 property를 읽는다.

기존 장치에 측정 항목을 추가할 때는 `POST /api/devices/{id}/properties`로 property 하나의 설정만 보낸다.
최신 등록 spec에 병합하고 이름 중복은409, 동시 변경은 resourceVersion 충돌로 보호한다.
`DELETE /api/devices/{id}/properties/{property}`는 해당 항목을 제거하며 마지막 항목이면 장치 등록도 삭제한다.
공유 Mapper와 저장 property 규격은 유지한다. 웹의 **항목 추가**는 동일 프로토콜의 저장 규격에서
미등록 항목을 선택하거나 직접 설정하며 기존 노드·연결·수집 간격을 사용한다.

등록 spec은 Device CRD의 `edgeai.io/device-profile` annotation에 보관한다. 재사용 property 규격은 `/api/device-profiles`를 통해 소유 namespace의 ConfigMap에 저장한다. 기존 앱의 버전형 SERVICE/VD Profile API와 UUID 장치 등록은 유지한다. 이 UUID 등록과 CRD 이름을 같은 ID라고 가정하지 않는다.

현재 실센서 규격은 `arduino-serial-json`, `sensehat-v1`으로 저장돼 있다. 새 설치에서는
`physical/*.json`의 `protocol`·`properties`로 저장 규격을 준비한다. 실제 보드의 출력과 매핑이
일치해야 하며 저장 규격을 선택했다는 사실만으로 연결이나 수신을 성공으로 판단하지 않는다.
[웹 등록 안내](../../docs/guides/sensor-registration.md)에서 항목 관리와 새 장치 연결을 구분한다.

## 모델과 Mapper

- DeviceModel 이름은 프로토콜 + property 이름/자료형/단위 인터페이스의 해시다. Host, Unit ID, register는 인스턴스 Device에 있어 다른 장치에서도 모델을 재사용한다.
- 노드/프로토콜당 `edgeai-{protocol}-<node-hash>` Deployment **하나**가 여러 장치를 처리한다. Modbus는 기존 이름 `edgeai-modbus-<node-hash>`를 유지한다. 장치 생성/수정/삭제는 공식 Mapper Framework의 DMI callback으로 전달한다. 30초 재등록으로 callback 누락을 보완한다. DMI socket 파일이 재생성되면 파일 mount가 예전 inode를 잡을 수 있다. 90초 이상 DMI 연결 수립 실패가 이어지면 liveness를 실패시켜 컨테이너 재시작과 새 socket bind를 유도한다. 일시적인 단절이나 서버의 rate-limit/config 오류는 재시작 대상에서 제외한다. 실제 inode 불일치와 Pod 재생성 복구, stale socket 회귀 시험을 확인했다; 같은 Pod의 liveness에 의한 자동 재시작 전체 경로는 아직 실측하지 않았다.
- Mapper는 Kubernetes 토큰을 갖지 않는다. 네트워크 Mapper는 EdgeCore DMI socket과 전용 callback socket 디렉터리만 mount한다. Serial/Sense HAT Mapper는 지정한 character device를 추가 mount하고, device plugin이 없는 현재 환경에서 privileged 실행이 필요하다. 호스트 루트 filesystem이나 Kubernetes API token은 mount하지 않는다. 관리 API가 지정한 노드와 아키텍처의 고정 digest 이미지를 배포한다.
- 원본 Framework/DMI 코드는 `mappers/third_party/README.md`의 upstream commit에 고정했다. 컨테이너는 이 저장소의 Go 드라이버와 함께 정적으로 빌드한다.
- `spec.stateReport`는 현재 설치된 CRD가 지원하지 않아 사용하지 않는다. DMI state 보고와 property의 `reportToCloud`/`reportCycle`을 사용한다.

## 데이터 경로

Device/DeviceStatus CRD가 설정과 관측의 기준이다. KubeEdge 1.23의 DeviceStatus에는 연결 상태/heartbeat/최신 twins가 있다. 앱 DB에 별도 장치 상태를 만들어 덮어쓰지 않는다.

수집은 100–60000ms이며, 최신 Twin 보고 후보는 5초 간격으로 만든다. 실제 DMI 보고 시각은 노드의 공통 요청 예산에 따라 늦어질 수 있다. 별도 MQTT topic `edgeai/devices/{id}/{property}`에는 매 수집마다 KubeEdge 표준 `DataModel` JSON(`DeviceName`, `PropertyName`, `Namespace`, `Value`, `Type`, `TimeStamp`)을 QoS 1로 발행한다. 현재 노드의 KubeEdge Mosquitto를 사용하므로 topic 경로를 control topic과 분리했다. 이 버전에는 MQTT 메시지를 영속 DB/Kafka에 저장하는 consumer가 포함되지 않는다. Broker 연결 장애 시 data plane 오류를 Mapper에서 기록하며, 재전송용 영속 outbox는 아직 없다.

장치 읽기 실패 또는 MQTT 입력 메시지 만료는 DMI로 `offline`을 보고한다. API는 CRD 상태와 함께 Mapper Deployment 준비 여부 및 30초 DMI heartbeat 만료를 확인해 유효 상태를 표시한다. 최신값은 장애 때 마지막 값을 보존하며 새 수집으로 표시하지 않는다.

KubeEdge 1.23 [DMI server 구현](https://github.com/kubeedge/kubeedge/blob/8e9e9fb155d9dee3fd8b6c00463b1b2be7b3e3b4/edge/pkg/devicetwin/dmiserver/server.go)은 노드 전체 RPC를 초당 1회로 제한한다. 각 프로토콜 Mapper는 공유 hostPath의 `dmi-budget` 파일 lock으로 요청을 1.1초 이상 간격으로 조절한다. DMI state는 한 RPC, Twin은 항목마다 별도 RPC로 전송한다. 현재 KubeEdge 1.23은 하나의 Twin batch를 여러 클라우드 갱신으로 분해하며 전체 배열 merge가 겹쳐 항목을 덮어쓸 수 있다. CloudCore의 `modules.deviceController.load.updateDeviceStatusWorkers`는 1로 조정했고, Mapper의 항목별 1.1초 RPC 간격도 적용했다. 각 장치의 비동기 보고 큐는 최신 상태만 보존하므로 DMI 대기가 센서 수집/MQTT 전송을 막지 않는다. 장치 수가 많으면 Twin 지연이 커진다; 기존 batch 방식의 테스트 증거는 한 노드 6개 장치이며, 현재 항목별 방식의 실센서 증거는 서로 다른 노드의 보드 2개·측정 항목 17개다. 다수 장치에서의 새 보고 방식 지연과 OFFLINE 전환 시간은 별도 부하 시험이 필요하다.

Workflow Run에서 아래처럼 사용한다.

```json
{"device":{"id":"motor-vibration-01","outputs":["vibration_x","vibration_y"]},"parameters":{}}
```

Run 접수 시 Device Manager가 CRD 최신값을 조회해 `parameters.device.values`와 `parameters.device.observedAt`으로 고정한다. OFFLINE 또는 없는 property는 Run 생성 전에 거절한다. 동일 Idempotency-Key 재조회는 값을 다시 읽지 않는다. 이는 **최신값을 사용하는 BATCH 입력**이며 연속 MQTT STREAM Route 자동 연결과는 다르다. Workflow에는 host/register/protocol이 들어가지 않는다.

## 환경과 권한

| 환경 변수 | 용도 |
|---|---|
| `EDGEAI_DEVICE_NAMESPACE` | 소유 Device namespace, 기본 `edgeai-devices` |
| `EDGEAI_DEVICE_DATA_BROKER` | 노드에서 접근할 MQTT 출력 주소, 현재 `tcp://127.0.0.1:1883` |
| `EDGEAI_MAPPER_ARM64`, `EDGEAI_MAPPER_AMD64` | 아키텍처별 `image@sha256:...` |
| `EDGEAI_MAPPER_SENSEHAT_ARM64` | Python/RTIMULib를 포함한 Sense HAT 전용 Mapper digest |
| `EDGEAI_INFRASTRUCTURE_SOURCE` | `api` 또는 개발용 `kubectl` |
| `EDGEAI_INFRASTRUCTURE_CONTEXT` | 개발용 고정 context |

소유 namespace는 먼저 만들어야 한다. 생산 API ServiceAccount는 node 조회 및 소유 namespace의 Device/DeviceModel/DeviceStatus 조회, Device/DeviceModel 생성·수정·삭제, ConfigMap과 Mapper Deployment 관리 권한이 필요하다. `device-manager-rbac.json`을 참조한다. 로컬 개발은 개발자의 고정 context를 사용한다. HTTP Kubernetes API는 loopback에서만 허용하며 클러스터 API는 검증된 HTTPS/CA/token을 사용한다.

## 실제 검증

현재 실센서 등록값은 [Arduino](physical/arduino-001.json)와 [Sense HAT](physical/sensehat-001.json)에 있다. 장치별 등록과 최신값 확인 방법은 [센서 가이드](../../docs/guides/sensor-registration.md)를 참조한다. 아래 fixture 파일은 시험용 소스이며 현재 클러스터에는 테스트 장치를 등록하지 않았다.

`modbus-fixture.json`은 **테스트 전용** TCP 장치다. Unit 1/2의 다른 세 값을 제공한다. `protocol-fixture.json`은 실제 OPC-UA 서버, MQTT 발행자, HTTP Virtual Runtime이 있는 테스트 컨테이너다. 실장 생산 센서로 표시하지 않는다. `scripts/test/test-mapper.sh`는 반복 실행 가능한 Go 단위·race 시험을 제공한다.

## 빌드와 시험 재현

Go 1.24.2에서 `mappers` 모듈의 `cmd/mapper`를 `CGO_ENABLED=0 GOOS=linux GOARCH=arm64` 또는 `amd64`로 정적 빌드한다. 단일 binary는 config의 protocol에 따라 해당 Driver를 사용하며, 노드에는 프로토콜별 별도 Deployment로 실행한다. `cmd/modbus-fixture`, `cmd/protocol-fixture`는 amd64 테스트 fixture다.

```bash
rtk proxy bash scripts/test/test-mapper.sh unit
```

웹은 기존 13080, API는 18080을 사용한다. 시험용 서버를 배포하더라도 Device 등록은
[센서 가이드](../../docs/guides/sensor-registration.md)의 웹/API 과정을 따른다.
특정 클러스터 장치 이름·주소·항목 수를 가정한 과거 검증 명령은 일반 실행 경로에서 제외했다.

개발 registry로 OCI image를 올리는 `scripts/internal/push_mapper_image.py`는 Docker daemon 없이 정적 binary의 scratch image를 생성한다. 임의 registry나 credentials를 받지 않는다. Mapper image 변경은 소유 Deployment를 명시적으로 갱신해야 한다; 새 장치 등록이 기존 shared Mapper image를 자동 교체하지 않는다.

Sense HAT은 `push_mapper_image.py --sensehat-runtime`으로 기존 고정 ARM64 Python/RTIMULib SDK layer에 이 저장소의 Mapper와 `mappers/hardware/read_sensehat.py`를 추가한다. SDK base의 이름에는 과거 EdgeX 이름이 있지만 서비스 실행 파일·Core Metadata/Core Data/MessageBus 의존성은 없다. 현재 RTIMULib calibration은 기존 `etri-dev0003-raspi5` 보드의 설정이다; 다른 보드는 calibration을 별도로 확인해야 한다.
