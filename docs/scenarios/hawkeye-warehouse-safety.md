# 창고 지게차 안전 점검 (Hawkeye Warehouse Safety VLM)

RTSP 영상에서 지게차·사람을 검출·추적하고 짧은 미래 경로와 최근접 접근 신호를 계산해(v2, 연속 관측), 플랫폼 SERVICE DAG의 창 Run으로
결과를 저장한다. 이전 방식(v1, `capture → infer` Hawkeye VLM 프레임 판정)도 그대로 실행·조회된다.
콘솔 기본 화면은 영상 점검이며, 고정 샘플 이미지 점검은 `?mode=samples`에서 별도로 제공한다.

## 연속 관측 (v2.0.0, 2026-10-26 구현)

계약 요약: [hawkeye-motion-contract.md](hawkeye-motion-contract.md). 구현·시험 수준: 코드와 관련 시험 통과, 실제 검출기로 로컬 관측 서비스 시험.
클러스터 배포와 실제 RTSP 세션은 이미지 digest 고정 뒤에 확인한다.

| 단계 | 실행 주체 | 위치 |
|---|---|---|
| 프레임 디코딩 | 카메라의 Edge RTSP Mapper(24 fps, 긴 변 640 이하, JPEG `-q:v 2`) | 세션 `cameraSource`(첫 창 Run이 고정)의 노드 |
| 읽기 → RT-DETR 검출 → ByteTrack → 등속 예측·최근접 접근 | 관측 서비스 `edgeai-hawkeye-motion` 한 Pod 안의 단계. **DAG 작업이 아님** | `etri-ser0001-cg0msb` RTX 5060 Ti(TensorRT). `/health`가 노드·Pod·provider·모델/engine digest를 보고 |
| `snapshot` Task | Runner Job. 관측 서비스에 창 k를 닫게 하고 프레임 JPEG를 SHA-256으로 검증해 `frames`·`observations` 결과로 저장 | 창 Run의 Placement |
| 결과 | Task Result(고정 object version·SHA-256) | MinIO |

- 세션 시작: 첫 창 Run이 카메라(설정 digest·source generation·Mapper 주소)를 고정한 뒤 API가 같은 source로 관측을 시작한다(`PUT /observers/{session}`).
  API는 매 주기(기본 1초) 임대(15초)를 갱신한다. 관측 서비스는 임대가 끊기면 스스로 읽기를 멈춘다(EXPIRED).
- 창은 이어진다: 창 k는 창 k-1의 마지막 프레임 다음부터 영상 10초 이상을 덮을 때 닫힌다. Job이 시작되는 동안에도 관측은 계속되므로 창 사이 빈 구간이 없다.
  추적 상태(ID·경로)는 창 경계와 무관하게 세션 내내 유지된다. 프레임 시각이 2.5초 넘게 비거나 역행하거나, 화면이 다른 장면으로 바뀌면(`SCENE_CUT`,
  카메라 전환·반복 영상의 처음) 추적을 새로 시작하고(`reset`) 표시한다. 같은 ID가 두 장면에 걸치지 않는다.
- 속도: 관측 서비스는 Mapper가 새 프레임을 낼 때마다(같은 프레임이면 1/48초 뒤 다시) 읽고 120초(2,880프레임)를 보관한다. 실시간 화면은 24 fps 원본의 최신 관측을
  겹치지 않게 최대 48 Hz로 확인하고 새 프레임 번호일 때만 JPEG를 한 번 읽으며, 최근 2초의 실제 **표시 fps**와 관측 서비스의 **관측 fps**(처리 수 증가)를 함께 보인다. 설정값이 아니라 측정값이다.
- 실패는 숨기지 않는다: 원본이 10초 넘게 실패, 검출기 오류, 임대 만료, 관측 인스턴스 재시작(epoch 변경), 다른 source는 세션을 `OBSERVER_FAILED`로 끝내고
  열린 창을 취소한다. 중지는 관측 서비스의 읽기를 멈추고(`DELETE`) 확정된 창 결과는 그대로 조회된다.
- 좌표·단위: 보정된 바닥 좌표가 없으므로 이미지 픽셀, 거리는 사람 박스 높이 배수, 시간은 프레임 디코딩 시각 기준 초다. 미터·km/h·충돌 확률을 만들지 않는다.
  - `NEAR_PATH`: 현재 또는 3초 안 예측 최근접 거리가 1.0 이하. `APPROACHING`: 거리가 줄고 최근접 거리가 2.0 이하. `NO_CONFLICT`: 둘 다 아님.
  - `UNKNOWN`: 속도를 정할 기록 부족(0.4초 미만) 또는 이번 프레임에서 한쪽이 검출되지 않음(최근 1.5초 추적). 안전이 아니다. `NO_PAIR`: 지게차와 사람이 함께 없음.
- 한계: 프레임 사이에 박스가 자기 폭 이상 움직이면 ByteTrack(IoU 연관)이 새 ID를 만든다(24 fps에서는 드묾). 실제 24 fps 도달 여부는 Mapper 노드(raspi5)의
  디코딩·JPEG 인코딩과 HTTP 왕복에 달려 있어 배포 뒤 화면의 측정값으로 확인한다. v1(`capture`)은 0.2초마다 새 프레임을 읽으므로 10장이 약 2초를 덮는다.
  원근 때문에 화면 거리와 실제 거리는 비례하지 않는다. 검출기의 창고 데이터 성능을 이 영상에서 평가하지 않았다.

로컬 실제 검출기 시험(2026-10-26, 3_te1 960×540 2 fps를 실시간으로 내보내는 Mapper 대역 + 실제 TensorRT 검출기·관측 서비스, 15초 1회):
31프레임, 창 3개 `1–11 / 12–21 / 22–31`(빈 구간·유실 0), 추적 시작 1회, 검출 약 9.2 ms. 사람에게 다가가는 동안 `APPROACHING`(최근접 1.6–1.95, 1.7–2.7초),
지게차 가까이에서 사람 검출이 끊긴 3프레임은 `UNKNOWN(TRACK_LOST)`, 영상 반복 시점에 지게차 새 ID. 중지 후 Mapper 읽기 0회.
`output/verification/forklift-motion-20261026/observer-smoke/`(Git 제외). 정확도·성능 평가가 아니다.

Mapper JPEG 품질(2026-10-26): `-q:v` 없이 ffmpeg 기본 200 kb/s 율 제어를 쓰면 연속 스트림에서 프레임이 약 11 KB(양자화 표 평균 약 100)로 떨어져,
같은 TensorRT 검출기로 3_te1 640×360 20프레임에서 사람 0, 지게차 9였다(실제 RTSP 미리보기와 동일). `-q:v 2`(약 65 KB)에서는 사람 16, 지게차 20.

**상태(2026-10-23):** 클러스터 배포 완료. [CI 39925164903](https://github.com/dsa04156/edgeai/actions/runs/39925164903) 통과 후 Argo CD `Synced/Healthy`와 실제 영상 구간 세 개의 성공을 확인했다.

## 사용

콘솔 [창고 지게차 안전 점검](http://10.254.192.47:13080/ui/main/scenarios/hawkeye)에서 RTSP 카메라와 워크플로 버전을 선택하고 **점검 시작**을 누른다.
구간을 선택하면 실제 DAG, Task의 노드·Job·Pod UID, 별도로 기록된 GPU 서버, 프레임별 판정이 보인다. **점검 중지**는 진행 중인 구간을 취소하고 다음 구간 생성을 막는다.

### 고정 샘플 점검

`?mode=samples`에서 실행 대상이 `준비됨`인지 확인하고 샘플을 고른 뒤
**안전 점검 시작**을 누른다. 결과는 모델 범주와 한 줄 설명, 샘플 참조 라벨과의 일치 여부, Ollama가 측정한 처리 시간
(`total_duration`, 로드 포함)과 API 왕복 시간, 노드와 추론 직후 `/api/ps`가 보고한 GPU/CPU 배치, 모델 digest·입력 SHA-256,
모델 원문 출력이다. 실행 기록은 DB에 남아 새로고침 후에도 보인다. 한 번에 한 실행만 가능하다.

로컬 화면 실행(2026-10-23, 로컬 API → 어댑터 → Ollama, 노드 etri-ser0001, 배치 GPU, VRAM 1.62/1.62 GB). 각 1회이며 정확도·성능 평가가 아니다.

| 샘플 (참조 라벨) | 모델 판정 | Ollama 처리 (로드 포함) | API 왕복 |
|---|---|---|---|
| forklift_0004 (forklift_violation) | forklift_violation · 일치 | 799.9 ms (380.4 ms) | 810.1 ms |
| forklift_0024 (safe) | spill · 불일치 | 642.7 ms (188.8 ms) | 647.2 ms |

## 영상 워크플로 (hawkeye-video-safety)

실제 실행 세션 `595c5e13-3150-4dc6-896f-afa5abb9e465`: 구간 1~3 SUCCEEDED(각 10개 서로 다른 프레임), 구간 4는 사용자 중지로 CANCELLED. 중지 후 실행 Job이 남지 않았고 다음 구간도 생성되지 않았다.

| 실제 관측 위치 | 노드 |
|---|---|
| RTSP Mapper | `etri-dev0002-raspi5` |
| capture / infer Runner | `etri-ser0004-cgnms0` |
| Hawkeye GPU 서버 | `etri-ser0001-cg0msb` (RTX 5060 Ti) |

입력 `hawkeye-forklift-video`는 [실제 공장 CCTV 데이터셋](https://data.mendeley.com/datasets/xjmtb22pff/1)의 녹화를 RTSP로 반복 송출한다(아래 실행 기록은 `3_te2.mp4`, 2026-10-26부터 `3_te1.mp4`).
출처·CC BY 4.0·고정 SHA-256은 `hawkeye-runtime/video-replay/NOTICE.md`와 `source.json`에 기록한다. 실시간 현장 카메라로 표시하지 않는다.
30프레임의 모델 출력은 spill 26 / safe 4였다. 지게차 과적 영상의 판정 정확도를 검증한 결과가 아니며, 오분류를 수정하거나 참조 라벨로 대체하지 않는다.
실행 증거는 `output/verification/hawkeye-video-20261023/deployed/`, 기존 상태 보존 비교는 `legacy-comparison.json`(기존 runtime 566·command 892·Run 350개 변경 0)이다.

발행된 SERVICE 워크플로 `hawkeye-video-safety`(DAG `capture` → `infer`, BATCH 연결 `frames`)를 **연속된 BATCH Run(창)**으로 실행한다.
각 창은 ONLINE RTSP 카메라의 Edge Mapper가 2 fps로 디코딩한 **새 프레임 10장**을 읽어 판정하고, 다음 창은 이전 창 Run이 SUCCEEDED일 때만 만든다.
창과 창 사이(Job 시작·추론 시간)의 프레임은 읽지 않으므로 연속 전량 처리가 아니다.

| 단계 | 실행 주체 | 위치 |
|---|---|---|
| 카메라·프레임 추출 | 카메라 장치와 해당 Edge 노드의 RTSP Mapper(ffmpeg). Task가 아님 | Run 접수 시 `parameters.cameraSource`(장치 UID·노드·Mapper Deployment·주소·설정 digest·source generation) |
| `capture` Task | Runner Job. Mapper `/devices/{id}/preview`에서 디코딩 시각이 앞 프레임보다 늦은 프레임만 받아 ustar(`manifest.json`+JPEG)로 저장 | Run의 Placement(실제 Pod 노드) |
| `infer` Task | Runner Job. 프레임마다 어댑터 `POST /classify-frame` 호출, 원문·범주·시간 저장 | Run의 Placement. 가속기(어댑터 노드·Pod·`/api/ps` GPU/CPU)는 `detections` 결과에 따로 기록 |
| 결과 | Task Result(고정 object version·SHA-256) | MinIO |

- 원본 오류(OFFLINE, 응답 없음, 오래된 프레임, 디코딩 시각 역행, 설정·generation 변경)는 Task를 실패시키며 고정 샘플로 대체하지 않는다. 실패·취소된 창은 세션을 FAILED로 끝낸다.
  세션 시작 후 같은 UID에서 카메라 설정이나 source generation이 바뀌면 다음 창을 만들지 않고 `CAMERA_SOURCE_CHANGED`로 끝낸다.
- 모델 출력 첫 줄에서 범주를 읽지 못한 프레임은 `category=null`로 남기며 safe로 바꾸지 않는다. 판정 정확도를 보장하지 않는다.
- API: `GET …/hawkeye-safety/video`, `POST|GET …/video/sessions`, `GET …/video/sessions/{id}`, `POST …/video/sessions/{id}/stop`,
  `GET …/video/tasks/{taskId}/frames/{seq}`, v2의 `GET …/video/sessions/{id}/live`, `GET …/video/sessions/{id}/live/frames/{seq}`.
  창의 Task·Attempt·배치·결과는 기존 Run API를 그대로 쓴다.
- 실행 범위: `EDGEAI_RUNTIME_WORKFLOW_KEYS`(기본 `*`)에 키를 지정하면 그 워크플로의 Run만 dispatch되고, 다른 Run과 이전에 남은 runtime 행·명령·재시도·결과
  발행·전환은 어떤 worker 조회에도 잡히지 않는다. `deploy/kubernetes/components/hawkeye-video`가 `hawkeye-video-safety`만 허용하고 실행 기능을 켠다.
- 게시: `hawkeye-runtime/workflow/hawkeye-video-safety.json`(Profile·DAG), `python3 -I hawkeye-runtime/workflow/publish.py`(공개 API, 재요청 안전).
  이미지는 `bash hawkeye-runtime/build.sh workflow --push <ref>`(배포된 Runner + 두 workload).

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
(`etri-ser0001-cg0msb`, RTX 5060 Ti 8 GB)뿐이다. 2026-10-26부터 이 GPU는 연속 관측 서비스(`components/hawkeye-motion`, `nvidia.com/gpu: 1`)가 쓰고,
Hawkeye VLM Pod는 GPU 요청·RuntimeClass 없이 CPU로 실행한다(`NVIDIA_VISIBLE_DEVICES=void`, CPU 시험 READY·`sizeVramBytes 0`). 아래는 GPU 배치 당시 기록이다.
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

두 Dockerfile에는 RUN이 없어 arm64 rootless builder가 amd64 이미지를 에뮬레이션 없이 조립한다. 2026-10-23 최초 이미지:

| 이미지 | digest |
|---|---|
| `hawkeye-ollama:20261023-v1` (공식 `ollama/ollama:0.32.5` + 모델 저장소) | `sha256:c57dc2929e6c883d76eb9d555ebf4844d22d2362b9d81469d73ffade35ef93b0` |
| `hawkeye-adapter:20261023-v1` (`python:3.12.12-slim-bookworm` + 어댑터·샘플 2장) | `sha256:c500262bfb261efb0ec128b3bf541bfb3ab47dcad374ce44dacdf5b6eccf7910` |

`deploy/kubernetes/components/hawkeye-safety`가 두 digest를 고정하고 `overlays/dev`에 포함된다. Pod는 `etri-ser0001-cg0msb`,
RuntimeClass `ra-nvidia-rtx5060`, Ollama `nvidia.com/gpu: 1`·메모리 4Gi이며 API와 `edgeai-runtimes` Runner만 어댑터에 접근한다(NetworkPolicy).
영상 배포에서는 adapter v2 `sha256:c7ed0047464cec3c4bd9760c2dbad0d2d4ff83920e0d55609cdcddb67d320310`, workflow v2
`sha256:4c94d2b934bb06e7263d8786bc5e3595fe4eb7c34d78a274188c659ba27e2520`을 사용한다. 실제 클러스터에서 READY·GPU 로드·프레임 추론을 확인했다.

### 연속 관측 빌드·발행

```bash
bash hawkeye-runtime/prepare-forklift-base.sh --push 10.254.192.47:5000/edgeai/forklift-base:<tag>       # TensorRT·모델 기반 이미지
HAWKEYE_MOTION_BASE=10.254.192.47:5000/edgeai/forklift-base@sha256:<digest> \
  BUILDX_BUILDER=edgeai-kube-arm64-rootless bash hawkeye-runtime/build.sh motion --push 10.254.192.47:5000/edgeai/hawkeye-motion:<tag>
BUILDX_BUILDER=edgeai-kube-arm64-rootless bash hawkeye-runtime/build.sh workflow --push 10.254.192.47:5000/edgeai/hawkeye-workflow:<tag>
python3 -I hawkeye-runtime/video-replay/prepare-scenes.py hse-blackpool hse-leggett                         # 장면 클립(지정한 장면만), scenes.json digest 대조
bash hawkeye-runtime/video-replay/build.sh 10.254.192.47:5000/edgeai/hawkeye-video-replay:<tag>                 # 3_te1 + 준비된 YouTube 2개 + 장면 10개
```

장면 경로 `/forklift-scene-<id>`는 `video-replay/scenes.json` 순서로 영상 원본 선택기에 나온다. 맨 앞 두 개는 영국 HSE가 공개한 실제 사고 CCTV다:
`hse-blackpool`(지게차–보행자(배송기사) 충돌, 원본 전체)과 `hse-leggett`(탑승형 팔레트 트럭–지게차 충돌, 보행자 충돌 아님, HSE 로고 outro 제외).
두 클립은 HSE 공개본의 1920×1080을 유지해 24fps로만 변환했고 HSE 로고·자막·강조 표시가 화면에 남아 있다. 공개본 자체가 흐리고 압축이 강하다.
Mapper는 다른 원본과 같이 640×640 이내로 줄여 분석하며, 가림·저해상도 때문에 `UNKNOWN`·`NO_PAIR`가 나올 수 있다. 출처·편집 내역은 `video-replay/NOTICE.md`.

digest를 `components/hawkeye-motion/kustomization.yaml`(`PIN_AFTER_BUILD`), `hawkeye-video-safety-v2.1.json`의 profile image(`pin-digest-after-build`),
`components/hawkeye-video-replay/replay.yaml`에 고정한 뒤 배포하고 `python3 -I hawkeye-runtime/workflow/publish.py hawkeye-runtime/workflow/hawkeye-video-safety-v2.1.json`으로
v2.1을 발행한다(publish.py는 digest가 없으면 거부). 재생 이미지는 `/forklift-replay`(3_te1), `/forklift-turn`, `/forklift-accidents`를 내보내며 장치 등록 본문은
`hawkeye-runtime/video-replay/source*.json`이다(출처·변환: `video-replay/NOTICE.md`). `EDGEAI_RUNTIME_WORKFLOW_KEYS`는 `hawkeye-video-safety` 그대로다. 재생 이미지를 바꾸면 카메라 source generation이
바뀌므로 그 전에 시작한 세션은 `CAMERA_SOURCE_CHANGED`로 끝난다.

## 시험

```bash
# 연속 관측: 추적·예측·위험(교차·후퇴·평행·측면 통과·기록 부족·검출 끊김·시간 공백), 관측 서비스 HTTP(창 연속성·임대·중지·단일 관측·source 고정), snapshot Task
python3 -m pytest -q hawkeye-runtime/motion/tests          # supervision 0.27.0, numpy (hawkeye-runtime/motion/requirements.txt)
python3 -m unittest discover -s hawkeye-runtime/tests
# API: 실제 PostgreSQL(격리 DB) + 시험 전용 가짜 관측 서비스(FakeMotionObserver)
backend/gradlew -p backend :app:integrationTest --tests io.edgeai.app.integration.HawkeyeVideoSessionIntegrationTest
# API: 공개 경로 + 실제 PostgreSQL + 시험 전용 가짜 어댑터(FakeHawkeyeRuntime)
backend/gradlew -p backend :app:integrationTest --tests io.edgeai.app.integration.HawkeyeSafetyScenarioIntegrationTest
# 콘솔: 프록시·표시 규칙 단위 시험, 브라우저 경계에서 API 응답을 대신하는 Playwright(데스크톱·모바일)
corepack pnpm --filter @edgeai/dashboard test
EDGEAI_DASHBOARD_PORT=<port> npx playwright test --config dashboard/playwright.profiles.config.ts tests/hawkeye.integration.spec.ts
```

가짜 어댑터와 Playwright 응답은 시험에서만 쓰며 모델 동작의 증거가 아니다. 실제 추론 증거는 로컬 검증 기록
`output/verification/hawkeye-20261023/`(Git 제외)의 `real/`에 있다.
