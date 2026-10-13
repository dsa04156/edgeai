# 기능별 지원 범위

이 표는 기능을 사용하기 위한 조건과 현재 수용 범위를 설명합니다.
코드 구현, 개별 시험 통과, 배포 반영, 전체 플랫폼 수용은 별도로 확인합니다.

| 기능 | 사용 조건 | 판단할 때 주의할 점 |
|---|---|---|
| SERVICE 실행 모듈 관리 | API와 PostgreSQL | 불변 버전 발행; DEVICE 신규 발행·별도 앱 장치 신규 등록 종료 |
| 서버·엣지 노드 목록 | Kubernetes 관측과 역할 라벨 | 라벨이 모호하면 분류 불명 |
| 자원 측정 | Prometheus와 해당 exporter | 장착 정보와 사용량 지원이 다름 |
| 센서 목록·최신값 | KubeEdge Device/DeviceStatus CRD·DMI·Mapper | ONLINE과 개별 값의 갱신 시각은 다름; 영속 이력 조회는 없음 |
| 센서 등록·연결 확인 | 지원 프로토콜·대상 Edge Node·DMI·아키텍처별 Mapper 이미지 | 등록과 실제 연결 성공은 다름; 연결 테스트는 대상 Mapper에서 항목 하나를 읽음 |
| 측정 항목 추가·삭제 | 기존 Mapper 장치와 호환 항목 규격 | 연결 설정을 재사용; 마지막 항목 삭제는 장치 등록도 삭제 |
| 매 수집 데이터 전달 | Mapper에서 접근할 MQTT broker | QoS 1 발행; 영속 재전송·이력 consumer는 별도 구현 필요 |
| 연속 가상 센서 등록·설정 | 센서 입력과 `EDGEAI_CONTINUOUS_ENABLED=true` | 내부 v3·공유 eKuiper 방식; 센서 등록만으로 자동 생성하지 않음 |
| 연속 계산 결과·그래프 | 유효 입력·processing Agent·eKuiper·엔진 lease | 계산 결과별 행과 generation·신선도를 확인; 설치/등록과 실제 실행은 다름 |
| 연속 결과 보관 이력 | 전용 history PostgreSQL과 활성 엔진 | 계산 결과 보관만 담당; 원본 MQTT 장기 저장과 다르고 저장 누락은 구간으로 표시 |
| Mapper 장치의 BATCH 입력 | ONLINE 장치와 존재하는 property, 활성 Workflow API | Run 접수 시 최신값 고정; 연속 STREAM 입력으로 자동 연결하지 않음 |
| DDS 배포 | Platform-Service·Gitea·Argo CD·이미지 | 캔버스 연결선은 실행 의존성이 아님 |
| Spring DAG 실행 | Runtime·Runner·저장소·클러스터 | 요청 접수와 검증된 Result는 다름 |
| Remote | 설정된 참조 제공자와 고정 계약 | 실제 외부 업체 API·상태형 복원 수용 별도 |
| STREAM | 별도 broker·권한·연결·실행 활성화 | 기본 비활성; 새 Mapper MQTT 경로의 자동 source binding은 없음 |
| 백업·복구 도구 | 도구별 버전·신원·격리 조건 | 개별 단계 성공은 전체 재가동이 아님 |

설계 원칙과 화면·API의 대응은 [원 설계 대조](design-alignment.md)를 확인합니다.

지원 프로토콜은 Modbus TCP, OPC-UA, MQTT 입력, HTTP Virtual, Serial JSON, Sense HAT v1입니다.
OPC-UA는 Anonymous/None, MQTT 입력은 인증 없는 TCP broker, Virtual은 HTTP JSON endpoint를 사용합니다.
Serial JSON은 정해진 프레임 규격을, Sense HAT은 ARM64 Raspberry Pi의 v1 보드를 요구합니다.
임의 프로토콜·보드의 자동 발견, 장치 명령 쓰기, 인증·TLS 확장은 구현되지 않았습니다.
기존 `/api/v1/sensors/*` API는 410 `SENSOR_API_REPLACED`로 종료됐습니다.
구형 VD v1/v2 프로필·표본/FFT BATCH·supervisor·poll과 Workflow의 VD 실행 정책은 종료됐습니다. 연속 처리의 기본 flag는 false이며 운영 Agent·history·qualified 입력 배포는 별도입니다. [현재 상태](../../PROGRESS.md)와 [연속 센서 안내](../guides/vd-processing.md)를 확인합니다.
세부 설정은 [센서 등록](../guides/sensor-registration.md)과 [Mapper 안내](../../deploy/kubeedge/README.md)를 따릅니다.

## 현재 개발 경계

관리 화면과 API 완성을 우선하고 Runner/STREAM 고도화는 보류 중입니다.
실제 GPU/NPU 모델, 외부 시스템 전체 계약, 성능 수용 기준, 사용자별 권한과 종합 복구 검증은
[개발 단계](../../PLAN.md)와 [현재 상태](../../PROGRESS.md)에서 남은 범위를 확인합니다.

특정 날짜의 장비 수, 시험 개수, CI 성공, 배포 digest는 해당 Git 이력의 검증 기록 조건에만 적용합니다.
이 프로젝트를 CNCF 소속·인증 프로젝트로 주장하지 않습니다. 문서 구성만 공개 프로젝트의 안내 방식을 참고합니다.
