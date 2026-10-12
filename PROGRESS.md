# 현재 상태

2026-10-08 확인. KubeEdge Mapper 기반 장치 관리와 실제 센서 연결은 구현·검증돼 있으며,
실제 학습 모델과 연속 데이터 경로를 포함한 전체 플랫폼 수용은 아직 완료되지 않았습니다.

기존 가상 디바이스 등록·프로필·실행 기능은 2026-10-12 제거했습니다. 일반 장치·워크플로와 과거 실행 이력은 유지합니다. [검증 기록](docs/evidence/virtual-device-removal-2026-10-12.md).

## 현재 구현

- **장치 실행 계층:** Device Manager가 등록 설정을 DeviceModel/Device CRD로 컴파일하고,
  노드·프로토콜별 공유 Mapper가 DMI로 최신값·상태를 보고합니다. 활성 EdgeX 실행 계층은
  2026-10-07 제거했으며 Arduino Serial JSON과 Sense HAT을 새 Mapper로 연결했습니다.
- **지원 프로토콜:** Modbus TCP, OPC-UA, MQTT 입력, Virtual HTTP JSON, Serial JSON,
  Sense HAT v1입니다. 인증·TLS, 임의 프로토콜 발견과 센서 쓰기 제어는 아직 지원하지 않습니다.
- **관리 화면:** 새 장치 등록·연결 테스트·수정, 저장 property 규격으로 기존 장치의 항목 추가,
  개별 항목 삭제와 장치 삭제를 제공합니다. 마지막 항목 삭제는 장치 등록도 제거합니다.
- **관측·데이터:** 장치별 항목의 최신값·보고 시각과 Mapper 준비 상태·DMI heartbeat를 조회합니다.
  MQTT에는 매 수집 데이터를 발행하지만 해당 경로의 영속 이력 consumer·재전송 outbox는 없습니다.
- **실행 입력:** 일반 Mapper 장치 참조 입력과 AUTO/NODE/REMOTE 실행을 유지합니다. 기존 VD의 센서 입력 snapshot은 읽기 전용 실행 이력으로 보존합니다.
- **앱과 워크플로:** 기본 관리 화면은 로그인 입력 없이 사용하며 쓰기 요청은 CSRF 보호를 적용합니다.
  Spring DAG가 기본 서비스 경로이고 DDS·Buildx·Gitea·Argo CD는 별도 경로로 유지합니다.
  워크플로는 현재 기본 설정에서 활성화돼 있습니다.

사용 방법은 [센서 등록](docs/guides/sensor-registration.md),
[Mapper 실행 계층](deploy/kubeedge/README.md), [API 참고](docs/reference/api.md)를 확인합니다.

## 검증된 범위

CRD/DMI 등록·변경·삭제와 Modbus·확장 프로토콜, BATCH 입력 snapshot을 검증했습니다.
Arduino·Sense HAT의 실제 읽기·Twin·MQTT 수신도 확인했으며 항목 수는 사용자 추가·삭제에 따라 바뀝니다.
개별 항목 수정과 VD 센서 BATCH에서 실제/fixture 경계를 나누어 검증했습니다.
Sense HAT 온도→호스트 supervisor·Runner→HTTP/분리DB/MinIO→검증 Result가 완료됐습니다.
이 시험의 Pod 인증은 fixture이며 실제 Kubernetes Pod 배포 수용과 구분합니다.
상세 기준·결과는 [VD 센서 BATCH 검증](docs/evidence/vd-sensor-batch.md)을 확인하세요.
날짜별 상세 보고서·스크린샷과 일회성 검증 도구는 Git에서 제외한 `output/`에 보관합니다.

## 개발 단계와 남은 범위

구형 `state-aggregator`·장치 확장 controller·Redis·문서 사이트와 미등록 시험 Mapper·fixture를
관련 실행체·설정까지 정리했습니다. HTTP 라우터는 30개이며 공통 인프라·실센서와 PVC/PV는
보존했습니다. API health, 실센서 ONLINE·새 MQTT 수신과 공통 관리 화면 응답을 확인했습니다.

- **완료 단계:** M0–M4, M6의 해당 구현·검증 범위입니다.
- **우선순위:** Dashboard 관리 흐름을 우선합니다. Runner/STREAM 고도화와 종합 수용은 보류합니다.
- **통합 입력:** 새 eKuiper 가상 디바이스 연동과 MQTT 영속 소비·재전송·연속 실행 연결은 후속 범위입니다.
- **실제 모델:** ARM/x86 Runner와 CUDA 계산·NPU 접근 증거는 있지만 학습 모델의 종단 추론 수용은 미완료입니다.
- **운영·성능:** M5 외부 계약·상태형 복원, M8 합의 성능 기준, M9 identity/RBAC·종합 복구,
  M10 실제 모델·2세부 연동이 남아 있습니다. 다수 항목의 DMI 보고 지연과 복구 시간도 추가 측정이 필요합니다.
- **기술 버전:** 공식 비교의 KubeEdge 패치·Go/Next 업그레이드는 검토 제안입니다.
  현재 운영 이미지의 patch/backport와 실제 빌드 toolchain은 별도 확인 대상입니다.

단계별 게이트는 [PLAN.md](PLAN.md), 장치 간 관계는 [리소스 모델](docs/concepts/resources.md)을 확인합니다.
