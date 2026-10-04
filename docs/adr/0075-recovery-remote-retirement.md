# ADR0075: 복원 DB의 Remote 종료 관측·runtime·명령 원자적 정리

상태: 로컬 실제 PG16/TLS 검증. [근거](../evidence/m9-recovery-remote-retirement.md).

ADR0073/0074는 제공자 차단·실제 계산 종료와 복원 DB의 전체 이력을 대조한다. 과거 DB의
runtime과 CREATE 명령이 남으면 이후 worker가 이미 끝난 할당을 다시 처리할 수 있다.
따라서 같은 제공자의 종료 사실을 관측값·runtime·기존 명령에 한 트랜잭션으로 반영한다.
작업의 성공·실패·재시도를 결정하는 종합 복구와는 별도의 단계다.

입력은 명시한 복원 DB/보고서와 기존 endpoint·CA·인증서 지문·provider key·설치/복구 UUID다.
저장된 inventory를 입력으로 받지 않는다. 매 실행마다 실제 TLS 연결로 차단·계산 종료·전체
페이지·신원/요청/관측을 다시 확인한다. 누락·충돌·다른 binding이 하나라도 있으면 DB를 쓰지 않는다.
복원 OID/marker 및 지원 schema V33/V34 검사와 일반 API 기동 차단은 유지한다.

관측용 READ ONLY/REPEATABLE READ snapshot에서 migration·Remote allocation·runtime·
command·Attempt·Result의 모든 행을 순서대로 SHA256에 반영한다. nonce·원문 work는 해시
비교에 포함하지만 개인 intent에도 복사하지 않는다. 잠금 전 생긴 행 추가·삭제·lease·상태 변경은
이 비교로 찾는다. 쓰기 트랜잭션은 여섯 테이블에 SHARE ROW EXCLUSIVE 잠금을 먼저 획득하고,
새 READ COMMITTED 조회로 복원 신원과 해시를 재검증한다. 잠금 대기는5초, 문장은30초 제한이다.
일반 SELECT는 허용하며 동시 복구 쓰기와 DML/DDL은 직렬화한다.

일치하면 아래 세 변경만 수행한다. V1–V34 migration과 불변 trigger는 변경하지 않는다.

1. 더 새로운 종료 관측만 `remote_allocation`에 기록한다. 같은 revision·기존 terminal 이력은 그대로 둔다.
2. 해당 `runtime_instance`를 STOPPED/TERMINATED로 만든다. 식별자·nonce·실패 사유·생성 이력은 유지한다.
3. 해당 runtime의 기존 CREATE/DELETE를 완료로 표시하고 lease를 비운다. ID·시도 횟수·생성/예약 시각은 유지한다.

할당마다 관측과 종료 상태·미처리 명령 부재를 확인한다. 어떤 오류도 전체 트랜잭션을 rollback한다.
커밋 뒤 별도 DB 연결과 TLS 전체 조회로 다시 확인한 경우에만 `REMOTE_RUNTIMES_RETIRED`를 기록한다.
선행 취소 tombstone도 정확히 같은 revision/관측을 이미 기록했다면 재실행에서 일치로 인정한다.
다른 tombstone·확정 Result와의 모순은 계속 거절한다. 두 번째 실행은 timestamp까지 그대로인0변경이다.

새700 출력 디렉터리에600 `intent.json`을 쓰고 파일·디렉터리를 fsync한 뒤 DB 명령을 보낸다.
SQL은 개인 파일에서 stdin으로 전달한다. 커밋 응답/후속 조회/receipt 저장 실패가 실제 rollback을
뜻하지는 않는다. 이때 `databaseModified:null`과 보존된 intent를 확인하고 동일 ID·새 출력 경로로
다시 실제 상태를 조회한다. 저장된 계획을 재생하거나 자동 보상/차단 해제를 하지 않는다.

Task/Attempt/Run 상태·확정 Result·artifact·제공자 이력은 보존한다. 아직 DB에 반영하지 못한
SUCCEEDED 파일의 회수와 workflow 결과 확정, 재시도, 장치 journal, 다른 producer 정리,
키 교체·서비스 활성화는 후속이다. `activated`와 `globalQuiescenceProven`은 false다.
복원 DB가 계속 격리되므로 중간의 Task/Attempt 상태만으로 실행 가능하다고 해석하지 않는다.
운영자가 DB/제공자 저장소를 직접 변조하거나 설치 복제본을 실행하는 경우는 기존 단일 설치 계약 밖이다.

시험은 실제 Java API 할당·pg_dump/restore·별도 TLS 계산·차단을 사용한다. 확정 Result와
artifact 참조는 trigger를 유지한 명시적 SQL fixture이며 이 시험은 파일 bytes 복구를 주장하지 않는다.
실제 중간 SQL 실패 rollback·동시 command 변경·marker 변경·잠금 timeout·커밋 후 응답 유실·
재실행·provider SIGKILL·기동 격리까지 확인한다. Compose17 CI에 같은 시험을 추가한다.
