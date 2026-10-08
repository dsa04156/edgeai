# 0115 — KubeEdge CRD/DMI/Mapper를 장치 실행 계층으로 사용

날짜: 2026-10-07. 결정: 채택. 사용자의 명시적 구현 목표가 앞선 기술 비교와 전환 보류를 대체한다.

## 결정과 이유

Device Manager는 사용자 Device Profile을 검증하고 DeviceModel/Device CRD로 컴파일한다. 실행 설정 및 관측 상태의 원본은 KubeEdge CRD이며 앱 DB에 경쟁하는 장치 상태를 만들지 않는다. 인터페이스 해시로 DeviceModel을 재사용한다. 연결 주소와 protocol visitor는 Device 인스턴스에 둔다.

노드/프로토콜마다 shared Mapper Deployment 하나가 여러 장치를 처리한다. 수집기에 Kubernetes 토큰을 주지 않고 공식 KubeEdge 1.23 Mapper Framework callback과 DMI RPC를 사용한다. 기존 프로토콜의 장치 추가는 설정 변경이며 새 프로토콜만 Driver 구현이 필요하다.

Modbus를 먼저 실제 API·TCP fixture·DeviceTwin·MQTT·Workflow 경로로 검증한 뒤 OPC-UA, MQTT 입력과 HTTP JSON 기반 Virtual Runtime을 확장했다. Physical과 Virtual은 동일 Device CRD/property 인터페이스를 사용한다. 기존 UUID 앱 Device/VD 모델과 CRD 이름을 암묵적으로 합치지 않는다.

상태/최신값은 DMI→DeviceTwin으로 보고하고 매 수집 데이터는 MQTT topic으로 분리한다. Workflow Run의 `device: {id, outputs}`는 CRD 최신값을 protocol-free BATCH 입력으로 고정한다. idempotent 재조회는 같은 값을 유지하며 OFFLINE/없는 property는 거절한다. 연속 STREAM Route 자동 구성과 영속 DB consumer는 범위 밖이다.

## 호환성과 운영 제약

설치된 Device CRD에는 upstream의 `spec.stateReport`가 없어 해당 필드를 내보내지 않는다. property 보고 설정과 DMI 상태 보고를 사용한다.

KubeEdge 1.23 DMI server는 노드 전체 RPC를 초당 1회로 제한한다. 여러 프로토콜의 반복 보고로 최초 burst가 소진되는 현상을 실제 검증에서 발견했다. shared hostPath의 파일 lock으로 미래 요청 시간을 순서대로 예약하며, 장치별 비동기 큐는 최신 보고만 유지한다. 센서 수집과 MQTT 발행은 DMI 대기로 막히지 않는다. Twin latency는 노드 장치 수와 요청 예산에 제약된다. 임의 규모의 성능을 검증했다고 주장하지 않는다.

후속 장애에서 DMI socket의 호스트 inode와 Mapper 파일 mount inode가 다른 것을 확인했다. 기존 Pod 재생성으로 연결을 복구하고, 지속적인 DMI transport 실패가 90초 이상 이어지면 liveness를 실패시켜 컨테이너 재시작을 유도하도록 추가했다. 일시 단절과 서버 응답 오류는 제외한다. 실제 stale Unix socket 회귀 시험은 포함하며, 같은 Pod의 자동 재시작 전체 경로는 아직 실측하지 않았다. 사용자의 후속 지시에 따라 별도 검증 웹/API를 종료하고 기존 13080/18080에서 작업한다.

OPC-UA Anonymous/None, 인증 없는 MQTT TCP 입력 및 HTTP Virtual endpoint만 현재 지원한다. 미지원 인증/보안 설정을 조용히 낮추지 않고 거절한다. MQTT 영속 outbox 및 production hardware의 Serial/I²C Driver는 아직 없다.

## EdgeX 종료

사용자의 최종 삭제 지시에 따라 Modbus cutover 확인 후 EdgeX 실행 리소스·어댑터·배포 파일을 제거했다. fresh PostgreSQL dump와 구성 백업을 별도 디스크에 저장하고 dump 목차를 검증했다. 기존 Serial Arduino/Sense HAT 수집은 중단됐다. 다른 ArgoCD AI demo의 PVC는 EdgeX 구성요소가 아니므로 보존했다. 종료된 sensor API는 410으로 새 Device Manager 경로를 안내한다.

[실행 안내](../../deploy/kubeedge/README.md)
