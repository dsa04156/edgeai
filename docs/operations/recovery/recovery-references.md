# 복원 DB와 MinIO 파일 참조 대조

[DB 복원](../backup/postgres-backup.md)과 [MinIO 백업](../backup/storage-backup.md)을 만든 다음, 새 DB의 모든
결과 파일·체크포인트 참조를 백업 저장소에서 읽어 확인한다. 원본 DB/MinIO는 필요하지 않다.
이 명령은 조회만 하며 API·worker를 활성화하지 않는다.
API로 복원 내용을 조회하려면 [조회 전용 점검 모드](recovery-inspection.md)를 사용한다.

## 입력

- 식별자를 포함한 성공 `RESTORED_DB_ONLY` 보고서와 해당 새 `edgeai_restore_*` DB.
- MinIO 백업 디렉터리의 owner-only `manifest.json`과 실제 replica 서버.
- `.env`의 PostgreSQL 연결 정보. Compose 사용 시 `--transport compose`를 추가한다.
- `EDGEAI_BACKUP_STORAGE_URL`, `EDGEAI_BACKUP_STORAGE_USER`, `EDGEAI_BACKUP_STORAGE_PASSWORD`.
- 사설 CA를 사용하면 `EDGEAI_BACKUP_CA_FILE`. TLS 검증을 생략하지 않는다.

원본용 `EDGEAI_BACKUP_SOURCE_*`는 필요하지 않다. PostgreSQL 계정은 대상 DB의 두 참조
테이블·Flyway 이력 조회가 가능해야 한다. replica 계정은 서버 식별 조회와 고정 버전 읽기
권한이 필요하다. 자격 증명·원본 보고서를 공개 Git/CI artifact에 넣지 않는다.

```bash
bash scripts/ops/verify-recovery-references.sh \
  --database edgeai_restore_drill \
  --restore-report .tools/postgres-backup-RESTORE_ID/restore-report.json \
  --storage-input .tools/backups/storage-first \
  --output .tools/recovery-check-first
```

`--restore-report`는 복원 명령이 출력한 실제 진단 경로로 바꾼다. output은 새 디렉터리여야 한다.
명령은 DB 이름/OID/복원 식별자와 MinIO deployment ID를 확인한다. 다른 DB의 보고서나
다른 MinIO의 manifest를 대신 사용할 수 없다. 식별자가 없는 이전 DB 복원 보고서는 새 DB로
다시 복원해서 생성한다.

## 판정

`VERIFIED_DB_STORAGE_REFERENCES`는 해당 DB 읽기 snapshot의 모든 `result_artifact`와
`stream_checkpoint`가 같은 bucket/key/version/bytes/SHA로 보존됐다는 뜻이다. 과거 체크포인트도
포함하며 같은 경로에 새 버전이 있어도 이전 고정 버전의 누락을 허용하지 않는다.

성공한 새 디렉터리에는0600 `verification-report.json`이 생긴다. 개수·정확한 참조 집합 SHA·
archive/manifest SHA·snapshot·시각을 기록한다. 원문 DB 내용이나 파일은 공개 report에 넣지
않는다. 실패는 nonzero이며 `failure.json`과 비공개 진단을 확인한다. 기존 output을 덮어쓰지 않는다.
V1부터 빠짐없이 적용된 V33·V34·V35·V36 schema를 지원한다. V35/V36의 결과 발행 큐는
기존 Result를 참조하며 새 고정 S3 파일 참조를 추가하지 않는다. 독립 시작/결과 기록은
별도 복구 증거다. 그 밖의 migration 이력은 exit2/BLOCKED이며 수집해야 하는 참조를
검토한 뒤 명령과 시험을 함께 갱신해야 한다.

권장 순서는 DB 백업 → MinIO 고정 버전 백업 → 새 DB 복원 → 이 대조다. 중간에 필요한 버전을
영구 삭제하면 검증이 실패한다. 운영 활성화 전 실행 중 producer와 worker의 권한·Secret·
journal 복구를 별도로 확인해야 한다. 종합 복구 완료나 합의 RPO/RTO 충족을 대신하지 않는다.

## 실제 시험

```bash
bash scripts/test/test-recovery-references.sh
```

전용 PostgreSQL DB·실제 패키징 API·TLS MinIO 두 개·합성 파일과 Runner SDK checkpoint를
사용한다. Pod/broker 경계는 SQL fixture이고 DB 제약은 유지한다. 원본 DB 삭제·원본 MinIO
종료 후 참조 전체를 검증하고, 누락/불일치/다른 신원/알 수 없는 migration을 거절한다.
CI에서는 `--transport compose --minio-binary .tools/minio-backup-tested`로 같은 job에서
빌드한 MinIO와 PostgreSQL17을 사용한다. [결정](../../adr/0061-restored-database-storage-reference-gate.md).
