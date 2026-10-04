# 복원 Kubernetes/VD 작업 상태 조정

[실행 정리](recovery-kubernetes-retirement.md)를 완료하고 같은 quota·Pod 종료 증거를
보존한 상태에서 실행한다. 원래 DB/외부 producer의 전체 격리 상태는 계속 유지한다.

```bash
bash scripts/recovery-kubernetes-workflows.sh \
  --context <확인한-context> \
  --namespace <전용-실행-namespace> \
  --namespace-uid <기존-namespace-UUID> \
  --recovery-id <동일-복구-UUID> \
  --termination-report /private/stop/termination-report.json \
  --database edgeai_restore_<복원명> \
  --restore-report /private/restore/restore-report.json \
  --output /private/kubernetes-workflows-new
```

`.env`의 DB 접속을 사용한다. Compose는 `--transport compose`, 별도 PostgreSQL 도구는
`--pg-bin <경로>`를 추가한다. 매번 새 출력 디렉터리를 지정한다. 입력/intent/SQL/결과는
개인 파일로 유지하며 공개 Git이나 CI artifact에 올리지 않는다.

`workflows.json`의 성공 상태는 `RECORDED_KUBERNETES_WORKFLOWS_RECONCILED`다.
`tasksCancelled`, `tasksSkipped`, `retriesExpired`, `runsReconciled`는 실제 변경 수다.
`pendingRetries`는 원래 기한을 유지한 예약 수이며 이 명령이 실행을 시작하지는 않는다.
`unresolvedProducers`는 종료를 증명하지 못한 실행, `unresolvedWorkflows`는 결과 미기록이나
STREAM/진행 중 offload 등 별도 복구가 필요한 작업이다. 두 목록이 남아 있어도 안전하게
처리할 수 있는 기록은 반영될 수 있으므로 성공 코드를 전체 복구 완료로 해석하지 않는다.

기록된 BATCH 전환도 조정하려면 같은 명령에 `--offloads`를 추가한다. source claim·종료 증거와
고정 target을 대조하고 취소를 확정하거나 기록된 drain/start 기한을 검사한다. 유효한 전환은
새 target이나 기한을 만들지 않고 유지한다. `offloadsCancelled`/`offloadsFailed`는 반영 수,
`pendingOffloads`는 증명 범위 안에서 아직 대기 중인 전환 수다. `unresolvedOffloads`는
target 증거 누락·Remote/STREAM 또는 새 epoch 등 별도 처리가 필요한 전환이다. 이 계수는
`--offloads`를 적용한 범위이며 기본 명령의0을 전체 전환 부재로 해석하지 않는다.
claim 전 target은 실행 정리와 이 명령에 모두 `--unclaimed-jobs`를 추가해, 기록된 Job과
보존 Pod 전체의 종료를 새로 대조할 수 있다. 실제 STARTING 기한 만료 시험은 target만
실패 처리하고 source OFFLOADED·원래 배치/기한·빈 claim을 보존한다. 미관측 Job과
Remote/STREAM/group은 여전히 별도다. [ADR0083](adr/0083-recovery-batch-offloads.md),
[ADR0084 검증](evidence/m9-recovery-unclaimed-jobs.md).

참조 Remote를 포함한 전환은 먼저 [Remote 실행 정리](recovery-remote-retirement.md)를
완료한 뒤 `--offloads --remote-connection /private/remote-connection.json`을 추가한다.
파일은 현재 사용자 소유0600이며 아래6개 필드만 허용한다. 토큰 자체 대신 기존 개인 파일의
절대 경로를 사용한다. TLS 제공자는 같은 recovery UUID로 이미 fence/quiescent 상태여야 한다.

```json
{
  "endpoint": "https://remote.example.test:8443",
  "caFile": "/private/remote-ca.pem",
  "certificateSha256": "<실제 leaf 인증서 SHA256>",
  "providerId": "<확인한 제공자 UUID>",
  "providerKey": "reference",
  "recoveryTokenFile": "/private/remote-recovery-token"
}
```

이 명령은 제공자를 실제로 다시 조회하며 접속 불가/다른 binding/미완료 retirement이면
진행하지 않는다. `remoteEvidence`에 확인한 binding·provider 상태·runtime ID가 남으며
bearer와 작업 본문은 포함하지 않는다. 현재 한 DB의 단일 SYNTHETIC 참조 제공자 계약을
지원한다. 활성 Task의 Remote target이 실제 FAILED이면 전환을 TARGET_FAILED로 닫고
기록된 실패 사유에 원래 재시도 횟수·첫 시도 기준 기한·backoff를 적용한다. 실제 실패는
시작 기한 만료보다 우선한다. `attemptsFailed`/`retriesScheduled`와 `offloadsFailed`는
같은 transaction의 실제 변경 수다. 재실행은 새 예약이나 기한을 만들지 않는다.
SUCCEEDED인데 전환이 STARTING이면 시작 허가가 누락된 상태이므로
`REMOTE_TARGET_OUTCOME_REQUIRES_RECONCILIATION`에 남긴다. 파일 회수·S3 등록은
가능하지만 별도 Result 복구도 진행 중인 전환이 있는 Run을 성공으로 확정하지 않는다.
[ADR0085](adr/0085-recovery-mixed-remote-offloads.md),
[ADR0086](adr/0086-recovery-remote-offload-outcomes.md)을 참고한다.

실제 종료 증거가 없는 후손 작업의 취소, 바뀐 재시도 기한, 모순된 Result/Attempt는 거절한다.
이미 완료된 결과와 실패/취소 이력은 보존한다. Pod exit code만으로 업무 결과를 만들지 않는다.

exit0은 커밋 후 재검증 일치, exit2는 관측/신원 충돌 등 BLOCKED, exit1은 입력/DB 오류다.
`failure.json`의 `databaseModified:null`이면 커밋 여부를 단정하지 않는다. intent를 보존하고
같은 입력/복구 UUID와 새 출력 디렉터리로 재실행한다. 완료된 변경은 다시 쓰지 않는다.
`activated=false`, `globalQuiescenceProven=false`를 유지하며 marker·quota·Pod finalizer를
임의로 해제하지 않는다.

시험: `bash scripts/test-recovery-kubernetes-retire.sh --context <시험-context> --vd-tasks --workflows`.
실제 합성 컨테이너와 별도 DB를 생성하며 소유 자원만 정리한다. 업무 상태·할당·Result 일부는
명시적인 DB fixture다. [ADR0082](adr/0082-recovery-kubernetes-workflows.md),
[검증 근거](evidence/m9-recovery-kubernetes-workflows.md).
