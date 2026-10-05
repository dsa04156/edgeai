# ADR 0114: 독립 기록에서 STREAM 완료 이력 복원

2026-10-05. [실제 복원·업그레이드·회귀 근거](../evidence/m9-stream-completion-recovery.md)에
통과한 범위와 남은 실패를 구분한다.

## 문제

DB 백업 이후에 STREAM 그룹이 완료되면, 독립 저장소에 Result가 있어도 복원 DB에는
완료 허가와 checkpoint가 없을 수 있다. Result만으로 허가를 추정할 수 없으므로
ADR0110의 원래 완료 문서와 ADR0111의 모든 선행 checkpoint receipt를 먼저 반영한다.

## 결정

`recovery_stream_completions.py`는 한 연결 그룹의 원래 완료 ID를 받는다. 원래 Run,
Task/Attempt/runtime, 경로 정의와 Device Session 고정 이력은 복원 DB에 이미 있어야 한다.
명령은 원본과 다른 저장소의 고정 version, 원래 시작 허가, checkpoint bytes와 실행 계약,
모든 member의 같은 grant 시각, 실제 Pod 종료 및 현재 broker 차단을 대조한다.
백업 이후 생성되어 DB에 없는 실행이나 독립 증거가 없는 선행 generation은 추정하지 않는다.

V39는 두 불변 이력 trigger에 제한된 INSERT 경로를 추가한다. `edgeai_restore_` DB 이름,
복원 marker와 OID, 현재 transaction ID 및 개인 temporary scope의 정확한 행이 필요하다.
일반 INSERT/UPDATE/DELETE/TRUNCATE 제약은 유지한다. 이 scope는 검증한 복구 운영자의
입력이며 PostgreSQL 자체가 독립 저장소의 진위를 인증하는 것은 아니다.

원래 generation의 활성화·lease·신원 시각은 유지하되 복원 transaction 시각에 CLOSED로
삽입한다. 원래 checkpoint는 ID·시각·serial·이전 receipt·handover·고정 파일 참조까지
그대로 삽입한다. Task/Device 완료 보고와 grant는 원래 시각을 유지한다. 완료 publication은
원래 문서를 그대로 저장하고 독립 이력 보존 완료를 표시한다. 복원 시각을 원래 완료 문서로
다시 발행하지 않는다. SQL 문서의 UTC offset은 비교 시에만 정규화한다.

V40은 이 복원 INSERT에서 기존 heartbeat 초기화 trigger를 건너뛴다. 완료 문서에는 마지막
lease만 있고 원래 heartbeat window와 중간 관측값은 없기 때문이다. V39 실제 시험에서
V21 trigger가 존재하지 않았던 heartbeat를 만드는 것을 확인했다. 이미 적용한 V39는
수정하지 않는다. 일반 generation의 heartbeat 초기화는 그대로 유지하며, 복원된 CLOSED
generation은 heartbeat 권한 확인에서 거절되어야 한다.

관측 후 원본 증거를 다시 읽고, 관련 테이블 잠금과 전체 행 digest 대조를 거쳐 하나의
transaction으로 반영한다. 중간 오류는 모두 rollback한다. COMMIT 응답 유실은 rollback으로
표현하지 않으며 같은 증거를 새 output에서 다시 관측한다. 이미 반영된 이력은 변경하지 않는다.

## 후속 절차와 한계

완료 이력 복원 뒤 기존 NODE/VD STREAM Result 복원 명령을 실행한다. 두 Result가 모두
복원되기 전에는 BATCH 후속 작업을 준비하지 않는다. 이 명령은 새 runtime이나 MQTT 권한을
발급하지 않고 DB·namespace 격리를 유지한다.

누락된 실행 자체의 복원, 증거가 없는 선행 generation, 전역 writer/API 차단, 종합 복구
활성화와 실제 모델·외부 계약 수용은 별도 작업이다. 이 기능의 성공은 전체 M9 또는 M0–M10
완료 판정이 아니다.
