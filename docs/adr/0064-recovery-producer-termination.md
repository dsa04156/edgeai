# ADR0064 — Kubernetes 실행 생성 차단과 종료 증거 보존

상태: 채택, 2026-10-04. ADR0063의 관측 뒤 실제 Kubernetes producer를 중지하는 구성 요소다.

## 경계와 결정

복원 DB를 활성화하기 전 원래 실행의 종료를 확인해야 한다. 단순히 Pod 조회가404가 되거나
phase가 Failed인 것은 충분하지 않다. 특히 강제 삭제는 노드의 프로세스 종료 확인을 기다리지
않는다. [Kubernetes Pod 수명 문서](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/).

명시 context·namespace·namespace UID·복구 UUID를 요구한다. 소유 namespace의 모든 Job과
Pod를 새로 읽고 ADR0063의 이름/label/실행 ID/Job 소유 관계를 확인한다. 무관한 객체나
소유 충돌은 namespace 전체 차단을 만들기 전에 거절한다. API가 속한 namespace에 적용하는
기능이 아니며, 전용 실행 namespace를 대상으로 한다.

`edgeai-recovery-fence` ResourceQuota의 `count/pods`와 `count/jobs.batch`를0으로 설정한다.
자신의 복구 UUID와 소유 label이 있는 quota만 재사용한다. status 반영을 기다리고 실제 서버
dry-run 생성이 해당 quota에 의해 거절되는지 중지 전후 확인한다.
[객체 수 quota](https://kubernetes.io/docs/concepts/policy/resource-quotas/)는 기존 객체를 종료하지
않으므로 별도의 중지 절차를 수행한다.

Pod마다 `edgeai.io/recovery-stop` finalizer와 복구 UUID를 UID/resourceVersion 비교 후
추가한다. 다른 finalizer/annotation은 보존한다. 관측한 Job을 suspend하고, Pod는 UID와
resourceVersion 선행조건을 넣어 원래 grace period에 따라 삭제 요청한다. 강제 삭제나
finalizer 제거로 종료를 추정하지 않는다. [Finalizer 동작](https://kubernetes.io/docs/concepts/overview/working-with-objects/finalizers/).

노드에 한 번도 배정되지 않고 컨테이너 상태도 없는 Pod는 삭제 시작 후 `NEVER_BOUND_TO_NODE`로
구분한다. 배정된 Pod는 terminal phase와 모든 일반/init 컨테이너의 실제 terminated 상태,
containerID·시작/종료 시각·exitCode를 요구한다. `ContainerStatusUnknown` 등 종료를
확정할 수 없는 상태와 ephemeral container는 통과하지 않는다. 이미 다른 과정에서 종료가
시작되어 기록을 보호할 수 없거나, 관측 대상 UID 집합이 달라지면 확인 불가로 처리한다.

## 재개와 미완료 범위

시간 초과는 exit2/BLOCKED이며 quota/finalizer를 자동 해제하지 않는다. 같은 복구 UUID와
새 출력 디렉터리로 다시 실행할 수 있다. 성공해도 quota·종료 Pod 기록을 보존한다.
성공 상태는 `OBSERVED_KUBERNETES_PRODUCERS_TERMINATED`, `activated=false`,
`globalQuiescenceProven=false`다. 관측한 실행의 종료 증명이며 플랫폼 전체의 중지 증명이 아니다.

원래 API/외부 제어기, 이미 admission을 지난 동시 생성 요청, 관리자의 병행 변경을 전역으로
직렬화하지 않는다. Remote·MQTT·장치·저장소 writer 권한 회수, Secret/journal 복원,
복원 DB의 실행 상태 조정, fence/종료 기록의 안전한 해제와 서비스 활성화가 남는다.
해당 조건을 합친 종합 복구에서만 M9 완료를 판단한다.

[실행법](../recovery-producer-stop.md), [실제 종료 검증](../evidence/m9-recovery-producer-stop.md).
