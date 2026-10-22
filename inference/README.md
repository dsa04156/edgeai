# GPU object detection runtime

이미지 1장을 받아 GPU에서 객체 검출을 실행하고 박스를 돌려주는 독립 HTTP 서버다.
Workflow/Runner/Runtime 엔진과 DB를 사용하지 않는다.

| 항목 | 값 |
|---|---|
| modelId | `fasterrcnn-resnet50-fpn-v2-coco-v1` |
| 모델 | torchvision `fasterrcnn_resnet50_fpn_v2`, 가중치 `FasterRCNN_ResNet50_FPN_V2_Weights.COCO_V1` (COCO val2017 box mAP 46.7) |
| 라벨 | COCO 80개 클래스(91개 인덱스 중 `N/A` 슬롯은 응답에서 제외) |
| 실행 | PyTorch 2.14.1+cu130 / torchvision 0.29.1+cu130, CUDA 전용(CPU 대체 없음), `provider=pytorch-cuda` |
| 대상 | `etri-ser0003-cg0ms0`(NVIDIA GB10, arm64), RuntimeClass `nvidia-spark`, `nvidia.com/gpu: 1` |

## HTTP 계약

- `GET /health`: CUDA에서 warm-up 추론이 성공한 뒤에만 `200 {status:"READY", modelId, nodeName, deviceName, provider}`.
  그 전이나 실패 시 `503`(`status:"NOT_READY"`, 실패 시 `error`).
- `POST /predict` `{"imageBase64": "<표준 base64, data URI 아님>"}`
  - JPEG/PNG, 디코딩 크기 ≤ 4 MiB, 각 변 ≤ 4096, 픽셀 ≤ 16,777,216. EXIF 회전은 적용하지 않는다(인코딩된 픽셀 기준).
  - 응답 `{modelId, nodeName, deviceName, provider, imageWidth, imageHeight, inferenceMs, detections:[{label, score, x, y, width, height}]}`.
    박스는 원본 이미지 픽셀 좌표(좌상단 x,y와 폭·높이), 점수 ≥ 0.5, 최대 100개.
  - 오류는 `{error, message}`: 400 `INVALID_REQUEST|INVALID_BASE64|INVALID_IMAGE|INVALID_JSON`, 411 `LENGTH_REQUIRED`,
    413 `BODY_TOO_LARGE|IMAGE_TOO_LARGE|IMAGE_TOO_LARGE_DIMENSIONS`, 415 `UNSUPPORTED_FORMAT`, 503 `NOT_READY|BUSY`.
- 동시 추론은 1개다. 실행 중 들어온 요청은 대기하지 않고 503 `BUSY`를 받는다. 클라이언트가 끊겨도 GPU 호출이 끝날 때까지 슬롯을 유지한다.

## 전처리

`COCO_V1.transforms()`(uint8 → float [0,1])만 적용한다. 크기 조정(짧은 변 800, 긴 변 최대 1333)과
ImageNet 평균/표준편차 정규화는 모델 내부 `GeneralizedRCNNTransform`이 수행하고, `postprocess`가
박스를 원본 크기로 되돌린다(torchvision v0.29.1 `models/detection/transform.py`).

## 빌드 자산

- 베이스: `python:3.12-slim-bookworm@sha256:6a0e06f5…bc70`
- `requirements.in` → `requirements.txt`(linux/arm64 cp312, 해시 고정). 아래 명령으로 해석 보고서를 만든 뒤
  `download_info.archive_info.hashes.sha256`으로 작성한다.

```bash
rtk proxy bash -c 'P=(); for v in $(seq 17 28); do P+=(--platform manylinux_2_${v}_aarch64); done; \
  python3 -m pip install --dry-run --ignore-installed --report report.json "${P[@]}" --platform manylinux2014_aarch64 \
  --python-version 3.12 --implementation cp --only-binary=:all: --target /tmp/none \
  --index-url https://download.pytorch.org/whl/cu130 --extra-index-url https://pypi.org/simple -r inference/requirements.in'
```

- 가중치: `https://download.pytorch.org/models/fasterrcnn_resnet50_fpn_v2_coco-dd69338a.pth`, 빌드 시 전체 SHA-256
  `dd69338a24b8d7381807e247652bdc356325bcbaf1cd3e092e00e0a1a58706bf` 검증 후 이미지에 포함한다. 실행 중에는 내려받지 않는다.
- 라이선스: torchvision 코드 BSD-3-Clause, PyTorch BSD 계열. torchvision 문서는 사전 학습 가중치가 학습 데이터셋에서
  유래한 별도 조건을 가질 수 있다고 명시한다(COCO).

빌드(arm64 rootless builder):

```bash
rtk proxy docker buildx build --builder edgeai-kube-arm64-rootless --platform linux/arm64 \
  --provenance=false --sbom=false [--build-context wheels=<dir>] \
  -t 10.254.192.47:5000/edgeai/gpu-inference:<tag> --push inference/
```

빌더에서 CUDA 휠 다운로드가 느리면(실측 수백 KiB/s) 호스트에서 같은 잠금 파일로 받은 휠 디렉터리를
`--build-context wheels=<dir>`로 넘긴다. 이때 pip는 인덱스 대신 그 디렉터리만 쓰고 해시 검증은 그대로다.

```bash
rtk proxy bash -c 'P=(); for v in $(seq 17 28); do P+=(--platform manylinux_2_${v}_aarch64); done; \
  python3 -m pip download --no-deps --require-hashes --only-binary=:all: --python-version 3.12 --implementation cp \
  "${P[@]}" --platform manylinux2014_aarch64 -d <dir> \
  --index-url https://download.pytorch.org/whl/cu130 --extra-index-url https://pypi.org/simple -r inference/requirements.txt'
```

## 배포와 API/콘솔 연결

- 매니페스트: `deploy/kubernetes/components/gpu-inference/`(Deployment·Service `edgeai-gpu-inference:8080`·
  `app: edgeai-api` Pod만 허용하는 NetworkPolicy). `overlays/dev`에 포함한다(2026-10-22 운영 배포, 기록 `output/verification/gpu-inference/deployment.md`). 이미지: `10.254.192.47:5000/edgeai/gpu-inference@sha256:dfbb9376…ce9a`(ser0003 containerd `certs.d/10.254.192.47:5000` HTTP 설정 필요).
- edgeai-api 설정(`application.yml` `edgeai.inference.*`, 기본 비활성):

| 환경 변수 | 값 |
|---|---|
| `EDGEAI_INFERENCE_ENABLED` | `true` |
| `EDGEAI_INFERENCE_RUNTIME_URL` | `http://edgeai-gpu-inference.edgeai.svc:8080` |
| `EDGEAI_INFERENCE_MODEL_ID` | `fasterrcnn-resnet50-fpn-v2-coco-v1` |
| `EDGEAI_INFERENCE_MODEL_NAME` | `Faster R-CNN ResNet-50 FPN v2 (COCO)` |
| `EDGEAI_INFERENCE_NODE_NAME` | `etri-ser0003-cg0ms0` |
| `EDGEAI_INFERENCE_DEVICE_NAME` | 런타임 `/health`의 `deviceName` |
| `EDGEAI_INFERENCE_PROVIDER` | `pytorch-cuda` |
| `EDGEAI_INFERENCE_TIMEOUT` | `20s`(기본값) |

- 공개 API: `GET /api/v1/inference/models`, `POST /api/v1/inference/predict`. 콘솔 화면: `/ui/main/inference`(“모델 추론”).

## 테스트

```bash
rtk proxy python3 -m unittest discover -s inference/tests
```

가짜 검출기로 HTTP 계약(readiness, 입력 제한, BUSY)을 검증한다. 실제 GPU 결과는
`output/verification/gpu-inference/runtime.md`에 기록한다.
