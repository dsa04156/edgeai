# ADR0076: 차단된 Remote의 미반영 성공 파일 회수

상태: 로컬 실제 PG/TLS 검증. [근거](../evidence/m9-recovery-remote-outputs.md).

ADR0075가 runtime과 기존 명령을 종료해도 DB에 아직 확정하지 못한 SUCCEEDED 파일은
제공자에 남을 수 있다. 일반 API는 ADR0073의 차단을 유지하므로 별도 복구 조회로 파일을
개인 묶음에 보존한다. 이 구성 요소를 이후 S3 등록·Result 확정 단계의 입력으로 사용한다.

`GET /reference/v1/recovery/allocations/{allocationId}/outputs/{port}`는 별도 운영 bearer와
providerId/recoveryId, 전체 제공자 차단·실제 worker 종료를 요구한다. 신원/메서드/중복 query를
검사하고 SUCCEEDED의 선언된 출력만 반환한다. 일반 bearer는 계속 거절한다.
제공자는 각 디렉터리와 파일을 열린 descriptor 기준으로 O_NOFOLLOW 조회하고 정규 파일만
허용한다. FIFO는 nonblocking open 뒤 거절한다. 고정 크기/SHA256 및 읽기 중 metadata 변경을
검사한 뒤 최대1MiB의 실제 bytes를 반환한다. 실패 시 파일이나 할당 이력을 고치지 않는다.

회수 CLI는 ADR0075의 준비 단계로 실제 복원 OID/marker/schema·전체 DB/제공자 이력을
재조회한다. 누락·충돌·다른 binding은 중단한다. 확정 Result가 없는 SUCCEEDED만 회수하며,
이미 확정한 Result는 별도 S3 고정 버전 복원 계약에 남긴다. 각 다운로드 연결의 실제 TLS CA/
leaf pin을 먼저 검증하고, Content-Type/Length 단일 값·실제 bytes/SHA를 고정 관측과 대조한다.
redirect·인코딩·불완전 본문·응답 metadata 변조는 허용하지 않는다.

새700 디렉터리에600 intent와 content-addressed `objects/<sha256>.bin`을 저장·fsync한다.
같은 bytes를 가진 여러 출력은 파일 하나를 공유하되 모든 allocation/port 관계를 유지한다.
회수 뒤 DB의 전체 행 해시와 제공자의 전체 이력 SHA256을 다시 확인한 다음에만 manifest를 쓴다.
`manifest.json`은 복원 DB/보고서·설치/복구 UUID·원본 이력·intent의 SHA256과 선택한 전체
성공 관측을 포함한다. DB·제공자·기존 bundle을 변경하지 않는다. 실패한 개인 경로는 진단용이며
재실행은 새 경로에 새 실제 관측으로 시작한다.

별도 `verify`는 DB 자격·네트워크 없이 파일/intent/manifest의 일치를 검사한다. manifest의
대상·선택한 전체 관측·확정 결과 제외 개수는 intent와 같아야 하며, 누락/불필요 객체·중복 할당·
관측 변조·symlink·비정규 파일·권한/크기/SHA 불일치를 거절한다. 성공은 묶음의 내부 일치와
bytes 보존 증거다. 개인 intent/manifest를 함께 악의적으로 바꾸는 행위를 검출하는 서명이나
외부 원본 인증을 대신하지 않으므로 원본 묶음과 검증 SHA를 신뢰하는 보관 경로로 전달해야 한다.

실제 공개 API·archive/restore·TLS 계산/차단·PG 트랜잭션 시험에 네 사례를 추가한다. 별도
제공자 시험은 접근/재시작, 원본 파일 변조/누락/링크/FIFO, 실제 TLS 응답 후 metadata/body
변조 거절을 검증한다. S3 PUT·고정 version 확보·Result/Task/Run 확정·재시도·종합 복원 활성화는
후속이며 M9 전체 완료를 주장하지 않는다. 기존 API JAR·DDL·Runner/SDK 동작은 유지한다.
