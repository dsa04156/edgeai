# Spring DAG 구성과 실행

기본 **서비스 워크플로** 화면(`/workflows`, 기존 `?mode=dag` 지원)에서 발행된 SERVICE 모듈을 연결하고 버전별로 실행을 요청합니다.
SERVICE 프로필의 서비스 구성 링크에서도 이 편집기로 진입합니다.

## 준비 사항

별도 실행용 Spring 인스턴스로 연결할 때 Dashboard의 서버 설정 `EDGEAI_WORKFLOW_API_BASE_URL`을 지정할 수 있습니다. Workflow/Run/Task/Operation/SERVICE와 입력 목록만 해당 고정 origin으로 전달하고 기존 센서·가상디바이스 관리 API는 유지합니다. 별도 인스턴스의 세션 cookie 이름은 `EDGEAI_WORKFLOW_SESSION`으로 설정하며 Dashboard는 워크플로용 CSRF/세션을 분리합니다. 이 설정이 없으면 기존 단일 API를 그대로 사용합니다. 두 인스턴스는 같은 control DB를 사용하고 원본 수집은 기존 인스턴스 한 곳에서만 실행합니다.

API와 Dashboard의 워크플로 기능을 활성화하고 필요한 SERVICE 프로필을 발행합니다.
실제 작업을 실행하려면 Runtime·Runner·Kubernetes·고정 버전 결과 저장소를 준비해야 합니다.
고급 STREAM·Remote 실행은 각각 별도 설정과 [지원 조건](../reference/support.md)을 확인합니다.

## 1. 작업과 의존성 작성

모듈을 드래그하거나 추가 버튼으로 캔버스에 놓습니다. 작업 이름·매개변수·입출력 포트를 설정합니다.
작업의 출력과 다음 작업의 입력을 연결하면 DAG 의존성이 됩니다.
순환, 없는 포트, 중복 입력, 지원하지 않는 연결은 발행 전에 거절합니다.

JSON 편집 파일은 `edgeai.workflow-editor/v1` 형식으로 DAG와 캔버스 좌표를 보존합니다.
파일을 불러올 때 기존 구성을 교체할지 확인합니다. 서버에는 DAG 계약을 발행합니다.

## 실시간 입력 선택

`WORKFLOW_DATA` 데이터 입력 모듈을 선택하면 **입력 종류 → 입력 데이터**에서 센서 값 또는 가상디바이스 계산 결과를 고릅니다. 입력을 바꿔도 뒤의 처리 작업·연결선은 유지합니다. 변경은 새 DAG 버전과 새 실행에 적용하며 이미 실행 중인 원본은 바꾸지 않습니다.

현재 운영 예제는 **실시간 데이터 입력 전환**(`live-input-switch`)입니다. 버전을 선택해 **이 버전으로 편집하기**를 누르고, **편집할 작업**에서 `input`을 선택해 원본을 바꿉니다. 처리 작업 `process`와 연결은 유지한 채 새 버전을 발행하고 실행합니다. `/workflows?workflow=<워크플로 UUID>` 주소로 목록 페이지를 넘기지 않고 해당 워크플로를 바로 열 수 있습니다.

센서는 NanoMQ에서 수집·저장한 검증 원본을 사용합니다. 가상디바이스는 eKuiper의 현재 유효 계산 결과를 사용하므로 결과 이력 저장 정책이 `NONE`이어도 입력으로 사용할 수 있습니다. 가상디바이스를 먼저 시작하고 유효 결과가 들어온 뒤 실행합니다.

기본 전달 방식 `LATEST`(`dataSource.delivery` 생략 시)는 같은 실행에서 최신 유효값을1초마다 읽고 중복 `sampleId`를 제거합니다. 중간 관측의 전량 전달은 보장하지 않습니다.

**전체 관측**(`dataSource.delivery: ALL_OBSERVATIONS`)을 고르면 실행 생성 시점의 시작 위치 이후 수락된 모든 관측을 수락 순서대로 한 번씩 전달합니다. 한 번의 조회 사이에 여러 값이 들어오거나 관측 시각이 같거나 역순이어도 빠뜨리지 않고, 센서 재전송·VD 엔진 재전송은 중복 전달하지 않습니다. 센서는 원본 저장 뒤 MQTT ACK 전, 가상디바이스는 결과 수락 transaction에서 feed에 기록합니다. Mapper 검증 숫자/불리언 센서와 실행 중인 가상디바이스 출력만 지원하며 문자열 센서는 거절합니다. collector가 받기 전의 손실, 시작 전 관측은 대상이 아닙니다. 적체가 한도(기본 10,000건)를 넘거나 보관 범위(기본1시간·원본별50,000건)를 벗어난 복원은 `WORKFLOW_SOURCE_GAP` 오류로 실행을 멈추며 조용히 건너뛰지 않습니다. 설계와 비보장 범위는 [ADR0118](../adr/0118-workflow-source-full-observation-feed.md)을 확인합니다.

Runner 이미지는 각 SERVICE Profile 버전의 `image` digest로 정해집니다. 새 Runner를 registry에 올려도 기존 Profile·Workflow 버전은 바뀌지 않으며, 이전 Runner는 ALL_OBSERVATIONS 응답을 `STREAM_INVALID_SOURCE_RESPONSE`로 실패시킵니다(최신값 방식은 양쪽 모두 그대로). 전체 관측을 쓰려면 `image`를 `10.254.192.47:5000/edgeai/processing-runner@sha256:1faf83fea8a5f726a6ea1a9f78c98511003f304e8301b2e51e30578b8f451de0`(소스 `215023c`, linux/amd64 전용)로 바꾼 새 Profile 버전을 게시하고, 그 버전을 참조하는 새 Workflow 버전을 게시한 뒤 실행합니다. 실행에는 runtime 활성화가 별도로 필요합니다.

센서 UID/설정/원본 세대 또는 가상디바이스 revision/실행 세대가 바뀌면 원본을 조용히 교체하지 않고 해당 실행을 차단합니다. 최신값 방식에서 만료되거나 아직 없는 측정은 기다립니다.

**정상 종료**는 새 입력을 끝내고(전체 관측은 요청 시점까지 확정된 관측을 모두 보낸 뒤) 마지막 DATA·END의 처리 ACK, 서버 확정 체크포인트와 공동 FINALIZE를 기다린 뒤 결과 파일을 저장합니다. 실행 취소와 다릅니다. 실행의 최종 상태·검증된 Result를 확인합니다. SERVICE의 실행 시간 한도는최대24시간이며 무기한 실행을 의미하지 않습니다.

소스와 예제 처리 프로파일은 [데이터 입력](../../contracts/profiles/service-workflow-input.example.json)과 [연속 표본 집계](../../contracts/profiles/service-workflow-sample.example.json)를 참고합니다. 예제 처리기는 표본 수·마지막 값과 숫자 값의 평균/최솟값/최댓값을 집계하고 정상 종료 후 버전 관리 결과 저장소에 저장합니다. 이미지의0 digest는 실제 준비한 native 이미지 digest로 바꿉니다.

## 2. 워크플로 생성과 버전 발행

**새 워크플로 만들기**로 모달을 열어 워크플로 키·이름을 등록합니다. 등록한 워크플로의 **새 DAG 버전 발행**을 누르면 별도 발행 모달에서 구성한 DAG와 버전을 확인할 수 있습니다. 실패하면 모달의 입력을 유지하고 성공하면 닫힙니다.
버전은 불변입니다. 같은 버전에 다른 내용을 덮어쓰지 말고 새 버전을 발행합니다.
다른 작업이 참조하는 SERVICE 버전을 임의로 바꾸지 않습니다.

## 3. 실행 요청

발행한 버전과 실행 정책을 선택합니다. AUTO/NODE와 준비된 Remote 대상은
해당 기능의 지원 조건에 따라 선택합니다. 작업별 실행 위치가 지정된 경우 Run 기본 정책보다 우선합니다.

`Idempotency-Key`는 동일 실행 요청의 재전송을 구분합니다. 응답이 유실됐을 때 같은 요청은 같은 키를
사용하고, 별도 실행을 만들 때는 새 키를 사용합니다. 기능이 비활성화된 상태에서 요청 저장과 실제 실행을 혼동하지 않습니다.

## 4. 실행 결과 확인

**실행·작업·결과**(`/runs`)에서 Run, 각 Task와 Attempt, Placement를 확인합니다.
배치 의도와 실제 Pod 위치를 구분하고 성공한 작업의 검증된 Result를 조회합니다.
재시도는 동일 Task에 새 Attempt를 만들며 이전 시도의 결과로 현재 성공을 추정하지 않습니다.

작업 취소는 의존 작업이나 연결된 스트림 그룹에 영향을 줄 수 있습니다.
확인 화면의 범위를 읽고 실행 후 Run·Task 상태를 다시 조회합니다.

## 고급 기능

API 실행 요청은 `device`에 CRD 장치 이름과 필요한 property를 지정할 수 있습니다.

```json
{"device":{"id":"sensor-01","outputs":["temperature"]},"parameters":{}}
```

`sensor-01`은 설치 환경에 등록된 장치 이름으로 바꿉니다. Run 접수 시 ONLINE 장치의 최신값을
`parameters.device.values`와 `parameters.device.observedAt`으로 고정하며 OFFLINE 또는 없는 항목은 거절합니다.
같은 `Idempotency-Key` 재호출은 값을 다시 읽지 않습니다. 이는 BATCH 입력이며 연속 MQTT STREAM
연결이나 UUID VD 원본 연결을 자동으로 만들지 않습니다. [Mapper 데이터 경로](../../deploy/kubeedge/README.md#데이터-경로)를 확인합니다.

재시도·NODE/Remote 전환·STREAM 그룹의 조건은 [API 참고](../reference/api.md)와
[Remote 설정](../operations/remote.md)을 따릅니다. 일반 SERVICE의 재시작과 checkpoint 복원은
서로 다른 계약이며 실제 외부 제공자 수용은 별도입니다.

다음 단계: [상태 해석](../concepts/observability.md), [감사 기록](../operations/management-audit.md).
