# 인프라와 센서 목록

노드는 Kubernetes에서, 센서는 KubeEdge Device/DeviceStatus CRD에서 조회합니다. Dashboard 인프라 관리의 센서 탭에서 지원 프로토콜의 연결과 property를 등록하고 측정 항목을 행별로 확인합니다.

Modbus TCP·OPC-UA·MQTT 입력·HTTP Virtual·Serial JSON·Sense HAT v1을 지원합니다.
기존 장치의 **항목 추가**는 저장한 규격을 재사용하며 **새 장치 등록**과 구분합니다.
연결 정보·항목 설정·추가 및 삭제 순서는 [센서 등록](sensor-registration.md)을 따릅니다.

[등록·데이터 경로·실행 권한·검증 안내](../../deploy/kubeedge/README.md)를 따르세요. GPU·CPU 자원 사용량은 [Prometheus 연결](node-metrics.md)을 사용합니다.

기존 앱 UUID 장치 등록과 CRD 센서는 ID 체계가 다릅니다. 현재 Workflow의 CRD 입력은 장치 이름과 property 이름을 사용하는 최신값 BATCH 입력입니다. 기존 UUID 기반 STREAM 세션과 자동으로 같은 자원으로 연결하지 않습니다.
