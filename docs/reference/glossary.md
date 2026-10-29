# 용어집

| 용어 | 의미 |
|---|---|
| ProfileVersion | 종류·키·버전으로 식별하는 발행 규격 |
| ResourceProfile(자원 프로파일) | SERVICE 버전의 CPU·메모리·GPU/NPU 요구량·허용 범위 불변 revision; 시간 기반 SchedulingProfile(성능 프로파일)과 별개이며 자원 예약이 아님 |
| 자원 분석(ResourceAnalysis) | 대상 워크로드·구간의 Prometheus 실측·장치 관측·KRR 추천을 서버가 저장한 불변 근거 |
| 장치 귀속(attribution) | 장치 지표가 서비스 단독 사용인지, 공유·소유 미확인의 노드 장치 전체 값인지의 구분 |
| Device | 현재 센서 관리에서는 측정 항목별 KubeEdge Device CRD; 과거 UUID 앱 Device는 별도 이력 신원 |
| Mapper Device | KubeEdge Device CRD 이름으로 식별하는 수집 장치; UUID 앱 Device와 별도 |
| DeviceModel | 장치 property 인터페이스를 정의하는 KubeEdge CRD; 연결 정보는 Device별 설정 |
| DeviceStatus / DeviceTwin | KubeEdge가 보고하는 장치 연결 상태·heartbeat·최신 property 값 |
| DMI | EdgeCore와 Mapper 사이의 장치 설정·상태 보고 인터페이스 |
| Mapper | 특정 프로토콜의 장치를 읽는 실행체; 노드·프로토콜별로 여러 장치가 공유 |
| Property | 장치의 측정 항목 하나와 자료형·단위·읽기 위치 규격 |
| VD 측정 항목 선택 | VD 원본 센서에서 작업에 사용할 측정 항목과 허용 수신 경과 시간 |
| VD 센서 입력 스냅샷 | 실행 요청에서 고정한 작업별 원본 신원·선택 측정값·수신 시각; 재호출·재시도에도 유지되는 입력 |
| Data plane | 매 수집 데이터를 별도 MQTT topic으로 소비자에게 전달하는 경로 |
| EdgeX Device (과거) | 제거된 EdgeX 실행 계층의 논리 장치; 과거 설계·검증 기록에서 사용하는 용어 |
| ExecutionNode | 실행과 연결하는 Kubernetes 노드 관측 |
| VD / VirtualDevice | 현재는 여러 센서 입력과 계산을 등록한 CONTINUOUS_EKUIPER 가상 센서; 구형 supervisor VD는 종료 |
| eKuiper | 공유 중앙 스트림 계산 엔진; 설치와 실제 입력·규칙·계산 활성화는 별도 |
| processing Agent | Mapper 관측 입력·eKuiper 규칙·세대·결과 전달을 조정하는 중앙 실행체 |
| NanoMQ | 센서 노드의 MQTT 출력 broker; 일반 STREAM Mosquitto broker와 구분 |
| Qualified observation | 원본 신원·설정 digest·세션 순서를 검증할 수 있는 연속 계산 입력 계약 |
| 연속 출력 | VD 계산 결과 하나; 출력별 최신값·그래프·보관 이력, 별도 CRD는 생성하지 않음 |
| Binding | 장치·원본·실행 간 연결과 그 수명 기록 |
| Revision | 등록 리소스의 동시 변경을 검사하는 버전 |
| WorkflowVersion | 발행한 Spring DAG의 불변 버전 |
| Run | 특정 워크플로 버전의 실행 요청 |
| Task | DAG의 개별 작업 |
| Attempt | 작업의 특정 실행 시도; 재시도·전환 시 새로 생성 |
| RuntimeInstance | 실제 실행과 연결하는 신원·수명 |
| Generation / Epoch | 교체 전후 실행·연결의 유효 범위를 구분하는 세대 |
| Lease | 실행 주체에게 부여한 시간 제한 권한 |
| Operation | 기동·교체·drain·전환 등 비동기 관리 작업 |
| Result | 실행 주체와 파일 내용을 검증해 확정한 결과 |
| Idempotency-Key | 동일 요청 재전송과 별도 요청을 구분하는 키 |
| Digest | 규격·이미지·파일 내용을 식별하는 해시 |
| Allocatable | Kubernetes가 작업에 할당 가능한 총량; 현재 여유량 아님 |
| Freshness | 원본 시각과 수집 상태를 기준으로 값의 신선도를 판정하는 조건 |
| GitOps | Git의 배포 상태를 컨트롤러가 클러스터에 반영하는 방식 |
| Quarantine / Fence | 복구 중 기존 실행·쓰기·접속을 제한하는 격리 또는 차단 |

개념 간 관계는 [리소스 모델](../concepts/resources.md)과 [워크플로 모델](../concepts/workflows.md)을 확인합니다.
