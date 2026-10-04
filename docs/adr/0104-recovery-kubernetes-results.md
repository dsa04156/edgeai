# ADR 0104: 원래 시작·확정 결과 기록으로 격리 DB의 Kubernetes BATCH Result를 복원한다

상태: 구현·실제 결합20개 검증 통과. 2026-10-05.
[검증 근거](../evidence/m9-recovery-kubernetes-results.md). 새 원격 CI/배포는 별도다.

ADR0101의 시작 기록, ADR0103의 DB 확정 결과 기록과 고정 출력 버전을 별도 S3 백업에서
읽고 ADR0080의 보존 Pod 종료 증거와 대조한다. 사용자가 명시한 runtime만 반영한다.
복원 DB는 V35 스키마·원래 복원 식별자·조회 전용 점검 상태여야 한다. 실제 namespace UID,
복구 작업 ID, quota의 새 Pod/Job 거절, suspend된 Job과 보존한 전체 Pod 종료 명단을 확인한다.
선택한 runtime은 STOPPED/TERMINATED이며 물리 명령의 처리와 lease 회수가 끝나야 한다.

백업 저장소의 배포 ID는 원본과 달라야 하며 TLS 인증서 pin을 명시한다. 시작·결과 기록은
각각 백업에 정확히 한 version만 있어야 한다. 현재 head와 고정 GET의 version, 길이,
SHA256, Content-Type, 엄격 JSON을 대조한다. 같은 bytes의 다른 version도 결과 권한을
대신할 수 없다. 출력 파일은 원래 task/attempt key와 고정 version으로 GET 검증한다.
그 파일의 최신 version은 사용하지 않는다. 모든 SERVICE 출력 포트·크기·mediaType과
Java 호환 manifest digest가 일치해야 한다. 비밀값·nonce·서명 URL은 복구 intent에 남기지 않는다.

Result의 Run/Task/Attempt/epoch·Job/Pod/node 신원은 검증한 최초 시작 기록과 같아야 한다.
원래 결과 ID와 DB microsecond 정밀도의 committedAt을 보존한다. 시작 허가는 원래 lease와
전환 기한 안에 있어야 하고, 결과 시각은 그 허가 이후 실제 Runner 생존 구간 안이어야 한다.
원본 API가 lease를 검사한 뒤 DB Result 시각을 생성하므로 두 사건의 극히 작은 시간 차이를
새 lease 검사로 재해석하지 않는다. 복구가 새로운 실행 기한이나 권한을 발급하지 않는다.

시작 이후의 claim 정보가 DB 백업에서 빠졌다면 두 독립 기록과 실제 종료 신원이 같은 경우에만
원래 producer Pod/node 이력을 채운다. 종료 상태·종료 시각·claim nonce는 그대로 둔다.
이미 Result가 있는 DB에서는 producer binding도 함께 존재해야 한다. 기존 성공 결과의
ID·시각·내용·파일·큐가 다르면 거절한다. 동일 parent의 나중 child 실패나 terminal Run은
재실행으로 덮어쓰지 않는다.

최신 Attempt만 허용한다. 실패·취소·대기 retry·활성 전환을 성공으로 덮어쓰지 않는다.
전환 target이면 먼저 ADR0102의 원래 시작 기록으로 전환을 조정해야 한다. 같은 Run의
관측된 실행이 복원 DB에 없으면 후속 작업 생성 전에 중단한다. STREAM/VD 결과는 별도의
그룹·실행 권한 검증이 필요하므로 이 BATCH 경로에 포함하지 않는다.

전체 관련 테이블 행 해시를 읽기 전용 snapshot에서 얻고 외부 증거를 다시 확인한다.
쓰기 transaction에서 테이블 잠금과 DB OID/복원 marker·행 해시를 다시 검사한다.
원래 Result ID/committedAt·고정 artifact를 넣고 기존 immutable seal 제약으로 봉인한다.
Attempt/Task 성공, 아직 실행 이력이 없는 BATCH child의 READY/QUEUED, terminal Run 집계,
이미 백업에서 확인한 결과의 발행 큐 완료를 한 transaction으로 처리한다. artifact 자체 ID는
기록에 포함되지 않으므로 새 UUID이며 port/key/version/bytes/SHA는 원래 값을 보존한다.
Remote 결과 복구와 child readiness/Run 집계 SQL을 공유하되 producer 및 시각 정책은 구분한다.

외부 S3/Kubernetes와 DB의 분산 transaction은 없다. COMMIT 후 증거를 다시 검증하지 못하면
성공 보고서를 만들지 않고 격리를 유지한다. DB rollback을 주장하거나 이미 봉인된 결과를
지우지 않는다. 같은 입력을 새로운 출력 경로로 재실행하면 기존 결과를 대조하며 중복을 막는다.
일반 API 기동, 새 producer·권한, 전역 writer 차단, 종합 활성화와 실제 모델 수용은 별도다.
