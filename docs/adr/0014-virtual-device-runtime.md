# ADR0014: 지속 VD runtime과 작업별 실행

상태: M6 구현 중. ADR0013의 등록과 별도로 runtime 세대와 실제 Pod 신원을 관리한다.
이 문서는 최종 연결 계약이며 구현·검증 완료 여부는 evidence를 따른다.

## 수명과 소유

VirtualDevice는 고정 UUID이고 한 실행 세대의 VDRuntime은 별도 UUID/generation을 가진다.
VD source binding과 runtime binding은 별개다. runtime은 지속 supervisor Pod이며 가짜 Task/Job으로
만들지 않는다. 같은 VD의 source/runtime 교체는 vdId를 유지한다. 한 generation의 Pod는
restartPolicy=Never이고 실제 종료 후 새 generation으로 교체한다. 일반 작업의 Job 경로는 유지한다.

SERVICE의 고정 이미지에는 기존 Runner와 `/opt/edgeai/vd.py`가 필요하다. Pod의 CPU/memory·장치 자원은
VD 전체의 자원 한도다. maxConcurrentTasks는 같은 자원을 공유하는 실행 slot 수이며 각 Task마다
GPU/NPU나 전체 자원을 독점 예약했다고 표시하지 않는다. 한 runtime의 모든 Task는 고정 SERVICE
Profile을 사용해야 한다. 입력/출력/parameters는 작업별 디렉터리를 사용하지만 같은 컨테이너/UID의
작업을 서로 신뢰하지 않는 보안 경계로 주장하지 않는다.
동시에 실행하는 Runner의 cgroup CPU/memory 측정은 같은 VD 컨테이너 전체 사용량이며 작업별
독립 자원 측정으로 해석하지 않는다. workload 지연 파일은 작업 디렉터리별로 분리한다.

## 감독 프로세스 계약

내부 `POST /internal/v1/vd-runtimes/{runtimeId}/poll`은 runtime별 HMAC credential과 Pod-bound token을
함께 요구한다. 일반 Basic 인증은 이 경로를 사용할 수 없다. 실제 Pod UID·namespace·ServiceAccount·
runtime/VD labels와 scheduler가 선택한 Node UID를 확인하며 caller JSON의 UID만 신뢰하지 않는다.
프로세스 session UUID와 단조 sequence를 요청/응답에 결합하고 재시도는 같은 요청 bytes를 사용한다.

응답의 RUN/DRAIN/STOP과 bounded lease, TaskAttempt assignment를 실제 감독 프로세스에 반영한다.
각 assignment는 고유 Attempt ID/epoch/claim token이다. 같은 assignment 재수신은 중복 실행하지 않고
다른 내용의 같은 Attempt는 거절한다. 종료 보고는 Runner 프로세스의 disposition일 뿐이며 Result
성공은 기존 실제 artifact 검증·현재 producer/epoch 확인·DB commit 경로만 확정한다.
완료 보고는 서버 acknowledgement 전까지 재전송한다. 토큰·서명 URL·parameters·workload stdout은
supervisor 로그와 공개 evidence에 쓰지 않는다. Pod token/runtime credential은 매 요청 파일에서 읽는다.

RUN의 유효한 응답을 받은 동안만 readiness marker를 갱신한다. 일시적인 서버 장애는 readiness를
제거하고 lease 만료까지 재시도하며, 갱신되지 않으면 활성 작업을 종료한다. DRAIN은 신규 실행을
받지 않고 진행 중 실행·완료 acknowledgement를 기다리되 Profile drain timeout을 넘기지 않는다.
STOP/인증 거절/잘못된 신원은 즉시 실행을 차단하고 종료한다. 같은 generation에서 supervisor를
수동 재시작해 이미 실행한 작업을 중복 실행하지 않도록 작업 volume의 소유 marker를 재사용하지 않는다.

작업별 Runner는 별도 POSIX session에 둔다. VD 감독 아래의 workload는 별도 process group을 만들되
Runner session을 유지하여 종료 시 해당 session의 프로세스를 정리할 수 있게 한다. 일반 Runner의
독립 session 동작은 유지한다. supervisor 생애 동안 중복 방지 metadata를 최대100,000개 보관하고,
상한에서는 신규 작업을 받지 않고 drain한다. 후속 controller는 종료를 확인해 새 generation으로 교체한다.

## Control Plane 연결의 남은 구현

영속 VDRuntime/명령 lease/Operation, 실제 Pod 생성·관측·UID 삭제, VD 수정/해제의 replacement/drain,
poll 서버와 Pod 신원 인증, VD용 Task allocation·Run VD 정책·Result producer 연결이 필요하다.
Pod Running만으로 VD Ready를 확정하지 않고 supervisor attestation과 관측 신원을 결합한다.
API 재시작·중복 명령·늦은 생성·교체 중 취소·죽은 runtime의 Task fence를 실제 DB와 kind로 검증한다.
이 연결 전에는 기존 공개 VD 등록을 Ready나 실행 가능으로 변경하지 않는다.
내부 wire 계약은 `contracts/openapi/vd-runtime-api.yaml`이며 현재 supervisor 구성 요소 상태를 명시한다.

근거: [Python subprocess의 session/process group](https://docs.python.org/3/library/subprocess.html),
[Kubernetes Pod lifecycle](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/),
[M6 원문 요구사항](../requirements/m6-requirements.md).
