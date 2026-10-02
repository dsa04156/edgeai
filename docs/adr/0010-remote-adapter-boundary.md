# ADR 0010: Remote 경계와 참조 프로토콜

상태: 참조 구성 요소 로컬 검증. 플랫폼 연결·실제 외부 수용은 미완료다.
2세부 실제 API를 대신하는 외부 계약이 아니다. 검증 근거는 ../evidence/m5-remote-adapter.md다.

원문은 RemoteAllocation과 remote runtime을 구분하며 외부 API를 추측해 확정하지 않도록 한다.
domain.remote.RemoteGateway를 플랫폼 내부 포트로 정의하고 reference HTTP adapter와 합성 계산
시뮬레이터로 검증한다. 실제 제공자의 endpoint/auth/실행·결과·종료 보장은 계약 확인 후 별도 adapter로
정합화한다. 이 단계에서 public Task가 Remote에 연결되었다고 주장하지 않는다.

allocation/run/task/attempt/epoch를 모든 요청·응답에 대조한다. 할당 ID를 플랫폼이 먼저 정하므로
응답 유실 뒤 동일 PUT/GET으로 복구할 수 있다. 불변 spec·parameters·입력 메타데이터·마감을 SHA-256
digest로 대조한다. 토큰·signed URL·artifact 본문은 이 문서나 digest manifest에 저장하지 않는다.

reserve→input upload→start 순서다. 입력은 고정 bytes/SHA/mediaType/port로 전송하며 metadata-only
DB와 실제 파일을 분리한다. 모든 필수 파일 검증 후 시작한다. 같은 start는 계산을 중복 실행하지 않는다.
cancel은 없는 할당에도 identity tombstone을 남긴다. 나중에 도착한 reserve/start가 작업을 되살리지
못해야 한다. CANCELLED는 실행 중단 확인 후에만 반환한다. expiresAt은 불변 lease이며 provider가
서버 시각으로 만료를 강제한다. 클라이언트는 무한 재시도하거나 404를 성공으로 바꾸지 않는다.

상태는 ALLOCATED/RUNNING/SUCCEEDED/FAILED/CANCELLING/CANCELLED, revision은 단조 증가한다.
SUCCEEDED의 metadata만으로 TaskResult를 확정하지 않는다. 별도 output 다운로드에서 bytes/SHA를
확인한 후 기존 S3·Result 검증과 producer fencing에 연결해야 한다. 원격 상태를 Pod UID나 Node UID로
위장하지 않는다. 실제 플랫폼 연결에는 별도 runtime kind/producer identity와 영속 outbox가 필요하다.

reference adapter는 allowlisted origin·HTTPS(명시적 loopback 개발만 HTTP)·bearer 파일·CA 검증,
redirect 거절·응답 크기·전체 요청 시간 제한을 적용한다. 출력은 새 파일에만 쓰며 checksum 실패 시
불완전한 파일을 결과로 남기지 않는다. 한 RPC 호출 안에서 조용히 여러 실행 요청을 만들지 않는다.

시뮬레이터는 loopback에서만 수신하며 SQLite에 할당/취소 tombstone과 상태를 저장한다. 이미 구현된
합성 선형 계산과 고정 입력을 실제로 계산한다. 임의 명령·OCI 이미지·GPU/NPU를 실행하지 않고 CPU나
메모리 예약도 보장하지 않는다. 재시작 시 진행 중 계산은 PROVIDER_RESTART로 끝내며 명시적 새 Attempt가
필요하다. 완료 파일·멱등성·취소 이력은 보존한다. 실제 장비 성능 수용과 구분한다.

후속 필수 연결: Remote runtime/producer 영속 모델, Run/Task/Offload API·UI, 비동기 command/관측,
S3→Remote 입력과 Remote→S3/Result, 실패 재시도·복구·취소·늦은 결과 차단, 실제 외부 계약 수용.
M5 범위를 참조 adapter만으로 완료하지 않는다.
