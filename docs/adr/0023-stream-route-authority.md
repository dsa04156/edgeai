# ADR0023 — 영속 DataRoute와 실행 세대의 권한

상태: 내부 제어 상태 구현·실제 PostgreSQL 검증 완료. 공개 STREAM Run·broker 계정 발급·실제 Runner 연결은 후속 게이트다.
[원문 요구사항](../m7-requirements.md)과 ADR0021–0022의 메시지/처리 기록을 제어 상태에 연결한다.

논리 DataRoute는 한 Run의 특정 Task 입력 port와 Task 출력 또는 Device 출력의 연결이다.
Task 간 연결은 같은 불변 DAG의 STREAM edge여야 한다. Device 연결은 별도 source port와
불변 Profile 버전·LIVE/REPLAY/SYNTHETIC 출처를 보존한다. 경로 저장만으로 실제 센서 payload
규격이나 adapter를 검증한 것으로 보지 않는다. 해당 검사는 공개 입력 배정 계약에 추가해야 한다.

실제 producer DeviceSession/TaskAttempt와 consumer TaskAttempt, 각각의 epoch는 별도
RouteGeneration에 저장한다. logical route ID는 유지하고 generation을 증가시킨다. 한 경로에는
아직 권한 회수를 확인하지 않은 세대가 최대 하나다. 세대는 PREPARING→ACTIVE→FENCED→CLOSED,
또는 PREPARING→FENCED→CLOSED로 진행하고 닫힌 이력은 수정/삭제하지 않는다.

생성 요청 ID·digest로 재전송을 복구한다. broker 설정 digest와 요청한 정확한 주체/경로의
policy digest는 불변이다. grant 확인은 같은 세대/설정/정책을 가리켜야 하며, fence 이후의 늦은
grant 확인으로 되살리지 않는다. 다음 세대는 별도 revoke 확인으로 이전 세대를 닫은 뒤에만 열린다.
broker 확인은 신뢰된 adapter의 내부 입력이며 단순 문자열 일치가 외부 권한 인증을 대신하지 않는다.

lease는5–120초이고 현재 주체가 유효할 때만 만료 전에 갱신한다. Device 재접속·해제, Task Attempt
종료·교체, Run 취소/종료, lease 만료는 데이터 사용 권한을 잃게 한다. 이 검사는 내부 제어 상태
검사이며 Pod-bound/Device 토큰 인증과 MQTT ACL을 별도로 요구한다. 소비자가 STREAM 배정을
계속 사용하려면 이 lease를 실제 adapter에서 지켜야 한다. 해당 연결 전까지 공개 STREAM은501이다.

잠금 순서는 기존 VD 수정의 VD→Device 순서를 보존해 선택적 VD→Device→Run→Route로 한다.
Task↔Task는 같은 Run을 요구한다. 관계 FK, epoch, 한 입력의 한 route, 한 열린 세대, 단조 증가와
불변성은 PostgreSQL에서 검사하고 실제 동시 요청/세션 변경/취소·재전송으로 검증한다.
관리 DB에는 payload나 MQTT 비밀번호를 저장하지 않는다. S3 checkpoint·ACK의 외부 내구성,
route 변경 시 재생 위치/일관성은 별도 구현이며 이 제어 상태만으로 복구 완료를 주장하지 않는다.

V19는 논리 경로/주체 FK·불변 세대·상태 전이·현재 실행 주체 검사를 추가한다. 서비스의 잠금이
장치/실행 상태 변경과 직렬화하며, 직접 SQL 사용자는 같은 잠금 계약을 지켜야 한다.
브로커 응답과 Task RUNNING을 명시적 fixture로 둔 실제 DB12개 및 기존 회귀는
[검증 기록](../evidence/m7-stream-routes.md)을 따른다. 주기적인 reconciliation worker는 아직 없다.
