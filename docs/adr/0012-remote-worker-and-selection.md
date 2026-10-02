# ADR0012: 공개 Remote 실행 선택과 자동 worker

상태: 로컬 구현·검증. M5는 진행 중이며 실제 외부 계약 수용과 Kubernetes↔Remote 종단 검증은 남는다.
ADR0011의 영속 실행/결과 경로를 공개 Run/Offload 요청과 Spring 스케줄러에 연결한다.
검증 범위는 [M5 worker 증거](../evidence/m5-remote-worker.md)를 따른다.

## 제공자 고정

공개 Run은 `execution={mode:REMOTE,providerKey:reference}`를 받는다. 서버는 현재 하나의 제공자를
설정하며, key·origin·프로토콜·CA 내용·sourceMode로 식별한 RemoteTarget을 Run/Attempt에 고정한다.
V12는 이 binding과 실행 대상을 DB trigger로 불변화한다. RemoteAllocation 생성 시 Attempt와도 대조한다.
원문 자격·URL은 Run이나 공개 응답에 넣지 않는다. 토큰 파일은 요청마다 읽으며 토큰 교체는 binding을
바꾸지 않는다. origin/CA/sourceMode를 바꾸면 기존 실행에 I/O하지 않고 원래 설정 복구를 기다린다.
여러 과거 제공자 설정을 동시에 유지하는 registry는 현재 없다.

Run idempotency digest는 사용자가 보낸 providerKey를 포함한다. 기존 요청 재전송은 현재 설정을
조회하기 전에 기존 Run을 반환한다. 하위 INITIAL Attempt는 최초 Run, RETRY는 직전 Attempt,
OFFLOAD는 접수된 Operation의 대상을 따른다. 특정 Task 전환이 Run 전체의 최초 정책을 바꾸지 않는다.

## 명령과 실제 파일

RemoteWorker는 기존 CREATE/DELETE outbox를 5분 lease로 가져오고 별도 주기로 관측한다.
예약·입력 전송·시작은 동일 할당 신원으로 재전송한다. 현재 desired state와 마감을 외부 I/O 사이에
검사하고 취소는 실제 제공자 terminal 관측까지 기다린다. 참조 제공자 재시작 실패는 RUNTIME_LOST로
분류하여 기존 retry budget을 사용한다. 새 Attempt/epoch/할당만 만들며 Task ID는 유지한다.

외부 HTTP/S3 전송은 DB 트랜잭션 밖에서 수행한다. ArtifactFiles는 서버가 접근 가능한 storage endpoint로
고정 S3 version을 다운로드하고 크기·형식·실제 SHA를 확인한다. 결과도 직접 versioned upload한 뒤
기존 ArtifactCommitService로 실제 내용을 다시 검증한다. Runner용 URL은 이 경로에서 사용하지 않는다.
Result 확정 시 Run 잠금에서 producer/epoch/할당/lease/취소를 재검사하고 하위 BATCH를 해제한다.

작업 디렉터리는700, 파일은600이며 정상 종료·예외 시 소유 파일을 정리한다. 프로세스 강제 종료 뒤의
임시 파일이나 중복/취소 경합으로 남은 미확정 S3 version에 대한 GC는 아직 없다(M9).
command lease는 파일 전송을 포함하고 heartbeat로 연장하지 않는다. 제공자 멱등성과 DB producer
검사가 경합을 막지만 장시간·대용량 전송의 처리량 수용은 후속 검증 범위다.

## 전환과 공개 표시

Offload는 targetNodeId와 targetProviderKey 중 정확히 하나를 받는다. RESTART 선언, source fencing,
실제 source 종료, 미완료 CREATE 소진, 새 Attempt 및 target claim 순서는 기존 ADR0007을 유지한다.
NODE↔REMOTE가 가능하며 같은 고정 제공자로의 재전환은 거절한다. 자동 측정 기반 전환은 Kubernetes
측정만 지원한다. REMOTE Run과 자동 정책을 함께 요청하면409이며 UI도 이를 명시한다.

Remote 응답에는 providerKey/configurationDigest/sourceMode를 표시하고 Pod/Node UID를 만들지 않는다.
SYNTHETIC 참조 계산은 실제 OCI/장비/모델 실행 증거와 구분한다. Remote 기능은 기본 비활성이며
runtime 활성화·worker·versioned S3·제공자 설정이 필요하다. 설정은 [Remote 실행](../remote.md)을 따른다.
