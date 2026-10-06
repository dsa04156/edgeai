# ADR0061 — 복원 DB의 모든 파일 참조 대조

2026-10-04. ADR0059의 DB 복원과 ADR0060의 MinIO 버전 보존을 연결한다.
파일 목록만 보존해도 DB가 다른 버전을 참조하면 사용할 수 없으므로, 복원 DB의 전체 참조를
별도 저장소에서 실제 읽는 명령 `verify-recovery-references.sh`를 추가한다.

검증 대상은 새 `edgeai_restore_*` DB다. 성공한 `RESTORED_DB_ONLY` 보고서의 DB 이름/OID와
복원 시 생성한 임의 식별자가 일치해야 한다. 복원 명령은 새 DB의 comment에 식별자를 저장하며
업무 테이블이나 Flyway migration은 바꾸지 않는다. 이름/OID가 우연히 같은 다른 클러스터의
DB를 같은 복원으로 인정하지 않는다. 기존 식별자 없는 보고서는 새로 복원해야 한다.
보고서·manifest는 운영자가 신뢰하는 owner-only 입력이며 서명된 원격 증명은 아니다.

한 `REPEATABLE READ READ ONLY` transaction에서 migration 이력과 `result_artifact`,
`stream_checkpoint`의 모든 행을 읽는다. 완료한 작업이나 최신 체크포인트만 선택하지 않는다.
V1–V33 전체를 확인하고 누락·실패·추가 migration은 거절한다. 새 파일 참조 테이블을 만드는
migration은 이 명령의 수집 범위와 실제 시험도 갱신해야 한다.

각 참조의 bucket/key/version ID가 MinIO 백업 manifest에 존재하고 bytes/SHA-256이 같아야
한다. 이후 별도 MinIO deployment ID를 확인한 저장소에서 모든 고유 참조 버전을 실제 읽고
길이/SHA를 대조한다. 최신 버전으로 대체하지 않고 원본 서버로 재시도하지 않는다. source
DB나 source MinIO 자격 증명은 검증에 사용하지 않는다. 여러 행이 같은 버전을 참조하면 모든
행의 증명을 확인한 뒤 내용 읽기는 한 번 수행한다.

성공 보고서는 `VERIFIED_DB_STORAGE_REFERENCES`다. DB archive·복원 보고서·storage manifest·
DB 참조 집합의 SHA와 참조 수, snapshot, 검증 시각을 비공개 디렉터리에 남긴다. 실패 시 성공
보고서를 만들지 않는다. 참조가 없는 정상 DB는 정확히 0으로 기록한다. 읽기 snapshot 이후
변경에 대한 연속 보장은 없으므로 활성화 전에 외부 writer/worker 경계를 별도로 확보해야 한다.

실제 시험은 API로 Profile/Device/Workflow/Run을 만든 새 DB와 TLS MinIO 두 개를 사용한다.
Pod claim과 broker activation은 명시적 SQL fixture이며 FK/check/trigger를 끄지 않는다.
실제 Runner SDK가 만든 두 체크포인트와 고정 결과 파일을 백업·복원하고 원본 DB/MinIO 없이
대조한다. 이는 실장비 실행이나 broker 재조정의 증거가 아니다.

운영 순서는 DB snapshot, 필요한 버전을 보존하는 S3 백업, 새 DB 복원, 이 대조 명령이다.
그 사이 원본 고정 버전을 영구 삭제하면 대조가 실패한다. 동시 쓰기가 있는 DB/S3의 원자적
snapshot을 주장하지 않는다. Secret/CA·journal·실행 권한 회수·중복 producer 방지·서비스
활성화와 RPO/RTO는 [M9 수용 범위](../requirements/m9-requirements.md)에 남는다.
