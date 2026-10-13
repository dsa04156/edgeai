# 현재 상태

2026-10-13 확인. 센서 관리와 연속 가상 센서의 관리 API·계산 Agent·결과 조회 구현은 제공됩니다.
실제 센서 수집과 중앙 연속 계산의 운영 활성화는 별도 단계입니다. 전체 플랫폼의 실모델·성능·복구 수용은 완료되지 않았습니다.

## 현재 구현

- **센서 관리:** Device Manager가 측정 항목마다 별도의 Device·DeviceStatus CRD를 구성합니다. 노드·프로토콜별 Mapper는 공유하며 Modbus TCP, OPC-UA, MQTT 입력, HTTP Virtual, Serial JSON, Sense HAT v1을 지원합니다.
- **관측과 원본 데이터:** 최신값·시각·heartbeat는 DeviceStatus/DMI로 조회하고 매 수집 데이터는 MQTT로 발행합니다. 원본 collector와 InfluxDB 저장·UID별 이력 조회 API를 제공합니다. 원본 저장과 최신 Twin 조회를 구분하며 일반 DAG의 센서 입력은 Run 접수 시 최신값을 고정합니다.
- **연속 가상 센서:** 내부 `edgeai.vd/v3 / CONTINUOUS_EKUIPER` 설정으로 여러 측정 센서를 조합합니다. 계산식·이동 평균·최솟값·최댓값·임계값·필터와 출력별 최신값·그래프·보관 이력 API를 구현했습니다. 센서 등록만으로 VD가 생기지 않으며 입력·계산을 명시적으로 등록합니다. VD마다 별도 SERVICE·supervisor·Pod를 만들지 않습니다.
- **등록 화면:** 센서·측정 항목 추가·가상 장치·실행 모듈·워크플로·DAG 발행은 모달을 사용합니다. 긴 폼은 모달 안에서 스크롤하고 오류 시 입력을 유지합니다.
- **그래프 조회:** 센서 이름·가상 장치 이름으로 그래프 모달을 엽니다. 센서는 UID별 InfluxDB 원본 이력을 기간·기록 표로 확인하며 가상 장치는 출력별 저장 이력을 조회합니다. 페이지를 닫아도 저장된 센서 기록은 유지됩니다. 가상 장치 설정은 별도 버튼을 사용합니다.
- **일반 서비스:** SERVICE 실행 모듈과 Spring DAG, AUTO/NODE/REMOTE 정책을 유지합니다. DDS·Buildx·Gitea·Argo CD는 별도 배포 경로입니다. DEVICE 신규 발행과 구형 VD 프로필·BATCH·supervisor 경로는 종료됐습니다.

## 현재 운영 상태

- 호스트 웹/API는 기존 13080/18080을 사용합니다. 실제 센서 17개가 ONLINE으로 조회됩니다.
- 센서용 NanoMQ는 Jetson·Pi의 노드 로컬 broker입니다. `ekuiper` namespace의 `my-ekuiper-0`은 worker `etri-ser0004-cgnms0`에서 eKuiper2.4.2를 실행합니다. `edgeai-mqtt-0` Mosquitto는 일반 STREAM용 별도 broker입니다.
- `EDGEAI_CONTINUOUS_ENABLED=false`입니다. 운영 processing Agent·전용 history·qualified observation 경로의 배포와 수용은 남아 있으므로 eKuiper Pod Running을 실제 가상 센서 계산 중으로 해석하지 않습니다.
- 명시적 삭제 요청으로 호스트 DB의 구형 등록 233개와 연결된 구형 VD 프로필·실행 데이터를 정리했습니다. 연속 처리 등록 30개는 보존됐으며 시험용 등록입니다. 일반 워크플로·SERVICE·센서·노드·감사 데이터와 migration은 보존했습니다.

## 검증과 한계

구형 데이터 정리는 전체 DB 백업을 별도 DB에 복원해 먼저 실행했고, 실제 적용 시 삭제 대상 외 행의 동일성과 FK·trigger 복구를 확인했습니다. 최신 백엔드 단위180개와 빌드가 통과했습니다. 등록 모달과 연속 결과·기존 모듈 기능은 PC·모바일 Chromium28개로 검증했습니다. 날짜별 로그·스크린샷·일회성 결과는 Git에서 제외한 `output/verification/`에 보관합니다.

실제 브로커·eKuiper를 사용하는 개발 시험은 운영 센서의 24시간 연속 처리·저장·부하 수용과 구분합니다. 새 Mapper 경로의 자동 STREAM 연결, 실제 학습 모델·외부 API 전체 계약, 운영 identity/RBAC·종합 복구·합의 성능 기준은 남아 있습니다. 원본 이력의 운영 보관·복구 수용은 collector 연결과 조회 가능 여부만으로 완료를 주장하지 않습니다.

사용 방법은 [센서 등록](docs/guides/sensor-registration.md), [연속 가상 센서](docs/guides/vd-processing.md), [지원 범위](docs/reference/support.md)를 확인합니다. 다음 게이트는 [PLAN.md](PLAN.md), 구형 방식의 종료·데이터 정리 원칙은 [제거 결정](docs/adr/0115-retire-legacy-virtual-devices.md)을 따릅니다.
