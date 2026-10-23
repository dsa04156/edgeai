# 창고 지게차 안전 점검 (Hawkeye Warehouse Safety VLM)

고정된 창고 샘플 이미지 1장을 실제 Hawkeye VLM에 보내 위험 범주·한 줄 설명·측정 시간을 받아 실행 기록으로 저장한다.
GPU/NPU 이미지 비교(`docs/scenarios/gpu-npu-image-comparison.md`)와 같은 실행 규칙(단일 활성 실행, DB lease, 불변 결과)을
따르지만 별도 API·테이블·런타임이며 그 기능과 워크플로·Runner·Faster R-CNN 추론은 바꾸지 않는다.

**상태(2026-10-23):** API·콘솔·어댑터 구현, 범위 시험 통과, 두 이미지 push와 dev overlay 정의까지 완료. **클러스터 배포·실장비 Pod 실행은 아직 없다.**
로컬(격리 PostgreSQL, 이 호스트 Ollama 0.32.5 · RTX 5060 Ti)에서 콘솔 화면 시작 버튼으로 두 샘플 실제 추론·저장·새로고침 유지를 확인했다.

## 사용

콘솔 메뉴 **창고 지게차 안전 점검**(`/ui/main/scenarios/hawkeye`)에서 실행 대상이 `준비됨`인지 확인하고 샘플을 고른 뒤
**안전 점검 시작**을 누른다. 결과는 모델 범주와 한 줄 설명, 샘플 참조 라벨과의 일치 여부, Ollama가 측정한 처리 시간
(`total_duration`, 로드 포함)과 API 왕복 시간, 노드와 추론 직후 `/api/ps`가 보고한 GPU/CPU 배치, 모델 digest·입력 SHA-256,
모델 원문 출력이다. 실행 기록은 DB에 남아 새로고침 후에도 보인다. 한 번에 한 실행만 가능하다.

로컬 화면 실행(2026-10-23, 로컬 API → 어댑터 → Ollama, 노드 etri-ser0001, 배치 GPU, VRAM 1.62/1.62 GB). 각 1회이며 정확도·성능 평가가 아니다.

| 샘플 (참조 라벨) | 모델 판정 | Ollama 처리 (로드 포함) | API 왕복 |
|---|---|---|---|
| forklift_0004 (forklift_violation) | forklift_violation · 일치 | 799.9 ms (380.4 ms) | 810.1 ms |
| forklift_0024 (safe) | spill · 불일치 | 642.7 ms (188.8 ms) | 647.2 ms |

## 입력과 모델 (출처·라이선스)

| 항목 | 값 |
|---|---|
| 모델 | [Jsohal174/hawkeye-warehouse-safety-gguf](https://huggingface.co/Jsohal174/hawkeye-warehouse-safety-gguf) rev `9aea3248`, CC-BY-4.0. Qwen3.5-2B LoRA 병합, 언어 Q4_K_M `beef633d…40f3`, 비전 인코더 F16 mmproj `e1ae1740…b2c2` |
| 받는 방법 | 모델 카드 quick start `ollama pull hf.co/Jsohal174/hawkeye-warehouse-safety-gguf` (Ollama 0.32.5). 생성된 blob 2개가 HF LFS SHA-256과 같고 manifest `edac48dc…f280` |
| 샘플 | [warehouse-safety-hazard-dataset](https://huggingface.co/datasets/Jsohal174/warehouse-safety-hazard-dataset) rev `b23808ec` test split, CC-BY-4.0. **생성(합성) 이미지이며 실제 CCTV가 아니다.** `forklift_0004.jpg`(참조 라벨 forklift_violation), `forklift_0024.jpg`(참조 라벨 safe) |
| 샘플 선택 규칙 | 범주별 정렬 첫 파일 중 upstream 공개 per-image 결과(`outputs/hawkeye_test_results_fixed.json` @ `67b00ab`)가 정답인 것. 정확도 표본이 아니다 |
| 코드 참고 | [safety-hazard-detection](https://github.com/Jsohal174/safety-hazard-detection) @ `67b00ab` (라이선스 미표기). 프롬프트 문구와 범주 이름만 사용 |

모든 SHA-256은 `hawkeye-runtime/provenance.json`과 `hawkeye-runtime/samples.json`에 있다. 큰 파일은 Git에 두지 않는다.

## 조사 결과 (2026-10-23, 이 호스트의 격리 Ollama 0.32.5 · RTX 5060 Ti)

- 범주: `spill, forklift_violation, improper_stacking, obstacle, safe`. 프롬프트는 모델 카드 quick start 문구를 그대로 쓴다.
- Ollama `POST /api/chat`의 `messages[].images`(base64)로 이미지가 전달된다([API 문서](https://github.com/ollama/ollama/blob/v0.32.5/docs/api.md)).
- 모델 카드의 `/no_think` 접두어는 Ollama chat API에서 thinking을 끄지 못했다(5장 중 3장 `content`가 비고 `thinking`만 생성).
  API 필드 `"think": false`로 끈다.
- 출력 형식이 고정되지 않는다(`**forklift_violation**`, `**Category: spill**`, `Classification: improper_stacking` 등).
  첫 줄에서 범주 하나를 정확히 읽지 못하면 실패(`UNPARSEABLE_OUTPUT`)로 저장하며 safe로 바꾸지 않는다. 추론 실패도 같다.
- 시간: 첫 로드 약 24 s, 이후 장당 `total_duration` 약 0.7 s. `/api/ps`의 `size_vram == size`로 전부 GPU에 올라간 것을 확인했다.
- 수동 확인에서 safe 참조 라벨 이미지를 spill·forklift_violation으로 분류한 경우가 있었다. 오분류는 그대로 보여 준다.
  upstream이 보고한 정확도(186장 91.9%)는 이 시나리오에서 재현·검증하지 않았다.

## 실행 위치

ser0002 RTX 5080은 기존 GPU/NPU 비교 런타임이 `nvidia.com/gpu` 1개를 모두 쓴다. 할당 가능한 amd64 GPU는 ser0001
(`etri-ser0001-cg0msb`, RTX 5060 Ti 8 GB, 2026-10-23 할당 0/1)뿐이므로 여기에 `nvidia.com/gpu: 1`로 둔다.
Pod는 공식 Ollama(모델 저장소 포함, loopback 전용)와 Python 어댑터 두 컨테이너다. 어댑터는 시작 시 `samples.json`과 샘플
SHA-256, Ollama `/api/tags`의 manifest digest를 확인하고 warm-up 추론 1회 뒤에만 READY가 된다. 실행마다 `/api/ps`로 실제 GPU/CPU
배치를 보고한다. 같은 GPU를 다른 프로세스(예: 호스트의 사용자 Ollama)가 쓰면 CPU/GPU 분할로 표시될 수 있으며 그대로 저장한다.

## API (`contracts/openapi/platform-api.yaml`, tag `Scenario`)

| 메서드·경로 | 동작 |
|---|---|
| `GET /api/v1/scenarios/hawkeye-safety` | 고정 모델·데이터셋·샘플, 어댑터 보고값과 `READY/NOT_READY/MISMATCH/UNAVAILABLE`, `activeRunId`. 비활성은 `enabled=false`, `runtime=null` |
| `GET …/samples/{sampleId}/image` | 고정 샘플 JPEG(설정 SHA-256과 같을 때만) |
| `POST …/runs` (`Idempotency-Key`, `{sampleId}`) | 202 QUEUED 저장 후 백그라운드 실행. 같은 키·값 200 기존 실행, 다른 값 409, 진행 중 409 |
| `GET …/runs`, `GET …/runs/{id}` | 실행 기록(최신순) / 상세 |

상태 `QUEUED → RUNNING → SUCCEEDED | FAILED | INTERRUPTED`. 단일 활성 실행(partial unique index), DB lease(기본 60초, 5초 갱신),
종료 후 불변(trigger)은 GPU/NPU 비교와 같다. 실패 코드: `TARGET_NOT_READY`, `RUNTIME_*`, `IDENTITY_MISMATCH`(노드·모델·로드된
digest가 시작 snapshot과 다름), `INPUT_MISMATCH`, `INVALID_RESPONSE`(시간이 API 왕복보다 길거나 형식 오류), `UNPARSEABLE_OUTPUT`
(원문·시간은 저장, `category=null`), `OWNER_LOST`, `PROCESS_STOPPED`. 자동 재시도는 없다. 출력 해석은 `HawkeyeOutput`:
첫 줄의 `*`·`#`·`` ` ``를 지우고 선택적 `Category:`/`Classification:` 뒤 단어가 5개 범주 중 하나와 정확히 같아야 한다.

설정은 `application.yml`의 `edgeai.hawkeye-safety.*`(모델·샘플 SHA-256 고정값). 기본 비활성이며
`EDGEAI_HAWKEYE_SAFETY_ENABLED=true`, `EDGEAI_HAWKEYE_SAFETY_URL`로 켠다(어댑터 호출 제한 `EDGEAI_HAWKEYE_SAFETY_TIMEOUT` 기본 120초).
어댑터 계약: `GET /health`, `GET /samples/{id}`, `POST /classify {"sampleId","samplesSha256"}`(1 KiB 이하, 동시 1건, 503 BUSY).
Ollama 요청은 `think:false`, `temperature:0`, `num_predict:256`, `keep_alive:-1`, 모델 카드 프롬프트(SHA-256 `f406389c…835b`)다.

## 빌드·배포

```bash
bash hawkeye-runtime/build.sh prepare        # output/hawkeye-runtime/{store,samples}, provenance 검증
BUILDX_BUILDER=edgeai-kube-arm64-rootless bash hawkeye-runtime/build.sh ollama --push 10.254.192.47:5000/edgeai/hawkeye-ollama:<tag>
BUILDX_BUILDER=edgeai-kube-arm64-rootless bash hawkeye-runtime/build.sh adapter --push 10.254.192.47:5000/edgeai/hawkeye-adapter:<tag>
```

두 Dockerfile에는 RUN이 없어 arm64 rootless builder가 amd64 이미지를 에뮬레이션 없이 조립한다. 2026-10-23 push(배포 안 함):

| 이미지 | digest |
|---|---|
| `hawkeye-ollama:20261023-v1` (공식 `ollama/ollama:0.32.5` + 모델 저장소) | `sha256:c57dc2929e6c883d76eb9d555ebf4844d22d2362b9d81469d73ffade35ef93b0` |
| `hawkeye-adapter:20261023-v1` (`python:3.12.12-slim-bookworm` + 어댑터·샘플 2장) | `sha256:c500262bfb261efb0ec128b3bf541bfb3ab47dcad374ce44dacdf5b6eccf7910` |

`deploy/kubernetes/components/hawkeye-safety`가 두 digest를 고정하고 `overlays/dev`에 포함된다. Pod는 `etri-ser0001-cg0msb`,
RuntimeClass `ra-nvidia-rtx5060`, Ollama `nvidia.com/gpu: 1`·메모리 4Gi, 어댑터만 edgeai-api에서 접근(NetworkPolicy)이며 API에
`EDGEAI_HAWKEYE_SAFETY_*`를 추가한다. 레지스트리 manifest(플랫폼 amd64, uid 10001, entrypoint, env)와 `kubectl apply --dry-run=server`는
확인했지만 **두 컨테이너를 클러스터에서 실행한 적은 없다**(읽기 전용 root filesystem에서의 Ollama 시작 포함). 배포 후 콘솔에서 READY와
노드·배치를 확인하고 두 샘플을 실행해 evidence를 남긴다.

## 시험

```bash
# API: 공개 경로 + 실제 PostgreSQL + 시험 전용 가짜 어댑터(FakeHawkeyeRuntime)
backend/gradlew -p backend :app:integrationTest --tests io.edgeai.app.integration.HawkeyeSafetyScenarioIntegrationTest
# 콘솔: 프록시·표시 규칙 단위 시험, 브라우저 경계에서 API 응답을 대신하는 Playwright(데스크톱·모바일)
corepack pnpm --filter @edgeai/dashboard test
EDGEAI_DASHBOARD_PORT=<port> npx playwright test --config dashboard/playwright.profiles.config.ts tests/hawkeye.integration.spec.ts
```

가짜 어댑터와 Playwright 응답은 시험에서만 쓰며 모델 동작의 증거가 아니다. 실제 추론 증거는 로컬 검증 기록
`output/verification/hawkeye-20261023/`(Git 제외)의 `real/`에 있다.
