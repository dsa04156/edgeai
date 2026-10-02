# M6 영속 VD lifecycle — V14 구성 요소

ADR0015/V14의 실행 세대·고정 설정·runtime binding·Operation·생성/삭제 명령 lease를 구현했다.
관리 중인 실행에 대한 기존 VD 수정/해제는 같은 트랜잭션에 replacement/drain을 저장한다.
공개 VD 생성은 여전히 등록만 수행하며 Kubernetes gateway/poll·VD Task 실행은 연결 전이다.

## 실제 PostgreSQL에서 확인한 동작

- 같은 키의 동시8개 provision은 Operation·runtime·binding·CREATE 각각 하나만 만든다.
  다른 내용의 키 재사용은409이고 재전송은 기존 결과를 반환한다. 제출만 된 runtime이나
  Pod Ready가 아닌 attestation은 준비 완료가 아니다. 실제 관측 경계의 Pod Ready와 유효한
  session/lease가 모두 주어졌을 때만 Operation을SUCCEEDED로 만든다.
- 이름 수정은 현재 실행과 진행 중 Operation을 유지한다. 원본 교체는 이전 실행을DRAINING으로
  만들고 target 생성은 기다린다. source binding을 닫아도 이전 runtime이 캡처한 Device의 해제는
  API409와 직접 SQL 제약으로 차단한다.
- 이전 실행의 종료 확인 뒤 generation2·새 UUID·최신 설정을 만들며 vdId를 유지한다. 기존
  runtime binding을 한번 닫고 새 binding을 연다. 서비스 객체를 새로 만들고 별도 트랜잭션에서
  처리해 DB에 저장된 요청만으로 이 흐름을 계속할 수 있는지 검증했다.
- 교체 중 VD 해제는 이전 Operation을SUPERSEDED로 보존하고 target을 생성하지 않는다.
  실제 종료 확인 후 원본 Device를 해제할 수 있다. 해제와 첫 Ready를8번 경합해 해제된 VD가
  작업 수용 상태로 남지 않는 것도 확인했다.
- CREATE가 아직 확정되지 않으면 종료 확인을 거절한다. 종료 확인·DELETE 완료 후 처음 보는
  Pod UID의 늦은 CREATE 응답이 도착하면 같은 DELETE를 다시 연다. 세대·binding은 되살리지 않는다.
- 명령 lease를8개 worker가 동시에 요청해 하나만 얻는다. 만료 후 새 owner가 같은 명령을 회수하고
  이전 owner의 완료·지연 요청은 반영하지 않는다.
- startup timeout·session lease 만료·drain 제한 시간과 강제 정리 요청을 확인한다. 만료 session은
  다시 Ready가 될 수 없다. 지정 Node의 UID 또는 이름 불일치도 거절한다. 실패한 실행을 정리한 뒤
  새 provision은 새 generation을 만든다. 과거 Ready Operation의 성공 이력은 보존한다.
- 직접 SQL로 snapshot·generation·Ready·종료·binding·Operation 이력을 위조하거나 삭제하는
  사례를 거절한다. VD 행 잠금, 부분 UNIQUE, 복합 FK, immutable/deferred trigger를 함께 적용한다.

## 로컬 증거 (2026-10-03 KST)

| testRunId | 범위 | 결과 |
|---|---|---|
| 20261002T154546Z-97772619 | 단위/MVC68개, 신규 service/repository/config 컴파일 포함 | PASS/0 |
| 20261002T155245Z-800e076c | 실제 PostgreSQL97개, lifecycle8개 및 해제/Ready 경합 포함 | PASS/0 |
| 20261002T155623Z-88a1a632 | 최종 실제 PostgreSQL98개, lifecycle9개 및 NODE 신원·실패 후 새 세대 포함 | PASS/0 |
| 20261002T155845Z-f0e2d626 | OpenAPI4개·MVC22개·생성 타입/패키징 원본 일치 | PASS/0 |
| 20261002T155928Z-74ac8081 | 실제 API/DB·PC/모바일10개·Swagger35개·DB 중단503/동일 프로세스 복구 | PASS/0 |

JUnit failures/errors/skipped는 최종 확인 시 모두0이다. Dashboard 런타임 소스는 변경하지 않았고
기존 빌드로 실제 HTTP 회귀를 수행했다. 생성 타입 변경은 Swagger 설명 주석뿐이다.
프로젝트 PostgreSQL을 복구해 유지하고 시험용 API/UI 프로세스는 종료했다.

첫 실행 `20261002T155048Z-34419faa`는96개 중1개 실패했다. 시험이 source DELETE 완료 receipt가
target CREATE보다 항상 먼저 lease된다고 가정했지만 두 명령의 available_at이 같은 시험 시각이고
UUID로 정렬되므로 순서는 정해져 있지 않았다. 실제 source 종료가 먼저 확인됐다는 필수 조건은
유지하고, 종료 확인된 source의 DELETE receipt와 target CREATE를 어느 순서로 처리해도 검증하도록
fixture를 수정했다. 서비스의 종료 확인/새 세대 조건이나 검증을 제거하지 않았다.

V14는 실제 DB에 적용됐다. 적용본 SHA-256은
`1b6552716bd3e61fad17884efcce507a593134912ef11c75b01201ee2bf8a41e`이며 이후 수정하지 않는다.
V1–V13도 변경하지 않았다. 재현은 `test-unit.sh`, `test-integration.sh`, `test-contract.sh`,
`test-profiles-stack.sh local`이다. 각 DB 시험은 별도 `vd-test-*` namespace와 합성 관측을 사용한다.

## 범위와 남은 게이트

이 시험의 Pod/Node UID·Ready·종료는 fixture이며 실제 Kubernetes 요청은 하지 않는다.
새 서비스 객체의 재개는 실제 API 프로세스 SIGKILL/재시작과 다르다. 실제 Pod 소유·TokenReview·
watch/relist·늦은 생성의 실제 정리, poll sequence 멱등 저장·lease 발급, 공개 Operation/상태 조회와 UI,
VD 정책을 통한 활성 runtime의 Task/Result 및 demo-vd는 이어서 구현한다.

이 신규 lifecycle 코드의 CI·배포도 확인 대상이다. 이전 감독 프로세스e442a5c는 CI37027216582의
5 jobs/JSON15개·실제 컨테이너27개·기존 kind22Run/S3결과20개와 배포까지 확인했다.
상세는 [감독 프로세스 기록](m6-vd-runtime.md)을 따른다. 이를 신규 V14나 실제 VD 전체 실행의
수용 증거로 사용하지 않는다. M5의 상태형 복원·외부 실제 계약과 M7–M10도 남는다.
