# M6 공개 VD 실행 요청·상태·화면 검증

2026-10-03 KST. 구현 범위는 ADR0018이다. VD Task 배정·실제 결과 연결·M6 전체 완료가 아니다.

## 동작

- provision/replace/drain 공개3 POST + execution GET, 기존 Operation 조회의 판별 가능한 VD 합집합.
- Basic·CSRF·UUID 키·엄격한 revision 입력. 새 요청202/재전송200과 Location, VD별 키 범위.
- 실행 준비·진행 Operation·세대/연결/작업 최근100개 이력과 truncation, REPEATABLE_READ 스냅샷.
- 준비 판단은 관측과 미만료 lease를 결합한다. 내부 session/nonce/configuration/자격은 반환하지 않는다.
- UI3초 갱신, 요청 확인/같은 키 재전송, 비활성/조회 실패/lease 만료와 상태/이력 표시.
- 기존 HTTP ingress에서도 요청 키를 생성할 수 있다. 등록과 실제 준비·Task 결과를 구분한다.

## 로컬 증거

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 최종 단위·MVC81개 | 20261002T175036Z-cfc26b5f | PASS/0 |
| 실제 PostgreSQL116개, 신규 공개 실행5개 | 20261002T174526Z-0f3977bf | PASS/0 |
| OpenAPI4개·생성 타입/패키징 일치·MVC25개 | 20261002T174631Z-02ddc974 | PASS/0 |
| lint/types/build·PC/모바일32개, HTTP API 부재 회귀 포함 | 20261002T175651Z-677b74ef | PASS/0 |
| 실제 Spring/Next/PG·PC/모바일10개·Swagger39·DB503/복구 | 20261002T175257Z-32f133c1 | PASS/0 |
| 종료 후1/2세대 이력 표시를 보강한 VD UI4개 | 20261002T175906Z-0bbe561d | PASS/0 |

공개 API 시험은 실제 Spring MVC/security와 PostgreSQL을 사용한다. 8개 동시 요청 중 하나만202,
나머지7개200이며 runtime/Operation 하나다. 준비 전 false, 교체 전 물리 종료 대기, 같은vdId의
새generation, drain의 물리 종료 전 RUNNING과 종료 후SUCCEEDED, 해제 후 이전 요청 재전송·
충돌·정수/중복필드/크기 검증·이력 제한·feature disabled 읽기 유지·lease 만료를 검증한다.
Pod 신원/Ready/물리 종료는 이 공개 API 시험에서 fixture이며 실제 Kubernetes 시험으로 해석하지 않는다.

UI 동작 시험은 HTTP 응답 fixture다. 서버 응답을1.8초 지연해1.5초 lease가 이미 만료된 경우에도
준비됨을 표시하지 않는 회귀를 포함한다. 최종 PC/모바일 화면은 `.tools/vd-public-ui/`에 보존했다. 실제 서버/DB의 등록·execution 조회·기본 비활성503·Swagger와
DB 장애/복구는 별도 stack 시험에서 통과했다. 실제 DB 중단 시 execution은503/VD_STORE_UNAVAILABLE이며
동일 API/UI 프로세스에서 DB 재시작 후 다시200으로 복구했다. PC/모바일 screenshot에서 넘침·겹침과 상태/세대/이력
계층을 직접 확인한다. 자격은 localStorage/sessionStorage에 저장하지 않는다.

## 발견·수정

첫 stack `20261002T174905Z-9dd7f570`은 Swagger의 기존35개 검사와 실제39개가 달라 PC/모바일2개가
실패하고 나머지8개는 통과했다. 새4개 API와 실제 contract를 확인하여 검사 수를39로 수정했다.
HTTP 배포에서는 secure-context-only randomUUID를 사용할 수 없어, 해당 API를 제거한 브라우저
회귀 `20261002T175034Z-72293697`에서 실행 요청 컨트롤이 진행하지 않는 것을 재현했다. 기존 Workflow의
getRandomValues UUID 생성 방식을 재사용하여 위 최종 UI32개를 통과했다. 실패 증거도 보존한다.

## 남은 수용

VD는 기본 비활성이다. 실제 Pod→감독→poll→Ready/교체/종료와 API 프로세스 재시작, VD Run의
Task 배정·claim·Result·취소/실패와 demo-vd는 후속이다. M5 상태형 복원·외부 실제 계약 수용,
M7–M10도 남는다. 신규 코드의 CI·배포는 push 이후 별도로 확인한다.
