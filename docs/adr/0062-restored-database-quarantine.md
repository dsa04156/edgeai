# ADR0062 — 복원 DB 격리와 조회 전용 API 점검

2026-10-04. ADR0061의 파일 대조만으로 과거 DB의 실행 권한을 되살릴 수 없다.
현재 worker는 DB의 미완료 명령·runtime·broker 상태를 재조정하므로, 복원 뒤 일반 API가
기동하는 것을 먼저 막는다. 원래 producer 종료/권한 회수와 운영 활성화는 후속 절차다.

DataSource 초기화 시 PostgreSQL의 DB 이름과 comment를 직접 조회한다. `edgeai_restore_*`
이름 또는 `edgeai-restore:` comment가 있으면 일반 기동을 거절한다. Flyway가 꺼져 있어도
이 검사 자체는 실행한다. 조회 실패는 기동 실패로 처리하고 초기화 중 거절한 Hikari pool은
닫는다. 기존 일반 DB의 migration과 실행 경로는 그대로 유지한다.

`EDGEAI_RECOVERY_INSPECT_ONLY=true`는 복원 DB의 조회 점검만 허용한다. 유효한 임의
restoreIdentity marker가 필요하고 Runtime/VD/Remote/Stream·binding·run·Kubernetes
inventory 설정을 모두 꺼야 한다. Flyway는 끄지 않고 `validate()`만 실행한다. 적용할
migration을 실행하거나 history를 쓰지 않는다. 유효한 현재 schema가 아니면 점검 기동도 실패한다.

점검 모드의 Hikari 연결은 생성할 때 `default_transaction_read_only=on`과 JDBC readOnly를
설정한다. 모든 새 연결에 적용하며 기동 시 실제 세션 설정도 확인한다. HTTP 계층은 인증을
계속 요구하고, 유효한 Basic/CSRF가 있어도 쓰기 요청을403으로 거절한다. 내부 실행 endpoint는
활성화하지 않는다. custom connection-init SQL을 임의로 덮어쓰는 대신 해당 설정은 거절한다.

실제 PostgreSQL 시험은 별도 소유 DB에서 두 연결의 autocommit/transaction 쓰기가 SQLSTATE
25006으로 거절되는지 확인한다. 실제 패키징 API 시험은 일반 기동·Flyway 비활성 우회·잘못된
원본 DB의 점검을 거절하고, 복원 데이터 조회·인증된 쓰기403·전체 테이블 불변을 확인한다.
기본 기동이 계속 가능한지도 기존 PostgreSQL 회귀로 검증한다.

이 모드는 복원 DB를 안전하게 조회하는 구성 요소다. DB 관리자에 의한 marker 변경을 막는
권한 체계나 운영 활성화 토큰을 구현한 것은 아니다. 고정 S3 참조·Secret/CA·broker/device
journal·원래 외부 작업의 중지와 권한 회수·중복 실행 방지까지 검증한 활성화 절차가 필요하다.
완료 전에 marker 삭제/DB 이름 변경으로 기동 차단을 우회하지 않는다.
