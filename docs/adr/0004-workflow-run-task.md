# ADR 0004 — 불변 Workflow DAG와 실행 요청

2026-10-02. Notion API/도메인 및 전체 설계도의 M3를 구체화한다.

- Workflow는 고유 key와 표시 이름을 가진다. 동일 생성 입력은 같은 ID를 반환하고 다른 입력은 409다.
- WorkflowVersion은 MAJOR.MINOR.PATCH와 SERVICE ProfileVersion 참조를 가진 불변 DAG다.
  tasks는 key/serviceProfileVersionId/parameters, dependencies는 fromTask/toTask/fromPort/toPort/mode다.
  1–128개 task, 최대 512개 edge, 전체 JSON 64 KiB를 허용한다. 알 수 없는 필드·자기 연결·중복 edge·
  없는 task 참조·동일 입력 포트의 다중 producer·순환을 거절한다. 배열 순서를 정렬해 내용 digest를 만든다.
- 버전과 정규화한 TaskDefinition/TaskDependency를 한 트랜잭션에서 생성한 뒤 seal한다.
  seal 뒤 UPDATE/DELETE/TRUNCATE 및 definition/dependency INSERT를 DB trigger로 거절한다.
  Run은 seal된 버전만 FK로 참조한다. 같은 버전/내용 재발행은 200, 다른 내용은 409다.
- Workflow 상세는 versions 페이지(limit/offset)와 선택적 version 필터로 과거 DAG까지 조회한다.
- Run 생성에는 UUID Idempotency-Key를 요구한다. 같은 키/정규화 요청은 기존 Run을 반환하며,
  다른 요청은 409다. 취소 뒤 재전송도 새 Run을 만들지 않는다. 새 실행은 새 키를 사용한다.
- Run은 발행 버전과 parameters 및 AUTO/NODE 실행 정책을 고정한다. NODE는 관측된 Node UID를 참조한다.
  M3는 실행 요청과 초기 Task/Attempt를 영속화한다. Pod 배치·실행·결과 성공은 M4에서 확인한다.
  STREAM DAG는 정의 발행이 가능하지만 실행은 M7까지 501로 거절한다. VD 정책은 M6에서 추가한다.
- 각 정의에 Task 하나를 만들고, root는 READY + QUEUED Attempt #1/epoch1,
  의존성이 있는 task는 WAITING으로 시작한다. Attempt 생성 API는 공개하지 않는다.
- Run row lock으로 취소·내부 상태 전이를 직렬화한다. Run 취소는 모든 대기 task/attempt를 취소한다.
  Task 취소는 해당 task와 아직 실행 전인 하위 의존 task를 CANCELLED/SKIPPED로 닫는다.
  실행 중 상태는 CANCELLING으로 전환해 M4의 실제 종료 확인을 기다린다. 성공/실패 결과를 취소로 덮어쓰지 않는다.
  반복 취소는 멱등이다. 별도 branch가 남아 있으면 Run을 완료로 표시하지 않는다.
- Task당 하나의 활성 Attempt를 partial UNIQUE로 보장한다. Task identity를 유지한 새 Attempt는 M5에서 구현한다.
- 구조적 DAG 검증과 SERVICE 종류 FK는 M3 범위다. Profile의 이미지/포트 타입·실행 자원 호환성은 M4 소비 계약에서 검증한다.
- 실제 PostgreSQL 동시 요청, 불변 trigger/FK, 취소 전파, UI와 Swagger, CI/배포 증거를 단계별로 남긴다.
