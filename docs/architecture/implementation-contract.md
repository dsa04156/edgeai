# 구현 계약 — M0–M6 작업 기준

상태: 네 설계 문서에서 확인한 원칙과 초기화·Profile·Device/Node·Workflow/실행/Result·VD 구현 범위.
M4 구현·검증 완료, M5 재시도·명시적 노드 전환은 실제 kind·CI·배포 검증을 통과했다.
실행 측정·ADR0009 자동 전환·참조 Remote는 실제 kind·CI·배포 검증을 통과했다.
VD의 현재 수용 범위는 `docs/evidence/m6-vd-task-execution.md`를 따른다.
별도 전체 계약 원문은 아직 확인되지 않았다.

## 현재 수용 범위

1. JDK 21 / Gradle wrapper / Node 22 / pnpm lockfile로 반복 빌드 가능하다.
2. Flyway가 실제 PostgreSQL에서 schema를 초기화한다.
3. `GET /actuator/health/readiness`는 DB 연결을 포함하며 장애 시 503을 반환한다.
4. Next.js `GET /api/health`는 Spring readiness 결과를 전달하며 연결 실패를 UP으로 표시하지 않는다.
5. `GET /api/v1/platform`은 인증을 요구하고 마지막 완료 milestone=M4, capabilities=[profiles,devices,nodes,workflows,runs,tasks,results]를 반환한다. M4 완료 증거는 docs/evidence/m4-runtime.md를 따른다.
6. 개발 서비스는 loopback에만 publish한다. 비밀번호는 무작위 local `.env`로 관리한다.
7. 미구현 시험은 exit 2/BLOCKED를 반환한다. scaffold 성공을 전체 플랫폼 완료로 표시하지 않는다.

## M1 Profile 수용 범위

- DEVICE/SERVICE/VD 등록·목록·정확한 버전 조회를 제공한다.
- 발행 즉시 불변, identity=(kind,key,version), UUID는 미래 FK 대상이다.
- 새 등록 201, 정규화 내용이 같은 재등록 200, 같은 identity의 다른 내용 409.
- JSON 정규화 SHA-256 digest와 원래 발행 ID/시각을 재등록에서도 보존한다.
- PostgreSQL UNIQUE와 UPDATE/DELETE/TRUNCATE 차단 trigger로 불변성을 보강한다.
- 동시 등록·충돌, 실제 인증·CSRF, paging/filter/404/400/413/503을 검증한다.
- Dashboard 입력/오류/조회와 큰 숫자의 정밀도 보존을 실제 DB 연결에서 검증한다.
- spec은 비어 있지 않은 JSON 객체다. kind별 실행 스키마나 참조 무결성은 아직
  보증하지 않는다. 등록만으로 장치 연결·서비스 실행 가능 상태로 판정하지 않는다.
- 구체적인 형식·한도·정규화·인증·페이지 일관성은 ADR 0002와 OpenAPI를 따른다.

## M2 Device/Node 수용 범위

- DEVICE ProfileVersion을 참조하는 장치 생성·조회·revision 기반 이름 수정·논리 해제를 제공한다.
- 장치와 실행 노드를 구분한다. Kubernetes UID와 실제 core/v1 관측만 Node 목록에 반영한다.
- 활성 attachment/session은 장치당 하나이며, 교체·해제 후 이력을 보존한다.
- bootId 재전송은 같은 Session을 반환한다. 새 연결은 epoch 증가 및 이전 Session 차단을 보장한다.
- Observation은 현재 세션의 증가 sequence만 수락하며 동일 내용 재전송은 중복 저장하지 않는다.
- 수신 시각 기준 60초 경과 시 STALE, 새 세션은 UNKNOWN, 해제는 RELEASED로 조회한다.
- 실제 DB 동시성·FK, API/CSRF, PC·모바일 UI와 DB 장애·복구, 실제 Kubernetes 목록 대조를 검증한다.
- Kubernetes는 HTTPS/CA 검증 및 전용 get/list nodes 권한을 사용한다. 로컬 kubectl proxy는 loopback만 허용한다.
- API/DB의 합성 장치 시험은 물리 센서·스트림·작업 실행 검증을 의미하지 않는다. 상세는 ADR 0003을 따른다.

## M3 Workflow/Run/Task 수용 범위

- Workflow와 불변 DAG 버전을 등록·조회한다. SERVICE 참조·순환·중복 입력 포트를 검증한다.
- 동일 버전/내용은 200, 다른 내용은 409. 발행 트랜잭션과 DB seal trigger로 하위 정의까지 고정한다.
- UUID Idempotency-Key와 AUTO/NODE 정책으로 Run/Task/root Attempt를 원자적으로 생성한다.
- 재전송은 취소 후에도 같은 Run이며 입력이 다르면 409다. 공개 Attempt 생성 API는 없다.
- root READY/QUEUED, 하위 WAITING을 조회하며 Run 취소와 Task 취소의 의존성 전파를 지원한다.
- 독립 분기가 남으면 Run을 완료로 표시하지 않으며 성공·실패를 취소로 덮어쓰지 않는다.
- 실제 DB 동시성·불변성·FK·활성 Attempt 제약과 PC/모바일 UI·Swagger·DB 장애 복구를 검증한다.
- M3는 실행 요청 관리다. 실제 Pod/Runner/Result는 M4, STREAM 실행은 M7(현재 501)이다.
- 상세한 한도·상태·정규화 규칙은 ADR 0004를 따른다.

## M4 실행·Result 수용 범위

- SERVICE 규격을 소비할 때 digest 고정 이미지·입출력·자원·플랫폼·제한을 검증한다.
- AUTO 요구조건/NODE hard affinity를 Job으로 만들고 실제 scheduler bind를 관측한다.
- 현재 Attempt에 Runtime/명령 lease를 연결하고 재시작·생성 응답 유실을 조정한다.
- 내부 API는 Attempt HMAC과 실제 Pod-bound TokenReview를 함께 요구한다. Pending 관측은 재시도한다.
- Runner는 실제 workload를 실행하고 고정 버전 S3 artifact를 업로드한다. 서버는 실제 bytes를 검사한 뒤
  현재 producer/epoch·취소 상태를 다시 확인하여 Result와 Task/Attempt를 원자적으로 확정한다.
- 검증된 BATCH 선행 출력만 하위 입력으로 사용하며 실패·취소는 하위 전파와 실제 리소스 종료를 확인한다.
- 공개 Result API/Swagger/UI는 검증된 metadata만 노출한다. 인증 토큰이나 presigned URL은 공개 조회에 없다.
- 실제 kind에서 AUTO/NODE BATCH·CPU/affinity 부족·취소·API 재시작·artifact 오류·중복/늦은 producer를 검증한다.
- 계약의 상세는 ADR0005와 OpenAPI를 따르며, 실제 통과 여부는 M4 evidence로 판단한다.

## 후속 구현에서 유지할 불변 조건

- 발행 ProfileVersion·WorkflowVersion 불변.
- Device/Node/VD/Runtime 구분; VD runtime 교체 후 vdId 유지.
- retry/offload는 동일 Task의 새 Attempt; 늦은 producer 결과는 epoch/claim으로 차단.
- kube-scheduler가 최종 bind; 일반 `nodeName` 우회 금지.
- 실제 runtime 상태 watch/reconciliation; Job 성공만으로 Result commit 금지.
- 외부 호출은 adapter 경계, timeout·retry budget·terminal error 구분.
- 비동기 작업 식별자/상태 및 runId/taskId/attemptId/runtimeId 관측 필드.
- idempotency와 DB→외부 전송 일관성은 관련 write 경로 설계 때 함께 확정.

## 미확정

상태형 checkpoint/복원·M7 STREAM 상세 계약, 운영 사용자 identity/RBAC,
2세부 실제 API, 실장비 inventory, GPU/NPU 공유 방식, 성능 수용 수치.
참조 Remote 및 VD의 정합화된 계약을 외부 실제 시스템 계약으로 간주하지 않는다.


## M5 재시도 계약

ADR0006·OpenAPI RetryPolicy와 Flyway V6를 따른다. Run의 retry는 선택 입력이며 기본 최초1회다.
같은 Task의 number/epoch를 증가시킨 새 Attempt를 만든다. 정책은 최대횟수·고정 backoff·첫 Attempt부터의
재시도 창·허용 오류를 갖는다. RETRY_WAIT/예약은 DB에 남고 이전 Runtime 종료 및 미완료 CREATE 부재를
확인한 뒤 재실행한다. 취소·commit·retry는 같은 Run 잠금으로 직렬화하며 하위 해제는 성공한 Result만 허용한다.
명시적 노드 전환은 ADR0007·V7을 따른다. 재시도 예산은 INITIAL+RETRY를 세고 OFFLOAD는 별도8회다.
재시도 target은 마지막 Attempt에서 유지하며 Run의 최초 배치 정책은 변경하지 않는다.

## M5 실행 중 전환 계약

`POST /tasks/{taskId}/offload`는 현재 sourceAttemptId·targetNodeId·drain/start 제한과 Idempotency-Key를 받는다.
SERVICE의 recovery.mode=RESTART 선언과 실제 RUNNING producer가 있어야 한다. 이전 Attempt를 OFFLOADED로
차단하고 Runtime 종료·CREATE 완료를 기다린 뒤 동일 Task에서 새 Attempt/epoch를 만든다.
`GET /operations/{operationId}`와 Task.offloads는 전환 이력을 제공한다. SUCCEEDED는 새 target claim이며
TaskResult 확정과 구분한다. 취소/마감/commit/재시도는 Run 잠금으로 직렬화한다.
자동 정책·Remote·상태형 복원·STREAM route/generation은 이 명시적 BATCH 전환으로 대체하지 않는다.

## M5 실행 측정 계약

ADR0008·Runner 내부 telemetry API·TaskDetail.telemetry·V8을 따른다. 현재 producer가 보고한
cgroup CPU/메모리와 서비스 개별 지연을 단위와 출처를 유지해 저장하며, 미수집은 null이다.
Run 잠금 아래 인증/epoch/시각/순번을 확인하고 동일 재전송을 멱등 처리한다. 종료/전환 뒤 거절한다.
최신64개 보관, 최신 Attempt 분리와 UI 만료 표시는 자동 판단의 입력 기반이며 자동 정책 자체는 아니다.

## M5 자동 전환 계약

RunCreate.offload는 생략/null이면 비활성인 불변 정책이다. 재시작 가능한 모든 SERVICE에만 허용한다.
최초 NODE 지정도 자동 전환 시 AUTO로 바뀔 수 있음을 API/화면에 명시한다. 같은 지표의 연속 유효
표본, minRunning/cooldown 이후 새 표본, 전환 예산과 호환 대체 노드 관측을 요구한다.
Operation.trigger/decision은 판단 당시 정책·표본을 보존한다. 자동 targetNodeId는 null이며
Attempt.excludedNodeNames를 PodSpec의 노드별 NotIn 조건으로 AND 결합한다. claim과 RETRY도
제외 목록을 지킨다. 상세 범위·단위·경계는 ADR0009와 OpenAPI를 따른다.

## M5 Remote 계약

ADR0010–0012와 V10–V12의 참조 제공자 경계를 따른다. 불변 provider binding과 별도
RemoteAllocation을 사용하며 Kubernetes Job/Pod 신원을 만들지 않는다. 공개 REMOTE
Run/명시적 offload, worker의 제출·조회·취소·종료 확인, 실제 고정 S3 입력/출력과 producer
검사를 연결한다. 외부 실제 2세부 API 및 상태형 복원 수용은 별도로 남아 있다.

## M6 VD 계약

ADR0013–0020와 V13–V18을 따른다. 영속 VD, source binding과 runtime binding을 분리하고
revision·세대·session·lease를 보존한다. 공개 생성/조회/수정/해제와 기동/교체/drain Operation을
제공하며 Ready는 실제 Pod 관측과 인증된 supervisor poll을 함께 요구한다.

공개 VD Run은 같은 namespace의 Ready VD와 같은 SERVICE 버전을 요구한다. 실제 Task는
해당 지속 Pod의 별도 자식 Runner로 실행한다. 배정·poll receipt·완료는 VD→Run 잠금 아래
원자적이며 재전송은 중복 실행을 만들지 않는다. slot은 실제 종료 보고·증명된 미시작 또는
supervisor Pod의 실제 종료 뒤에 반환한다. 취소 하나가 공유 Pod를 삭제하지 않는다.

Task HMAC과 Pod-bound 신원, 현재 배정/세대/session/lease로 claim·결과를 검증한다.
고정 버전 S3 내용 검증과 producer 재검사 뒤에만 Result를 확정하고 실제 vdRuntimeId/Pod를
저장한다. 교체는 기존 작업 drain·물리 종료 뒤 새 세대를 만든다. VD CPU/메모리는 공유
컨테이너 값이므로 작업별 자동 offload는 거절한다. 기본 로컬 VD 실행은 명시적 활성화가 필요하다.
