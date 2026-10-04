# ADR0059 — PostgreSQL 백업과 새 DB 복원

2026-10-04. M9의 DB 복구 구성 요소이며 전체 플랫폼 재해 복구 완료를 뜻하지 않는다.
[실행 지시](https://app.notion.com/p/3ebbafd382d681bd920ae91452b0463a)의 M9는 Recovery /
Security / Backup을 요구한다. 백업 주기·보관 기간·RPO/RTO·외부 신원 제공자는 미정이다.
기존 M5/M7 수용 범위를 유지하고 독립적으로 검증할 수 있는 DB 복원부터 구현한다.

단일 DB의 전체 스키마·데이터·시퀀스·제약·Flyway 이력을 PostgreSQL custom archive로
저장한다. pg_dump의 일관된 스냅샷은 동시 접근을 지원하지만 S3와 broker의 원자적 스냅샷은
제공하지 않는다. 역할·tablespace 같은 cluster 전역 객체도 이 백업에 포함되지 않는다.
[PostgreSQL pg_dump](https://www.postgresql.org/docs/16/app-pgdump.html).

백업 폴더는 새 이름으로만 생성하며 0700, archive/manifest/진단 로그는 0600이다.
dump 성공·stderr 없음·archive 목록 파싱·SHA-256 계산 후 manifest를 마지막으로 쓴다.
경고가 있으면 성공을 반환하지 않고 비공개 로그를 보존한다. 부분 폴더는 덮어쓰지 않는다.
비밀번호는 환경으로 전달하고 SQL 데이터/서버 오류 원문은 공개 시험 로그에 출력하지 않는다.
DB에 저장된 민감한 열도 dump에 들어가므로 DB 백업은 비밀 자료로 취급한다.

복원은 새 `edgeai_restore_*` DB만 허용하고 기존 DB가 있으면 거절한다. 파일 크기·SHA-256·
파일 자체의 symlink/권한·archive 목록을 먼저 검사한다. 현재 지원 범위는 PostgreSQL16 이상,
같은 major의 서버/pg_dump/pg_restore와 libc locale다. 데이터베이스 encoding/collation/ctype를
보존하며 다른 major/locale provider는 명시적으로 거절한다. native의 `--pg-bin` 또는
고정 edgeai-dev Compose 컨테이너의 일치하는 클라이언트를 선택한다.

`pg_restore --single-transaction --exit-on-error --no-owner --no-privileges`로 새 DB에 복원한다.
소유권과 ACL은 원본 그대로 복원하지 않는다. 이는 역할·인증·접근 통제를 별도 복구해야 한다는
의미다. 복원은 archive의 SQL을 실행하므로 신뢰하는 출처의 백업만 사용한다. SHA는 손상
검출용이며 발신자 인증이 아니다.
[PostgreSQL pg_restore](https://www.postgresql.org/docs/16/app-pgrestore.html).

실패 시 이번 명령이 생성했고 OID가 일치하는 새 DB만 삭제한다. 정리에 실패하면 비공개
restore report에 기록한다. 강제 연결 종료나 기존 DB 삭제는 하지 않는다. 프로세스/호스트
강제 종료 시 자동 정리를 보장하지 않으며 고유 DB 이름과 report로 남은 자원을 식별한다.

복원 성공은 `RESTORED_DB_ONLY`다. API 설정 변경, worker 활성화, 원래 서비스로 전환은
실행하지 않는다. S3 고정 version/bytes, checkpoint, 외부 Secret/키, broker·장치 journal,
이미 실행 중인 Pod/Remote와 DB 상태의 일치 확인을 선행해야 전체 서비스를 복구할 수 있다.
DB만 과거 시점으로 되돌린 후 worker를 활성화하는 절차는 제공하지 않는다.

검증은 전용 DB와 loopback API를 사용한다. 공개 API로 생성한 합성 Profile·Device·세션·관측·
Workflow·Run을 백업하고, 새 DB의 모든 edgeai 테이블 내용·API 응답·불변 제약을 대조한다.
백업 후 새 쓰기는 복원에 나타나지 않아야 한다. 손상/기존 DB/파일 권한 거절 및 실제 복원
제약 오류 이후 소유 DB 정리를 검증한다. 오류 없는 dump에 대한 stderr 주입은 별도 fixture다.
[실행 방법](../postgres-backup.md), [전체 M9 범위](../m9-requirements.md).
