# AI 모델 관리 시험 기록 (2026-10-30)

기준 커밋 `3419303`(origin/main) 위 작업 트리 `ai-model-catalog`의 미커밋 변경. 운영 배포·운영 DB 등록·push는 하지 않았습니다. 가이드: [AI 모델 관리](../guides/ai-model-catalog.md).

## 환경

| 항목 | 값 |
|---|---|
| 구현 | Claude Code, 모델 `claude-opus-5-5`, `claude auth status` = claude.ai 구독 로그인·`apiProvider: firstParty`(API key 미사용) |
| DB | 작업 트리 전용 PostgreSQL 16.15(`127.0.0.1:55480`, `scripts/dev/dev-postgres-local.sh`). 통합 시험은 `isolated-db.sh`의 일회용 DB, 로컬 E2E는 별도 DB `edgeai_aimodel_e2e`. 공유 `.env`·운영 DB 미사용 |
| Registry | 실제 `http://10.254.192.47:5000`(GET만). 시험용은 JDK `HttpServer` 기반 실제 HTTP OCI 엔드포인트(`FakeRegistry`) |
| 공식 근거 | opencontainers/distribution-spec `spec.md`(main `f07c4c4…`): manifest GET·digest 검증, 이름 정규식, Accept, `n`/`last` 페이지 |
| 브라우저 | Playwright 단독 Chromium, desktop·mobile(iPhone 13) |

## RED → GREEN

| 단계 | RED | GREEN |
|---|---|---|
| Registry adapter | `DistributionRegistryClientTest` 컴파일 실패(클래스 없음) | 7/7 |
| 검토 반영(variant 불일치·JSON null) | 새 시험 2개 실패(index v7 ↔ config v6 통과, `null` 본문) | 9/9 |
| API·DB | 서비스 구현 뒤 작성. 효력 확인을 위해 mutation 2개(재전송 조기 응답 제거, 수정된 프로파일 값의 MANUAL 강제 제거) 적용 시 3개 실패 | 원복 후 `AiModelIntegrationTest` 7/7 |
| 콘솔 proxy | `ai-models` 경로 미허용으로 실패 | 9/9 |
| UI 모듈 | `ai-model.ts` 없음 | 4/4 |
| 브라우저 | mobile 2개 실패(숨김 머리글이 표 스크롤 밖으로 나가 페이지 폭 833px, 긴 digest 미줄바꿈) | 6/6 |

## 시험 결과

| 명령 | 결과 |
|---|---|
| `backend/gradlew -p backend test` | 260 통과, 실패 0 |
| `integrationTest --tests '*AiModelIntegrationTest' '*ResourceProfileIntegrationTest' '*ResourceAnalysisDeadlineIntegrationTest'`(격리 DB) | 14 통과(7+6+1). 등록→조회/목록·검색·페이지·불변 trigger, index digest/플랫폼 manifest 보존·구성별 등록·중복 409, 재전송 200(Registry 비운 뒤에도)·다른 입력 409·tag 이동 후 확인 digest 저장, 동시 8건 중 1건 201·같은 키 6건 동일 ID, 범위/NaN/소수/근거 위조/자유 이미지 문자열/URL 저장소/registry 필드 400, 404·422(플랫폼·artifact)·502(digest 불일치·redirect)·504 저장 없음, 프로파일 재사용·위조·명령/조건 변경·이미지/플랫폼 불일치·원본 불변 |
| `bash scripts/test/test-contract.sh` | 통과(생성 타입·패키지 OpenAPI 일치, `ManagementAuditRoutesTest` 포함) |
| `corepack pnpm typecheck` / `lint` / `test` / `build` | 0 / 0 / 146 통과 / release 빌드 성공 |
| Playwright `tests/ai-model.integration.spec.ts` | desktop·mobile 6/6: 목록 첫 화면·빈/실패 구분, 빈 요구량 기본값, push 안내, digest 확인, 실패 후 초안·요청 키 유지, 더블클릭 1회 전송, 상세 포커스·키보드, 늦은 이전 조회가 새 선택을 덮지 않음, 페이지 가로 넘침 없음 |
| `bash scripts/test/test-docs.sh` | 통과 |

## 실제 Registry·공개 API(로컬 API 18190 → 10.254.192.47:5000)

세션·CSRF를 사용한 HTTP 호출 결과입니다.

| 호출 | 결과 |
|---|---|
| 저장소 `limit=5` → `last=digits-model-arm64` | 200, 5개씩 다음 페이지(`next`=마지막 이름) |
| `digits-model-amd64:generic-model-20260915` manifest | 200 `MANIFEST`, `sha256:f1e97a098403e830f18a39ad92f94b29dab057e38c11da6961a8170c45a6697f`, config 기준 `linux/amd64` |
| `edgeai/offload-lab:20261029-v1` | 200 `INDEX` `sha256:94a0fc50…`, `linux/amd64`·`linux/arm64`(실제 index에 attestation 없음) |
| 없는 태그·digest / URL 형태 저장소 | 404 `IMAGE_NOT_FOUND` / 400 `INVALID_IMAGE_REFERENCE` |
| 등록 → 같은 키 재전송 → 같은 키 다른 입력 → 새 키 같은 모델·버전 → CSRF 없음 → MEASURED 위조 | 201 → 200(같은 ID) → 409 `IDEMPOTENCY_CONFLICT` → 409 `AI_MODEL_VERSION_EXISTS` → 403 → 400 |
| 브라우저(콘솔 13190 → API → Registry), desktop·mobile | 저장소 직접 입력·태그 확인·digest 표시 후 실행 구성 `ui-desktop`/`ui-mobile` 등록 201, 상세에 `10.254.192.47:5000/digits-model-amd64@sha256:f1e97a…` 표시, JS 오류 0 |

## 남은 사항

- 운영 반영(API 이미지·콘솔·V71 적용·dev overlay의 `EDGEAI_AI_MODELS_REGISTRY_URL`)은 하지 않았습니다.
- 워크플로 연동, SERVICE 발행, 실행·측정은 후속입니다. 이 기능은 3-3 전체 완료가 아닙니다.
- Bearer 토큰 방식 Registry 인증은 지원하지 않습니다(현재 Registry는 인증 없음, Basic만 선택 지원).
