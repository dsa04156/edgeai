# M5 Retry / Offload / Remote — 진행 중

M0–M4 완료 후 M5의 재시도 수직 슬라이스를 구현했다. 전체 M5 완료 판정은 아니다.
실행 중 offload·fence/drain/route 전환과 Remote adapter/실제 외부 연동이 남아 있다.

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

## 실제 kind 게이트 — 구현, 신규 CI 검증 전

기존 M4 시험에 다음을 추가했다. 소유 label이 확인된 일회성 kind 클러스터에서만 장애를 주입한다.

1. 실제 Runner의 workload 자식 프로세스 강제 종료 → RETRY_WAIT 확인.
2. API 교체 → DB 예약 복구 → 같은 Task에서 Attempt/epoch2와 다른 producer Pod.
3. 검증된 단일 Result·하위 BATCH 해제, 고정 S3 version의 SHA/크기/실제 계산값 대조.
4. 이전 producer 늦은 commit 차단, Run의 Job/Pod/Secret 전부 정리.
5. 프로세스 실패2회로 예산 소진·하위 미실행 및 RETRY_WAIT 취소·추가 Attempt 없음.

이 신규 시나리오의 CI·이미지·배포 검증은 아직 남아 있다. 최신 상태는 아래 기록을 따른다.
외부 Remote API, 실제 장비/모델과 성능 합격 기준도 별도 수용 증거를 요구한다.
