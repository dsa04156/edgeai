# Swagger UI와 CI/CD 연결 상태

Swagger UI 5.32.15 WebJar를 JAR에 포함하고 `/swagger-ui.html` → `/swagger-ui/index.html`로
제공한다. 문서 원본 `/openapi.yaml`은 Gradle processResources가 기존 계약 파일을 복사한다.
별도 annotation 기반 계약을 생성하지 않는다. 계약 검증 스크립트가 두 파일의 byte 일치를 확인한다.

Basic 인증과 CSRF 보호는 유지한다. OpenAPI의 POST security는 Basic + X-CSRF-TOKEN이며,
Swagger requestInterceptor가 쓰기 직전 실제 `/api/v1/csrf`를 호출해 세션 쿠키와 토큰을 연결한다.
원본 요청을 복사해 토큰을 추가하고 showMutatedRequest=false로 동적 토큰을 curl 표시에 넣지 않는다.
승인된 문서 서버는 상대 URL `/`로 현재 API origin을 사용한다. 다른 origin으로 보내는 요청은 거절한다.
Swagger 브라우저 인증 영속 저장과 온라인 validator를 끄고 모든 자산을 로컬 JAR에서 제공한다.

참고: [Swagger UI configuration](https://swagger.io/docs/open-source-tools/swagger-ui/usage/configuration/).

GitHub Actions CI는 연결되어 있다. push/PR 이벤트에서 scaffold와 storage job이 동작하며,
Swagger 실브라우저 시험도 기존 Profile 통합 브라우저 경로에 포함된다.
현재 저장소에 ArgoCD Application, 배포용 manifests, 이미지 publish 또는 CD workflow는 없다.
ArgoCD/배포 대상 정보는 아직 제공되지 않았으며 임의의 클러스터 연결이나 배포를 수행하지 않았다.


## 검증 (2026-10-02)

- `swagger-contract` / `20261002T013643Z-9a51c080`: PASS/0. 인증·자산·생성 타입·계약 byte 일치.
- `swagger-browser` / `20261002T013819Z-e005f614`: PASS/0. 실제 PostgreSQL/API에서
  desktop/mobile 각각 Swagger 화면 렌더, 자동 CSRF 발급과 POST201, 버전 GET200.
  외부 origin 요청 없음과 local/sessionStorage 비저장도 확인했다. 기존 Profile UI도 함께 통과했다.
- Dashboard lint/typecheck 및 Gradle test/bootJar/lock 갱신 성공.
- 최초 문서 fetch가 method를 생략하는 경우를 처리하지 못했던 오류는 GET 기본값으로 수정했고
  실브라우저 재검증을 통과했다. 최초 실패 기록 `20261002T013705Z-50688b1b`도 보존한다.
- Swagger desktop/mobile 스크린샷을 열어 실제 화면을 확인했다.

코드 fd729ca의 [GitHub Actions 36952012074](https://github.com/dsa04156/edgeai/actions/runs/36952012074)
scaffold/storage 모두 success다. 내려받은 결과 JSON 8개 모두 PASS/0을 확인했다.
테스트용 API/UI/DB 프로세스는 종료했다. CD를 구성하거나 배포하지 않았다.
후속 완료 기록은 문서만 변경하므로 이 코드의 검증을 재사용한다.
