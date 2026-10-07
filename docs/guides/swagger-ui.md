# Swagger와 직접 API 호출

Swagger에서 관리 API의 입력·응답·오류를 확인하고 실행할 수 있습니다.
조회와 변경은 실제 연결된 API에 수행됩니다. 시험 데이터를 만들 때는 개발 환경을 사용합니다.

## 문서 열기

로컬 기본 주소는 `http://127.0.0.1:18080/swagger-ui.html`입니다.
관리 계약은 `/openapi.yaml`, 스트림 계약은 `/stream-openapi.yaml`로 제공합니다.
`/swagger-ui/index.html?contract=streams`는 내부 스트림 계약을 표시합니다.

현재 일반 관리 API와 Swagger는 Basic 로그인을 요구하지 않습니다.
예전 배포가 인증을 요구한다면 소스와 실행 중 이미지·설정의 차이를 먼저 확인합니다.
내부 Runner·VD·스트림 인증은 별도의 계약을 따릅니다.

## 조회 호출

```bash
curl --fail-with-body http://127.0.0.1:18080/api/v1/platform
curl --fail-with-body http://127.0.0.1:18080/api/v1/profiles/DEVICE
```

페이지 크기와 필터는 각 endpoint의 매개변수를 확인합니다.
목록이 빈 것과 API 연결 실패를 구분합니다.

## 변경 요청과 CSRF

쓰기 전 `GET /api/v1/csrf`에서 세션 쿠키와 토큰을 발급받습니다.
같은 세션 쿠키를 유지하고 응답의 `token`을 `X-CSRF-TOKEN` 헤더로 보냅니다.
Swagger UI와 Dashboard는 이 연결을 처리합니다.

직접 도구를 구현한다면 다음 순서를 유지합니다.

1. 쿠키 저장소를 유지하는 HTTP 클라이언트로 CSRF endpoint를 조회합니다.
2. 응답 토큰과 `EDGEAI_SESSION` 쿠키를 같은 변경 요청에 보냅니다.
3. 403이면 토큰·쿠키 짝과 조회 전용 모드를 확인합니다.
4. 재전송 전에 대상 리소스와 기존 요청의 결과를 확인합니다.

CSRF는 사용자별 역할·권한을 제공하지 않습니다. [접근 경계](../reference/access.md)를 확인합니다.

## 요청 결과 해석

| 예시 | 해석 |
|---|---|
| 프로필 발행 201 | 새 버전 생성 |
| 동일 프로필 재발행 200 | 기존 버전 반환 |
| 같은 버전의 다른 내용 409 | 불변 버전 충돌 |
| 비동기 요청 202 | 접수; 작업 완료는 별도 조회 |
| 삭제 409 | 참조 또는 상태 조건으로 삭제 불가 |

오류 본문의 코드와 대상 리소스를 함께 확인합니다. 감사 ID가 있으면 **감사 기록**에서 HTTP 결과를 추적할 수 있습니다.

## 계약 변경 후 확인

개발자가 OpenAPI를 바꿨다면 타입을 생성하고 계약 검사를 실행합니다.

```bash
corepack pnpm contract:generate
bash scripts/test/test-contract.sh
```

API 버전이나 서버를 재기동하지 않고 문서 파일만 바꾸면 실제 구현과 다를 수 있습니다.
관리 endpoint 목록은 [API 참고](../reference/api.md), 내부 계약 경계는 [API 영역](../architecture/api-scope.md)을 확인합니다.
