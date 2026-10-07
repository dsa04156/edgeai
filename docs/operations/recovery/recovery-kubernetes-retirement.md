# 복원 DB의 Kubernetes 실행 정리

> **심화 운영 절차** — 격리된 복원 환경에서 사용하는 구성 요소별 절차입니다.
> 적용할 schema·대상 신원·선행 보고서는 아래 조건을 따릅니다. 이 명령의 성공만으로 서비스를 재가동하지 않습니다.
> 전체 순서는 [백업과 복구 안내](../backup-and-recovery.md)를 확인하세요.

## 목적과 선행 조건

[실행 중지](recovery-producer-stop.md)로 종료 Pod와 생성 차단을 보존한 뒤 사용한다.
이 명령은 실제 Kubernetes를 다시 조회하고 복원 DB에 확인한 종료 사실만 반영한다.

## 실행

```bash
bash scripts/ops/retire-recovery-kubernetes.sh \
  --context <확인한-context> \
  --namespace <전용-실행-namespace> \
  --namespace-uid <기존-namespace-UUID> \
  --recovery-id <동일-복구-UUID> \
  --termination-report /private/stop/termination-report.json \
  --database edgeai_restore_<복원명> \
  --restore-report /private/restore/restore-report.json \
  --output /private/kubernetes-retirement-new
```

## 결과 확인과 제한

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
이후 [기록된 workflow 취소·재시도 조정](recovery-kubernetes-workflows.md)을 실행할 수 있다.
결과 미기록 작업·진행 중 offload/STREAM 조정은 별도다.

Job UID는 기록됐지만 Runner claim 전 장애가 난 경우에는 `--unclaimed-jobs`를 추가할 수
있다. 정확한 Job·보존된 자식 Pod 전체의 실제 종료와 Job 상태를 대조한다. 결과의
`unclaimedJobs`는 이 별도 증거이며 원래 producer/node 필드는 채우지 않는다. Job UID가
없거나 자식 Pod가 전혀 남지 않은 Job은 계속 미해결이다. 후속 workflow 명령에도 같은
옵션이 필요하다. [ADR0084](../../adr/0084-recovery-unclaimed-jobs.md)와
[실제56개 검증](../../evidence/m9-recovery-unclaimed-jobs.md)을 참고한다.

종료0은 커밋 후 재검증 일치, 종료2는 관측/신원 충돌 등 BLOCKED, 종료1은 입력/DB 오류
등 FAIL이다. `failure.json`의 `databaseModified:null`은 커밋 여부가 확정되지 않았다는 뜻이다.
intent를 보존하고 같은 복구 입력·UUID로 재실행한다. 이미 반영한 행은 변경0으로 확인한다.

DB marker, quota, Pod finalizer를 이 명령 뒤에 임의로 해제하지 않는다. 전역 writer 권한
회수·다른 producer/journal·결과 복구를 함께 검증한 종합 절차에서만 일반 API/worker를 시작한다.

시험: `bash scripts/test/test-recovery-kubernetes-retire.sh --context <시험-context> --vd-tasks`.
실제 합성 부모/자식 컨테이너와 별도 DB를 만들고, 종료 확인 뒤 소유 자원을 정리한다.
CI에서는 정확히 빌드한 API 이미지의 JAR·검증된 Runner digest·Compose PostgreSQL17을
사용해 kind 게이트에서 실행한다. [ADR0080](../../adr/0080-recovery-kubernetes-retirement.md),
[기본 검증](../../evidence/m9-recovery-kubernetes-retirement.md),
[VD Task 확장](../../evidence/m9-recovery-vd-tasks.md)과 [ADR0081](../../adr/0081-recovery-vd-task-retirement.md).
