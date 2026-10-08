# 용어집

| 용어 | 의미 |
|---|---|
| ProfileVersion | 종류·키·버전으로 식별하는 발행 규격 |
| Device | EdgeAI 앱에 등록한 입력 장치의 신원 |
| Mapper Device | KubeEdge Device CRD 이름으로 식별하는 수집 장치; UUID 앱 Device와 별도 |
| DeviceModel | 장치 property 인터페이스를 정의하는 KubeEdge CRD; 연결 정보는 Device별 설정 |
| DeviceStatus / DeviceTwin | KubeEdge가 보고하는 장치 연결 상태·heartbeat·최신 property 값 |
| DMI | EdgeCore와 Mapper 사이의 장치 설정·상태 보고 인터페이스 |
| Mapper | 특정 프로토콜의 장치를 읽는 실행체; 노드·프로토콜별로 여러 장치가 공유 |
| Property | 장치의 측정 항목 하나와 자료형·단위·읽기 위치 규격 |
| Data plane | 매 수집 데이터를 별도 MQTT topic으로 소비자에게 전달하는 경로 |
| EdgeX Device (과거) | 제거된 EdgeX 실행 계층의 논리 장치; 과거 설계·검증 기록에서 사용하는 용어 |
| ExecutionNode | 실행과 연결하는 Kubernetes 노드 관측 |
| VD / VirtualDevice | 원본 장치 연결과 SERVICE 규격을 묶은 가상 장치 등록 |
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
