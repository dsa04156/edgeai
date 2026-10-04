# ADR 0103: DB에 확정된 Kubernetes Result를 독립 기록과 영속 발행 큐에 보존한다

상태: 구현·실제 PG/API/S3 9개, Kubernetes3개, 기존 복구91개 검증 통과. 2026-10-05.
[검증 근거](../evidence/m9-kubernetes-result-journal.md). 새 원격 CI/배포는 별도다.

DB 백업 이후에 확정된 Result를 복구하려면 원래 Result ID·producer와 정확한 출력 버전을
증명해야 한다. Pod exit 0, 최신 S3 객체, 시작 허가만으로 성공을 만들지 않는다.
`authority/runtime-result/<runtime UUID>.json`에 `edgeai.runtime.result/v1` 문서를 저장한다.
Result ID, Run/Task/Attempt/runtime/epoch, namespace/Job/Pod/node 신원, manifest digest,
DB에 저장된 원래 committedAt과 모든 출력의 port/bucket/key/version/bytes/SHA/mediaType,
별도 시작 기록 key를 포함한다. 토큰·claim nonce·parameters·서명 URL은 제외한다.

DB commit 이전에 이 기록을 발행하지 않는다. 내용 검증 뒤 취소·producer fencing 또는
DB rollback이 발생하면 성공 기록이 남아서는 안 된다. V35의 Result seal trigger는
Kubernetes 결과에 대해 같은 transaction에서 `runtime_result_publication`을 생성한다.
Result와 요청은 함께 확정되거나 함께 rollback된다. 기존 DB의 확정된 Kubernetes Result도
큐에 넣지만, 누락된 과거 시작 허가를 소급 생성하지 않는다. Remote/VD 결과는 대상이 아니다.

이 큐는 runtime CREATE/DELETE 명령과 분리한다. 이전 API가 함께 실행되는 롤링 배포나
복구의 Pod 정리 명령이 S3 발행을 완료 처리할 수 없다. 기존 버전이 Result를 seal해도
DB trigger가 큐를 만든다. V1–V34와 immutable Result/artifact 제약을 변경하지 않는다.
새 테이블은 기존 Result ID/runtime ID만 참조하며 새 고정 S3 참조를 만들지 않는다.
DB/storage 참조 검증은 V35의 기존 artifact/checkpoint inventory를 지원한다.
독립 authority journal의 복구 검증은 이 일반 DB 참조 검증에 포함되지 않는다.

HTTP commit은 lifecycle transaction이 끝난 후 PG의 immutable Result를 다시 읽어 발행한다.
이 재조회로 timestamp를 PG의 microsecond 정밀도로 고정해 최초 응답과 재발행의 차이를 막는다.
발행기는 활성 DB transaction 안에서 호출되면 거절한다. 저장소 versioning과 크기/형식을
확인하고 `If-None-Match: *`로 생성한다. 기존 객체는 고정 version GET 및 엄격 JSON 전체
동등성으로 확인한다. 충돌·잘못된 metadata·중복/미지정 필드는 덮어쓰지 않고 거절한다.

S3 확인 후 최초201/재요청200을 반환한다. DB 확정 뒤 저장소 장애는503일 수 있으며,
이미 확정된 Result를 취소·실패로 되돌리지 않는다. 저장 후 응답 유실도 원래 기록을 재사용한다.
HTTP 응답을 기다리지 않는 별도 worker는 namespace별 `FOR UPDATE SKIP LOCKED` lease로
큐를 처리한다. API 재시작, lease 만료, producer가 이미 종료된 경우에도 같은 Result를 읽는다.
실패는5초 뒤 재시도하며 완료/연기는 lease owner가 일치할 때만 반영한다.
HTTP 경로는 worker의 lease를 임의로 완료하지 않는다. 양쪽의 동시 발행은 동일 S3 version을
공유한다. 기록 충돌은 큐에 남고 확정 Result 및 실행 상태는 보존한다.

DB와 S3는 분산 transaction이 아니다. S3가 기록되기 전에 DB와 해당 큐까지 유실되면
성공을 입증할 기록이 없을 수 있다. HTTP 성공을 받지 못했다는 사실로 실패도 단정하지 않는다.
저장 후 관리자 삭제/교체는 Object Lock으로 막는 구조가 아니므로 원본 writer 차단,
정확한 version 백업, 복원 시 재검증을 함께 사용해야 한다. `startKey`는 참조일 뿐이며
해당 허가 기록의 존재/내용을 이 발행기가 새로 보증하거나 생성하지 않는다.

이 변경은 결과 기록의 생성·보존·재발행이다. 복원 DB에 이 기록을 소비해 Result/자식 작업을
조정하는 별도 복구 경로와 전체 재활성화는 후속 작업이다. STREAM 그룹/VD/실제 모델 및
외부 계약 수용까지 완료한 것으로 확대하지 않는다.
