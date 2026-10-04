# 복원 STREAM 그룹의 취소·재시도 기한 조정

[실제 producer 종료](recovery-producer-stop.md), [복원 runtime 정리](recovery-kubernetes-retirement.md),
[원본 MQTT 차단](recovery-mqtt-fence.md)과 [복원 generation 종료](recovery-stream-retirement.md) 후
기록된 STREAM 그룹 업무 상태를 조정한다. 명령은 원본 Kubernetes와 broker를 다시 조회한다.

```bash
EDGEAI_STREAM_PYTHON=<고정-Paho-환경>/bin/python \
  bash scripts/recovery-stream-workflows.sh \
  --context '<명시적 context>' \
  --namespace '<종료한 전용 namespace>' \
  --namespace-uid '<원래 namespace UID>' \
  --recovery-id '<종료·MQTT 차단과 동일한 복구 UUID>' \
  --database edgeai_restore_example \
  --restore-report /private/postgres-restore/restore-report.json \
  --termination-report /private/producer-stop/termination-report.json \
  --run-id '<복원 STREAM Run UUID>' \
  --broker-digest 'sha256:<원래 broker digest>' \
  --mqtt-state-directory /private/original-mqtt-fence-state \
  --mqtt-ca-file /private/original-broker-ca.crt \
  --mqtt-original-password-file /private/original-admin.password \
  --output /private/new-stream-workflows
```

루트에서 실행하며 `lib.sh`로 DB 환경을 읽는다. Compose DB는 `--transport compose`,
비표준 PostgreSQL 경로는 `--pg-bin`을 사용한다. output은 새 개인 디렉터리다.
`--unclaimed-jobs`는 앞선 runtime 복구에서 사용하는, 실제 종료한 claim 전 Job 증명 규칙을
적용한다. namespace와 보존 Pod 증거를 먼저 지우지 않는다.

`workflows.json`의 `RECORDED_STREAM_WORKFLOWS_RECONCILED`/exit0은 현재 처리 가능한 기록을
조정하고 다시 관측했다는 뜻이다. `unresolvedGroups`가 비어 있는지 별도로 확인한다.
`retriesExpired`, `tasksCancelled`, `tasksSkipped`, `runsReconciled`가 실제 변경 수다.
원래 기한 전의 retry queue와 이미 끝난 상태는 유지하며 반복 실행에서는 변경 수가 0이다.

같은 Device fanout과 Task STREAM 연결은 한 그룹이다. 일부만 종료됐거나 경로가 열려
있으면 그룹 전체를 미해결로 남긴다. 재시도는 원래 그룹 기한·횟수·실패 사유를 확인하며
기한이 지났을 때만 전체 그룹을 실패로 확정한다. 활성 offload와 최종 처리 권한을 받은
그룹의 재시도는 별도 복구가 필요하다. 미기록 성공을 추정하거나 새 실행을 만들지 않는다.

SQL 오류는 전체 원복한다. 실제 COMMIT 뒤 응답 유실이나 권한 변경이 생기면 DB가 이미
바뀌었을 수 있으므로 `failure.json`·`intent.json`을 보존한다. 같은 복구 UUID와 새 output으로
재관측·재실행한다. DB marker·broker 차단·namespace 차단을 유지한다. `activated`와
`globalQuiescenceProven`은 false다. 일반 API/worker 재가동은 종합 복구 수용 이후 단계다.

전용 시험은 다음과 같다. 현재 검증한 Runner 이미지 digest/소스는 배포 pin에서 읽으며
CI에서는 해당 실행이 빌드·검증한 image/source와 API JAR를 명시한다.

```bash
EDGEAI_STREAM_PYTHON=<고정-Paho-환경>/bin/python \
  bash scripts/collect-evidence.sh recovery-stream-workflows \
  bash scripts/test-recovery-stream-workflows.sh \
  --context '<시험 context>' --minio-binary '<검증한 MinIO 실행파일>' \
  --report .tools/recovery-stream-workflows-test.json
```

전용 namespace와 소유 DB/API·TLS MinIO/MQTT를 만들고 삭제한다. [ADR0092](adr/0092-restored-stream-workflows.md)에
정의한 업무 상태 복구 범위이며 실제 엣지 모델·전체 서비스 재개 수용과는 별도다.
[실제18개 검증 근거](evidence/m9-recovery-stream-workflows.md)를 참고한다.
