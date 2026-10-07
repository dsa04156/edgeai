# 센서 측정값 조회와 명령 실행

EdgeX 센서의 저장된 측정 이력을 읽고 장치가 제공하는 명령을 실행합니다.
가상 장치 정리는 [가상 장치 관리](virtual-devices.md#4-미사용-등록-영구-삭제)에서 설명합니다.

## 준비 사항

EdgeX Core Metadata·Data·Command를 연결하고 인프라 관리의 센서 목록을 확인합니다.
설정은 [노드와 센서 연결](infrastructure-inventory.md)을 따릅니다.
새 센서를 추가하는 순서와 지원 수집기는 [센서 등록](sensor-registration.md)을 확인합니다.
명령을 실행할 장치가 잠겨 있지 않고 해당 읽기·쓰기 명령을 지원해야 합니다.

## 1. 측정 이력 조회

1. **인프라 관리 → 센서**에서 센서 이름을 누릅니다.
2. 측정 항목과 최근 50·100·500개 중 조회 범위를 선택합니다.
3. 그래프의 실제 시각과 원본 표의 값·단위를 확인합니다.

이력은 5초마다 조회합니다. 0, 빈 이력, 조회 실패, 90초를 넘긴 측정값을 구분합니다.
실패 시 남아 있는 값은 마지막 조회 데이터입니다. 큰 정수는 원본 문자열을 보존하며
안전한 숫자 표현 범위를 벗어나면 그래프에 반올림한 값으로 그리지 않습니다.

## 2. 장치에서 직접 읽기

지원 명령을 선택하고 **지금 읽기**를 누릅니다. 이 버튼은 Core Command를 통해 실제 장치 읽기를 요청합니다.
Core Data의 저장 이력과 별도 결과로 표시하며 `ds-pushevent=false`로 이벤트 저장을 요청하지 않습니다.
주기적 화면 조회가 읽기 명령을 자동으로 실행하지는 않습니다.

## 3. 쓰기 명령

Core Command가 `set=true`로 제공한 명령에만 쓰기 입력을 표시합니다.
항목별 값을 입력하고 **쓰기 명령 검토**에서 대상·명령·값을 확인한 뒤 전송합니다.
서버는 최신 지원 명령과 장치 잠금 상태를 다시 확인합니다.

현재 저장소의 Arduino·Sense HAT 프로필은 읽기 전용이므로 쓰기 버튼이 없는 것이 정상입니다.
EdgeX 성공 응답은 요청 처리 결과이며 물리 장치가 기대대로 바뀌었는지는 장치 상태·측정값으로 확인합니다.
명령은 자동 재시도하지 않습니다. 통신 실패 시 실제 결과를 확인한 뒤 재요청 여부를 결정합니다.

## API와 오류

| API | 역할 |
|---|---|
| `GET /api/v1/sensors/readings` | 장치·항목별 최근 저장 측정값 |
| `GET /api/v1/sensors/commands` | 장치 상태와 지원 명령 |
| `POST /api/v1/sensors/command` | 직접 읽기 또는 쓰기 요청 |

POST에는 CSRF가 필요하고 `executeSensorCommand` 감사 작업으로 기록됩니다.
정상 GET 폴링은 감사 기록을 생성하지 않습니다. 비활성은 503, 원본 조회 실패는 502,
잠금은 409 등으로 구분하며 정확한 입력·오류 코드는 [API 참고](../reference/api.md)를 확인합니다.

## 관련 문서

- [관측값과 상태](../concepts/observability.md)
- [EdgeX Reading 조회](https://docs.edgexfoundry.org/4.1/walk-through/Ch-WalkthroughReading/)
- [EdgeX Core Command](https://docs.edgexfoundry.org/4.0.2/microservices/core/command/GettingStarted/)
- [해당 기능의 검증 범위](../evidence/sensor-controls-and-vd-deletion-2026-10-06.md)
