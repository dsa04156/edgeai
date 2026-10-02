# M6 — 인증된 poll·영속 순번·idle lifecycle

ADR0017/V15의 서버 poll을 감독 프로세스에 연결했다. 실제 VD Task 배정은 아직 없으며
assignments/cancel/ack는 비어 있다. 미배정 active/completed 보고는409이고 exitCode0을
Result 성공으로 처리하지 않는다. `EDGEAI_VD_ENABLED=false` 기본값을 유지한다.

## 검증한 동작

- HMAC 자격과 Pod identity 경계를 함께 통과해야 내부 API를 호출할 수 있다. Basic 사용자,
  다른 runtime 자격, 잘못된 Pod proof·본문 generation/session은 거절한다. 공개 쓰기의 CSRF와
  VD 비활성 시 내부 경로 거절도 유지한다. Pod UID 반영 지연은503이다.
- UTF-8 오류·중복/알 수 없는 필드·비정수/큰 수·소문자 UUID·목록 크기/중복/교집합을 검증한다.
  본문256KiB를 경계에서 제한하며 잘못된 요청은 poll row/session을 생성하지 않는다.
- 첫 sequence0·순차 증가·동일 bytes 재전송을 보존한다. 같은 순번의 다른 공백/내용, 과거 순번,
  순번 건너뛰기와 다른 session은409다. 서비스 객체를 재생성해도 저장한 순번을 사용한다.
- 동일 요청8개를 경합해 receipt 하나·동일 응답을 확인했다. 서로 다른 session2개를 경합해
  하나만200, 다른 하나는409를 받는다. DB의 순번 건너뛰기·같은 순번 해시 변경·명령 위조·삭제도 거절한다.
- 첫 poll의 RUN 권한과 실제 Pod Ready를 구분한다. Ready 관측 뒤에만 PROVISION 성공을 확정한다.
  Node UID 변경·만료 lease는 fence하고 초기 lease는 startup 잔여 시간으로 제한한다.
- 생성 반영 지연·Kubernetes 일시503은 순번을 잃지 않고 재전송한다. lease 만료 후에는
  같은 session으로 갱신할 수 없다. drain 이후 과거 RUN 응답을 재생하지 않고STOP으로 진행한다.
- supervisor 자체 DRAINING은 REPLACE Operation을 저장한다. 이전 Pod 종료 확인 전 target은
  없고 runtime 이력도 하나다. 현재는 배정된 작업이 없으므로 비어 있는 인증된 보고가 들어오면
  STOP을 저장한 뒤 종료를 지시한다. 물리 종료 확인 전 binding/Operation은 닫지 않는다.

## 실제 프로세스 시험의 범위

`VDPollIntegrationTest`는 실제 PostgreSQL, Spring의 실제 HTTP 서버/보안 chain과 호스트의
`runner/vd.py` 프로세스를 함께 실행한다. Python이 RUN 응답 뒤 실제 readiness marker를 만들고,
다음 poll을 거쳐 DB Ready에 도달하며 drain 시 서버 STOP 저장 뒤 실제 프로세스가0으로 종료하는
것을 확인했다. 503을 지속 주입하면 marker를 제거하고 lease 안에서 재시도한 뒤 실제 프로세스가
실패 종료하며 서버도LEASE_EXPIRED를 기록한다. 모든 자식 프로세스는 시험 후 종료한다.

이 시험의 Kubernetes TokenReview/Node/Ready 관측은 fixture다. Ready fixture는 실제 Python
marker 파일을 반영하지만 kubelet 자체의 관측은 아니다. 실제 클러스터 gateway 검증은
[ADR0016 증거](m6-vd-gateway.md)에 별도로 있다. 실제 Pod→서버 poll→Task/Result 전체 경로와
실제 API 프로세스 SIGKILL/재시작 수용은 이어서 검증해야 한다. 객체 재생성을 프로세스 재시작으로
표현하지 않는다.

## 로컬 결과 (2026-10-03 KST)

| testRunId | 범위 | 결과 |
|---|---|---|
| 20261002T170705Z-61e39c45 | 초기 실제 PostgreSQL109개, poll7개·실제 Python/HTTP2개 포함 | PASS/0 |
| 20261002T171058Z-c3e3fa50 | 최종 단위79개, poll 입력3개·비활성 내부 거절1개 추가 | PASS/0 |
| 20261002T171221Z-e5398b4b | 최종 실제 PostgreSQL111개, poll9개·동시session/Node/초기 lease 포함 | PASS/0 |
| 20261002T171436Z-941af76c | OpenAPI4개·MVC23개·공개 타입/패키징 원본 일치 | PASS/0 |

JUnit failures/errors/skipped는 모두0이다. 공개 UI/API 계약은 변경하지 않았다. 기존 UI 검증 범위는
유지하며 신규 내부 프로토콜은 실제 Spring 보안·DB·HTTP·감독 프로세스로 검증했다.
로컬 개발 PostgreSQL은 유지하고 시험 HTTP 서버와 감독 프로세스는 종료했다.

V15는 실제 DB에 적용됐으며 SHA-256은
`9267e6321d746cee30bd1717141116a3b670d011fb835c875a8d85839372def7`이다.
V1–V15 적용본을 수정하지 않는다. 재현은 `scripts/test-unit.sh`, `scripts/test-integration.sh`,
`scripts/test-contract.sh`이며 모든 셸 실행은 `rtk proxy`를 사용한다.

## 남은 게이트

이전 gateway cf499be는 CI37036686347의5 jobs/JSON15개·Runner27·kind22Run/S3결과20과
pin86ff9bd의 실제 이미지/Ready/PVC Bound/Argo Synced까지 확인했다
(`20261002T172048Z-00d56a91`). 이를 신규 V15나 poll 전체 배포 수용으로 사용하지 않는다.

신규 poll 코드의 CI·배포는 별도 확인 대상이다. 기본 비활성이므로 이미지 배포만으로 실제 VD
실행 활성화를 뜻하지 않는다. 공개 provision/교체/drain·Operation/상태 API와 화면, VD 정책의
실제 활성 Runtime Task 배정·claim/Result·취소·장애 복구 및 demo-vd를 구현해야 한다.
M5 상태형 복원·실제 외부 계약 수용, M7–M10도 남아 있으며 전체 플랫폼은 PARTIAL이다.

## d89d2bb CI와 실제 배포 확인

CI37040484693은 storage/runner/scaffold/images/gitops5 jobs 모두 success다. 내려받은
검증 result.json15개는 모두 PASS/exit0이며 실제 Runner 컨테이너27개와 기존 실제kind22Run을 포함한다.
고정 S3 artifact20개의 체크섬/바이트/계산값 검증과 생성한 kind 삭제를 확인했다. kind 종료 직전
Dashboard 컨테이너는 Running/Ready=false였다. 최종 kind UI 준비 상태의 증거로 취급하지 않으며
아래 기존 클러스터의 실제 Dashboard Ready/imageID 검증과 구분한다.
이는 poll 코드를 포함한 기존 실행 회귀이고 실제 VD Pod/poll/Task 종단 완료 증거는 아니다.
GitOps6d46ec4의 API/Dashboard/MinIO3개 imageID가 source d89d2bb의 pin과 일치하고 모두 Ready,
PVC Bound, Argo Synced인 것을 `20261002T175035Z-94ede97d`에서 확인했다. 공유 Ingress 제한으로
Argo aggregate health는 Progressing이며 이 제한을 숨기지 않는다.
