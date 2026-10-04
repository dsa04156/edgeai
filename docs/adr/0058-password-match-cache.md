# ADR0058 — HTTP Basic의 성공한 비밀번호 비교 재사용

2026-10-04. M8의 실제1,000대 관리 부하에서 API CPU 약5.15core가 관측됐다.
독립 JFR 진단의11,551개 실행 표본 중99.2%가 BCrypt 계산에 있었다. API 관리 계정의
같은 자격을 매 요청 BCrypt로 검증하는 비용을 줄이되 인증·비밀번호 저장 강도를 유지한다.

기존 HTTP Basic·CSRF·사용자 조회와 비밀번호 업그레이드를 유지한다. 관리 SecurityFilterChain의
DaoAuthenticationProvider에만 기존 DelegatingPasswordEncoder를 감싼 성공 비교 캐시를 적용한다.
PasswordEncoder bean을 새로 등록하면 Boot의 초기 평문 설정 처리 방식까지 바뀌므로 그렇게
등록하지 않는다. Runner·VD·Device 내부 토큰 체인은 변경하지 않는다.

성공한 `(입력 비밀번호, 저장된 encodedPassword)` 비교만 최대64개·고정30초 동안 재사용한다.
읽기로 만료를 연장하지 않는다. 실패는 저장하지 않고, 비밀번호 hash가 바뀌면 이전 성공과
다른 항목이 된다. 사용자의 존재·잠금/활성 상태·권한은 기존 provider가 매 요청 확인하며
인증 객체나 사용자/권한, HTTP 세션의 인증 상태를 이 캐시에 저장하지 않는다.

캐시 key는 프로세스마다 새32byte 비밀키를 사용하는 HMAC-SHA256이다. 두 문자열의 길이와
UTF-16 code unit을 구분해 입력하여 Unicode 치환/결합 모호성을 피한다. 캐시에는 HMAC과
단조 시계의 검증 시각만 남고 평문·저장 hash·사용자 정보·secret key를 출력하거나 영속화하지 않는다.
Mac 인스턴스를 요청 간 공유하지 않는다. 동시 최초 요청의 중복 검증은 허용하며 캐시 삽입만
동기화한다. BCrypt cost·encode·upgradeEncoding은 기존 encoder에 그대로 위임한다.

단위 검증은 실제 BCrypt와 고정 시계로 만료·용량·입력/저장 hash 변경·실패 미저장·Unicode·
동시 조회를 확인한다. 실제 Spring 인증 체인에서 성공 이후 잘못된 비밀번호/사용자, 쿠키만의
접근, CSRF 누락, 비밀번호 교체·권한 변경·계정 비활성화를 검증한다. 패키지 API 부하 시험에서도
Basic/CSRF 거절을 실제 HTTP로 확인하고, 동일 전체 규모 측정으로 성능 영향을 대조한다.
