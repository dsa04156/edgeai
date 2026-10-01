# Backend

- 루트에서 `bash scripts/test-unit.sh`; 실제 PostgreSQL은 `bash scripts/test-integration.sh`.
- 실행: 루트에서 `bash scripts/dev-backend.sh`.
- JDK 21, `./gradlew` wrapper 사용. dependency 변경 시 lockfile도 검증해 갱신한다.
- `app`: HTTP/security/transactions/controllers, `domain`: 순수 도메인, `adapters`: 외부 연동 경계.
- `domain`에 Spring/Kubernetes SDK 의존성을 추가하지 않는다.
- OpenAPI가 REST 계약의 기준이다. DB 변경은 `app/src/main/resources/db/migration/`의 새 Flyway migration으로만 한다.
- 적용된 migration 파일은 수정하지 않는다. M0는 schema 초기화만 하며 도메인 테이블을 확정하지 않았다.
- `test`는 외부 DB 없이 동작해야 한다. `integrationTest`는 실제 PostgreSQL을 요구하며 조용히 skip하지 않는다.
