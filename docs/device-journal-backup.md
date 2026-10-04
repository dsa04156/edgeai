# 장치 journal 암호화 백업·격리 복원

`DeviceSource`의 source 디렉터리(`journal/journal.sqlite`, 선택적 `completion.json`)를
대상으로 한다. 단일 Device Session의 LOCAL 출력 journal만 지원한다. Task의 외부
checkpoint는 기존 S3/서버 확정 절차를 사용한다.

```bash
bash scripts/install-age.sh
bash scripts/backup-device-journal.sh \
  --source /private/device-source \
  --recipient '<age 공개 수신자>' \
  --timeout 30 \
  --output /private/new-device-backup

bash scripts/restore-device-journal.sh \
  --input /private/new-device-backup \
  --identity /private/recovery-identity \
  --output /private/new-device-restore
```

수신자와 개인 키 준비는 [정적 파일 백업](private-material-backup.md)을 따른다. 출력의
부모 디렉터리는 존재해야 하며 출력 자체는 새 경로여야 한다. 원본과 모든 결과는 현재 사용자
소유의 디렉터리0700/파일0600으로 관리한다. 키 값·암호문·센서 상태는 Git/CI artifact에 올리지 않는다.

실행 중인 source도 읽기 트랜잭션으로 백업할 수 있다. 원래 owner와 프레임은 바꾸지 않는다.
완료 파일이 도중에 바뀌거나 SQLite 잠금 제한에 걸리면 실패하며 새 출력 경로로 재시도한다.
`manifest.json`과 `journal-backup.json`이 없는 출력은 완료된 장치 백업으로 사용하지 않는다.
백업 이후 센서 데이터는 이 snapshot에 포함되지 않는다.

복원 결과는 `new-device-restore/source/`이며 `restore-report.json` 상태는
`QUARANTINED_DEVICE_JOURNAL`이다. source의 동일 session/epoch·generation·adapter state·
순번·미확인 DATA/END·완료 intent를 보존하지만 직접 실행할 수는 없다.
`journal/recovery.json`이 남아 있으면 SDK의 Journal 열기가 거절된다.
원본 실행 종료와 현재 DB/브로커 권한을 검증하는 종합 복구 절차가 먼저 필요하다.
marker를 임의로 삭제하거나 새 세션에 옛 순번을 붙이지 않는다.

exit0은 암호화 또는 격리 복원 성공이며 서비스 재개가 아니다. exit2는 고정 도구 등 조건
미충족, 다른 nonzero는 입력/DB/암호화 오류다. `failure.json`에는 비밀값 없는 오류 종류만
남긴다. 새 디렉터리 게시 후 보고서 쓰기 전에 중단됐다면 source가 있어도 격리 상태를 유지한다.

검사: `bash scripts/test-device-journal.sh --report /private/new-test-report.json`.
합성 장치의 실제 SQLite/age·별도 writer 프로세스·SIGKILL을 사용하며 기존 broker/API를
변경하지 않는다. [ADR0087](adr/0087-device-journal-backup.md)을 따른다.

격리 복원 다음에는 [장치 journal과 DB 대조](recovery-device-journal.md)를 실행해 세션·
경로·처리 순번을 확인한다. 대조 성공 후에도 원본 종료와 권한/활성화 검증은 남는다.
