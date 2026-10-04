# ADR 0088: 복원 Device journal과 DB의 읽기 전용 일관성 검사

상태: 채택. 2026-10-04.

ADR0087의 장치 snapshot에는 미확인 프레임과 센서 상태가 있지만, 다른 시점에 복원한
PostgreSQL의 소비자 checkpoint와 일치한다는 보장은 없다. 원래 ACK를 받고 삭제한
데이터가 복원 소비자에 없거나, 소비자가 오래된 장치 snapshot보다 앞서 있을 수 있다.

복원 DB의 OID/marker/영수증과 격리 journal의 marker/영수증/SHA를 고정하고 원래 Run의
Device pin·전체 경로·generation·최신 소비자 Attempt/체크포인트를 대조한다. 경로별
ACK/수신/확정/END 순번과 완료 intent도 검사한다. 진행 중 offload는 별도 조정이 필요하다.
읽기 전용 repeatable-read 관측을 두 번 수행하고 관계된 18개 테이블의 전체 행 해시와
journal 증거가 달라지면 결론을 폐기한다.

ADR0087의 snapshot 추출에 내부 전용 격리 읽기 옵션을 추가한다. 공개 백업은 기존처럼
격리 source를 거절한다. Journal 실행 경로의 격리 차단과 SQLite schema는 변경하지 않는다.
새 CLI는 상태/충돌 보고서만 작성하며 DB와 source·marker를 변경하지 않는다.

검사가 통과해도 checkpoint 객체의 바이트·원본 장치 종료·브로커 권한은 검증되지 않는다.
이 상태를 `DEVICE_JOURNAL_METADATA_MATCHED`로 명명하고 검증되지 않은 항목을 명시한다.
자동 활성화나 세션/경로의 재할당은 이 결정의 범위가 아니다.

실제 공개 API/PG 복원 3개·암호화 journal 원본 삭제·43개 테이블 및 journal 파일 보존을
[시험](../evidence/m9-recovery-device-journal.md)한다. 런타임/broker/checkpoint receipt는
제약조건을 유지한 SQL fixture임을 구분한다. V1–V34와 Java/Runner 실행 코드는 불변이다.
