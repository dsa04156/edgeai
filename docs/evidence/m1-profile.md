# M1 Profile 검증 기록

검증일: 2026-10-02. M0 이후 독립적인 Profile registry 수직 슬라이스.
Notion 4개 문서를 다시 조회했고 수정 시각이 기존 출처와 같음을 확인했다.
상세 계약은 ADR 0002, OpenAPI, Flyway V2에 정합화했다.

## 구현

- DEVICE/SERVICE/VD 등록·목록·버전 조회와 generated TypeScript 계약.
- canonical JSON 기반 SHA-256; 숫자 정밀도·Unicode 보존, 배열 순서 구분.
- 새 등록 201, 동일 내용 재등록 200, 변경된 동일 identity 409.
- PostgreSQL unique 및 UPDATE/DELETE/TRUNCATE 차단.
- UUID·발행 시각·원래 내용을 재등록에서도 보존. 실제 8개 동시 요청과
  서로 다른 내용의 경쟁을 검증했다. 같은 키를 세 종류에서 독립적으로 발행한다.
- Basic 인증, 기존 CSRF 보호, 사용자 자격 정보만 전달하는 Next.js 경로.
- desktop/mobile UI로 실제 PostgreSQL 등록·재등록·충돌·새 버전·이전 버전 조회.
- 100자 키의 모바일 줄바꿈, 9007199254740993 숫자 보존, 새로고침 전 메모리 인증.

## 로컬 근거

JDK 21, Node 22.23.2, PostgreSQL 16.15(프로젝트 전용 portable 실행), Chromium.

| 시험 | testRunId | 결과 |
|---|---|---|
| unit | 20261002T010706Z-4998bb63 | PASS / 0 |
| OpenAPI 생성 타입·HTTP 계약 | 20261002T011001Z-bf9d9fe0 | PASS / 0 |
| 실제 PostgreSQL·동시성·불변성·인증 | 20261002T011249Z-57917a21 | PASS / 0 |
| UI lint/typecheck/build + offline desktop/mobile | 20261002T011215Z-262a94c4 | PASS / 0 |
| 실제 Profile UI + DB 장애·503·복구 | 20261002T011310Z-810c8753 | PASS / 0 |

원시 로그와 result.json은 `docs/evidence/runs/<testRunId>/`에 보존하며 Git에서 제외한다.
브라우저 스크린샷은 `dashboard/test-results/`에 있으며 desktop/mobile을 직접 열어
배치·읽기·가로 넘침을 확인했다. 최초 UI 시험의 알림 선택자가 Next.js route announcer와
충돌해 실패한 이력(20261002T010842Z-c8a54cd8)은 보존했다. 실제 오류 메시지가 있는
알림으로 선택자를 한정한 후 해당 시험을 다시 통과했다.

## 한계 및 다음 단계

spec은 JSON 문서이며 kind별 실행 스키마·장치 호환성·이미지 유효성·참조 무결성을
검증하지 않는다. M2/M4/M6 소비 기능에서 구체화한다. 목록 offset은 요청 간 스냅샷이
아니며 concurrent insert 시 페이지가 이동할 수 있다. DB 소유자는 trigger를 해제할 수 있다.
발행 불변성 때문에 고유 키로 생성한 시험 행도 개발 DB에 보존한다.
M1에는 Kubernetes 동작이 없으며 kind·실장비 시험을 이 단계에서 실행했다고 주장하지 않는다.
전체 플랫폼 LOCAL_VERIFIED/FULL_ACCEPTANCE는 아직 아니다. 다음 단계는 M2 Device/Node.


## 최초 CI의 새 DB 초기화 실패와 수정

CI 36950026136의 storage는 성공했지만 scaffold의 PostgreSQL integration이 실패했다.
빈 DB에서 첫 Spring context가 public에 Flyway history를 만들고 V1이 edgeai schema를
생성한 뒤, 다음 context의 PostgreSQL 기본 schema가 edgeai로 바뀌었다. 이때 history는
public에 있으므로 Flyway가 비어 있지 않은 edgeai schema를 거절했다.
로컬의 별도 신규 DB에서도 같은 실패를 재현했다(20261002T011929Z-404a85e1).
`spring.flyway.default-schema: edgeai`를 명시하고 회귀 검사도 해당 값을 확인하도록 바꿨다.
기존 migration 내용, baselineOnMigrate, clean 설정은 변경하지 않았다.

수정 후 완전히 새 DB 통합 시험 `20261002T012015Z-0e19456e`는 PASS/0이다.
기존 로컬 DB는 별도 통합 시험 결과로 확인한다.
