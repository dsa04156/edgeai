# M9 복원 Device journal과 DB 대조 검증

2026-10-04, ADR0088. 실제 16개 `20261004T142409Z-b11d1b61` PASS/0.
보고서 `.tools/recovery-device-journal-verified.json`.

패키징 API로 SERVICE/DEVICE Profile, Device Session, Workflow와 공개 STREAM Run을
등록했다. 고정된 동일 장치의 fanout 2개를 사용한다. 런타임 claim·broker 활성화·checkpoint
접수만 제약조건을 유지한 SQL fixture다. 소비자 checkpoint 내용은 실제 EXTERNAL Journal과
snapshot 캡처로 생성했지만 S3 객체는 이 시험에서 생성/검증하지 않는다.

서로 다른 시점의 PostgreSQL을 새 DB 3개에 pg_restore하고 원본 DB를 삭제했다. 실제 SQLite
장치 source를 age 암호화한 뒤 원본 디렉터리를 삭제하고 격리 복원했다. 정상 비교에서
경로별 ACK 1/2, 소비자 확정 2, 장치 생성 3과 재전송 참고 위치 3이 일치했다.

검사한 거절 경계는 다음과 같다.

- 소비자보다 앞선 장치 ACK와 이미 삭제한 데이터, 장치보다 앞선 소비자 순번.
- checkpoint 부재 시 재전송 데이터 누락, 일부 fanout 경로만 복원한 상태.
- 다른 Device Session, DB에 없는 generation, 다른 Run.
- END/완료 intent와 실제 DB 완료 기록이 다른 상태, 종료된 Session.
- 다른 DB의 복원 영수증과 실제 DB marker 교체.
- 두 관측 사이의 실제 DB 행 갱신, 격리 marker 파일 교체, offload 삽입.
- DRAINING/CANCELLING 전환 중의 비교와 검사 성공 후 producer 실행 시도.

각 정상/충돌 비교 전후 DB 43개 테이블의 해시와 journal 디렉터리의 모든 파일 해시가
같았다. 격리 marker와 실행 거절은 유지됐다. 시험 DB/API는 OID/프로세스 소유권을 확인해
정리했다. CLI의 성공0/충돌2도 실제 실행했다.

첫 실행 `20261004T142121Z-95d1c86b`는 9개 통과 뒤 영수증 검증의 기존 ValueError를
시험이 Blocked로 기대해 실패했다. 기대 타입을 실제 계약에 맞춘 14개
`20261004T142211Z-85b4a325`와 전환/marker 추가 후 최종 16개가 통과했다.

격리 읽기 옵션 변경의 기존 백업 회귀는 13개 `20261004T142719Z-0da4249f` PASS/0이다.
선행 `20261004T142410Z-a4608027`에서는 동시 writer의 SELECT 진입이 SQLITE_BUSY로
실패했다. 별도 실제 writer probe에서 12개 snapshot은 일치했고 읽기 대기가 최대 4.933초였다.
동시성 시험은 SQLITE_BUSY만 공개 기본 30초 예산 안에서 재시도하도록 수정했다. 다른 오류는
즉시 실패하며 별도 BEGIN EXCLUSIVE의 1초 제한/실패 시험은 유지했다. 제품의 잠금 제한이나
원본 writer는 변경하지 않았다. 최종 8개 snapshot·실제 revision 증가·SIGKILL 2개도 통과했다.

Java JAR SHA256은 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`로
동일하고 V1–V34 및 Runner 실행 코드는 변경하지 않았다. CI에 16개 gate를 추가했다.
새 CI/배포 확인, 실제 checkpoint 객체·원본 producer 종료·브로커 권한·종합 활성화와
M5 잔여/M7–M10 전체 수용은 별도다.
