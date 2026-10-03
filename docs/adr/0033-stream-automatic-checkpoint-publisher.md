# ADR0033 — Session의 인증 체크포인트 자동 저장

상태: SDK 자동 저장·실제 HTTP/HTTPS/MQTT 및 Spring/PG/S3 구성 요소 검증.
새 Attempt/세대 인계·운영 Runner 분기·공개 STREAM 실행은 후속이다.

## 제어와 저장소 통신

`CheckpointClient`는 기존 Runner `BindingClient`의 현재 claim/Pod 파일과 CA 검증 HTTP
연결을 사용한다. 매 API 요청마다 투영 자격 파일을 다시 읽는다. 상대 Task/epoch·Run·전체
generation 집합이 일치해야 하며, 확정 receipt는 후보의 serial/내용 해시/크기/실행 digest,
이전 ID와 manifest/커서/계산 상태 해시까지 일치해야 한다.

S3는 별도 opener를 사용한다. API 자격 헤더를 전달하지 않으며 서버가 정한 Content-Type,
SHA metadata/checksum 세 헤더만 허용한다. API/S3 모두 redirect를 거절한다. 저장소는
검증된 HTTPS가 기본이며 명시적으로 허용한127.0.0.1 시험에 한해 HTTP를 사용한다.
최신 download grant도 receipt의 고정 versionId를 가리켜야 한다. URL/토큰/응답 원문을
예외나 journal에 기록하지 않는다. 요청 제한은 .01–5초이며 Session은 남은 기한의 일부만
할당한다. 소켓 timeout은 전체 wall-clock deadline을 대신하지 않는다.

## 소유 스레드의 단계별 저장

`CheckpointPublisher`는 journal 소유 스레드에서 한 step에 외부 요청 하나만 수행한다.
최신 확정 조회 → 고정 후보 업로드 권한 → 실제 S3 PUT → 인증된 commit 순서다.
매 외부 요청 전/후 Session 권한을 확인하고 정확한 receipt를 받았을 때만 SDK `confirm`을
호출한다. PUT 성공으로 처리 ACK/출력 frontier를 전진시키지 않는다.

일시 API/저장소 오류는 후보를 보존한 채 제한된 backoff로 재시도한다. PUT 응답이 없거나
grant가 만료되면 같은 후보의 새 grant를 요청한다. commit 응답 유실은 같은 후보·이전 ID·
version을 재전송한다. 오류/권한 변경을 새 generation 조회로 덮지 않는다.

재시작하면 인증된 latest가 로컬 confirmed serial/SHA 또는 SQLite에 남은 정확한 후보와
일치해야 한다. 전자는 다음 후보를 계속하고, 후자는 유실된 확정 응답을 복구해 원자적으로
frontier를 갱신한다. 로컬보다 최신인 다른 내용이나 더 오래된 서버 이력은 거절한다.
빈 볼륨·오래된 볼륨을 조용히 최신 상태로 취급하지 않는다. 명시적 복원/인계가 필요하다.

`Session(..., durability='EXTERNAL', checkpoint_client=client)`가 publisher를 소유한다.
클라이언트는 같은 BindingClient·Run·전체 generation 집합이어야 한다. heartbeat를 먼저
처리하고 일시 heartbeat 오류가 없는 step에서 checkpoint 요청 하나를 처리한다.
확정 직후 MQTT가 새 frontier를 전송할 수 있다. 계산 중 기존 독립 watchdog과
취소/전체 timeout/lease guard를 유지한다. publisher 없는 LOCAL/수동 EXTERNAL 계약도 유지한다.

## 검증 경계

[검증 기록](../evidence/m7-stream-checkpoint-publisher.md)을 따른다.
실제 HTTPS/MQTT Session 시험은 명시적 API/S3 응답 fixture이며, 실제 Spring·PostgreSQL·
MinIO 시험은 Python publisher를 사용하되 Pod 신원/broker 활성화가 fixture다.
두 결과를 실제 Kubernetes의 통합 STREAM 실행이라고 합쳐서 주장하지 않는다.
새 Pod/Attempt/generation handover·SERVICE stream ports/Result·운영 broker·공개 API/UI·
실제 다중 Device 종단과 M5 잔여/M8–M10 수용은 계속 남아 있다.
