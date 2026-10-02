# 개발 계획

## 활성 목표: M0–M10 전체 구현과 검증

2026-10-02 사용자가 전체 단계의 구현·검증을 지시했다. M1 이후를 진행하며 전체 완료를
현재 구현 범위로 축소하지 않는다. 매 단계 OpenAPI/DDL/코드/UI/시험/증거를 함께 갱신한다.

| 단계 | 남은 구현·검증 게이트 |
|---|---|
| M2 | 완료 — Device/Node/Observation, UI·실DB·CI·실 Kubernetes 읽기·배포 검증 |
| M3 | 완료 — DAG/Run/Task/Attempt·로컬·CI·이미지·ArgoCD·실제 Ingress 검증 |
| M4 | 완료 — 실제 kind·기존 클러스터 Runner/MinIO/Result·실패/취소·CI/배포 검증 |
| M5 | 동일 Task 새 Attempt, retry budget, 실행 중 offload/fence/drain/route 전환, remote adapter 계약·장애·늦은 결과 차단 |
| M6 | 영속 VD, source/runtime binding 분리, provision/readiness/replacement/drain 및 Operation 상태, Run의 VD 정책으로 활성 Runtime 실행, UI |
| M7 | 다중 장치 BATCH/STREAM DAG, 데이터 route/generation, backpressure·재연결·실제 데이터 흐름 |
| M8 | 100→300→1,000 장치 부하, 측정 환경·지연·오류·자원 증거 및 병목 개선 |
| M9 | outbox/reconciliation/restart recovery, identity/RBAC, 감사, TLS, backup/restore·fault 시험 |
| M10 | 실제 KubeEdge·ARM/x86·GPU/NPU, 실제 모델/2세부 연동, 합의한 성능 수용 기준 충족 |

M4까지 구현·검증을 완료했으며 현재 M5를 진행한다. 실행 규격·Job compiler·S3 adapter·독립 Runner의
구성 요소 구현과 CI·배포 시험을 완료했다. 이어 V5의 실행 상태·producer claim·명령 lease·결과 확정과
BATCH 해제·취소를 실제 PostgreSQL/MinIO로 시험했다. Kubernetes 생성/관측 worker·내부 인증/API를
연결했고 실제 클러스터의 scheduler·Pod TokenReview·UID 삭제를 대기 컨테이너로 검증했다.
로컬 실행은 기본 비활성이고 실제 배포는 실행 활성화·Result API/UI를 반영했다. Pending Pod 관측 지연
재시도 수정 뒤 실제 kind의 AUTO/NODE BATCH·재시작·artifact/producer fault와 기존 클러스터 종단
실행을 통과했다. CPU 부족·출력 누락·프로세스 실패의 추가 조건도 기존 클러스터에서 통과했고,
동일 조건의 CI36986090769도 통과하여 M4 완료를 판정했다.
M5는 ADR0006·V6의 재시도 예약/예산·새 Attempt/epoch를 실제 kind·CI·배포까지 검증했다.
ADR0007·V7의 실행 중 NODE 전환은 실제 kind·CI·배포 검증을 통과했다.
ADR0008·V8 실행 측정은 CI36996007482·실제 kind·배포까지 통과했다.
ADR0009·V9의 선택적 측정 기반 자동 전환 정책·판단 이력·AUTO 노드 제외는 CI36999672446의
실제 kind18Run 및 기존 클러스터의 source951c4bd 배포까지 검증했다.
ADR0010 Remote 참조 계약/HTTP adapter/영속 계산 시뮬레이터는 로컬 HTTP/TLS10개·실제 프로세스13개와
CI37003825328의 5 jobs/결과JSON15개 및 source0143094 실제 배포 검증을 통과했다.
ADR0011/V10–V11의 RemoteAllocation·producer fencing·결과 API/화면은 CI37008176219의
5 jobs/결과JSON15개와 source6009136 실제 배포 검증을 통과했다.
ADR0012/V12는 자동 Remote worker·직접 S3 전송·공개 Run/Offload REMOTE 선택·불변 제공자 binding을
연결했다. 단위60·PostgreSQL80·실제 S3/DB/provider14·UI26·실DB브라우저8·DB 장애/복구를 로컬 검증했다.
실제 Spring 스케줄러 BATCH, 제공자 프로세스 재시작·재시도·취소·변조·중복 처리도 포함한다.
초기 Kubernetes↔Remote 통합 시험의 Kubernetes 부분은 fixture였다. 후속45ce85f CI37016556197에서
실제 kind22Run(새Remote4개 포함)·S3 결과20개·API 교체/취소와 클러스터 삭제를 확인했다.
상태형 복원 및 실제 외부 계약 수용은 남는다. worker f6dc087의 CI37013658656은5 jobs/JSON15개와
기존 실제 kind18Run을 통과했고 GitOps f6c5a2d에 이미지 digest를 기록했다.
실제 imageID·Ready/PVCBound/ArgoSynced도 `20261002T135530Z-df0f3f31`에서 확인했다.
상세는 `docs/evidence/m5-remote-worker.md`다. M5 완료로 판정하지 않는다.
후속으로 독립 TLS Remote Pod/PVC와 실제 양방향 전환·API 교체/취소의 kind4개 Run을 추가했다.
TLS2·참조 제공자13·Runner14·실서버 manifest 검증과 새 실제 kind 종단을 통과했다.
CI37016556197은5jobs/JSON15개 PASS이며 GitOps861663f와 실제 배포 imageID/Ready/PVC/ArgoSynced도
확인했다. 상세와 kind 종료 직전 Dashboard Ready 진단 한계는 `docs/evidence/m5-remote-kind.md`를 따른다.
외부 Remote API·장비/모델·성능 합격 기준은 원문에서 미정이며
사용자에게 자료 위치를 요청했다. 독립 구현·시뮬레이터 계약 시험은 계속 진행하되 실제 외부
수용시험과 구분한다. LOCAL_VERIFIED와 FULL_ACCEPTANCE는 각각 전체 필수 증거를 요구한다.

## 완료: M0 초기화

1. 설계 출처·범위·미확정 사항을 로컬 docs로 정리한다.
2. Spring/Next.js/PostgreSQL health path, Flyway, OpenAPI, 실행 스크립트를 구현한다.
3. 단위·계약·실DB·브라우저·health 시험을 수행하고 evidence를 기록한다.
4. 공개 GitHub 저장소를 생성하고 커밋·푸시한 뒤 CI 결과를 확인한다.

위 항목과 DB 장애·복구 및 실제 MinIO S3 검증을 완료했다. 코드 b469f62의 CI 36834353000은 두 job 모두 success다.
요구사항별 증거는 `docs/evidence/m0-completion-audit.md`에서 확인한다.

## 완료: M1 Profile

계약·Flyway V2·순수 도메인/저장 adapter·HTTP·Dashboard 수직 슬라이스를 구현했다.
구체적 결정은 ADR 0002에 기록한다. 검증 결과는 PROGRESS와 M1 evidence를 따른다.
다음 수직 슬라이스는 ProfileVersion을 참조하는 M2 Device/Node/Observation이다.

## 후속 순서

M1 Profile → M2 Device/Node → M3 Workflow/Run/Task/Attempt → M4 PodSpec/Kubernetes/Runner/Result →
M5 Retry/Offload/Remote → M6 VD → M7 다중 장치 DAG/Streaming → M8 부하 → M9 운영/복구/보안 → M10 실장비.

M1부터 각 기능은 설계 → OpenAPI → Flyway → 구현 → unit/integration/contract → 가능한 kind → 증거 순서다.
첫 기능 목표는 Profile 등록부터 검증된 Result까지 연결하는 한 경로다.

## 위험과 재개

- Docker 접근: `bash scripts/preflight.sh compose`. 현재 권한 차단; 권한 있는 개발 환경에서 `dev-up.sh` 재실행.
- 외부 상세 계약: M1 registry 계약은 ADR 0002로 정합화. M2+ 상태 전이·외부 계약은 해당 단계에서 확정.
- MinIO: source build/live health/실제 S3 검증 완료. `bash scripts/dev-storage.sh`와 `bash scripts/test-storage.sh`로 재현한다.
- kind/실장비: 전용 context·namespace·소유 label을 준비한 뒤 해당 단계에서 구현. 기존 context를 변경하지 않는다.

## M4 실행 경로 구현 순서

M3가 저장한 요청을 실제 작업으로 연결한다. 아래는 구현 계획이며 검증 완료 기록이 아니다.
구성 요소별 확인 결과와 남은 연결 작업은 `docs/evidence/m4-runtime.md`를 따른다.

1. **실행 계약**: SERVICE spec의 digest 고정 이미지·entrypoint·입출력 포트·자원·arch/OS·timeout을
   정의하고, 기존 임의 JSON Profile은 변경하지 않은 채 소비 시 실행 가능성을 검증한다.
2. **PodSpec와 adapter**: AUTO 요구조건과 NODE hard affinity를 컴파일한다. nodeName을 쓰지 않고
   scheduler bind를 관측한다. 전용 runtime namespace·최소 RBAC·소유 labels로 실행 범위를 제한한다.
3. **영속 실행 상태**: RuntimeInstance·명령/outbox·claim/epoch를 새 migration으로 추가한다.
   결정적 Job 이름과 UID 대조, 요청 타임아웃 뒤 재조회, 재시작 후 조정으로 중복 생성을 막는다.
   목록 resourceVersion에서 watch를 시작하고 종료/410 뒤 재목록하며 주기적으로 상태를 조정한다.
4. **Runner와 결과**: Attempt 범위 인증, 입력 artifact 참조, 실제 실행, S3 업로드와 commit을 연결한다.
   저장소에서 object 크기·내용 SHA-256·version을 검증하고 현재 claim/epoch를 다시 확인한 뒤
   Result와 Task/Attempt 상태를 원자적으로 반영한다. Job Complete만으로 성공하지 않는다.
5. **BATCH와 취소**: 검증된 선행 결과만 하위 task의 입력으로 전달한다. 취소는 먼저 producer를
   차단하고 실제 Job/Pod 종료를 확인한다. 실패·누락 artifact·중복/늦은 commit을 실제 경로에서 검증한다.
6. **수용 증거**: Result API·UI·Swagger, 실제 MinIO, 격리 kind의 AUTO/NODE·불가능한 affinity·
   취소·API 재시작·잘못된 artifact·최소 BATCH DAG를 검증한다. 로컬 Docker 제한은 유지하며
   GitHub runner의 실제 kind 시험을 준비한다. 기존 demo-workflow/test-kind의 기준을 축소하지 않는다.

Kubernetes의 [노드 지정](https://kubernetes.io/docs/concepts/scheduling-eviction/assign-pod-node/),
[Job lifecycle](https://kubernetes.io/docs/concepts/workloads/controllers/job/),
[watch/relist](https://kubernetes.io/docs/reference/using-api/api-concepts/)를 확인했다.
S3 [무결성 계약](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html)에 따라
ETag 또는 사용자 제공 SHA metadata만을 실제 내용 검증으로 사용하지 않는다.


## M6 진행: VD 등록·원본 연결

ADR0013/V13의 영속 VD·불변 Profile 참조·원본 호환성·연결 이력·revision 수정·논리 해제와
장치 해제 보호를 구현했다. 공개5 API, 한국어 Swagger35개, `/virtual-devices` 관리 화면을 연결한다.
실제 PostgreSQL 동시 생성/수정/장치 해제 경합과 DB 제약, PC·모바일 실제 API 흐름을 검증한다.
상세 결과는 `docs/evidence/m6-vd-registry.md`다. 이 단계는 M6 전체 완료가 아니다.

다음 M6 구현은 지속 runtime·source/runtime binding 분리, provision/readiness·교체/drain Operation,
Run VD 정책의 실제 활성 runtime Task 실행과 demo-vd다. 등록 상태 REGISTERED를 Ready로 바꾸거나
Node ID만 복사한 별도 Job으로 실제 VD 실행 수용 게이트를 대신하지 않는다.
