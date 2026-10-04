# ADR 0091: 원본 브로커 차단 후 복원 STREAM 경로 종료

상태: 채택. 2026-10-04.

복구 중 원본 MQTT를 차단해도 복원 DB의 PREPARING/ACTIVE/FENCED generation은 그대로
남는다. 과거 DB 상태로 grant worker를 재가동하면 원래 권한을 재발급할 수 있으며,
일반 STREAM 재시도는 이전 경로가 CLOSED일 때까지 진행하지 않는다.

같은 복구 UUID·broker digest·TLS 신원으로 현재 원본 브로커의 marker·기본 deny·전체
계정 disabled·기존 관리자 거절을 실제로 확인한다. 정확한 복원 DB의 해당 broker generation
전체를 고정하고, 22개 관련 테이블의 변경 guard와 잠금 아래 fence→close를 한 transaction으로
반영한다. 기존 fence 이유와 시각은 유지하고 새 fence에만 REPLACED를 기록한다. 기존
DB lifecycle trigger와 불변 제약은 계속 활성화한다.

현재 broker 관측과 DB inventory를 실행 직전 및 COMMIT 이후에 다시 확인한다. 실제 SQL
중간 실패는 모두 원복한다. COMMIT 응답 유실 또는 이후 권한 변경은 완료라고 말하지 않고
격리·intent를 보존한다. 같은 복구 UUID와 새 output으로 재관측·재실행하면 이미 종료한
generation은 변경하지 않는다. 다른 broker의 열린 generation은 미해결로 보고하고 보존한다.

이 단계는 broker 권한 차단을 복원 DB 경로 상태에 연결한다. Run/Task/Attempt 결과,
checkpoint·처리/완료 이력, heartbeat·원본 Device session, runtime·기존 명령은 바꾸지 않는다.
장치/Task 물리 종료나 종합 중지를 증명하지 않으며 새 generation/grant·Secret·재시도·offload·
서비스 활성화는 생성하지 않는다. 복원 DB marker와 원본 broker 차단은 그대로 유지한다.

실제 PG 복원본/TLS Mosquitto에서 연결 회수 뒤 권한 재활성화, DB 관측 경쟁, 실제 잠금
경합·transaction rollback·COMMIT 응답 유실·반복 실행·타 broker 보존으로 검증한다.
기존 Device/DB/S3/원본 owner 결합 시험도 같은 실행에서 유지한다. 서버 JAR·V1–V34는 불변이다.
