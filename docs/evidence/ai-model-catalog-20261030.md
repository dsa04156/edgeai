# AI 모델 관리 시험 기록 (2026-10-30)

구현 `f2c71f2`, 검토 보완 `b56927f`, 최신 오프로딩 화면 변경을 보존한 병합 소스 `ea88b51`. 아래 시험은 격리 DB 및 로컬 API에서 수행했습니다. 운영 반영 결과는 아래 배포 검증 절에 구분합니다. 가이드: [AI 모델 관리](../guides/ai-model-catalog.md).

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

## 검토 수정 1차(커밋 `f2c71f2` 기준)

| 지적 | 수정 | RED → GREEN |
|---|---|---|
| JDK 21 `HttpRequest.timeout`은 응답 헤더까지만 제한해 본문이 멈추면 무기한 대기 | `DistributionRegistryClient#get`을 `sendAsync`+`get(timeout)`의 전체 기한으로 바꾸고, 기한 초과·중단 시 future 취소와 본문 구독 취소로 연결을 놓음. 크기 초과·오류 매핑 유지 | 새 시험(헤더+본문 1바이트 후 8초간 미완료, 기한 1초): 수정 전 약 8초 뒤 `INVALID_RESPONSE`로 실패 → 수정 후 `TIMEOUT`이 2.5초 안에 반환되고 서버 쪽 쓰기가 실패해 연결 해제 확인. adapter 시험 10/10 |
| 등록 성공 시 `open(id, entry)`가 이전 상세 로딩 상태를 해제하지 않아 새 상세가 가려짐 | 알고 있는 항목을 바로 열 때 로딩 상태를 끔 | 브라우저: 이전 상세 응답을 붙잡은 채 등록 → 수정 전 로딩 표시가 남아 실패, 수정 후 새 상세 포커스·이전 응답 도착 후에도 유지 |
| 자원 프로파일 불러오는 동안 채워질 입력을 고칠 수 있어 응답이 덮어씀 | 불러오는 동안 실행 명령·인자·입력 조건·런타임·CPU/메모리·가속기 입력과 추가/삭제를 잠금(프로파일 선택·모델 키 등은 편집 가능) | 브라우저: 서비스 버전 응답을 붙잡은 동안 수정 전에는 입력이 활성이라 실패, 수정 후 잠금 → 응답 후 프로파일 값(177m 등)·`자원 프로파일 그대로`, 모델 키 입력 유지 |

| 명령 | 결과 |
|---|---|
| `gradlew :app:test --tests 'io.edgeai.adapters.registry.*'` | 10 통과 |
| `integrationTest --tests '*AiModelIntegrationTest'`(격리 DB) | 7 통과(Registry 504 경로 포함) |
| `corepack pnpm typecheck` / `lint` / `test` / `build` | 0 / 0 / 146 통과 / 성공 |
| Playwright `tests/ai-model.integration.spec.ts` + 실제 Registry 브라우저 등록(임시 spec) | desktop·mobile 12/12(새 회귀 시험 2개×2 포함, 실구성 `r1-desktop`·`r1-mobile` 201) |

## 남은 사항

- 등록·목록은 운영 반영했습니다. 워크플로에서 선택해 가져오는 기능은 추후입니다.
- 워크플로 연동, SERVICE 발행, 실행·측정은 후속입니다. 이 기능은 3-3 전체 완료가 아닙니다.
- Bearer 토큰 방식 Registry 인증은 지원하지 않습니다(현재 Registry는 인증 없음, Basic만 선택 지원).

## 최종 통합 검토·패키징

- 별도 Codex 리뷰어는 `agent-update-prompt`로 시작하지 못했습니다. 프로젝트 AGENTS의 예외에 따라 조정자가 요구사항 충족과 코드 품질·회귀 위험을 실제 diff와 시험 결과로 직접 검토했습니다. 발견한 세 결함은 위 1차 수정에서 해결했으며, 수정 diff를 다시 검토했습니다. 훅 신뢰나 도구 설정은 변경하지 않았습니다.
- 병합 소스 `ea88b51`: UI 단위 시험 148/148, typecheck·lint·콘솔 release 빌드 통과, backend bootJar 성공. 다른 작업의 오프로딩 화면·기존 이미지 고정 설정을 보존했습니다.
- 원본 Dockerfile과 같은 고정 JRE/Node 최종 기반 이미지에 검증한 로컬 JAR·콘솔 release를 COPY하여 linux/amd64 이미지를 패키징했습니다. 빌드 중 RUN 또는 아키텍처 에뮬레이션은 없습니다. 이미지 revision label은 `ea88b5170a8171a304c61da718082a08e8ae2854`입니다.
- api: `10.254.192.47:5000/edgeai-api@sha256:d8562c314a36f44dbd65c61e546906b5ee244b8a2efb23ccbc89966153d62d0b`
- dashboard: `10.254.192.47:5000/edgeai-dashboard@sha256:37f126ef2302699a6d6bef76bc8ede8ed9aeab4020104e4a28ed870237f3cbb9`
- 배포 직전 PostgreSQL custom dump SHA256: `aec5dfc6248c27cd8bbd59fd9eb18d37ebffffde4849f07f72959d7a8560bd1b`. 비공개 로컬 백업과 직전 Deployment 명세를 보존합니다.

## 운영 배포 검증

- `db227e5`를 main에 push하고 Argo CD `edgeai-dev`의 `Synced / Healthy / Succeeded`를 확인했습니다. API·콘솔 모두 Ready 1/1, restart 0, 실제 Pod imageID가 위 고정 digest와 일치했습니다.
- API 컨테이너 JAR SHA256 `e2a86f8ecc22fb6729b338e2edfcd27981106b1bc272836b97d4bfc68183b2ec`가 로컬 검증 산출물과 일치했고, 콘솔 `/app/REVISION`도 `ea88b51`이었습니다.
- 운영 DB Flyway V71 `success=true`, `edgeai.ai_model_execution` 테이블 조회 성공(0행). 운영 DB에 시험용 모델을 등록하지 않았습니다.
- `http://10.254.192.47:13080/ui/main/ai-models`: desktop 1440×1000·mobile 390×844에서 빈 목록, 등록 양식, 실제 `digits-model-amd64:generic-model-20260915` digest 확인 통과. JS 오류 0·페이지 가로 넘침 없음. 등록 POST의 실동작은 위 격리 DB 시험으로 확인했습니다.
- 콘솔 proxy의 모델 목록·Registry 설정·자원 프로파일·오프로딩 API 모두 200. 기존 오프로딩 화면 변경을 포함합니다.
- 작업 전용 API 18190·콘솔 13190·PostgreSQL 55480은 검증 후 정상 종료했습니다. Orca 회수 대상 worker는 0이며, 소유권 규칙상 retained 터미널은 강제로 닫지 않았습니다.
- 기존 `.tools/kestra-publish`에는 다른 작업의 미커밋 변경이 있어 자동 fast-forward하지 않았습니다. 구현·배포 소스는 main과 `ai-model-catalog` 작업 트리에 보존했습니다.

### 다른 작업 병합 후 최종 확인

- 이후 GPU 오프로딩 작업이 `1b8fb80`에서 모델 관리 구현을 보존해 병합하고 `fa5daa9`로 새 통합 이미지를 배포했습니다. Argo CD `fa5daa9`도 Synced·Healthy·Succeeded입니다.
- 통합본에서 AI 모델 목록·등록 양식과 모델 목록/Registry/자원 프로파일/오프로딩 API 200, JS 오류 0을 재확인했습니다. 모델 관리 구현 파일은 이후 병합에서 변경되지 않았습니다.
- [이번 CI](https://github.com/dsa04156/edgeai/actions/runs/41011489990)는 scaffold·storage·data-store·influxdb·Runner 재사용 검증을 통과한 뒤, 다른 작업의 새 main push로 이미지/GitOps 단계가 취소됐습니다. [통합본 CI](https://github.com/dsa04156/edgeai/actions/runs/41011807819)는 기록 시점 진행 중이며 전체 성공으로 표시하지 않습니다.
- 최종 배포 근거 문서는 진행 중인 main CI의 source revision을 바꾸지 않도록 작업 브랜치 `dsa04156/ai-model-catalog`에 별도로 보존합니다. 기능 코드와 배포 설정은 main에 반영되어 있습니다.
