# GPU/NPU 고정 이미지 비교 시나리오

같은 순서의 고정 100장 입력을 GPU(ONNX Runtime CUDA)와 Hailo-8 NPU에 보내 이미지별 분류 결과와
시간을 저장하고 비교한다. 워크플로·Runner·ResourceAdvisor와 무관한 독립 기능이며, 기존 Faster R-CNN
추론(`/api/v1/inference/*`)은 바꾸지 않는다.

**상태(2026-10-22):** 코드·관련 시험 통과, 두 런타임 이미지 빌드 완료(아래). registry push,
클러스터 배포, 실장비 실행은 진행 중이다. 기존 2026-10-13 파일럿 수치는 이 기능의 결과가 아니다.

검증 기록(`output/`은 Git 제외, 로컬 전용) `output/verification/gpu-npu-scenario-20261022/`:

| 기록 | 결과 |
|---|---|
| `round1-fixes/backend-scenario-integration.log`, `round1-fixes/TEST-io.edgeai.app.integration.ImageComparisonScenarioIntegrationTest.xml` | 리뷰 수정 후 API 통합 시험 12/12 (isolated-db.sh, 로컬 PostgreSQL 16) |
| `round1-fixes/test-contract.log`, `round1-fixes/dashboard-scenario-unit.log` | 계약 검사 통과, 시나리오 콘솔 단위 시험 11/11 |
| `round1-fixes/playwright-scenario.log`, `round1-fixes/screenshots/{desktop,mobile}-scenario-{ready,stopped}.png` | 리뷰 수정 후 Playwright 데스크톱·모바일 8/8 |
| `backend-scenario-integration.log`, `TEST-io.edgeai.app.integration.ImageComparisonScenarioIntegrationTest.xml` | 리뷰 수정 전 API 통합 시험 10/10 |
| `scenario-runtime-unittest.log` | 런타임 HTTP 시험 11/11 |
| `dashboard-unit.log` | 콘솔 단위 시험 90/90 |
| `playwright-last-run.json`, `screenshots/{desktop,mobile}-scenario-{ready,stopped}.png` | Playwright 데스크톱·모바일 4/4 |
| `gpu-context.sha256`, `npu-context.sha256` | 이미지 빌드 context 파일 SHA-256 |

## 구성

| 구성 | 위치 |
|---|---|
| API·실행 | `backend/` `ScenarioController`, `ImageComparisonService`, `ImageComparisonExecutor`, Flyway `V62__image_comparison_scenario.sql` |
| 런타임 | `scenario-runtime/` (Python 표준 HTTP 서버, 엔진 `CudaEngine`/`HailoEngine`) |
| 콘솔 | `dashboard/kestra-native/ScenarioPage.vue` (`/ui/main/scenarios`, 메뉴 "GPU/NPU 이미지 비교") |
| 배포 정의 | `deploy/kubernetes/components/scenario-runtimes/` (dev overlay에 포함) |

## 데이터셋과 모델

- 데이터셋: Imagenette2-160 val 100장을 TensorFlow 2.20.0으로 한 번 전처리한 float32 NHWC 텐서
  (RGB, bilinear 짧은 변 256, 중앙 224). 원본 JPEG는 배포에 없으므로 콘솔은 미리보기 없이 경로·라벨만 보인다.
  manifest `7740c342…d30f`, inputs `cb0d2db8…50d5`.
- 라벨: manifest의 `label`(Imagenette 폴더 WNID → ImageNet-1k 번호). 설정 `edgeai.scenario.dataset.label-source`가
  있을 때만 정확도를 계산하고, 비우면 대상 간 top-1 일치만 보인다.
- GPU: TFLite(`2625772f…`)를 tf2onnx로 변환한 `resnet50.onnx`(`1737a0c6…`), FP32, TF32 비활성,
  `session.disable_cpu_ep_fallback=1`로 CPU 배정 시 세션 생성 실패. 입력에서 `[123.68,116.78,103.94]`를 뺀다.
- NPU: 기존 Hailo-8 HEF(`a1d82e91…`), HailoRT 4.23.0, float32 입출력. 원시 float RGB를 넣는다.
- 두 모델의 가중치·양자화 동일성은 확인하지 않았다. 콘솔은 정밀도가 다르면 이를 표시한다.
- 모든 SHA-256은 `scenario-runtime/provenance.json`에 있고 런타임이 시작 시 다시 검증한다.

## API (`contracts/openapi/platform-api.yaml`, tag `Scenario`)

| 메서드·경로 | 동작 |
|---|---|
| `GET /api/v1/scenarios/image-comparison` | 데이터셋, 대상별 기대 모델·provider와 런타임 자체 보고값, `READY/NOT_READY/MISMATCH/UNAVAILABLE`, `activeRunId` |
| `POST …/runs` (`Idempotency-Key`, `{imageCount: 1~100}`) | 202 QUEUED 저장 후 즉시 반환. 같은 키·값은 200 기존 실행, 다른 값 409 |
| `GET …/runs`, `GET …/runs/{id}` | 목록 / 상태·snapshot·집계 |
| `GET …/runs/{id}/results?limit&offset` | 이미지 순서별 GPU·NPU top-5, 시간, 실패 코드 |
| `POST …/runs/{id}/stop` | QUEUED→STOPPED, RUNNING→STOPPING→STOPPED. 반복·종료 후 요청은 현재 값 반환 |

상태: `QUEUED → RUNNING → SUCCEEDED | STOPPING → STOPPED | FAILED | INTERRUPTED`.

- 단일 실행: `image_comparison_run`의 partial unique index가 QUEUED/RUNNING/STOPPING을 서버 전체 1개로 제한한다.
- 시작 검증: 두 런타임이 READY이고 보고한 모델·manifest·inputs SHA-256과 provider가 설정과 같아야 하며,
  두 런타임의 manifest 바이트 SHA-256이 설정과 같아야 한다. 결과는 불변 snapshot으로 저장한다(DB trigger).
- 전송: 이미지마다 `dispatched_count`를 조건부 UPDATE로 먼저 점유한 뒤 두 대상에 동시에 보낸다. 수락된 Stop
  이후에는 점유가 실패하므로 새 이미지를 보내지 않는다. 처리 중인 1장은 런타임 호출 제한(기본 20초) 안에 끝난다.
  공유 런타임 프로세스는 종료하지 않는다.
- 검증: 응답의 index·manifest·모델 SHA·노드·장치·provider가 snapshot과 같고 `inputSha256`이 manifest 행과
  같아야 한다(두 대상이 같은 바이트를 받았는지). top-K는 0~999, 점수 내림차순, 최대 5개. 한 대상이라도 실패하면
  그 이미지 결과를 기록하고 실행을 FAILED(`TARGET_FAILED`)로 끝낸다. 자동 재시도는 없다.
- 재시작: 실행 소유 프로세스가 DB lease(기본 60초, 5초마다 갱신)를 유지한다. 정상 종료는 즉시 INTERRUPTED
  (`PROCESS_STOPPED`), 비정상 종료는 lease 만료 후 어느 API 인스턴스든 INTERRUPTED(`OWNER_LOST`)로 표시한다.
  재실행하지 않는다.
- 수락된 Stop 이후 처리 중인 1장이 실패해도 실행은 STOPPED로 끝나며, 실패는 `failureCode`·`failureMessage`와 이미지 결과에 남는다.
- 결과 행은 수정·삭제할 수 없다(DB trigger). 끝난 실행에서 대상 결과가 null인 이미지(예: INTERRUPTED)는
  앞으로도 결과가 생기지 않으며 콘솔은 "결과 없음"으로 표시한다. 결과 목록의 `nextOffset`은 뒤에 전송된 이미지가 있을 때만 값이 있다.

### 집계 의미

| 값 | 정의 |
|---|---|
| `meanInferenceMs`, p50, p95 | 성공 호출에서 런타임이 측정한 시간: 정규화·장치 전송·추론 완료 대기까지 |
| `meanRoundTripMs` | 성공 호출에서 API가 측정한 HTTP 호출 시간(네트워크 포함) 평균 |
| `callWallSeconds` | 실패·시간 초과를 포함한 해당 대상의 모든 순차 HTTP 호출 시간 합(API 측정) |
| `computeImagesPerSecond` | 성공 수 / 성공 호출의 런타임 측정 시간 합 |
| `callWallImagesPerSecond` | 성공 수 / `callWallSeconds` (예: 10 ms 성공 + 20 s 시간 초과 → 1/20.01 s) |
| `runWallSeconds`, `pairedImagesPerRunWallSecond` | RUNNING 시작부터 종료(진행 중이면 현재)까지와 그 동안 양쪽 성공 수 |
| `pairedCount`, `top1AgreementCount`, `pairedLabeledCorrect` | 양쪽 모두 성공한 이미지만 사용. 중지·실패 시 분모를 함께 표시 |

점수는 런타임 원시 출력이며 대상 간 크기를 비교하지 않는다. NPU 전력·사용률은 측정하지 않는다.

## 런타임 HTTP 계약 (`scenario-runtime/`)

- `GET /health`: 고정 데이터셋 SHA-256 검증 → 장치·모델 SHA 검증 → warm-up 4회 출력 검증 후에만 200 READY.
  그 전·실패는 503 NOT_READY와 `error`. 필드 `target,nodeName,deviceName,provider,precision,runtime,modelSha256,manifestSha256,inputsSha256,sampleCount`.
- `GET /dataset/manifest`: manifest 원본 바이트.
- `POST /classify {"index", "manifestSha256"}`(1 KiB 이하): 400 범위·형식, 409 다른 manifest, 503 NOT_READY/BUSY(동시 1건, 대기열 없음),
  500 INVALID_OUTPUT(1000개 유한 점수가 아님).

## 설정 (API)

기본 비활성(`EDGEAI_SCENARIO_ENABLED=false`). 켜면 `EDGEAI_SCENARIO_GPU_URL`, `EDGEAI_SCENARIO_NPU_URL`이 필요하다.
데이터셋·모델 SHA-256, provider는 `application.yml`의 `edgeai.scenario.*` 고정값이다. 브라우저는 런타임 주소를
고를 수 없고 콘솔 프록시는 위 6개 경로만 허용한다(쓰기는 CSRF·같은 Origin·64 KiB).

## 빌드·배포

베이스 이미지는 fixture와 모델을 이미 포함한 `10.254.192.47:5000/ra-pool-cuda@sha256:21cf10ae…`(amd64),
`10.254.192.47:5000/ra-pool-hailo@sha256:7f70d7b9…`(arm64)이다. 저장소에는 큰 바이너리를 두지 않는다.

```bash
# 로컬 원본 사본 확인 (선택)
python3 -I scenario-runtime/verify-provenance.py <prepared> <hailo-resnet50> <accelerator-pool-20261013-v1>
# 로컬 OCI 산출물: output/scenario-runtime/{gpu,npu}.oci.tar
bash scenario-runtime/build.sh gpu
bash scenario-runtime/build.sh npu
# push, 출력 digest를 components/scenario-runtimes/kustomization.yaml images에 고정
bash scenario-runtime/build.sh gpu --push 10.254.192.47:5000/edgeai/scenario-runtime-gpu:<tag>
bash scenario-runtime/build.sh npu --push 10.254.192.47:5000/edgeai/scenario-runtime-npu:<tag>
```

로컬 빌드 결과(2026-10-22, push 안 함): 이 호스트의 Docker socket은 권한이 없어 기존 buildx builder
`edgeai-kube-arm64-rootless`(Kubernetes driver)를 `BUILDX_BUILDER`로 지정해 `build.sh`를 실행했다. Dockerfile에 RUN이
없어 amd64 이미지도 에뮬레이션 없이 조립된다. 산출물의 플랫폼·`USER 10001:10001`·entrypoint·`SCENARIO_*` env, 최상위
layer 파일과 소스 SHA-256 일치, 베이스 layer의 fixture·모델 SHA-256 일치를 확인했다. 컨테이너 실행·장치 시험은 하지 않았다.

| 대상 | 산출물 | OCI manifest | tar SHA-256 |
|---|---|---|---|
| GPU (linux/amd64) | `output/scenario-runtime/gpu.oci.tar` | `sha256:ebc6cde6d6413acf1df47b2b60d31629db97d7736b233739e6f02d22696828ee` | `4f61abf19e9cf3dd7f1a85529b12f9a590d18b8f83888d2063c5f16ade1d6fca` |
| NPU (linux/arm64) | `output/scenario-runtime/npu.oci.tar` | `sha256:b8cab1c9772841f045132089c8d3c3a395bab151eb10016e9beb7d0aeefa815d` | `168fbc2ae6b44dbc648e878599584d0e7ecfd8e8c7173791c01acfeb50728239` |

push 후 registry digest는 이 OCI manifest digest와 다를 수 있으므로 `--push` 출력값을 고정한다.
그 뒤 `overlays/dev`의 `components`에 `../../components/scenario-runtimes`를 추가한다. 이 component는
GPU 런타임을 `etri-ser0002-cgnmsb`(amd64, `nvidia.com/gpu: 1`, 메모리 3Gi — 파일럿에서 1Gi는 OOM),
NPU 런타임을 `etri-dev0006-raspi5`(`hailo.ai/h8: 1`)에 두고, edgeai-api에서만 접근하게 하며 API에 `EDGEAI_SCENARIO_*`를 추가한다.
arm64 GB10(ser0003)에는 amd64 CUDA 이미지를 두지 않는다. 실장비 확인: 콘솔에서 두 대상 READY와 보고 장치를
확인한 뒤 1장, 이어서 100장 실행·중지를 수행하고 결과를 evidence로 남긴다.

## 시험

```bash
# API: 공개 경로 + 실제 PostgreSQL + 시험 전용 가짜 런타임(FakeClassificationRuntime)
backend/gradlew -p backend :app:integrationTest --tests io.edgeai.app.integration.ImageComparisonScenarioIntegrationTest
# 런타임 HTTP: 시험 전용 FakeEngine과 생성한 3장 fixture (numpy 필요)
(cd scenario-runtime && python3 -m unittest discover -s tests)
# 콘솔: 단위(프록시·표시 규칙)와 Playwright(브라우저 경계에서 API 응답, 데스크톱·모바일)
corepack pnpm --filter @edgeai/dashboard test
EDGEAI_DASHBOARD_PORT=<port> npx playwright test --config dashboard/playwright.profiles.config.ts tests/scenario.integration.spec.ts
```

가짜 런타임·엔진은 시험에서만 쓰며 장치 성능의 증거가 아니다.
