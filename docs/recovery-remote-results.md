# 복원 Remote 성공 결과 확정

ADR0075 실행 정리 → ADR0076 파일 회수 → ADR0077 S3 등록 뒤 실행한다.
복원 DB는 일반 기동이 차단된 상태를 유지하며 원본 Remote 제공자는 필요하지 않다.

```bash
# .env의 DB 접속 설정과 별도로, 복구 대상 저장소 환경변수를 명시한다.
export EDGEAI_BACKUP_STORAGE_URL=https://recovery-storage.example:9000
export EDGEAI_BACKUP_STORAGE_USER=operator
# EDGEAI_BACKUP_STORAGE_PASSWORD는 개인 비밀 관리 경로로 전달한다.
export EDGEAI_BACKUP_CA_FILE=/private/recovery-ca.crt

bash scripts/recovery-remote-results.sh \
  --database edgeai_restore_example \
  --restore-report /private/db-restore/restore-report.json \
  --bundle /private/remote-output-bundle \
  --storage-backup /private/storage-backup \
  --receipt /private/remote-publication/publication.json \
  --bucket edgeai-artifacts \
  --certificate-sha256 '<실제 대상 leaf 인증서 SHA256>' \
  --output /private/new-result-commit
```

`--transport compose` 또는 native `--pg-bin`을 선택할 수 있다. 저장소의 전체 검증 제한은
`--timeout`(기본300초), DB lock 제한5초·statement 제한30초다. 출력은 반드시 새 경로다.

성공은 `REMOTE_RESULTS_COMMITTED`, exit0이다. 개인 `results.json`은 새 결과·준비한 자식·
정리한 Run 수, 실제 결과 검증 수, 입력 해시, `activated:false`를 기록한다. 후속 Task는
READY/QUEUED까지만 전환하며 자동 실행하지 않는다. 일반 API 기동 격리도 해제하지 않는다.

취소·실패·다음 epoch·다른 복원 DB·달라진 기존 Result·종료되지 않은 runtime·고정 파일 유실은
거절한다. exit2는 조건 미충족/접속 문제, 다른 nonzero는 검증/DB 오류다. `failure.json`과
`intent.json`을 개인 경로에 보존한다. `databaseModified:null`이면 쓰기가 반영됐을 수 있다.
같은 입력을 새 출력 경로로 재실행해 실제 상태를 확인하며 파일·결과를 임의로 지우지 않는다.

원본 확정 Result/checkpoint 전체 검증은 [복원 참조 대조](recovery-references.md)에서 수행한다.
실패/취소 작업의 복구, 장치 journal와 서비스 활성화는 [M9 수용 범위](m9-requirements.md)에 남는다.
결정 근거는 [ADR0078](adr/0078-recovery-remote-results.md)를 따른다.
