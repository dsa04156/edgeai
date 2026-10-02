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
| M2-UNIT | scripts/test-unit.sh | 60초 신선도, 입력 제한, 모든 쓰기 CSRF, Node pagination·실패·HTTP proxy | local |
| M2-DB | scripts/test-integration.sh | 재등록/충돌, 동시 생성·재접속·보고, session fence, FK·활성 UNIQUE·attachment 이력 | 실제 PostgreSQL |
| M2-UI | scripts/test-profiles-stack.sh local 또는 compose | PC·모바일 등록/수정/보고/재접속/해제, 이전 session 409, 큰 숫자 보존, DB 장애·복구 | 실제 DB/API/browser |
| M2-NODE | scripts/test-node-inventory.sh <명시적-context> | 실제 Node UID·메타데이터 대조 및 Ready Node에 합성 장치 연결/중복/해제 | 읽기 가능한 실제 Kubernetes, 로컬 DB, 빌드된 UI |
| M2-DEPLOY | scripts/smoke-deployment.py --through device | 배포 HTTP 경유 장치 lifecycle, CSRF, 201/200/409, Node 조회 | 실행 중인 API/UI·환경 변수 인증 |
| M3-UNIT | scripts/test-unit.sh | DAG cycle/self/reference/port 검증, JSON 정밀도, 인증·CSRF·Idempotency-Key 필수 | local |
| M3-DB | scripts/test-integration.sh | seal·FK·활성 Attempt UNIQUE, 동시 발행/실행 1개, Run/Task 취소 경쟁·독립 분기·재전송 | 실제 PostgreSQL |
| M3-UI | scripts/test-profiles-stack.sh local 또는 compose | DAG 발행/재발행/409, Run 생성/재전송/새 키, Attempt·취소 전파, PC·모바일·DB 장애 복구 | 실제 DB/API/browser |
| M3-DEPLOY | scripts/smoke-deployment.py | 이미지 경유 불변 DAG·Run/Task/Attempt·취소·재전송, 큰 숫자·인증/CSRF | 실행 중인 API/UI |
| M4-SPEC | scripts/test-unit.sh | 실행 규격·자원·QoS·AUTO/NODE affinity·Pod 보안 설정 | 외부 서비스 없는 compiler 시험 |
| M4-STORAGE | scripts/test-runtime-storage.sh | 실제 byte SHA-256·길이·형식·version 검증, 변조 업로드 거절, 고정 버전 다운로드 | 실제 MinIO |
| M4-RUNNER | scripts/test-runner.sh | 실제 workload, 입력/출력 검증, commit 재전송, timeout·취소·claim 거절 | Python + HTTP fixture; Control Plane 미연결 |
| M4-RUNNER-IMAGE | EDGEAI_RUNNER_IMAGE=<image> scripts/test-runner.sh | 같은 프로토콜 시험을 비루트·읽기 전용 컨테이너로 수행 | Linux Docker; CI runner job |
| M4-KIND | scripts/test-kind.sh | 실제 scheduler→Job→Result | NOT_IMPLEMENTED |
| M5/M9-FAULT | scripts/test-fault.sh | 실패·취소·복구 | NOT_IMPLEMENTED |
| M8-LOAD | scripts/test-load.sh | 100→300→1,000 관리 부하 | NOT_IMPLEMENTED |
| M10-HW | scripts/test-hardware.sh | KubeEdge/장비/2세부/성능 | NOT_IMPLEMENTED |

`SCAFFOLD_VERIFIED`는 M0 일부 시험에 한정한다. `LOCAL_VERIFIED`는 kind/UI/fault 등 필수
플랫폼 시험까지, `FULL_ACCEPTANCE`는 실장비 증거까지 충족해야 하며 현재 둘 다 해당하지 않는다.
미구현·외부 차단은 `BLOCKED/PARTIAL`로 기록한다. 테스트 이름이 존재한다고 구현된 것은 아니다.
