# 복원 DB의 누락 STREAM 완료 이력 반영

V40 DB의 원래 Run/Task/Attempt/runtime와 경로 정의가 남아 있고, 완료 허가 및 checkpoint만
백업 이후 생긴 경우 사용하는 복구 단계다. [producer 종료](recovery-producer-stop.md),
[runtime 정리](recovery-kubernetes-retirement.md), [broker 차단](recovery-mqtt-fence.md),
[경로 종료](recovery-stream-retirement.md)를 먼저 수행한다. 같은 recovery ID와 원래 종료
증거를 사용하고 Pod·독립 저장소·개인 MQTT 상태를 유지한다.

DB 접속과 독립 저장소 환경은 [결과 복원](recovery-kubernetes-results.md)을 따른다.
원래 완료 문서, 모든 선행 checkpoint receipt와 payload, 시작 허가가 같은 독립 백업에
있어야 한다. 완료 ID는 원래 `authority/stream-completion/<id>.json`의 ID다.

```bash
EDGEAI_STREAM_PYTHON='<고정 Paho 환경>/bin/python' \
  bash scripts/ops/recovery-stream-completions.sh \
  --context '<복구 context>' \
  --namespace '<원래 namespace>' --namespace-uid '<원래 UID>' \
  --recovery-id '<같은 복구 UUID>' \
  --database 'edgeai_restore_<복원 DB>' \
  --restore-report /private/restore/restore-report.json \
  --termination-report /private/stop/termination-report.json \
  --completion-id '<원래 완료 UUID>' \
  --runtime-start-backup /private/storage-backup \
  --runtime-start-bucket '<원래 bucket>' \
  --runtime-start-certificate-sha256 '<독립 저장소 leaf 인증서 SHA256>' \
  --broker-digest 'sha256:<원래 broker digest>' \
  --mqtt-state-directory /private/original-mqtt-fence-state \
  --mqtt-ca-file /private/original-broker-ca.crt \
  --mqtt-original-password-file /private/original-admin.password \
  --output /private/new-stream-completion-recovery
```

`--transport native|compose`, `--pg-bin`, `--timeout`은 다른 복구 명령과 같다.
DB 백업 당시 claim되지 않은 Job도 관측하려면 선행 종료/정리와 동일하게
`--unclaimed-jobs`를 지정한다. NODE와 VD member는 한 그룹으로 함께 검증한다.

성공 시 exit0, `completion.json`의 status는 `STREAM_COMPLETION_HISTORY_RESTORED`다.
`checkpointsRestored`, `generationsRestored`, `grantsRestored`, `publicationsRestored`를
확인한다. 모든 generation은 CLOSED이며 `activated:false`다. 동일 입력의 재실행은
`databaseModified:false`여야 한다. 이후 [STREAM Result 복원](recovery-stream-results.md)을
각 member에 실행한다. 완료 이력만 복원해서 계산이나 후속 작업을 시작하지 않는다.

조건 불일치는 exit2, 다른 오류는 exit1이다. 개인 `intent.json`, `transaction.sql`,
`failure.json`을 보존한다. COMMIT 뒤 실패했으면 일부가 아닌 전체 transaction이 반영됐을 수
있으므로 같은 증거를 새 output에서 다시 관측한다. 누락된 선행 권한이나 실행을 만들어
통과시키지 않는다. 전역 중지와 서비스 재개는 종합 복구 단계에서 처리한다.

설계와 아직 남은 범위는 [ADR0114](../../adr/0114-restored-stream-completion-history.md)를 따른다.
