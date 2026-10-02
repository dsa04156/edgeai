# M6 VD Task 배정의 영속 기반

2026-10-03 KST. ADR0019의 첫 구현인 V16/domain/repository 검증이다.
공개 VD Run 정책·poll Task 전달·Runner 인증/결과 서비스 연결은 아직 구현하지 않았다.
기존 API는 VD Run을 수용하지 않으며 일반 Job 실행 경로도 VD runtime을 거절한다.

## 저장 계약

- Run/Attempt는 불변 VD 대상 UUID를 저장한다. retry와 BATCH 하위 초기 Attempt가 대상을 계승한다.
- Task RuntimeInstance의 공급자 VD는 Job/Remote와 별개이며 Job 이름·UID가 없다.
  Job/Remote worker 조회와 명령 lease에서 제외하고 CREATE/DELETE Job 명령을 만들지 않는다.
- VDTaskAllocation은 작업 runtime·VD·실행 세대·session·Pod·slot·최초 poll sequence를 보존한다.
  현재 Ready·유효 lease·동일 SERVICE/namespace/VD, 제한 내 slot과 열린 slot UNIQUE를 적용한다.
- Result commit과 취소 요청만으로 slot을 닫지 않는다. 작업 runtime의 종료 확인을 요구하고,
  POD_GONE 종료는 VD runtime의 종료 기록도 요구한다. 완료 이력은 수정·삭제/재개할 수 없다.
- VD Result의 실제 Pod UID와 vdRuntimeId는 해당 allocation을 FK로 참조한다.
  기존 결과·artifact의 불변 봉인과 Job/Remote 공급자 제약도 유지한다.
- `ExecutionRepository.run(id,true)`는 고정 VD를 먼저 잠근 후 Run을 잠근다.

V16은 실제 로컬 DB에 적용됐다. SHA-256은
`5686bc81fe11522477fa5696c83bf7c5f46b36667aa455a6685e6702717ee191`이며 이후 수정하지 않는다.

## 검증

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 기존 포함 실제 PostgreSQL124개, 신규8개 | 20261002T185140Z-9ee795a9 | PASS/0, skip0 |
| 단위/MVC81개 | 20261002T185235Z-ed2e1a4a | PASS/0 |
| 계약4개·타입/패키징 일치·MVC25개 | 20261002T185330Z-24eda9ca | PASS/0 |
| 실제 API/DB·PC/모바일10·DB503/동일프로세스 복구 | 20261002T185414Z-ae0c107d | PASS/0 |
| 실제 S3/DB/참조 provider의 기존 결과·worker14개 | 20261002T185717Z-20005ece | PASS/0 |
| 빈 DB에 V1–V16 적용·실제 Kubernetes VD 수명3개 | 20261002T185539Z-65044df4 | PASS/0 |
| Job/VD 관측 경계 회귀 포함 최종 단위82개 | 20261002T190136Z-bee6adf7 | PASS/0 |

신규 시험은 8개 동시 요청의 2개 slot 배정·6개 대기, 저장된 배정 재조회, 초과 slot/중복 slot 거절,
취소 요청 후 slot 유지·종료 뒤 재사용·종료 이력 불변성, SERVICE 불일치·만료 lease·다른 session/Pod/Node,
drain 중 기존 작업의 신원 검증, VD 물리 종료 관측 전 POD_GONE 거절, 정확한 결과 공급자 FK와 봉인을 확인한다.
두 실제 DB 연결로 VD 잠금 대기 중 Run 잠금은 아직 잡지 않았음을 NOWAIT 조회로 확인했다.

VD Ready/Pod 종료 관측과 Result의 artifact 검증 receipt는 이 신규 시험에서 명시적인 fixture다.
exitCode0 보고만으로 Result가 생성되지 않는 것도 확인했다. 실제 VD Task 계산·S3 결과 종단으로 해석하지 않는다.
별도 실제 S3/DB 시험은 기존 Job/Remote 결과 경로의 회귀다. 임시 Kubernetes API/DB에서 새 JAR을
검증한 시험은 기존 VD idle 실행의 AUTO/API 재시작·NODE·시작 실패와 자원 정리를 포함하며,
새 Task 배정 실행 시험은 아니다. 종료 뒤 두 namespace의 시험 자원0개를 별도 조회했다.

첫 실행 `20261002T184802Z-07c87392`는122개 중1개 실패였다. 결과 저장 fixture가 Job UID를 필수로
요구하는 RuntimePod 타입에 null을 넣었다. VD allocation을 검사하는 별도 repository claim 경로를
추가하여 Job 신원 규칙을 유지했고, 신규6개 `20261002T184931Z-f7a6b433`과 최종124개가 통과했다.

추가 검토에서 Job 목록에 VD Attempt ID가 섞이면 Job 이름 null 접근으로 해당 감시 주기가
중단됨을 `20261002T190109Z-380f673c`에서 재현했다. 실제 변경은 Job 관측에서 VD 공급자를
제외하는 조건1개다. 회귀는 해당 관측을 무시하고 watch를 계속하는지 확인하며 최종 단위82개가 통과했다.
이 조건 추가는 위 DB/HTTP/S3 시험의 저장·응답 동작을 바꾸지 않는다.

## 남은 연결

poll receipt와 배정·완료 acknowledgement의 원자 처리, VD child Runner 인증과 claim/commit,
취소·drain·runtime 장애 시 모든 배정의 종료/재시도, 공개 VD 실행 정책·Swagger/UI,
실제 supervisor→Runner→S3→Result와 Kubernetes/kind 수용 검증을 이어간다.
새 코드 CI·배포와 전체 M6 완료는 이 문서의 로컬 PASS만으로 판정하지 않는다.

후속 확인: source54fc671의 CI37051755016은5개 job 및 다운로드한15개 result.json 모두PASS/0이다.
kind VD 수명3개와 기존 Task/S3 경로를 통과했다. pin578ce77의 실제 API/dashboard/MinIO imageID,
Ready·PVC Bound·Argo Synced 대조는 `20261002T194048Z-458246f4` PASS다. 공유 Ingress에 따른
aggregate health Progressing은 유지한다. 이 이전 CI의 `taskExecution=false`는 VD Task 실행을
포함하지 않음을 뜻하며, 이후 연결과 검증은 [작업 실행 기록](m6-vd-task-execution.md)을 따른다.
