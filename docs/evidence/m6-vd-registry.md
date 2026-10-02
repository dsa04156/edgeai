# M6 VD 등록·원본 연결 — 첫 수직 슬라이스

ADR0013/V13의 영속 VD·원본 연결 관리 API와 `/virtual-devices` 화면을 구현했다.
등록은 REGISTERED이고 실제 VD runtime Ready 상태가 아니다. 전체 M6 및 M0–M10 목표는 진행 중이다.

## 확인한 동작

- 불변 VD Profile과 실행 가능한 SERVICE Profile, 모든 DEVICE source Profile 참조를 검증한다.
  sensorMirror/processing은 필수 원본을 요구하고 emulation은 원본 없이 등록할 수 있다.
  source slot별 Profile UUID·LIVE/REPLAY/SYNTHETIC 호환성과 ACTIVE Device 조건을 검사한다.
- 같은 key/정규화 입력의 동시8개 생성은 VD 하나와 원본 binding 하나만 만든다.
  원본 교체·Device 해제·VD 해제 뒤 최초 생성 재전송도 기존 VD를 반환하며 다시 활성화하지 않는다.
- revision 동시8개 수정은 하나만 반영하고 나머지7개를409로 거절한다. 같은 설정은 revision을
  증가시키지 않고, 다른 slot을 바꿔도 유지된 원본 binding ID는 보존한다.
- 원본 교체 시 기존 연결을 닫고 새 연결을 추가한다. VD ID·Profile 참조·생성 신원은 불변이다.
  101번 교체 시험에서 최근100개 이력 밖의 오래된 활성 원본도 activeSources에 계속 표시한다.
- Device 행 잠금으로 새 binding과 Device 해제를 직렬화한다. 해제 경합8회를 실제 PostgreSQL에서
  수행했고 RELEASED Device를 가리키는 활성 연결은 생기지 않았다. 연결 중인 Device 해제는409다.
- DB 복합 FK·활성 partial UNIQUE·불변 이력 trigger·지연 필수 source 집합 검증이 잘못된 직접
  SQL 변경·삭제·재활성화·원본 누락을 거절한다. 실패한 생성/수정은 전체 트랜잭션을 되돌린다.
- 공개5 API와 한국어 Swagger35개, 생성 타입·패키징 YAML 일치를 확인한다. 읽기는 인증,
  POST/PATCH/DELETE는 CSRF를 요구하며 DB 상세 오류와 creationDigest는 공개 응답에 노출하지 않는다.
- PC·모바일에서 실제 등록→원본 교체→동시 수정 충돌→새로고침→해제까지 검증한다.
  거절된 수정 입력은 유지하고, VD ID와 원본 이력을 보존하며 원본 Device를 삭제하지 않는다.
  해제는 화면 내 확인을 거친다. 연결 정보는 브라우저 영속 저장소에 남기지 않는다.

## 로컬 증거 (2026-10-02)

| testRunId | 실제 범위 | 결과 |
|---|---|---|
| 20261002T142508Z-c8a7ba4f | 단위/MVC66개. VD 입력 규격·정규화·인증/CSRF·저장소 오류 포함 | PASS/0 |
| 20261002T142604Z-72e62b4d | 실제 PostgreSQL89개, 신규 VD9개 포함. V13·동시성·직접 SQL 제약·이력 | PASS/0 |
| 20261002T143829Z-cbaacf4e | OpenAPI3개 생성·MVC22개·Dashboard 타입과 JAR YAML 일치. metadata capability 갱신 포함 | PASS/0 |
| 20261002T143829Z-bb09dffe | lint/typecheck/build·PC/모바일 UI28개. 모바일 표 보완 후 최종 | PASS/0 |
| 20261002T144123Z-bf144394 | 실제 API/DB PC·모바일10개·Swagger35개·VD 포함 DB 중단503/동일 프로세스 복구 | PASS/0 |

JUnit failures/errors/skipped는 각 확인 시 모두0이었다. VD Profile JSON Schema는 예시, 필수 원본 누락,
필수 source가 없는 processing 거절과 원본 없는 emulation 허용을 별도로 검증했다.
원본 교체·해제 PC/모바일 스크린샷을 직접 확인했다. 긴 ID는 줄바꿈하고 좁은 화면의 표는 표 안에서
가로 스크롤한다. 문서 전체의 가로 넘침과 브라우저 예외는 없었다.

첫 OpenAPI 초안의 schema가 tags 뒤에 놓인 구문 오류는 YAML 파싱으로 재현한 뒤 schemas 아래로
옮겼다. 첫 UI 실행 `20261002T143413Z-22caccbd`는 Next.js의 빈 route announcer까지 alert 선택자가
포함해2개가 실패했다. 실제 내용이 있는 오류 영역으로 좁혔고28개 재실행과 최종 build/UI가 통과했다.
제품 동작 실패를 테스트 성공으로 덮거나 검사를 제거하지 않았다.

재현: `test-unit.sh`, `test-integration.sh`, `test-contract.sh`, `test-ui.sh`,
`test-profiles-stack.sh local` (Compose는 `compose`). 실제 API/UI 시험 프로세스는 종료하고
프로젝트 PostgreSQL은 복구해 유지했다. V1–V12는 수정하지 않았으며 적용된 V13도 이후 변경하지 않는다.

## 다음 게이트

이 코드의 신규 CI·이미지·실제 배포 확인이 남는다. 기존 Remote 코드45ce85f의 CI와 배포 성공을
새 VD 기능의 배포 증거로 사용하지 않는다. 해당 이전 결과는 [Remote kind 기록](m5-remote-kind.md)에 있다.

M6의 지속 VD runtime, source/runtime binding 분리, provision/readiness·교체/drain Operation,
Run VD 정책을 통한 실제 활성 runtime Task/Result, 재시작·실패·취소와 demo-vd는 아직 미구현이다.
이를 단순 Node 지정 Job이나 등록 API 성공으로 대체하지 않는다. M5 상태형 복원·실제 외부 계약,
M7 다중 장치/스트림, M8 부하, M9 운영/복구/보안, M10 실장비/모델 수용도 남아 있다.
