# ADR0078: 격리된 복원 DB의 Remote 성공 결과 확정

상태: 실제 PG16/API/Remote/TLS MinIO 결합14개 검증 완료.
[검증 근거](../evidence/m9-recovery-remote-results.md). 새 원격 CI/배포는 별도 확인한다.

ADR0077의 S3 publication은 DB Result를 만들지 않는다. 일반 결과 API는 살아 있는 producer의
권한을 요구하고 후속 작업을 실행할 수 있으므로 복구 경로로 재사용하지 않는다. 별도 CLI에서
격리된 복원 DB의 신원과 전체 Remote 종료 이력, 실제 고정 S3 version을 확인하고 결과를 확정한다.

입력은 동일 복원 DB에 대해 생성한 ADR0076 bundle, ADR0060 저장소 backup manifest,
ADR0077 publication receipt, DB restore report와 대상 MinIO TLS/자격이다. 원본 제공자와
원본 DB가 없어도 실행할 수 있다. 개인 bundle은 신뢰된 운영 입력이며 외부 서명을 대신하지 않는다.
복구 DB OID·marker·restore report SHA와 bundle의 신원이 달라지면 쓰기를 거절한다.

전체 Remote 할당의 불변 binding·요청·실행 신원과 정확한 종료 observation을 대조한다.
모든 해당 runtime은 STOPPED/TERMINATED이고 기존 명령은 완료·lease 해제 상태여야 한다.
선택한 성공은 최신 epoch의 REMOTE 시도이며 Task/Run이 실행 중이고 취소·실패·재시도 예약이
없어야 한다. DISPATCHING에서 제공자만 먼저 성공한 경우도 복구한다. SERVICE 출력의 전체
port·크기·mediaType 계약을 검사하며 STREAM DAG는 별도 복구 절차가 필요하므로 거절한다.

14개 관련 테이블 전체 행의 해시를 읽기 snapshot에서 만들고, SHARE ROW EXCLUSIVE 잠금을
얻은 후 새 snapshot의 해시·DB 신원을 다시 검사한다. 불일치·5초 lock timeout·30초 statement
timeout은 변경 없이 실패한다. immutable trigger를 유지한 채 Result와 artifact를 생성·seal하고
Attempt/Task를 SUCCEEDED로 전환한다. BATCH 자식은 모든 부모의 성공 Result가 있을 때만
READY/QUEUED로 만들며 저장된 초기 target을 그대로 복사한다. 전체 Task 상태로 완료한 Run을
정리한다. 새 runtime·명령·producer는 만들지 않고 기존 종료 기록을 그대로 보존한다.

manifest digest는 Java `JsonDocuments`의 `edgeai-result-v1`과 정렬된 ResultManifest 계약을
따른다. 이미 존재하는 Result는 전체 신원·digest·고정 version·출력 매핑과 Task/Attempt 성공이
정확히 같아야 재사용한다. 재실행은 Result ID·시간·준비된 자식 시도를 변경하지 않는다.

개인 intent를 먼저 fsync하고 SQL을 개인 파일/stdin으로 전달한다. commit 직전과 직후 실제
TLS S3 고정 파일을 다시 검사하고 커밋 후 DB 전체 guard 및 결과 매핑을 별도 연결로 확인한다.
S3 관리자의 버전 삭제와 PostgreSQL commit 사이에는 분산 원자성이 없다. commit 응답 유실이나
이후 검증 실패는 rollback으로 단정하지 않으며 격리를 유지하고 같은 입력으로 재실행한다.
파일을 자동 삭제하거나 다른 version으로 확정 Result를 재작성하지 않는다.

실패/취소 시도 재조정·장치 journal·STREAM 그룹·다른 producer·운영 권한/보관 정책·전체
복구 활성화는 남는다. 이 결과 확정 receipt만으로 전체 M9 수용이나 재기동을 허용하지 않는다.
