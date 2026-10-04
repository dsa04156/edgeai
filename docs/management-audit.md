# 관리 요청 감사 기록

관리 API의 변경 요청을 실행하기 전에 접수 기록을 저장하고, 처리 후 관측한 HTTP 결과와
인증된 계정을 별도로 남긴다. [ADR0066](adr/0066-management-request-audit.md)의 M9 구성 요소다.

## 조회

Dashboard의 `/audit`에서 기존 관리 계정으로 연결한다. 최근 요청을 페이지로 조회하거나
응답 헤더 `X-EdgeAI-Audit-Id`의 UUID로 특정 요청을 찾는다. 접수 시각·작업·대상 UUID·
인증 주체·HTTP 상태를 확인할 수 있다. 인증 정보는 화면 메모리에만 두며 연결 해제로 지운다.

Swagger에는 다음 두 API의 역할, 매개변수와 결과를 설명한다.

- `GET /api/v1/audit-requests?limit=20&offset=0`: 최신 접수부터 조회. limit은1–100,
  offset은0–1,000,000이다. 변경 중인 목록의 offset 페이지는 스냅샷/손실 없는 export가 아니다.
- `GET /api/v1/audit-requests/{auditId}`: 서버가 발급한 감사 UUID 조회. 없으면404다.

`OUTCOME_RECORDED`는 HTTP 결과를 기록했다는 뜻이다. HTTP202는 비동기 작업의 접수이며
실제 작업 완료는 Run/Task/Operation을 조회해야 한다. `OUTCOME_UNKNOWN`은 결과가 아직
없다는 뜻으로, 처리 중이거나 장애로 결과 저장을 못 했을 수 있다. 실패/성공을 추정하거나
새 감사 ID로 작업을 재실행하지 않고 원래 API의 조회·멱등성 계약으로 상태를 확인한다.

## 저장 경계와 장애

`/api/v1/**`의 GET/HEAD/OPTIONS 외 요청을 인증·CSRF·핸들러보다 먼저 별도 DB 트랜잭션으로
접수한다. 저장할 수 없으면503 `AUDIT_STORE_UNAVAILABLE`을 반환하고 변경을 실행하지 않는다.
접수가 저장되면 서버가 UUID를 반환한다. 요청자가 보낸 같은 이름의 헤더는 채택하지 않는다.

처리 결과 저장 실패는 이미 실행된 변경이나 실제 응답을 덮지 않는다. 접수 기록은 남고
`edgeai.audit.storage.failures` 카운터의 `phase=completion` 및 audit UUID만 포함한 로그로
관측한다. 접수·조회 거절·비동기 미확정은 각각 `admission`, `denial`, `async`다. async Servlet
처리의 최종 결과는 현재 지원하지 않아 미확정으로 남긴다.

정상 조회는 기록하지 않는다. 관리 조회의401/403은 기록하되 이미 응답이 전송됐다면
감사 ID 헤더를 추가할 수 없다. 인증 실패의 입력 계정명은 신뢰하지 않으며 `UNAUTHENTICATED`로
표시한다. 정상 Basic 계정은 `LOCAL_BASIC/NAME`, 길이256 초과 또는 제어문자가 있는 이름은
`LOCAL_BASIC/SHA256`으로 저장한다. 미확정 요청의 주체도 인증된 것으로 추정하지 않는다.

원문 URL/query, 요청·응답 본문, 인증/CSRF/쿠키/토큰, IP/User-Agent와 예외 메시지는 저장하지
않는다. 등록된 operation ID·route template과 경로 UUID만 남기며 미등록 경로는 고정 이름으로
분류한다. 내부 Device/Runner/VD, 방화벽·외부 프록시, worker·직접 DB/설정 변경은 범위 밖이다.

V34의 접수/결과 테이블은 UPDATE/DELETE/TRUNCATE를 거절하며 결과는 접수당 최대1개다.
DB 관리자에 대한 변조 방지나 외부 서명 증거는 아니다. 보관 기간/삭제 정책이 정해지기 전에는
자동 삭제하지 않는다. 정상 요청의 저장 증가도 고려해야 하며 장치 부하 시험에서 누락을 확인한다.

복원 점검 모드에서는 저장을 끄고 기존 이력 조회만 허용해 DB 읽기 전용을 유지한다.
V1–V33 또는 V1–V34 복원 참조 점검을 지원하며 알 수 없는 schema는 거절한다.
사용자별 역할, 감사 보관/외부 저장, 도메인 변경과의 원자적 연결 및 전체 M9 수용은 남아 있다.

## 검증

```bash
bash scripts/test-management-audit.sh # 격리 DB/실제 패키징 API, 실패 주입·재시작 포함
bash scripts/test-management-audit.sh --transport compose # Compose PostgreSQL
bash scripts/test-profiles-stack.sh local # 실제 API·PC/모바일·Swagger·DB 장애/복구
```

감사 전용 시험은 생성한 DB에만 실패 trigger를 설치하고 종료 시 OID를 대조해 그 DB만 정리한다.
운영 DB에 오류를 주입하지 않는다. 원시 API 로그는 private `.tools`에 두며 공개 보고서는 합성
시나리오·개수·검증 여부만 담는다. [검증 근거](evidence/m9-management-audit.md)를 따른다.
