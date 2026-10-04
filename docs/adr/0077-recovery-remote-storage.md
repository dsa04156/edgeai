# ADR0077: 회수한 Remote 파일의 고정 S3 버전 등록

상태: 로컬 실제 TLS MinIO 검증. [근거](../evidence/m9-recovery-remote-storage.md).

ADR0076의 개인 파일 묶음은 원본 제공자 없이 검증할 수 있다. 이후 DB Result를 확정하려면
플랫폼이 읽을 수 있는 버킷·object key·고정 version이 필요하다. 기존 S3ArtifactStore는 하나의
버킷을 사용하므로, ADR0060 백업 manifest에 명시된 대상 설치의 기존 버킷에 등록한다.
새 버킷을 만들어 기존 Result 참조와 분리하거나 DB 참조를 임의로 재작성하지 않는다.

입력은 검증한 Remote bundle, 원본과 다른 설치에 만든 S3 backup manifest, 명시적 bucket,
대상 HTTPS/CA/leaf SHA256과 자격이다. 실제 socket에서 TLS 지문을 대조한 뒤 AWS SigV4
자격을 전송한다. MinIO deploymentID·bucket versioning Enabled를 전후 확인한다.
버킷 생성·권한 변경·versioning 변경·기존 객체 삭제를 수행하지 않는다.

object key는 Java ArtifactContent와 같은 `tasks/{taskId}/attempts/{attemptId}/{port}/{sha256}`다.
각 key의 현재 객체가 있으면 응답의 version ID를 고정하고 실제 해당 버전을 GET해
Content-Type/Length·bytes/SHA를 검증한 후 재사용한다. 없으면 `If-None-Match: *`와 실제
SHA256 checksum을 서명한 PUT을 보낸다. 성공 응답의 version ID를 고정하고 다시 GET으로
검증한다. 조건부 쓰기 경합409/412는 최대3회 다시 조회하며, 기존 내용 불일치는 거절한다.
ETag·사용자 SHA metadata·latest 조회만으로 성공 판정하지 않는다.

새 개인700/600 출력 경로에 대상·bundle/backup SHA·전체 계획을 intent로 먼저 fsync한다.
PUT 응답이 유실되면 실제 객체가 남을 수 있다. 자동 삭제/rollback 대신 같은 입력을 새 경로로
실행하면 이미 저장된 정확한 버전을 검증해 재사용한다. 동시 publisher도 조건부 PUT으로
현재 객체를 덮어쓰지 않는다. 다른 관리자가 versioning/객체를 바꾸면 확인 실패로 남기며
버전 보존을 방해하는 외부 관리 작업은 별도의 운영 권한·보관 정책으로 통제해야 한다.

모든 출력과 전후 설치/versioning·로컬 입력이 일치해야 `publication.json`을 발행한다.
provider/recovery UUID·원본 bundle/backup SHA·각 작업 신원·bucket/key/version·bytes/SHA/
mediaType·새로 만든/재사용한 version 수를 기록한다. 별도 verify는 이 매핑 전체와 실제
고정 버전을 다시 읽어 검사한다. 이후 latest가 달라도 고정 버전 검증은 유지하며 해당 버전이
없으면 실패한다. 개인 receipt의 서명/외부 원본 인증을 주장하지 않는다.

CLI는 DB·원본 Remote에 연결하지 않는다. 원본/기존 확정 artifact의 전체 복원 검증은
ADR0061에 남기며, 여기서는 신규 성공 파일 등록을 담당한다. Result/Task/Run commit과
서비스 활성화는 후속이다. 이 receipt만으로 전체 복구가 끝났다고 판정하지 않는다.

실제 PG/Java/Remote/복원 시험이 생성한 bundle을 private `.tools`에 전달하고, 별도 TLS
MinIO2개의 실제 버전 보존 백업에 새 파일을 등록한다. 응답 유실·동시 HEAD404/조건부 PUT·
충돌하는 latest·잘못된 설치/CA/pin·versioning 중지·SIGKILL·고정 버전 삭제를 시험한다.
CI는 기존 Remote retirement17개를 storage job으로 옮겨 같은 job에서 이 입력을 생성하고
검증한다. 공개 artifact에는 요약만 업로드하며 개인 묶음/자격/receipt는 포함하지 않는다.
