# 복원 Remote 실패·취소 작업 정리

실행 정리와 파일 회수가 끝난 동일 복원 DB에 적용한다. 성공 파일이0개여도 전체 Remote
이력이 포함된 검증된 ADR0076 bundle을 사용한다. 원본 제공자와 S3는 접속하지 않는다.

```bash
bash scripts/recovery-remote-failures.sh \
  --database edgeai_restore_example \
  --restore-report /private/db-restore/restore-report.json \
  --bundle /private/remote-output-bundle \
  --output /private/new-failure-recovery
```

DB 접속은 `.env`의 기존 설정을 사용한다. `--transport compose` 또는 native `--pg-bin`을
선택할 수 있다. 출력은 새 개인 경로여야 한다. DB lock은5초, statement는30초로 제한한다.

성공은 `REMOTE_FAILURES_RECONCILED`, exit0이다. 개인 `failures.json`에 실패한 시도,
예약/만료한 재시도, 취소/건너뛴 작업, 정리한 Run, 남은 재시도와 성공 미확정 수를 기록한다.
`activated:false`이며 일반 기동 격리는 유지한다. `successesPending`이 있으면
[성공 결과 확정](recovery-remote-results.md)의 조건도 확인해야 한다.

기존 재시도 기한과 횟수는 늘리지 않는다. 기한 전에는 같은 예약을 유지하고 기한이 지난
재실행은 해당 작업을 실패로 정리한다. 새 작업 실행은 전체 복구와 활성화가 끝난 후 기존
worker가 담당한다. 사용자 취소는 제공자의 성공이나 일반 중단과 별도로 보존한다.

다른 복원 신원·완료하지 않은 Remote·상태 충돌·STREAM/진행 중 offload는 거절한다.
exit2는 선행 조건/접속 문제, 다른 nonzero는 입력 또는 DB 검증 오류다. 실패 출력의
`databaseModified:null`은 commit이 반영됐을 수 있다는 뜻이다. 개인 intent를 보존하고
같은 입력을 새 출력 경로로 재실행한다. 자동으로 상태를 되돌리거나 기존 결과를 삭제하지 않는다.

[ADR0079](adr/0079-recovery-remote-failures.md)와 [M9 남은 수용 범위](m9-requirements.md)를 따른다.
