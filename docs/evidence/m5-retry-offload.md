# M5 Retry / Offload / Remote — 진행 중

M0–M4 완료 후 M5 재시도와 명시적 실행 중 NODE 전환의 실제 kind·CI·배포를 검증했다.
자동 판단을 위한 [실행 측정](m5-runtime-telemetry.md)을 구현·검증 중이다. 전체 M5 완료 판정은 아니다.
자동 전환 정책·Remote adapter/실제 외부 연동·상태형 복원/STREAM route는 남아 있다.

## 구현한 계약

- ADR0006·OpenAPI RetryPolicy·Flyway V6. V1–V5는 변경하지 않았다.
  V6는 로컬 실제 PostgreSQL에 적용되었으므로 이후 변경은 새 migration으로 한다.
- Run 생성의 선택 정책: 최초 포함 최대1–8회, 고정 backoff1–300초,
  첫 Attempt부터의 재시도 창1–86400초, 허용 오류 코드. 기본 최초1회는 이전 digest를 유지한다.
- 동일 Task ID, 새 Attempt UUID/number/epoch/claim/Job/artifact 경로.
  실패 Attempt를 FAILED로 보존하고 Task RETRY_WAIT와 예약을 같은 DB 트랜잭션에 저장한다.
- Run 잠금으로 실패·재시도·취소·결과를 직렬화한다. 이전 Runtime 종료와 CREATE 명령 완료를
  확인하기 전에는 새 Attempt를 만들지 않는다. API 재시작 후 DB 예약으로 재개한다.
- 재시도 중 하위 WAITING 유지, 예산 소진/창 만료 시 FAILED→하위 SKIPPED.
  취소는 예약을 없애고 물리 종료를 기다린다. 이전 Runtime의 늦은 종료가 새 실행의 취소를
  조기 완료시키지 않도록 모든 관련 Runtime 종료를 검사한다.
- Dashboard 정책 입력/조회·재시도 대기·기존 Attempt 이력을 표시한다.
  한국어 Swagger의 RunCreate/WorkflowRun에 같은 정책을 문서화했다.

## 로컬 직접 증거

모든 기록은 `docs/evidence/runs/<testRunId>/result.json`의 PASS/exit0이다.

| testRunId | 확인 범위 |
|---|---|
| 20261002T090148Z-91b43552 | 단위/MVC39, 정책 범위·오류 코드·중복·정수 정밀도 거절 |
| 20261002T090648Z-eec747eb | 최신 OpenAPI 생성 타입·JAR YAML·MVC15 일치, M4 metadata |
| 20261002T091025Z-eeb2e8bc | 실제 PostgreSQL50, 새 재시도9개 및 기존41 회귀 |
| 20261002T091025Z-8c119d3c | UI lint/type/build·PC/모바일16, RETRY_WAIT/실패 이력/취소/빈 결과 |
| 20261002T091200Z-abac42bb | 실제 DB/API의 PC/모바일8, 정책 저장·조회·멱등 요청·취소와 DB503/복구 |

DB 재시도 시험은 backoff·실제 종료 대기, 미완료 CREATE 차단, 두 번째 Attempt 성공·단일 Result·
하위 해제, 횟수/기간 소진, 재시도 불가 오류, 재시도 대기 중 취소, 취소와 재시도의 경합,
동시8 worker에서 새 Attempt/Runtime 단1개, 이전 commit permit 차단, 정책 재전송을 확인한다.
Kubernetes 신원과 storage receipt는 이 DB 시험에서 명시적인 fixture이며 실제 종단 실행을 대신하지 않는다.
정책 입력의 실제 PC/모바일 스크린샷 `dashboard/test-results/workflows.integration-*/retry-policy.png`를
직접 검토했다. 두 폭에서 입력·오류 선택·버튼이 겹치지 않고 기존 UI 간격을 유지한다.
재시도 대기 상태도 Result UI 시험의 `retry-wait.png`로 확인했다.

## 실제 kind 재시도 게이트 — CI 검증 완료

기존 M4 시험에 다음을 추가했다. 소유 label이 확인된 일회성 kind 클러스터에서만 장애를 주입한다.

1. 실제 Runner의 workload 자식 프로세스 강제 종료 → RETRY_WAIT 확인.
2. API 교체 → DB 예약 복구 → 같은 Task에서 Attempt/epoch2와 다른 producer Pod.
3. 검증된 단일 Result·하위 BATCH 해제, 고정 S3 version의 SHA/크기/실제 계산값 대조.
4. 이전 producer 늦은 commit 차단, Run의 Job/Pod/Secret 전부 정리.
5. 프로세스 실패2회로 예산 소진·하위 미실행 및 RETRY_WAIT 취소·추가 Attempt 없음.

이 신규 시나리오의 CI·이미지·배포 검증은 아래와 같이 완료했다.
외부 Remote API, 실제 장비/모델과 성능 합격 기준도 별도 수용 증거를 요구한다.

### 재시도 CI와 실제 배포 — 2026-10-02

- b0b31c1의 CI36988678375는 신규 재시도 전에 기존 active-Job API 교체 시험에서 실패했다.
  rollout 직후 조회503, 정리 요청의 이전 세션 CSRF403을 관측했다. 503의 정확한 내부 원인은 확정하지 않는다.
- e777233은 이전 API Pod UID가 사라질 때까지 기다리고 새 CSRF 세션을 받는다. 명시적인 복구 구간의
  GET 연결 실패/502/503/504만 제한 시간 안에 재조회한다. 일반 요청·POST·401은 재시도하지 않는다.
  실제 로컬 HTTP 응답으로 503→200·401/POST 비재시도·지속503 제한을 확인했다.
- CI36990194234의 scaffold/storage/runner/images/gitops 모두 success다. 내려받은 result.json14개가
  PASS/exit0이며 실제 kind 결과20261002T093609Z-aa973f1a도 PASS다. 12개 Run 시나리오에 신규
  retry-restart-recovery/retry-exhaustion/cancel-retry-wait가 포함된다. 두 Attempt·기존 producer 차단·
  단일 Result·하위 결과·자원0개와 실제 S3 계산값 대조를 확인했다.
- GitOps pin f209efe를 반영하고 `20261002T094921Z-7d583def`로 e777233의 API/UI/MinIO 실제 imageID,
  Ready·PVC Bound·Argo Synced를 확인했다. 기존 공유 Ingress status 문제로 aggregate health는 Progressing이다.

## 실행 중 NODE 전환 — 로컬·실제 kind·배포 검증 완료

ADR0007·V7·OffloadService/Worker·OperationController·UI를 구현했다. V7은 로컬 PG에 적용했으므로
이후 수정하지 않는다. SERVICE recovery.mode=RESTART 선언을 요구하고, 실제 RUNNING producer를
fence한 뒤 물리 종료와 CREATE 완료를 기다린다. 같은 Task의 새 Attempt/epoch로 다른 NODE를 지정하고
새 producer claim을 확인하면 Operation을 성공 처리한다. TaskResult 성공은 별도다.
동일 요청 키·다른 입력409, 최대8회, 단일 진행 Operation, 취소·drain/start 마감·재시도 target 유지와
별도 retry 예산을 검증했다. 상태형 checkpoint·자동 policy·Remote·STREAM 전환의 대체 증거는 아니다.

| testRunId | 확인 범위 |
|---|---|
| 20261002T095913Z-2914e26f | 실제 PostgreSQL60: offload10개 + retry/기존50 회귀, 실제8회 전환 후9번째 거절·현재 producer 보존 |
| 20261002T100001Z-fac8c0c9 | 단위/MVC42, 새 Operation 인증·CSRF·키 필수·404/503·202/200 및 Location/내부 필드 비노출 |
| 20261002T100015Z-fb05931b | 계약 생성 타입/YAML 일치 및 선택 MVC18 |
| 20261002T094742Z-ecdb197a | PC/모바일 UI18, 전환 상태·동일 키 재전송·빈 결과 유지 |
| 20261002T095243Z-b6c8245c | 실제 DB/API PC·모바일8, Swagger30, DB503/복구 |
| 20261002T100001Z-625bb761 | 실제 Kubernetes API에서 전체 dev overlay server dry-run, Recreate upgrade 설정 수용 |

UI lint/type/build는 20261002T094102Z-87d9539e에서 성공했다. 이후 UI 시험의 alert 선택자만 수정하여
동일 build로 위 브라우저18개를 재검증했다. desktop 요청 폼과 mobile 전환 성공 스크린샷을 직접 확인했다.

실패 및 수정 이력:

- 20261002T093652Z-ee97a426: RuntimePod fixture의 jobUid/podUid 순서 오류. 올바른 UID로 수정했으며
  producer 신원 검사 코드는 완화하지 않았다.
- 20261002T094100Z-a7e7fccb: source가 이미 종료된 OFFLOADING Task 취소가 CANCELLING에 머무는
  회귀를 재현했다. cancelTask의 즉시 종료 후보에 OFFLOADING을 추가하고 기존 종료 확인 조건은 유지했다.
  source 종료 뒤 추가 콜백 없이 취소가 완료됨을 재검증했다.
- 20261002T094102Z-87d9539e: Next 빈 route announcer와 오류 alert가 중복 선택됐다. 오류 문구로 한정했다.
- 20261002T094830Z-c9251769: Mockito 예외 stub 재정의 중 기존 예외가 발생했다. doThrow로 수정했고
  실제 HTTP 오류 처리 변경 없이 단위41개가 통과했다.
- 20261002T095803Z-c293a158: 추가 MVC 시험의 Creation import 경로 오류를 수정했고 후속 단위42·PG60을 통과했다.

V7의 필수 Attempt 필드와 OFFLOADING 상태를 구버전 API가 지원하지 않으므로 단일 API upgrade를
Recreate로 변경했다. 실제 서버 dry-run은 통과했으며 새 kind에서 API 재시작·예약 복구를 다시 검증한다.
새 API 준비 중 접속 중단이 있고 V7 이전 바이너리로 단순 rollback할 수 없다. M9의 호환 migration/HA는 남아 있다.

실제 kind 신규 조건은 기존 disposable cluster에서만 노드 cordon/API restart를 사용한다.
root와 입력을 받는 child의 노드 이동, STARTING 중 API 재시작·같은 target Attempt 복구,
이전 Pod 부재·늦은 commit 차단·고정 BATCH 입력·단일 Result·target 시작 취소/마감·자원 정리를 검사한다.
f886dd7의 CI36993166041에서 이 조건을 통과했다. 세부 증거는 다음과 같다.

### 실제 offload CI와 배포 — 2026-10-02

5 jobs 모두 success, 내려받은 result.json14개가 모두 PASS/exit0다. 실제 kind
20261002T100931Z-cb4dd0a2의 15개 Run에는 running-offload-restart-and-batch-input,
cancel-offload-starting, offload-start-timeout이 포함된다. root·child 각각 동일 Task의 Attempt/epoch2,
실제 target Node/다른 Pod, source Pod 부재, 늦은 commit401, 결과1개씩, 고정 입력 계산값,
root STARTING Operation/API 재시작 복구, 취소/마감 및 Run 자원0개를 확인했다.
pin94a5e24를 반영한 실제 배포 시험20261002T102330Z-bcec2e83도 PASS다. f886dd7의 API/UI/MinIO
imageID·Ready·PVC Bound·Argo Synced를 확인했다. aggregate health는 기존 공유 Ingress 문제로 Progressing이다.
