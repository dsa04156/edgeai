# 복원 VD BATCH 성공 결과 반영

[물리 실행 종료 조정](recovery-kubernetes-retirement.md)을 마친 V36 격리 DB에서
[기존 결과 복원 명령](recovery-kubernetes-results.md)에 `--vd-tasks`를 추가한다.
`--runtime-id`는 VD supervisor ID가 아니라 자식 Task의 runtime ID다.

```bash
bash scripts/recovery-kubernetes-results.sh \
  --vd-tasks \
  --context '<대상 Kubernetes context>' \
  --namespace '<격리 namespace>' --namespace-uid '<원래 namespace UUID>' \
  --recovery-id '<종료 작업 UUID>' \
  --database edgeai_restore_example \
  --restore-report /private/restore/restore-report.json \
  --termination-report /private/termination/termination-report.json \
  --runtime-start-backup /private/storage-backup \
  --runtime-start-bucket edgeai-artifacts \
  --runtime-start-certificate-sha256 '<백업 leaf 인증서 SHA256>' \
  --runtime-id '<VD 자식 runtime UUID>' \
  --output /private/new-vd-result-recovery
```

백업 저장소 접속 환경 변수와 native/compose 선택은 기존 명령과 같다. 비밀번호는
개인 비밀 관리 경로로 전달한다. `--runtime-id`를 반복하여 같은 종류의 대상을 선택한다.
Job 결과와 VD 결과는 종류별로 실행한다. 같은 Pod를 쓴 작업이라도 각 작업의
`authority/vd-task-start/<runtime>.json`과 `authority/vd-task-result/<runtime>.json`,
전체 고정 출력, 원래 배정/세션이 모두 필요하다.

성공은 exit0과 `VD_RESULTS_COMMITTED`다. `results.json`의 복원/자식 준비/발행 완료
수와 `activated:false`를 확인한다. allocation과 supervisor 이력은 바뀌지 않는다.
기록에 없는 producer binding만 원래 값으로 채우고 자식은 READY/QUEUED에 둔다.
같은 입력의 재실행은 중복 Result/Attempt를 만들지 않는다.

기록·배정·설정·세션·슬롯·기한·파일·실제 종료가 다르거나 취소/실패/새 Attempt,
STREAM·활성 전환이 있으면 거절한다. 조건 미충족은 exit2, 다른 오류는 exit1이다.
COMMIT 응답 유실 또는 후검증 실패 시 개인 출력의 증거를 보존하고 같은 입력을 새
출력 경로로 다시 검증한다. 전체 writer 차단과 서비스 재가동을 완료하는 명령은 아니다.
[설계](adr/0107-recovery-vd-results.md), [M9 잔여 범위](m9-requirements.md).
