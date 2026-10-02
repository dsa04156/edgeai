# ADR0011: RemoteAllocation과 플랫폼 producer

상태: 영속 모델·producer fencing·결과 확정은 CI·배포 검증. 아래는 최초 결정이며 자동 worker와
공개 REMOTE 실행 선택의 후속 구현은 [ADR0012](0012-remote-worker-and-selection.md)를 따른다.
ADR0010의 참조 프로토콜을 플랫폼 실행에 연결한다. 검증 범위는 `docs/evidence/m5-remote-runtime.md`를 따른다.

RuntimeInstance와 RemoteAllocation을1:1로 연결하고 원격 실행에는 Job/Pod/Node 신원을 기록하지 않는다.
namespace는 worker의 배포/조정 범위로만 남긴다. RemoteAllocation은 제공자 key·설정 digest·sourceMode,
불변 실행 요청·마감·요청 digest와 revision이 있는 관측을 저장한다. 자격·URL·파일 본문은 넣지 않는다.
제공자 설정 digest를 고정한다. 후속 worker는 현재 설정이 이 digest와 일치할 때만 전송해야 한다.
공개 실행 요청부터 하위 작업·재시도·오프로딩까지 제공자 선택을 유지하는 연결은 아직 남아 있다.

TaskResult는 실제 producerPodUid 또는 remoteAllocationId 중 하나만 가진다. 기존 결과의 값이나 봉인을
수정하지 않는다. Remote 성공 관측은 결과 확정이 아니며 고정 출력 다운로드→S3 업로드/검증 후
Run 잠금에서 현재 Attempt/epoch/할당·lease·취소·출력 계약을 다시 검사해야 확정한다.
최종 결과와 하위 BATCH 해제는 기존 트랜잭션을 공유한다.

생성/취소 명령은 기존 outbox/lease를 재사용하되 Kubernetes와 Remote worker의 조회를 구분한다.
취소는 먼저 producer를 차단하고 영속 tombstone 또는 실제 원격 종료 관측을 기다린다.
재시도/오프로딩은 이전 Runtime 종료와 미완료 CREATE 소진을 모두 확인한다. 같은 Task의 새 Attempt다.
원격 관측의 역순 응답은 무시하고 같은 revision의 다른 내용과 terminal 이후 상태 변경은 거절한다.

V10은 Runtime과 Result의 producer 종류를 원본 신원 컬럼에서 계산한다. PostgreSQL의 저장 생성 컬럼은
BEFORE trigger 뒤에 계산되므로 V11의 결과 봉인 trigger는 생성 컬럼을 비교에서 제외하고 원본 컬럼을
모두 비교한다. 이미 적용한 V10은 수정하지 않는다. 기존 Kubernetes 결과와 Remote 결과의 봉인을
실제 PostgreSQL에서 함께 검증한다.

연결 순서는 영속 모델/producer fencing→Remote worker/S3 전송→Run/Offload API·UI→실제 DB/S3/HTTP
종단 및 CI다. 중간 계층 시험만으로 M5 완료를 판정하지 않는다. 실제2세부 계약·장비/모델 수용도 남는다.
