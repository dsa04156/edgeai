# 복원 STREAM 확정 결과 반영

[Kubernetes 결과 복원](recovery-kubernetes-results.md)의 공통 옵션에 다음을 추가한다.
VD 자식은 [VD 결과 복원](recovery-vd-results.md)의 `--vd-tasks`도 지정한다.

```bash
--stream-results \
--broker-digest 'sha256:<원래 broker digest>' \
--mqtt-state-directory /private/original-mqtt-fence-state \
--mqtt-ca-file /private/original-broker-ca.crt \
--mqtt-original-password-file /private/original-admin.password
```

루트에서 `EDGEAI_STREAM_PYTHON=<고정 Paho 환경>/bin/python`을 지정해 실행한다.
DB/독립 백업 저장소 접속 설정은 공통 결과 복원 명령을 따른다. 개인 MQTT 상태의 복구 ID와
namespace 종료 작업의 복구 ID는 같아야 한다. 결과 output은 새 개인 디렉터리다.

선행 절차는 [producer 종료](recovery-producer-stop.md),
[복원 runtime 정리](recovery-kubernetes-retirement.md),
[원본 MQTT 차단](recovery-mqtt-fence.md), [복원 경로 종료](recovery-stream-retirement.md)다.
활성 전환이 있으면 [원래 그룹 시작 기록](recovery-stream-workflows.md)으로 먼저 조정한다.
원래 Pod와 고정 버전 파일을 삭제하지 않는다.

복원 DB에는 연결 그룹 전체의 원래 완료 허가와 최신 봉인 checkpoint가 있어야 한다.
동일한 독립 백업에 원래 시작·Result 기록, 결과 파일과 checkpoint의 고정 version을
모두 보존한다. 명령은 실제 bytes·실행 계약·모든 경로 actor와 END 커서·공통 grant
시각을 검증하고 원래 broker 차단을 다시 관측한다. 오래된 백업에 허가가 없으면 exit2다.
Result만으로 빠진 허가를 추정하거나 계산을 다시 실행하지 않는다.

V37부터 정상 실행 API는 원래 그룹 허가도 `authority/stream-completion/<id>.json`에
독립 보존한다. V38의 선행 checkpoint receipt까지 있다면 V40 DB에서 별도
[완료 이력 복원](recovery-stream-completions.md)을 먼저 수행한다. 원래 Run/Task/Attempt와
경로 이력이 필요하며, 결과 복원 명령 자체의 누락 허가 거절은 유지된다. V37의 outbox에
복사된 checkpoint 참조는 [저장소 참조 검증](recovery-references.md)에 포함되며,
결과 반영 transaction은 이 새 테이블도 잠금·변경 대조 대상으로 보호한다.
보존 형식과 장애 시 재발행은 [ADR0110](adr/0110-stream-completion-journal.md)을 따른다.

최종 저장 재시도의 결과라면 `--runtime-id`에는 실제 결과를 확정한 후속 runtime을
지정한다. 원래 grant까지 이어지는 모든 `stream_finalization_recovery` 기록과 실패
사유·재시도 정책이 필요하다. 선행 실패 runtime의 Pod도 종료 증거를 위해 보존한다.
결과의 실행 신원은 후속 Attempt, checkpoint와 완료 허가의 신원은 원래 Attempt다.
명령은 이 연결을 검증하며 새 재시도나 완료 허가를 만들지 않는다.

성공 시 exit0이며 `results.json` scope는 `restored-kubernetes-stream-result-commit` 또는
`restored-vd-stream-result-commit`이다. `resultsCreated`, `childrenReadied`,
`publicationsCompleted`, `activated:false`를 확인한다. 일부 결과만 선택할 수도 있지만
완료 장벽은 연결 그룹 전체를 검사한다. 후속 그룹은 모든 member의 BATCH 부모가 확정된
경우 함께 준비시키며 새 runtime은 생성하지 않는다.

조건 불일치는 exit2, 다른 오류는 exit1이다. `failure.json`과 개인 `intent.json`을 보존한다.
COMMIT 뒤 실패하면 변경이 이미 반영됐을 수 있으므로 같은 입력을 새 output으로 재검사한다.
성공해도 DB·namespace·broker 격리를 유지한다. 서비스 재가동은 종합 복구 검증 이후다.

전용 시험은 실제 Kubernetes context에 소유 namespace/DB/API/TLS S3/MQTT를 생성한다.

```bash
EDGEAI_STREAM_PYTHON=<고정 Paho 환경>/bin/python \
  bash scripts/test-recovery-kubernetes-results.sh \
  --context '<시험 context>' --stream-results \
  --minio-binary '<검증한 MinIO 실행파일>' \
  --report .tools/new-stream-results.json
```

`--vd-tasks`로 VD 경로를 검사한다. 시험에 `--finalizer-retries`를 추가하면 원래 완료
허가 뒤 두 번 실패한 후속 실행의 결과 복원을 검사한다. 이 옵션은 시험 명령 전용이며
`--stream-results`가 필요하다. 자동 재시도 배정의 fixture 범위와 실제 API/복원 검증은
[상속 finalizer 시험](evidence/m9-recovery-stream-finalizer-results.md)에 구분한다.

혼합 그룹은 `bash scripts/test-recovery-stream-group-results.sh --context '<시험 context>'`
명령으로 검사한다. 위와 같은 SDK 환경과 `--minio-binary`를 사용한다. 이 시험은
Device → Kubernetes → VD와 두 BATCH 결과를 기다리는 후속 작업을 만든다. 각 결과를
어느 순서로 복원해도 두 번째 결과가 확인되기 전에는 후속 Attempt를 생성하지 않아야 한다.
공통 grant와 양쪽 checkpoint는 선택하지 않은 peer까지 모두 검증한다.
[혼합 그룹 시험](evidence/m9-recovery-stream-group-results.md)에 실제 경계와 fixture를 구분한다.

현재 수용 범위와 미검증 항목은
[시험 근거](evidence/m9-recovery-stream-results.md)와 [ADR0109](adr/0109-restored-stream-results.md)를 따른다.
