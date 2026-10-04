# ADR0073: 참조 Remote 제공자의 영속 차단과 계산 종료

상태: 구현. 검증 결과는 [복구 시험 근거](../evidence/m9-recovery-remote-fence.md)를 따른다.

복원 DB에는 백업 이후 만들어진 Remote 할당이 없을 수 있다. 플랫폼 DB의 알려진 ID만
취소하면 이 실행을 놓친다. ADR0010의 SYNTHETIC 참조 제공자 전체에 새 요청 차단과
전체 계산 중단을 적용한다. 실제 외부 시스템의 종료 계약을 이 구현으로 대체하지 않는다.

`/reference/v1/recovery` GET/PUT은 일반 bearer와 값이 다른 별도 운영 자격을 요구한다.
파일을 설정하지 않으면404이며 플랫폼 API에 이 자격을 주지 않는다. 매 요청 파일을 다시 읽어
회전할 수 있고, PUT은 본문 수신 뒤에도 다시 인증한다. 일반 bearer로 복구를 실행할 수 없다.

SQLite의 단일 recovery 행에 설치 UUID와 복구 UUID를 저장한다. 기존 allocations DB에는
새 테이블만 추가한다. 운영자는 CA·실제 TLS 인증서 지문과 조회한 설치 UUID를 확인해 대상을
고정한다. 다른 설치나 다른 복구 UUID는409, 같은 UUID는 재개다. 기존 프로세스 소유 flock과
동기 FULL/WAL 설정을 유지한다. 복제된 DB를 동시에 실행하는 환경까지 식별하지는 않는다.

하나의 제공자 RLock/transaction에서 차단 기록과 ALLOCATED→CANCELLED,
RUNNING→CANCELLING을 commit한다. reserve/upload/start/cancel은 본문을 받은 뒤에도
같은 잠금 안에서 차단 여부를 확인한다. 일반 조회를 포함한 기존 bearer 접근은403
PROVIDER_FENCED다. 이전에 인증하고 HTTP100을 받은 요청도 늦은 예약·입력을 저장하지 못한다.
이미 완료한 작업·파일·취소 tombstone을 삭제하거나 성공/실패 이력을 덮어쓰지 않는다.

계산의 최종 파일 발행은 기존 취소와 같은 잠금 아래에서 확인한다. 차단 전에 발행한 결과는
보존하고 차단 후에는 새 결과를 발행하지 않는다. 상태만 terminal인 것을 종료로 판정하지 않는다.
worker 객체는 실제 `is_alive()==false`가 된 뒤에만 집계에서 제거한다. 전체 SQLite 상태 집계에서
ALLOCATED/RUNNING/CANCELLING이0이고 실제 worker도0이며 차단 중인 경우만 quiescent다.
HTTP200은 차단 접수이고, quiescent가 종료 증거다. 스레드가 멈추지 않으면 timeout/BLOCKED다.

운영 CLI는 HTTPS CA/hostname과 실제 요청 socket의 SHA256을 검증한 뒤 자격을 보낸다.
providerId/recoveryId·응답 구조·모든 상태 집계·quiescent 정합성과 이전 controller의 실제403을
확인한다. inspect는 읽기만 하며 fence는 명시적인 ID와 두 자격 파일이 필요하다. 응답 유실은
완료로 보지 않으며 동일 ID로 재개한다. timeout 후 차단을 해제하거나 서비스를 활성화하지 않는다.
별도 해제 API는 없다. 새700 출력 디렉터리/600 보고서에만 기록하고 비밀값·원문 오류는 출력하지 않는다.

이 증거는 단일 참조 제공자의 계산에 한정한다. 대기 HTTP 요청은 남을 수 있지만 차단 후
파일/할당 변경은 거절한다. 실제 외부 Remote·복원 DB와 종료 이력의 조정·장치 journal·
전체 복구 순서·새 실행 활성화는 남는다. `globalQuiescenceProven`과 `activated`는 false다.
