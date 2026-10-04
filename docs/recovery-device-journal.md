# 복원한 장치 journal과 PostgreSQL 대조

[장치 journal 격리 복원](device-journal-backup.md)과 [PostgreSQL 복원](postgres-backup.md)을
완료한 뒤 실행한다. 읽기 전용 검사이며 장치 송신이나 API 서비스를 재개하지 않는다.

```bash
bash scripts/inspect-recovery-device-journal.sh \
  --database edgeai_restore_example \
  --restore-report /private/postgres-restore/restore-report.json \
  --journal-restore /private/device-restore \
  --run-id '<원래 Run UUID>' \
  --output /private/new-journal-comparison
```

DB 접속 환경변수는 기존 PostgreSQL 복원과 같다. Compose DB는 `--transport compose`를
추가한다. journal 경로는 `source/`의 부모이며 출력은 존재하지 않는 새 디렉터리여야 한다.
영수증·marker·journal은 현재 사용자 소유의 비공개 파일/디렉터리를 사용한다.

검사는 DB 이름/OID/복원 marker, journal 영수증과 논리 snapshot SHA256, 원래 Run의
고정된 Device Session과 전체 출력 경로, generation, 소비자 Attempt, 최신 checkpoint의
입력 바인딩과 처리 순번, DATA 계약, END와 완료 보고를 대조한다. 관계된 DB 18개 테이블의
전체 행 해시와 journal 증거를 다시 읽어 관측 사이 변경도 거절한다.

특히 장치가 ACK를 받고 이미 삭제한 순번이 복원된 소비자의 확정 순번보다 크면 현재
snapshot으로 복구할 수 없는 구간이다. 소비자가 장치 snapshot의 마지막 순번보다 앞서
있어도 순번 재사용 위험이 있으므로 충돌로 표시한다. 소비자 checkpoint가 없다면 장치의
전체 데이터가 재전송 가능한 상태여야 한다. 여러 출력의 ACK는 경로별로 검사한다.

`comparison.json` 결과:

| 상태 / 종료 코드 | 의미 |
|---|---|
| `DEVICE_JOURNAL_METADATA_MATCHED` / 0 | 해당 관측에서 DB 메타데이터와 journal이 일치 |
| `DEVICE_JOURNAL_CONFLICTS` / 2 | `conflicts`의 사유와 경로를 확인하고 복구 계획을 조정 |
| `BLOCKED` / 2 | 관측 중 변경 등으로 결론을 낼 수 없음 |
| `FAIL` / 다른 nonzero | 잘못된 입력·복원 신원·접속 오류 등, 비공개 진단 확인 |

`replayFromSequence`는 소비자 확정 순번 다음 위치를 표시하는 참고값이다. 실행 허가가
아니다. generation의 현재 DB 상태를 함께 보고하며, 만료/회수된 권한을 다시 만들지 않는다.
보고서가 일치하더라도 S3 checkpoint 바이트 검증, 원본 producer 종료, 브로커 권한 회수와
재적용, 복원 환경 전체의 활성화 검증이 남는다. `checkpointObjectsVerified`와
`producerQuiescenceProven`은 항상 false다. 원래 장치 journal의 격리 marker도 유지한다.

실제 시험: `bash scripts/test-recovery-device-journal.sh --report /private/new-report.json`.
공개 STREAM API/실제 PostgreSQL·SQLite·age를 사용한다. 런타임 claim·broker 활성화·
checkpoint 접수는 SQL fixture이므로 실제 클러스터 종단 복구 완료 근거로 사용하지 않는다.
