# ADR0111 — 완료된 STREAM 그룹의 원래 체크포인트 이력 보존

## 결정과 이유

ADR0110의 완료 기록은 각 Task의 마지막 체크포인트만 포함한다. 백업 이후에 생긴
이전 체크포인트의 원래 ID·시각·이전 참조·handover 참조는 payload bytes만으로
복원할 수 없다. 따라서 완료 발행 전에 마지막 체크포인트부터 `previous_id`를 따라
모든 불변 행을 별도 S3 기록으로 보존한다. 원래 DB 행이나 V37 완료 객체는 변경하지 않는다.

객체 키는 `authority/stream-checkpoint/<id>.json`, media type은
`application/vnd.edgeai.stream-checkpoint-authority+json`, apiVersion은
`edgeai.stream.checkpoint-authority/v1`이다. 최대128KiB이며 root는
`apiVersion/id/runId/namespace/checkpoint`다. checkpoint는 원래 DB 행의 모든 필드다.
원시 상태/프레임·claim nonce·비밀번호는 포함하지 않는다. 고정 payload의 별도 백업도 필요하다.
불변 행의 `created_at`은 원래 instant를 유지한 UTC 마이크로초 표기로 변환하여
DB 연결의 시간대 설정이 달라도 같은 객체가 된다. V37 문서의 시각 표기는 바꾸지 않는다.

조건부 쓰기와 고정 version 재검증이 끝난 뒤에만 원래 그룹 완료 객체를 확인/발행한다.
일부 이력 저장이나 응답이 실패하면 같은 요청이 최초 version을 재사용한다. 다른 내용,
잘못된 media type·중복 필드·추가 필드·trailing JSON·버전 관리 중단은 거절한다.
이력 일부만 저장된 상태는 그룹 완료 보존 성공으로 응답하지 않는다.

V38은 기존 outbox에 `checkpoint_history_completed=false`를 추가한다. 이미 완료
객체를 발행한 행도 이력이 보존되지 않았으면 namespace 제한 worker가 다시 처리한다.
원래 `completed=true`를 되돌리지 않으며 lease owner만 두 표시를 함께 완료한다.
새 표시는 true→false로 변경할 수 없다. 기존 지연 trigger, 불변 완료 문서와 과거 마이그레이션은 유지한다.

독립 백업의 이력 reader는 고정 version·현재 head·S3 설치 신원·실제 TLS bytes를
확인하고, 각 metadata와 payload/summary를 대조한다. Task·SERVICE·실행 digest·
순번·시각·명시적 handover를 따라 원래 이력을 읽는다. 별도 receipt가 없는 선행
체크포인트의 ID나 시각을 payload에서 추정하지 않는다.

## 범위와 검증

이 단계는 완료 그룹의 체크포인트 이력 보존과 독립 읽기다. 진행 중인 미완료 그룹의
모든 새 체크포인트를 즉시 발행하지 않으며, 복원 DB에 누락 grant/체크포인트/실행을
원자적으로 반영하는 소비 CLI도 아직 남는다. 그 CLI는 원래 시작 허가·작업 계약·
producer 종료와 broker 차단을 별도로 대조해야 한다. 이 reader만으로 실행을 허가하지 않는다.

검증은 [증거 문서](../evidence/m9-stream-completion-checkpoint-history.md)에 기록한다.
실패한 시도와 후속 검증, 원격 CI·실제 배포의 범위는 구분한다.
