# 설계 출처

> **개발 기준 자료** — 단계별 설계·수용 조건과 당시 판단을 보존한 문서입니다.
> 현재 사용자 기능과 기본 설정은 [지원 범위](../reference/support.md)와 [API 참고](../reference/api.md)를 확인하세요.
> 아래의 과거 완료·미구현 표현을 현재 배포 상태로 해석하지 않습니다.

2026-10-01에 본문을 읽고 확인한 사용자 설계 문서다. 이 저장소에는 필요한 요구사항을 요약하고 원문 링크를 보존한다.

| 읽는 순서 | 문서 | 확인한 수정 시각 (UTC) |
|---|---|---|
| 1 | [전체 설계도](https://app.notion.com/p/3ecbafd382d681b295f4f878aad79160) | 2026-10-01 07:31:23 |
| 2 | [API 기능·도메인 정의서](https://app.notion.com/p/3ebbafd382d681feb4a5c3610d9d3b3b) | 2026-10-01 07:01:38 |
| 3 | [ERD](https://app.notion.com/p/3ebbafd382d681cf8568e6d870fe97f3) | 2026-10-01 07:01:33 |
| 실행 규칙 | [실행 지시·명령어](https://app.notion.com/p/3ebbafd382d681bd920ae91452b0463a) | 2026-10-01 07:33:54 |

실행 문서의 제목은 v0.1이지만 본문은 v0.2다. 최신 본문은 greenfield, contract-first, migration-first,
modular monolith, 최소 PostgreSQL→Spring→Next.js health 경로와 증거 기반 보고를 요구한다.

별도 상세 implementation-contract·verification-matrix 원문은 위 링크들에서 제공되지 않았다.
현재 로컬 파일은 네 문서에서 확인된 제약과 M0 수용 기준을 정리한 작업용 기준이며,
미제공된 전체 실행 계약을 읽거나 완성했다고 간주하지 않는다.
