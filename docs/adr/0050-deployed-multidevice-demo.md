# ADR 0050: 운영 TLS 연결과 배포된 다중 장치 데모

2026-10-04 KST. ADR0048 HTTPS 이미지533d850의 CI37131722732와 실제 배포 확인 뒤 ADR0049의
영속 TLS 컴포넌트를 dev overlay에 연결한다. Run/Runtime/VD 활성 상태가0인지 확인하고,
MinIO 기존 파일10개·고정 버전·체크섬과 두 기존 data PVC UID를 기준으로 전환 후 보존을 검증한다.
API는 Recreate·단일 writer를 유지한다. 새로운 Flyway/도메인 계약은 없다.

## 실제 배포를 쓰는 데모

`scripts/demo/demo-multidevice.sh <context>`는 기존 배포의 API·DB·broker·MinIO를 사용한다.
고유 소유 label의 ConfigMap/합성 장치 driver Pod만 만들고 공개 관리 API·DeviceRunSource SDK로
Profile/장치/세션·워크플로·Run을 생성한다. SOURCE는 SYNTHETIC이며 실제 물리 장치로 표기하지 않는다.

공유 `stream_acceptance.py`는 기본5개 장애 수용 시나리오를 유지하고, 데모에서는 AUTO/NODE/cancel
3개를 명시한다. 두 장치 값4/5와2/3이 실제 STREAM → STREAM → BATCH로 전달되어14/23/37이
되어야 한다. 읽기 전용 DB 관측으로 checkpoint 상태9,14,23을 확인하고 실제 Runner Pod·노드UID·
고정 이미지와 Result producer 신원을 대조한다. 마지막으로 고정 S3 object version을 내려받아
bytes/SHA-256 및 예상 JSON을 검사한다. 공유 API/broker/저장소의 재시작은 이 데모가 하지 않는다.

Run 생성 멱등 UUID는 밖에서 먼저 정한다. 요청 응답 유실/driver 실패 뒤에도 읽기 전용 DB 조회로
이번 UUID의 Run만 찾아 공개 취소를 요청하고, terminal 상태·닫힌 경로·Pod/Job/Secret 회수를 기다린다.
driver 자원 삭제도 namespace·소유 label·UID를 확인한다. 합성 API 이력과 결과 파일은 보존한다.
실패 진단에는 공개 상태·소유 UID만 남기고 토큰/비밀번호/인증 헤더나 workload payload는 기록하지 않는다.

## 검증과 경계

MinIO는 별도 임시 Pod에서 실제 컴포넌트의 읽기 전용 TLS Secret 마운트·고정 이미지·임시 data로
기동하고 TLS health, 인증 S3 왕복256KiB, 익명403과 정리를 검증한다. HTTP 기본 probe도 유지한다.
kind CI에는 신원 복구·영속 broker Pod 교체·MinIO TLS 기동 뒤 실제 base 배포에 컴포넌트를
적용하고 같은 다중 장치 데모를 실행하는 게이트를 추가한다. 검증 없는 dummy 결과로 대체하지 않는다.

API 인증서 검증을 끄지 않는다. 외부 장치 라우팅, 스트리밍의 실행 중 offload/REMOTE/VD,
인증서 교체·전원 손실 복구·M8 부하·M9 전체 운영·M10 실장비는 각각 남은 수용 범위다.
이미 존재하는 MQTT 간헐 실패의 원인 해결을 이 정상 데모로 판정하지 않는다.

[실행 근거](../evidence/m7-deployed-multidevice-demo.md).
