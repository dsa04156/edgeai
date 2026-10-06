# 복구 중 Kubernetes producer 중지

이 명령은 선택한 전용 실행 namespace에서 새 Pod/Job 생성을 막고 관측한 실행을 종료한다.
정상 운영의 Run 취소 API와 목적이 다르다. [복원 점검](recovery-kubernetes.md)에서 대상과
namespace UID를 확인한 뒤 같은 복구 작업에 사용할 UUID를 정한다.

```bash
bash scripts/ops/stop-recovery-kubernetes.sh \
  --context <복구-context> \
  --namespace <전용-실행-namespace> \
  --namespace-uid <확인한-namespace-UID> \
  --recovery-id <이번-복구-UUID> \
  --timeout 180 \
  --output .tools/recovery-stop-<새-실행명>
```

조회/생성/patch/delete 권한이 있는 운영자의 명시 context를 사용한다. API ServiceAccount에
새 권한을 추가하지 않는다. 소유하지 않은 Job/Pod, 이름·실행 신원 충돌, 다른 복구 작업의
차단 설정은 거절한다. 출력 경로는 새 디렉터리여야 하고 결과는 소유자 전용 파일로 저장한다.

`termination-report.json`의 상태와 실제 대상 UID를 확인한다. exit0은 관측한 Kubernetes
실행의 종료 확인, exit2는 아직 확인 불가, 나머지 nonzero는 실패다. 시간 초과를 정상 종료로
간주하지 않는다. 같은 복구 UUID로 새 출력 경로를 지정하면 보존한 quota/finalizer에서 재개한다.

성공 또는 실패 뒤에도 이 명령은 quota나 finalizer를 제거하지 않는다. 다른 finalizer도 유지한다.
관측한 Pod의 containerID·종료 시각·exitCode와 생성 거절 근거를 보존하는 중간 단계다.
원래 제어기의 쓰기 차단, MQTT/Remote/장치 권한 회수, 데이터/키/journal 복원까지 조정해야
서비스를 활성화할 수 있다. 현재 `globalQuiescenceProven=false`, `activated=false`를 유지한다.
Pod가 목록에서 사라졌다는 이유로 수동으로 finalizer를 제거하는 것을 이 명령의 대체 절차로
사용하지 않는다. 해제/활성화 명령은 아직 구현하지 않았다.

```bash
python3 scripts/internal/test-recovery-stop.py
bash scripts/test/test-recovery-stop-live.sh --context <시험-context>
```

실제 시험은 별도 소유 namespace에 컨테이너2개와 각 자식 프로세스를 생성한다. 중지 지연 중
시간 초과·차단 보존·재개·부모/자식 종료·다른 finalizer 보존을 확인한 뒤, 시험이 직접 만든
namespace의 UID를 확인하고 정리한다. 실제 모델이나 장치 프로토콜 시험은 아니다.
CI에서는 같은 commit의 검증된 Runner 이미지 digest를 명시해 kind에서 수행한다.
[범위와 근거](../../evidence/m9-recovery-producer-stop.md).
