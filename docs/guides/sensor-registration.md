# 웹에서 센서와 Virtual Device 등록

인프라 관리 → 센서에서 기존 장치의 측정 항목 추가와 새 장치 연결을 구분합니다. **새 장치 등록**을 누르면 등록 모달이 열립니다. 프로토콜, 네트워크 Host/Port 또는 로컬 장치 경로, 대상 Edge Node, 수집 간격과 측정 항목을 입력합니다. 긴 폼은 모달 안에서 스크롤하며 ESC·취소·닫기로 돌아갈 수 있습니다. 등록 초안은 다시 열어도 유지합니다. 장치 종류는 프로토콜에 따라 Physical 또는 Virtual입니다. 설치된 KubeEdge EdgeCore DMI와 아키텍처별 Mapper 이미지가 필요합니다.

센서 목록은 측정 항목마다 별도 행으로 최신값·상태·수집 시각과 관리 기능을 표시합니다. 상태는 5초마다 갱신합니다.

센서 이름을 누르면 **센서 그래프** 모달이 열립니다. 최신값·단위·수신 시각과 InfluxDB에 저장된 센서 원본 이력을 확인합니다. 센서 UID와 항목으로 조회하므로 동일한 이름으로 재등록한 다른 센서의 이력을 섞지 않습니다. 기본5분이며1분부터 보관 기간까지 **조회 기간**을 선택하고 **자동 갱신 · 5초**를 켜거나 끕니다. 숫자·참/거짓은 시간순 선 그래프, 문자열은 기록 표로 확인합니다. 상단 최신값은 읽기 쉽게 줄여 표시하며 반올림 시≈가 붙습니다. 정확한 값은 툴팁·기록 표·**센서·수집 정보**에서 확인합니다. 연결된 선의 점 표식은 숨기고 단독 측정값만 점으로 표시합니다. 수신 간격이 크게 벌어지거나 수집 설정·세대가 바뀐 구간은 선을 끊습니다. 이전 Mapper의 UNVERIFIED 기록은 수집 설정 미검증으로 표시합니다. 기록은1000개씩 조회하며 **다음 기록**은 자동 갱신을 멈추고 같은 기간을 이어서 조회합니다. 표시 한도2000개에 도달하면 기간을 줄입니다. 페이지 이동·새로고침 후에도 저장된 이력은 유지됩니다. ESC·닫기로 목록에 돌아갑니다. 공개 조회 API는 `GET /api/v1/data-store/sensors/{uid}/{property}/history`이며 브라우저는 같은 출처 프록시를 사용합니다.

그래프는 ECharts를 사용합니다. 마우스·터치로 측정 시각·값·단위를 확인하고 아래 시간축 슬라이더 또는 **확대·축소·전체 구간** 버튼으로 현재 조회된 기록을 탐색합니다. 더 긴 이력은 **조회 기간**을 변경합니다. 자동 갱신 중 확대 비율과 펼친 기록 표를 유지합니다. 같은 원본은 **데이터 저장소** 화면에서도 같은 차트로 조회합니다.

기존 Arduino에서 측정 항목 하나를 지웠다면 남아 있는 센서 행의 **같은 연결에 추가**를 눌러 모달을 엽니다. **측정 항목 선택 → 항목 추가**를 사용합니다. 예를 들어 `acceleration_x_raw`를 선택하면 자료형·프레임 sensor·JSON 경로·단위가 저장 규격에서 채워집니다. 노드·장치 경로·baud rate·수집 간격은 기존 연결 설정을 그대로 사용합니다. **항목 설정 확인·수정**에서 값의 위치를 확인하거나 수정할 수 있으며, 새로운 항목은 **직접 입력**으로 추가합니다. 이미 등록된 이름은 선택 목록에서 제외되고 서버에서도409로 거절합니다. 오류는 모달 안에 표시하고 입력을 유지합니다. 항목 추가를 취소하면 해당 초안은 초기화됩니다.

운영 중인 Arduino/Sense HAT의 항목 규격은 `arduino-serial-json`, `sensehat-v1`으로 저장했습니다. 새 설치에서는 `deploy/kubeedge/physical/*.json`의 `protocol`과 `properties`를 `POST /api/device-profiles`로 저장해 같은 선택 목록을 준비할 수 있습니다. 저장 규격은 자동 발견 결과가 아니므로 실제 보드의 출력 규격과 일치해야 합니다. 장치가 통째로 삭제되어 목록에 없으면 **새 장치 등록**에서 저장 규격과 연결 정보를 입력합니다.

| 프로토콜 | 장치 설정 | property 매핑 |
|---|---|---|
| Modbus TCP | Unit ID, timeout | Register, 자료형, 함수, 바이트 순서 |
| OPC-UA | endpoint path, timeout; Anonymous / SecurityMode None | NodeId, 자료형 |
| MQTT 입력 | broker Host/Port, 메시지 유효 시간 | 정확한 topic, JSON 경로, 자료형 |
| Virtual | Container/Emulator/AI Service의 HTTP JSON endpoint path | JSON 경로, 자료형 |
| Serial JSON | `/dev/edgeai/<endpoint>`, source Device ID, baud rate | 프레임 `sensor`, JSON 경로, 자료형 |
| Sense HAT v1 | ARM64 Raspberry Pi, `/dev/i2c-1`, source Device ID | `temp_humidity`, `humidity`, `pitch` 등의 JSON 경로 |

Modbus holding 40001, input 30001, coil 1은 각각 wire address 0입니다. MQTT 입력에는 wildcard 및 출력용 `edgeai/devices/` topic을 사용할 수 없습니다. JSON 경로는 `values.temperature`처럼 객체 키를 점으로 연결합니다. 인증정보를 Profile에 넣는 기능은 현재 지원하지 않습니다.

**연결 테스트**는 대상 노드의 Mapper에서 실제 property를 읽습니다. 처음 Mapper가 배포되는 동안에는 준비를 기다린 뒤 다시 시도하세요. **장치 등록**은 Profile을 검증하고 인터페이스가 같은 DeviceModel을 재사용하거나 생성한 뒤 Device CRD를 생성합니다. 노드/프로토콜마다 하나의 Mapper를 공유합니다. 두 번째 같은 프로토콜 장치는 설정만 추가하면 됩니다.

등록 성공은 연결 성공을 뜻하지 않습니다. 화면의 ONLINE/OFFLINE, 마지막 수집 시각과 값을 확인하세요. 값이 0인 경우에도 정상 측정값으로 표시합니다. 같은 이름의 새 등록은 409이며 수정은 각 측정 행의 **항목 수정 → 항목 저장**을 사용합니다. Property 규격을 저장하고 같은 프로토콜의 새 등록과 항목 추가에서 선택할 수 있습니다. 항목 추가 API는 `POST /api/devices/{id}/properties`이며 본문에는 측정 항목 하나의 설정만 보냅니다. 서버가 최신 Profile에 병합하고 동시 변경은 resourceVersion으로 보호합니다. 공유 Mapper Pod를 새로 만들지 않습니다.

각 측정 행의 **항목 수정**은 해당 항목 하나의 자료형·값 위치·단위·배율·보정값을 편집합니다. 항목 이름은 Workflow 참조를 유지하기 위해 변경하지 않습니다. `PUT /api/devices/{id}/properties/{property}`에 항목 하나의 설정만 보내며 서버가 최신 Profile에 반영합니다. 다른 항목과 연결 설정·노드·수집 간격은 유지하고 공유 Mapper Pod를 다시 만들지 않습니다. 5초 갱신과 검색 중에도 입력을 유지하며 저장 충돌이면 입력을 보존하고 오류를 표시합니다. 기존 장치의 전체 properties를 한꺼번에 편집하는 버튼은 제공하지 않습니다.

각 측정 행의 **항목 삭제 → 삭제 확인**은 해당 센서의 수집 설정을 제거합니다. 현재 CRD는 측정 항목마다 분리돼 있으므로 마지막 항목 삭제는 그 센서의 Device·DeviceStatus 등록도 제거합니다. 같은 물리 연결의 다른 센서와 공유 Mapper는 유지합니다. API의 항목 삭제 경로는 `DELETE /api/devices/{id}/properties/{property}`입니다. 저장한 Property 규격은 삭제하지 않으므로 재등록 때 불러올 수 있습니다.

실행 상태의 기준은 Device/DeviceStatus CRD입니다. 앱 DB나 레포 YAML을 별도 상태 원본으로 만들지 않습니다. 등록 API는 `/api/devices`, Profile API는 `/api/device-profiles`, 지원 프로토콜은 `/api/protocols`입니다. 브라우저는 같은 출처 프록시와 기존 CSRF 토큰을 사용합니다.

기존 `/api/v1/sensors/*` 경로는 410 `SENSOR_API_REPLACED`로 종료됐습니다. Arduino와 Sense HAT은 현재 실제 보드를 새 Mapper에 연결했습니다. Arduino의 `*_raw` 값은 환산 전 ADC/디지털 값이며 °C 또는 가속도 물리 단위로 표시하지 않습니다. 시리얼 endpoint는 노드의 `/run/edgeai/devices`에 존재해야 합니다. Sense HAT 지원은 v1 보드로 한정됩니다. UUID 앱 장치 이력과 CRD 이름은 다른 신원입니다. 구형 VD BATCH·supervisor는 종료됐으며 현재 연속 가상 센서는 [별도 계산 등록](vd-processing.md)을 사용합니다.

[실행 구조·환경·데이터 경로](../../deploy/kubeedge/README.md)

기존 웹 `http://192.168.0.56:13080/devices`의 **센서** 탭에서 등록된 측정 항목을 각각 한 행으로 확인합니다. 기본 저장 규격은 Arduino 6개와 Sense HAT 11개이며 실제 등록 목록은 항목 추가·삭제 결과를 따릅니다. 값은 실제 센서에서 읽고, 최신 상태는 DeviceTwin/DMI, 매 수집 데이터는 해당 노드의 MQTT broker로 전달합니다. Twin 갱신 간격은 DMI 예산과 항목 수에 따라 달라집니다. 동일 값이면 KubeEdge가 값을 다시 갱신하지 않을 수 있으며 수집 지속 여부는 DMI heartbeat와 별도 MQTT 수신으로 검증합니다.

## Modbus 모의 장치로 등록 연습

현재 준비한 `modbus-fixture` 서버로 웹 등록을 연습할 수 있습니다. 서버를 실행한 환경에서
**새 장치 등록**에 이름 `practice-modbus-01`, 프로토콜 `modbus-tcp`, 저장 규격 `modbus-practice`,
Host `10.77.0.5`, Port `1502`, Unit ID `1`, 대상 노드 `etri-dev0002-raspi5`, 수집 간격·제한 시간
각 `1000` ms를 입력합니다. 다른 환경에서는 서버 주소와 대상 노드를 바꿉니다.

**연결 테스트 → 장치 등록** 후 `temperature=23.5`, `vibration_x=0.125`, `vibration_y=0.25`를
확인합니다. Holding Register는 각각 `40001`, `40003`, `40005`이며 자료형은 `float32`,
바이트 순서는 `ABCD`입니다. 두 번째 장치는 이름 `practice-modbus-02`, Unit ID `2`를 사용하며
값은 각각 `33.5`, `0.25`, `0.5`입니다. 이 서버는 실제 생산 센서가 아닌 고정값 모의 장치입니다.
