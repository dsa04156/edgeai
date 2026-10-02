# 검증 기준

| ID | 명령 | 수용 기준 | 범위 |
|---|---|---|---|
| M0-ENV | scripts/preflight.sh local | 도구 존재·버전 확인 | local |
| M0-UNIT | scripts/test-unit.sh | 비인증 401, 인증 metadata 계약 | local |
| M0-CONTRACT | scripts/test-contract.sh | OpenAPI 타입 생성 일치·API 검증 | local |
| M0-DB | scripts/test-integration.sh | 실제 PostgreSQL의 Flyway 성공 | DB 필요 |
| M0-UI | scripts/test-ui.sh | lint/typecheck/build + desktop/mobile 표시 | local browser |
| M0-HEALTH | scripts/test-health.sh | DB→API→UI UP, API 인증 401/200 | 실행 중인 서비스 |
| M0-HEALTH-RECOVERY | scripts/test-health-stack.sh compose (로컬 PG 대안: local) | DB 중지 시 API/UI 503, DB 재시작 시 같은 앱 프로세스 UP | 프로젝트 전용 DB |
| M0-INFRA | scripts/test-infra.sh | PG ready, MQTT 실제 pub/sub | Docker 필요 |
| M0-STORAGE | scripts/dev-storage.sh + scripts/test-storage.sh | 공식 MinIO source build, 실제 S3 PUT/stat/GET byte 일치·SHA-256 metadata·비인증 403 | storage profile |
| M1-UNIT | scripts/test-unit.sh | JSON 정규화·숫자 정밀도·중복 필드·크기/깊이/Unicode·인증/CSRF | local |
| M1-CONTRACT | scripts/test-contract.sh | 생성 타입 일치, HTTP 오류/인증 계약 | local |
| M1-DB | scripts/test-integration.sh | 3종 CRUD 중 생성/조회, 동시 재등록/충돌, 불변 trigger·UNIQUE, paging/filter | 실제 PostgreSQL |
| M1-UI | scripts/test-profiles-stack.sh local 또는 compose | 실제 DB/API와 desktop/mobile 등록·재등록·409·새 버전·상세·인증·정밀도 | 프로젝트 전용 DB, 빌드된 UI |
| SWAGGER-CONTRACT | scripts/test-contract.sh | 문서/자산 인증401, 렌더 자산, packaged YAML과 원본 byte 일치 | local |
| SWAGGER-UI | scripts/test-profiles-stack.sh | desktop/mobile 실제 Swagger 렌더·자동 CSRF POST201·정확한 계약·외부 요청 없음 | 실제 DB/API/browser |
| M4-KIND | scripts/test-kind.sh | 실제 scheduler→Job→Result | NOT_IMPLEMENTED |
| M5/M9-FAULT | scripts/test-fault.sh | 실패·취소·복구 | NOT_IMPLEMENTED |
| M8-LOAD | scripts/test-load.sh | 100→300→1,000 관리 부하 | NOT_IMPLEMENTED |
| M10-HW | scripts/test-hardware.sh | KubeEdge/장비/2세부/성능 | NOT_IMPLEMENTED |

`SCAFFOLD_VERIFIED`는 M0 일부 시험에 한정한다. `LOCAL_VERIFIED`는 kind/UI/fault 등 필수
플랫폼 시험까지, `FULL_ACCEPTANCE`는 실장비 증거까지 충족해야 하며 현재 둘 다 해당하지 않는다.
미구현·외부 차단은 `BLOCKED/PARTIAL`로 기록한다. 테스트 이름이 존재한다고 구현된 것은 아니다.
