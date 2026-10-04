# ADR0066 — 관리 요청의 영속 접수 기록과 관측 결과

상태: 채택, 2026-10-04. M9 감사의 관리 HTTP 경계다. [검증 근거](../evidence/m9-management-audit.md).

관리 `/api/v1/**` 변경 요청은 인증/CSRF/핸들러 실행 전에 별도 트랜잭션으로 접수 기록을
저장한다. 저장 실패는503이며 핸들러를 호출하지 않는다. 서버가 생성한 UUID를
`X-EdgeAI-Audit-Id`로 반환한다. 요청자가 보낸 같은 이름의 헤더는 신뢰하지 않는다.

처리 후 별도 append-only 결과 행에 HTTP 상태, 종료 방식, 인증된 계정을 기록한다.
결과가 없는 접수 기록은 `OUTCOME_UNKNOWN`이다. 장애 뒤 이를 성공/실패로 추정하지 않는다.
HTTP202와 실제 Run/Operation 성공을 구분하며 이 기록은 도메인 트랜잭션의 성공 증명이 아니다.
결과 저장 실패 때 이미 실행된 변경을 재실행하거나 응답을 덮지 않고 접수 기록을 남기며
고정 이름의 오류 카운터와 비밀값 없는 audit ID 로그로 운영자가 감지하도록 한다.

정상 조회는 저장하지 않고, 관리 API 조회의401/403은 별도 거절 기록으로 저장한다.
인증되지 않은 요청의 Authorization 내용을 파싱해 사용자를 추정하지 않는다. 현재 신원은
기존 Basic 인증의 계정이며 사용자별 신원 제공자/역할 선택은 별도다. 정상 계정 이름은 저장하되
길이256 초과 또는 제어문자가 있는 이름은 SHA256 표현으로 구분한다.

정해진 route template·operation ID와 경로의 UUID만 저장한다. 원문 URL/query, 요청/응답 본문,
Authorization·Cookie·CSRF·토큰·IP·User-Agent·예외 메시지는 저장하지 않는다.
미등록 관리 경로는 `unmappedManagementRequest`로 분류하고 원래 경로는 남기지 않는다.

필터는 관리 SecurityFilterChain 안의 CSRF 앞에 두어 CSRF/인증 거절도 관측한다.
순서와 SecurityContext 수명은 [Spring Security 구조 문서](https://docs.spring.io/spring-security/reference/servlet/architecture.html)를 따른다.
내부 Device/Runner/VD, firewall의 선행 거절, 외부 프록시, 직접 DB/설정 변경과 비동기 worker는
이 필터 범위 밖이며 도메인/외부 감사 수용에 남긴다. 복원 점검 모드에서는 감사 저장도 하지
않아 실제 DB 읽기 전용 경계를 유지한다. 이미 저장한 감사 이력 조회는 허용한다.

접수/결과 두 테이블은 V34로 추가하며 UPDATE/DELETE/TRUNCATE를 거절한다. 결과는
접수 UUID당 최대1개다. 보관 기간/삭제 정책이 확정될 때까지 자동 삭제하지 않는다.
DB 소유자/관리자의 trigger 해제까지 방어하거나 암호학적 외부 증거를 제공하지는 않는다.

조회는 현재 관리 API와 같은 인증 경계에서 제한된 페이지/정확한 UUID로 제공한다.
offset 페이지는 요청 간 스냅샷이나 손실 없는 export가 아니다. 사용자별 권한·감사 보관 정책·
외부 저장·도메인 변경과의 원자적 연결·전체 M9 수용은 남는다.
