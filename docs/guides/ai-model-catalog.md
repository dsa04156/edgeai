# AI 모델 관리

AI 서비스는 전체 워크플로이며, 이 화면은 그 안에서 쓸 **AI 모델의 실행 이미지와 자원 요구량**을 모델 버전·실행 구성별로 등록·조회합니다.
콘솔 `AI 모델 관리`(`/ui/main/ai-models`)와 `AiModel` API 7개 operation을 제공합니다.

## 범위

| 지금 제공 | 후속(이번 범위 아님) |
|---|---|
| 기존 Registry 이미지 검색·선택·digest 검증, 모델 등록·목록 검색/페이지·상세 | 워크플로 노드 선택·추가, `resources` 자동 주입, Pod YAML 생성, 오프로딩 |
| CPU·메모리·GPU/NPU 요구량(수동 입력 또는 기존 자원 프로파일 재사용) | 모델 실행·측정, 기존 `모델 추론`(inference/models) 교체 |
| 불변 등록·중복 방지·재전송(Idempotency-Key) | 웹 이미지 업로드(tar)·외부 이미지 복사·이미지 빌드·새 Registry 설치 |

- 등록은 SERVICE 실행 모듈을 발행하지 않으며 기존 워크플로에서 자동으로 보이지 않습니다. 실행·자원 예약·HAMi/VPA·워커 설치도 하지 않습니다.
- 요구량 선언은 자원 보장이 아니며, 이 기능은 과제 3-3 전체 완료를 뜻하지 않습니다. 3-3 범위는 [PLAN](../../PLAN.md#3-3-자원-프로파일링-계획)을 따릅니다.

## 이미지 준비: 기존 Registry로 push

이미지 바이너리는 서버 설정의 기존 Registry(개발 환경 `10.254.192.47:5000`)에 두고, 카탈로그에는 검증한 참조·메타데이터·요구량만 저장합니다.
웹에서 이미지를 올리지 않으므로 먼저 기존 방식으로 push합니다.

```bash
# 단일 플랫폼 이미지
docker tag <로컬 이미지> 10.254.192.47:5000/edgeai/<모델 이름>:<태그>
docker push 10.254.192.47:5000/edgeai/<모델 이름>:<태그>
# 여러 플랫폼(index) 이미지
docker buildx build --platform linux/amd64,linux/arm64 -t 10.254.192.47:5000/edgeai/<모델 이름>:<태그> --push <빌드 경로>
```

이 Registry는 HTTP이므로 push하는 Docker에 `insecure-registries` 설정이 필요할 수 있습니다(기존 빌드·배포와 같음).

## 등록 흐름

1. **모델 등록** — 목록 화면에서 `모델 등록`을 열고 모델 키(`^[a-z][a-z0-9._-]{0,99}$`), 표시 이름, 모델 버전, 실행 구성명(기본 `default`)을 입력합니다. 같은 모델·버전에 장치·이미지·입력 조건이 다른 구성은 실행 구성명을 바꿔 추가합니다.
2. **실행 이미지** — 저장소를 목록(`_catalog`, `저장소 더 보기`)에서 고르거나 직접 입력하고, 태그를 고르거나 태그·digest를 입력한 뒤 `이미지 확인`을 누릅니다. 서버가 manifest를 읽어 digest와 플랫폼을 보여 줍니다. index는 실제 선언된 플랫폼만, 단일 이미지는 config의 OS·아키텍처를 보여 주며 linux/amd64·linux/arm64만 등록할 수 있습니다.
3. **실행 구성** — 실행 명령·인자(선택, 비우면 이미지 기본값), 입력 조건(`key=value`), 런타임을 입력합니다.
4. **자원 요구량** — 비어 있는 칸에 CPU(`250m`·`0.25`)·메모리(`512Mi`·bytes) 최소/요청/최대와 GPU/NPU 요구량을 입력합니다. 단위·범위 규칙은 [서비스 자원 프로파일](resource-profiling.md#값과-단위)과 같습니다. 직접 입력한 값은 **사용자 지정(MANUAL)·미측정(UNMEASURED)** 이며 자동 산정이 아닙니다.
5. **등록** — 성공하면 목록을 다시 조회하고 저장된 상세를 엽니다. 실패하면 입력을 유지합니다. 같은 내용을 다시 등록하면 같은 요청 키를 써 중복되지 않습니다.

### 기존 자원 프로파일 재사용(선택)

`기존 자원 프로파일 가져오기`로 불변 자원 프로파일 revision을 고르면 요구량·입력 조건·런타임과 그 SERVICE 버전의 실행 명령·인자를 채웁니다. 서버는 다음을 확인합니다.

- 프로파일의 SERVICE `image`가 `<registry>/<repository>@<등록 digest>` 또는 선택 플랫폼 manifest digest와 같은 불변 이미지일 것(아니면 409 `RESOURCE_PROFILE_IMAGE_MISMATCH`).
- 프로파일 호환 아키텍처에 선택 플랫폼이 있을 것(아니면 409 `RESOURCE_PROFILE_PLATFORM_MISMATCH`).
- 실행 명령·인자·입력 조건·런타임·요구량이 원본과 모두 같을 때만 원본의 근거(evidence)·측정 상태를 서버가 불러온 사본으로 유지(`RESOURCE_PROFILE`). 하나라도 바꾸면 모든 근거가 `MANUAL`이어야 하며 `MANUAL_FROM_RESOURCE_PROFILE`·`UNMEASURED`로 저장합니다. 바꾼 값을 실측·추천으로 보내면 400입니다.

원본 프로파일은 변경하지 않으며 카탈로그 항목은 원본 revision을 외래 키로 참조합니다.

## 이미지 검증 규칙

| 항목 | 규칙 |
|---|---|
| Registry | 서버 설정 `EDGEAI_AI_MODELS_REGISTRY_URL` 하나만. 브라우저는 URL·host를 보낼 수 없고 저장소·태그·digest만 보냅니다 |
| 이름 | OCI distribution-spec 정규식(저장소 255자, 태그 128자), digest는 `sha256:` 64 hex만 |
| manifest | `Accept`에 OCI image index/manifest, Docker manifest list/v2를 넣어 GET하고 반환 bytes의 sha256이 요청 digest(및 `Docker-Content-Digest`)와 같아야 함 |
| 플랫폼 | 단일 이미지는 config blob(digest 검증)의 os/architecture/variant. index는 descriptor 플랫폼에서 `unknown/unknown`·attestation 제외, 등록 시 선택 플랫폼 manifest와 config를 다시 읽어 os/architecture(명시된 variant 포함)가 일치해야 함 |
| 지원하지 않음 | schema1, `artifactType`이 있는 artifact, 이미지 config가 아닌 manifest(차트·SBOM 등), index 안의 index → 422 `UNSUPPORTED_IMAGE_ARTIFACT` |
| 제한 | redirect 따르지 않음(502 `REGISTRY_REDIRECT_BLOCKED`), 응답 목록 1 MiB·manifest/config 4 MiB, index 항목 64개, 깊이 1, 기본 timeout 10초(504 `REGISTRY_TIMEOUT`) |
| 페이지 | `n`·`last`로 조회하며 `Link`는 다음 페이지 존재 신호로만 쓰고 그 URL을 요청하지 않습니다. API의 `next`가 다음 `last` 값입니다 |

등록은 확인한 digest로 고정합니다. 이후 태그가 다른 이미지를 가리켜도 기존 항목은 바뀌지 않으며, 태그는 조회 출처(`sourceTag`)로만 남습니다.
index를 등록하면 `image.digest`는 index digest, `image.platformManifestDigest`는 선택 플랫폼의 이미지 manifest digest입니다.

## API

| operation | 경로 |
|---|---|
| `getAiModelRegistry` | `GET /api/v1/ai-models/registry` — 설정 여부와 push host |
| `listAiModelRegistryRepositories` | `GET /api/v1/ai-models/registry/repositories?limit&last` |
| `listAiModelRegistryTags` | `GET /api/v1/ai-models/registry/tags?repository&limit&last` |
| `getAiModelRegistryManifest` | `GET /api/v1/ai-models/registry/manifest?repository&reference` |
| `registerAiModel` | `POST /api/v1/ai-models`(`Idempotency-Key`, CSRF) |
| `listAiModels` | `GET /api/v1/ai-models?q&limit&offset` |
| `getAiModel` | `GET /api/v1/ai-models/{modelId}` |

- 같은 (모델 키, 버전, 실행 구성명)은 409 `AI_MODEL_VERSION_EXISTS`. 같은 Idempotency-Key·같은 정규화 입력은 Registry 상태와 무관하게 저장된 결과를 200으로, 다른 입력은 409 `IDEMPOTENCY_CONFLICT`. 동시 등록은 advisory lock과 유니크 제약으로 하나만 저장됩니다.
- 저장소: Flyway `V71__ai_model_catalog.sql`의 `edgeai.ai_model_execution`(불변 trigger, 요청 fingerprint·문서 digest, 선택 `resource_profile_id` FK).
- Registry 오류는 응답 본문이나 인증 정보를 노출하지 않는 고정 메시지로 반환하며 저장하지 않습니다.

## 설정

| 환경 변수(API) | 기본값 | 의미 |
|---|---|---|
| `EDGEAI_AI_MODELS_ENABLED` | `true` | 카탈로그 API(목록·상세는 DB만 필요) |
| `EDGEAI_AI_MODELS_REGISTRY_URL` | 비어 있음 | 비어 있으면 이미지 조회·등록이 503 `REGISTRY_NOT_CONFIGURED`. dev overlay는 `http://10.254.192.47:5000` |
| `EDGEAI_AI_MODELS_REGISTRY_TIMEOUT_SECONDS` | `10` | 요청당 대기(1–120초) |
| `EDGEAI_AI_MODELS_REGISTRY_USERNAME` / `_PASSWORD_FILE` | 비어 있음 | 선택 Basic 인증. 비밀번호는 파일로만 읽으며 브라우저·오류에 노출하지 않습니다 |

시험·검증 기록은 [AI 모델 관리 시험 기록](../evidence/ai-model-catalog-20261030.md)에 있습니다.
