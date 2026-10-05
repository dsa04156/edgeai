# ADR0110 — STREAM 연결 그룹의 완료 허가 독립 보존

## 결정과 이유

ADR0109는 복원 DB에 있는 원래 완료 허가를 검증한다. DB 백업 이후에 허가가 생긴
경우를 복원하려면 Result와 별개로 그 허가 자체의 독립 증거가 필요하다. V37은
동일 transaction의 지연 trigger로 연결 그룹 전체의 완료 허가를 outbox에 고정한다.
Task 간 STREAM 경로와 같은 Device의 fanout을 묶으며 별도 그룹은 따로 보존한다.
모든 Task와 Device가 동일한 원래 시각에 허가됐을 때만 기록한다. 기존 완전한 그룹도
backfill하며 일부만 남은 과거 허가나 서로 다른 허가 시각은 추론하여 채우지 않는다.

원래 Attempt UUID를 문자열 정렬한 첫 값을 그룹 기록 ID로 사용한다. 객체는
`authority/stream-completion/<id>.json`, media type은
`application/vnd.edgeai.stream-completion+json`, apiVersion은
`edgeai.stream.completion/v1`이다. 최대 16 MiB이며 그룹은 최대 128개 Task다.

기록은 `id/runId/namespace/brokerDigest/routeDigest/taskIds/attemptIds/grantedAt`,
`taskCompletions/deviceCompletions/checkpoints/generations/routes/producers`를 가진다.
완료 행·마지막 checkpoint의 고정 object version/bytes/SHA와 summary·이전 checkpoint
참조·원래 경로/세대·producer의 runtime/Attempt/epoch/Pod/Node/VD·시작 기록 키를
보존한다. 가변 generation state, claim nonce, 작업 parameters, MQTT 비밀번호,
원시 프레임과 서명 URL은 넣지 않는다. checkpoint 참조 보존이 해당 bytes의 별도 백업을
대신하지 않으며, 이전 checkpoint 전체 이력을 이 기록 하나에 복제하지 않는다.
SQL snapshot의 시각은 생성 당시의 ISO8601 UTC offset을 유지한다. 복원 대조는 UTC 표기
문자열의 동일성 대신 원래 instant와 PostgreSQL의 마이크로초 정밀도를 비교해야 한다.

DB commit 후 transaction 밖에서 조건부 S3 쓰기를 수행하고 고정 version을 다시 읽어
전체 JSON의 구조적 동일성을 확인한다. 버전 관리 비활성, 다른 내용/media type,
중복 필드와 trailing JSON은 거절하며 기존 객체를 덮어쓰지 않는다. 같은 허가는
최초 객체 version을 재사용한다. 공개 FINALIZE·최종 checkpoint 다운로드·STREAM 결과
업로드/commit 경로에서 보존을 확인한다. FINALIZE/다운로드는 저장 뒤 원래 권한도
재확인하여 저장 중 취소나 만료를 성공으로 응답하지 않는다.

저장 장애로503을 반환하더라도 DB의 원래 허가는 보존한다. 계산을 재개하지 않고 동일
요청을 재전송한다. namespace로 제한한 영속 worker는 API 재시작·producer 종료 뒤에도
재발행할 수 있다. lease owner만 큐 완료/연기를 기록하며 오래된 owner의 응답은 거절한다.
worker 활성화는 기존 `edgeai.runtime.worker-enabled` 설정을 따른다.

V37 백업 참조 검증은 outbox에 복사된 checkpoint 참조도 개별적으로 검사한다. 기존
V33–V36 복원과 호환되며 STREAM 결과 복원 transaction은 새 테이블도 잠금/내용 guard에
포함한다. 보존한 허가를 누락된 복원 DB에 반영하는 소비 단계는 별도 구현·검증이 필요하다.
이 단계는 새로운 계산 권한이나 서비스 재개를 허용하지 않는다.

## 검증과 남은 범위

[검증 기록](../evidence/m9-stream-completion-journal.md)을 따른다. 실제 PostgreSQL·HTTP·
버전 관리 S3를 사용하는 보존 시험과 명시적 Pod/broker/checkpoint metadata fixture의
범위를 구분한다. 기존 V36→V37 업무 데이터 보존, 기존 완료 그룹 backfill, 동시 쓰기,
응답 유실, 취소, transaction rollback, producer 종료 뒤 lease 인계와 재발행을 검증한다.

독립 백업에서 누락 완료 허가·checkpoint 선행 이력·실행을 복원하는 CLI, 전역 writer/API
차단과 종합 활성화, 실모델·외부 계약 및 M0–M10 전체 수용은 남은 작업이다.
