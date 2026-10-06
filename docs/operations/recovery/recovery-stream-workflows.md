# 복원 STREAM 그룹의 취소·재시도 기한 조정

[실제 producer 종료](recovery-producer-stop.md), [복원 runtime 정리](recovery-kubernetes-retirement.md),
[원본 MQTT 차단](recovery-mqtt-fence.md)과 [복원 generation 종료](recovery-stream-retirement.md) 후
기록된 STREAM 그룹 업무 상태를 조정한다. 명령은 원본 Kubernetes와 broker를 다시 조회한다.

```bash
EDGEAI_STREAM_PYTHON=<고정-Paho-환경>/bin/python \
  bash scripts/ops/recovery-stream-workflows.sh \
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
기한이 지났을 때만 전체 그룹을 실패로 확정한다. 활성 offload는 아래 옵션으로 별도 조정한다.
최종 처리 권한을 받은 작업의 재시도는 아래 `--finalizers` 옵션으로 별도 검사한다.
미기록 성공을 추정하거나 새 실행을 만들지 않는다.

기록된 전환의 취소·drain 기한 만료도 조정하려면 같은 명령에 `--offloads`를 추가한다. claim 전
target은 정확한 Job UID와 보존한 모든 자식의 실제 종료 증거가 있을 때 `--unclaimed-jobs`로
검사한다. 전체 member/checkpoint/source/target/배치가 고정 계획과 맞아야 한다.
`offloadsFailed`, `offloadsCancelled`, `attemptsFailed`가 추가 변경 수다. 기한 전 예약은
그대로이며 원래 기한을 연장하지 않는다. drain 기한 만료는 선택한 Task만 실패로 만들고
peer와 후속 작업을 건너뛴다. **STARTING은 시작 기록 없이 시간 초과로 확정하지 않는다.**
백업 이후 실제로 시작했을 수 있으므로 시작 기한이 지나도 그룹 전체가 미해결로 남는다.
겹친 Operation·부분 target·더 최신 Attempt·미기록 실패/완료는 미해결로 남긴다.
[ADR0093](../../adr/0093-restored-stream-offloads.md)에 전환 복구 계약을 기록한다.

원래 전환 시작을 복원하려면 `--offloads`와 다음 세 옵션을 함께 지정한다.

```bash
--runtime-start-backup /private/fixed-storage-backup \
--runtime-start-bucket '<원래 authority bucket>' \
--runtime-start-certificate-sha256 '<복제 저장소 TLS 인증서 SHA-256>'
```

저장소 접속 환경은 [시작 기록 복구](recovery-kubernetes-workflows.md)의 독립 backup
설정을 사용한다. 각 target의 고정 S3 시작 기록을 원래 작업/입력·Pod/Node 종료 증거·전환
신원/기한과 대조한다. VD target은 원래 배정/세대/세션/슬롯·설정·최초 supervisor lease까지
대조한다. 모든 member가 원래 기한 안에 허가됐을 때만 Operation을 SUCCEEDED로 바꾸며
`offloadsCompleted`에 반영한다. 원래 claim·Attempt·Result·checkpoint·경로·실행 배정은 보존한다.
한 member라도 기록이 없으면 `STREAM_GROUP_START_AUTHORITY_NOT_PROVEN`이며, 옵션을 생략해도
이 검사를 우회할 수 없다. 기록/실제 신원 불일치는 BLOCKED다. 성공 복원 후에도 작업 결과와
실행 재개는 별도이며 격리를 유지한다. [ADR0108](../../adr/0108-restored-stream-starts.md)을 따른다.

같은 `--offloads`는 이미 FAILED/TARGET_FAILED인 Operation의 실패한 target Attempt/runtime과
peer 취소도 대조한다. 원래 실패 사유나 최신 Attempt가 맞지 않거나 retry queue가 남아 있으면
그룹을 미해결로 유지한다. 일관된 실패 이력은 보존하고 남은 peer 취소·Run만 조정한다.
이미 실패한 Operation을 취소로 덮어쓰거나 새 재시도를 만들지 않는다. 옵션이 없으면 이
검사를 기본 취소로 우회할 수 없다. [ADR0094](../../adr/0094-recorded-stream-target-failures.md)를 따른다.

`--finalizers`는 봉인된 계산의 최종 저장 재시도에 원래 **작업별** 기한을 적용한다.
원래 grant/checkpoint·최신 실패·상속·실패 사유와 queue를 대조하며 일반 그룹의 가장 이른
기한으로 바꾸지 않는다. 기한 전에는 queue를 유지하고 기한이 지나면 첫 실패와 미완료
peer/후속 작업만 정리한다. `finalizerRetriesExpired`는 그 실패 수이며 `retriesExpired`에도
포함된다. 새 최종 저장 실행이나 권한을 만들지는 않는다. 옵션 없이 일반 그룹 재시도로
우회할 수 없으며 계산 중 peer·봉인되지 않은 retry·미기록 결과는 별도 복구가 필요하다.
[ADR0097](../../adr/0097-restored-stream-finalizer-retries.md)에 범위를 기록한다.

SQL 오류는 전체 원복한다. 실제 COMMIT 뒤 응답 유실이나 권한 변경이 생기면 DB가 이미
바뀌었을 수 있으므로 `failure.json`·`intent.json`을 보존한다. 같은 복구 UUID와 새 output으로
재관측·재실행한다. DB marker·broker 차단·namespace 차단을 유지한다. `activated`와
`globalQuiescenceProven`은 false다. 일반 API/worker 재가동은 종합 복구 수용 이후 단계다.

전용 시험은 다음과 같다. 현재 검증한 Runner 이미지 digest/소스는 배포 pin에서 읽으며
CI에서는 해당 실행이 빌드·검증한 image/source와 API JAR를 명시한다.

```bash
EDGEAI_STREAM_PYTHON=<고정-Paho-환경>/bin/python \
  bash scripts/collect-evidence.sh recovery-stream-workflows \
  bash scripts/test/test-recovery-stream-workflows.sh \
  --context '<시험 context>' --minio-binary '<검증한 MinIO 실행파일>' \
  --report .tools/recovery-stream-workflows-test.json
```

전용 namespace와 소유 DB/API·TLS MinIO/MQTT를 만들고 삭제한다. [ADR0092](../../adr/0092-restored-stream-workflows.md)에
정의한 업무 상태 복구 범위이며 실제 엣지 모델·전체 서비스 재개 수용과는 별도다.
[실제18개 검증 근거](../../evidence/m9-recovery-stream-workflows.md)를 참고한다.
같은 시험에 `--offloads`를 추가하면 실제 source→target 교체와 복원 전환 검사를 포함한다.
[전환 포함29개 근거](../../evidence/m9-recovery-stream-offloads.md)를 참고한다.
기록된 target 실패 검사를 더한 [최신36개 근거](../../evidence/m9-recorded-stream-target-failures.md)도
같은 `--offloads` 시험으로 실행한다.
`--finalizers`를 함께 지정하면 봉인된 작업별 재시도의 원래 기한·경쟁·원복·이력 보존도
실제 복원 DB에서 검사한다. 검증 결과는 해당 실행 증거를 확인한다.
