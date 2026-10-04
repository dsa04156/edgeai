# 복원 DB의 Kubernetes 실행 정리

[실행 중지](recovery-producer-stop.md)로 종료 Pod와 생성 차단을 보존한 뒤 사용한다.
이 명령은 실제 Kubernetes를 다시 조회하고 복원 DB에 확인한 종료 사실만 반영한다.

```bash
bash scripts/retire-recovery-kubernetes.sh \
  --context <확인한-context> \
  --namespace <전용-실행-namespace> \
  --namespace-uid <기존-namespace-UUID> \
  --recovery-id <동일-복구-UUID> \
  --termination-report /private/stop/termination-report.json \
  --database edgeai_restore_<복원명> \
  --restore-report /private/restore/restore-report.json \
  --output /private/kubernetes-retirement-new
```

DB 접속은 `.env`를 사용하며 Compose는 `--transport compose`를 추가한다. 매번 새 출력
디렉터리가 필요하다. 입력 보고서는0600 개인 파일이어야 하며 intent/SQL/결과도 개인 파일로
남긴다. kubeconfig의 현재 context를 암묵적으로 사용하지 않는다.

성공 상태는 `OBSERVED_KUBERNETES_RUNTIMES_RETIRED`, `activated=false`,
`globalQuiescenceProven=false`다. `retirement.json`의 선택된 실행과 변경 개수,
`unresolved`의 미관측/UID 미기록/다른 namespace 행을 확인한다. 성공만으로 전체 실행이
종료됐다고 판단하지 않는다. Kubernetes Job 기반 Task runtime과 VD runtime/binding,
해당 supervisor에 연결된 내부 Task runtime/allocation을 정리한다. 새로 닫는 allocation은
`POD_GONE`이며 실제 자식의 exit code를 추정하지 않는다. 기존 PROCESS_EXIT/NOT_STARTED
기록과 성공 Result는 보존한다. `vdTaskRuntimesRetired`/`allocationsClosed`에 변경 개수가 나온다.
배정 이력이 없는 Task는 `VD_ALLOCATION_NOT_RECORDED`로 미해결에 남는다.
workflow/offload 결과·재시도 조정은 후속이다.

종료0은 커밋 후 재검증 일치, 종료2는 관측/신원 충돌 등 BLOCKED, 종료1은 입력/DB 오류
등 FAIL이다. `failure.json`의 `databaseModified:null`은 커밋 여부가 확정되지 않았다는 뜻이다.
intent를 보존하고 같은 복구 입력·UUID로 재실행한다. 이미 반영한 행은 변경0으로 확인한다.

DB marker, quota, Pod finalizer를 이 명령 뒤에 임의로 해제하지 않는다. 전역 writer 권한
회수·다른 producer/journal·결과 복구를 함께 검증한 종합 절차에서만 일반 API/worker를 시작한다.

시험: `bash scripts/test-recovery-kubernetes-retire.sh --context <시험-context> --vd-tasks`.
실제 합성 부모/자식 컨테이너와 별도 DB를 만들고, 종료 확인 뒤 소유 자원을 정리한다.
CI에서는 정확히 빌드한 API 이미지의 JAR·검증된 Runner digest·Compose PostgreSQL17을
사용해 kind 게이트에서 실행한다. [ADR0080](adr/0080-recovery-kubernetes-retirement.md),
[기본 검증](evidence/m9-recovery-kubernetes-retirement.md),
[VD Task 확장](evidence/m9-recovery-vd-tasks.md)과 [ADR0081](adr/0081-recovery-vd-task-retirement.md).
