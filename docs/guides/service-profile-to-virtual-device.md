# SERVICE 프로필부터 VD 생성

VD는 처리 SERVICE와 원본 센서 디바이스, 실행 배치를 연결하는 논리 리소스입니다.
센서 디바이스를 **센서** 화면에서 한 번 등록하면 VD에서 직접 선택합니다.
별도 앱 장치 등록이나 DEVICE 프로필 발행은 필요하지 않습니다.

## 준비

1. **인프라 관리 → 센서**에서 사용할 디바이스와 측정 항목을 확인합니다.
2. **가상 장치 → 가상 장치 등록 → 실행 모듈 SERVICE 등록**에서 Runner를 포함한 실행 이미지,
   입력·출력 포트와 자원 요구사항을 작성하고 버전을 발행합니다.
3. **VD 템플릿 등록·수정**에서 기본 양식을 채우고 발행한 SERVICE를 선택합니다.

## 센서 원본이 있는 VD

템플릿 종류를 `sensorMirror` 또는 `processing`으로 선택하고, 원본 슬롯의 **센서 DeviceModel**에서
사용할 센서의 모델을 선택합니다. 같은 모델을 쓰는 디바이스가 여러 개면 인스턴스 단계에서 개별 디바이스를 선택합니다.
원본 키와 필수 여부, 동시 작업 수·시작·종료 제한을 정하고 템플릿을 발행합니다.

JSON 예시는 다음과 같습니다. SERVICE UUID와 모델 이름은 실제 선택 값으로 바꿉니다.

```json
{
  "apiVersion": "edgeai.vd/v2",
  "type": "sensorMirror",
  "serviceProfileVersionId": "11111111-1111-4111-8111-111111111111",
  "sources": {
    "input": {"deviceModel": "edgeai-model-temperature", "required": true}
  },
  "state": {"mode": "STATELESS"},
  "runtime": {"maxConcurrentTasks": 1, "startupTimeoutSeconds": 120, "drainTimeoutSeconds": 120}
}
```

아래 **가상 장치 구성**에서 키·이름·VD 템플릿을 선택하고 `input` 원본에 센서 디바이스를 선택합니다.
자동 배치 또는 특정 실행 노드를 지정한 뒤 **VD 등록**을 누릅니다. 서버는 센서 이름·UID·DeviceModel을
고정하며 실제 센서 수집·실행 준비 상태는 각각 확인해야 합니다.

## 원본 없는 모의 VD

템플릿 종류를 `emulation`으로 선택하고 원본 조건을 모두 삭제합니다. `sources: {}`로 발행하면
센서 선택 없이 인스턴스를 등록할 수 있습니다. 실행 이미지와 SERVICE는 여전히 필요합니다.
이 VD와 센서 화면의 HTTP `protocol: virtual` 디바이스는 서로 다른 실행 수명을 갖습니다.

## 기존 VD 수정·전환

VD 상세의 **설정 수정**에서 새 v2 템플릿과 센서를 선택하고 저장합니다. VD ID는 유지되며
기존 원본 연결은 종료 이력으로 보존됩니다. 이전 v1 템플릿을 그대로 복제하기보다 v2 기본 양식에서
기존 SERVICE와 실행 정책을 선택해 새 버전을 발행합니다. 이미 발행된 프로필 내용은 수정하지 않습니다.

센서의 삭제·모델 변경은 VD 원본으로 사용 중이면 409입니다. 먼저 VD 원본을 교체하고 이전 실행의
종료를 확인합니다. 클러스터에서 같은 센서 이름이 재생성됐다면 UID가 달라 실행 시작을 거절하므로
원본을 다시 선택해 저장합니다.

## 실행과 정리

등록 `REGISTERED`는 Runtime Ready가 아닙니다. VD 상세에서 실행 시작·교체·종료를 요청하고
Operation과 실제 runtime 세대를 확인합니다. 원본 등록만으로 연속 MQTT 데이터 consumer가 생기지는 않습니다.
현재 Mapper 최신값을 Workflow Run에 입력하는 경로는 [워크플로 모델](../concepts/workflows.md)을 확인합니다.

실습 등록은 **VD 해제** 후 **영구 삭제**로 정리합니다. 실행·작업 이력이 참조하는 등록은 삭제할 수 없습니다.
원본 센서 디바이스와 SERVICE·VD 프로필은 별도로 관리합니다. 자세한 수명과 삭제 조건은
[가상 장치 관리](virtual-devices.md)를 확인합니다.
