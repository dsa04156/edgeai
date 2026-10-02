# ADR 0017 — 인증된 VD poll과 영속 순번

상태: 수락 (idle lifecycle 연결, VD Task 배정은 후속). 날짜: 2026-10-03 KST.

## 인증과 입력

`POST /internal/v1/vd-runtimes/{runtimeId}/poll`은 전용 stateless security chain을 사용한다.
VD HMAC 자격과 실제 Kubernetes gateway의 Pod-bound TokenReview가 모두 필요하다. Basic 사용자나
Task Runner 자격으로 접근할 수 없으며 공개 쓰기 API의 CSRF 정책은 유지한다. VD 기능을 끄면
기존 `/internal/**` 거절 정책을 유지한다. 생성한 Pod UID의 DB 반영 전에는503을 반환한다.

경계에서 본문256KiB를 제한하고 UTF-8 오류·중복/알 수 없는 JSON 필드·비정수/범위·중복/겹친
Attempt 목록을 거절한다. path/HMAC의 runtime·VD·generation, 실제 Pod UID, 요청 본문을 대조한다.
Pod/Node 관측 I/O는 DB 트랜잭션 전에 수행하고 VD 행을 잠근 후 현재 session/lease/세대를 다시 검증한다.

## V15의 순번 보존

`vd_runtime_poll`은 runtime당 마지막 session/sequence/요청 bytes 해시/명령/시각 하나를 보존한다.
첫 sequence는0이고 마지막 요청의 정확한 재전송 또는 다음 순번만 허용한다. 공백까지 포함한
UTF-8 bytes가 다르면 같은 순번으로 처리하지 않는다. 과거 순번·다른 session·순번 건너뛰기는409다.
본문·인증 토큰·claimToken은 저장하지 않는다. 이후 Task 배정 시 재전송할 배정/ack 신원도 같은
트랜잭션에 보존해야 하며 이 V15 receipt만으로 Task 배정 완료를 주장하지 않는다.

VD 행 잠금으로 동시 요청을 직렬화하고 lease/Ready/Operation·poll receipt를 같은 트랜잭션에서
변경한다. runtime/session 복합 FK와 INSERT/UPDATE/DELETE/TRUNCATE trigger는 첫 순번,
단조 증가, 같은 순번의 해시, 명령의 RUN→DRAIN→STOP 방향과 현재 runtime 권한을 검증한다.
재전송에서도 현재 실행 권한을 확인한다. 따라서 같은 요청의 응답 명령은 서버의 drain 진행에
따라 STOP으로 바뀔 수 있으나 종료된 runtime에 이전 RUN을 재생하지 않는다.

## Readiness와 종료

첫 poll이 유효해도 실제 Pod Ready가 아니면 UNREADY이며 runtime의 PROVISION은 진행 중이다.
감독 프로세스는 RUN 응답을 받은 뒤 readiness marker를 만들고, 다음 실제 Pod Ready 관측과 유효
session/lease가 결합되면 Ready와 Operation 성공을 저장한다. lease 기본값은30초, 허용범위1–60초이고
startup/drain의 남은 초를 반영한다. 갱신이 끊기거나 session/Node 신원이 바뀌면 기존 fence를 적용한다.

현재는 Task 배정이 없고 assignments/cancel/ack는 빈 배열이다. 미배정 active/completed는409이며
exitCode0을 Result 성공으로 간주하지 않는다. 서버가 drain을 요청했고 미배정 작업도 없으며
인증된 보고의 active/completed가 비어 있으면 STOPPED를 저장한 뒤 STOP을 보낸다.
기존 supervisor는 빈 DRAIN에서 종료하므로 DB 상태를 저장하기 전에 종료를 지시하지 않는다.
Pod/Secret의 부재 확인은 여전히 worker가 수행하며 그 전에는 binding과 DRAIN Operation을 닫지 않는다.

감독 프로세스가 제한된 실행 이력 때문에 자체 DRAINING을 요청하면 현재 VD/설정으로 추적 가능한
REPLACE Operation을 남긴다. 이미 진행 중인 drain/replacement를 뒤집지 않으며 새 세대는 이전
실행의 물리 종료 확인 뒤에만 만든다. 후속 Task 배정은 실제 allocation/완료/ack 상태를 함께 검증한
후 이 종료 경로를 호출해야 한다.

## 검증과 활성화

실제 PostgreSQL·Spring security chain·실제 HTTP 서버·호스트 Python 감독 프로세스를 함께 시험한다.
이 시험의 Kubernetes identity/Ready는 fixture다. ADR0016의 실제 클러스터 경계 시험과 구분하고,
실제 Pod→서버 poll 전체 연결·API 프로세스 재시작·VD Task/Result/demo-vd는 후속 수용 게이트로 남긴다.
`EDGEAI_VD_ENABLED=false` 기본값을 유지한다. 공개 provision/Operation/상태 API·UI도 이어서 연결한다.
상세: [poll 검증 기록](../evidence/m6-vd-poll.md).
