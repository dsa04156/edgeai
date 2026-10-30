# 오프로딩 테스트 화면

콘솔 **오프로딩 테스트**(`/ui/main/offload-lab`)는 edge 과부하 → worker 이동 → 부하 해제 후 원래 edge 복귀를
실제 Pod로 보여주는 시험용 화면입니다. 부하 종류는 모델 실행 전에 **CPU 부하** 또는 **GPU 부하**로 고르며 시험 중에는 고정됩니다. 새 배치 엔진이 아니라 [노드 기반 전환](node-placement-offload.md)의
`returnToEdge` 정책과 기존 Run·전환 경로를 그대로 사용합니다.

2026-10-29 실제 클러스터에서 웹 버튼으로 모델 실행 → 부하 시작 → worker 이동 → 부하 중지 → 원래 edge 복귀 → 모델 중지를 검증했습니다.
접속: `http://10.254.192.47:13080/ui/main/offload-lab`.

## 무엇이 실제로 실행되나

| 구성 | 내용 |
|---|---|
| 모델 | `runner/examples/offload_lab_model.py`. UCI 손글씨 숫자(scikit-learn `load_digits`, CC BY 4.0)로 학습한 MLP 64-32-10을 CPU 순수 Python으로 추론합니다. 가중치·검증 표본은 `runner/examples/models/digits-mlp-v1.json`(sha256 `7a88a173…f5469`, 학습 시 검증 정확도 0.969)이며 실행 시 digest를 확인합니다. 초당 약 20회 추론하고 1회 지연(μs)을 Runner 측정으로 보고하며, 종료 시 추론 수·검증 표본 정확도를 결과로 저장합니다. GPU 추론·성능 측정이 아닙니다. |
| 학습 | `runner/examples/models/train_digits_mlp.py`(scikit-learn 1.5.2, random_state=0, 80/20 층화 분할)로 재현합니다. 이미지 안에서는 학습하지 않습니다. |
| CPU 부하 | `runner/examples/offload_lab_load.py`. 세션 edge 하나에 고정 프로그램 Job(프로세스 1–16개, 30–1800초)을 만들어 실제 노드 CPU를 사용합니다. 화면은 이미지·명령을 보내지 않습니다. |
| GPU 부하 | `runner/examples/offload_lab_gpu_load.py`. ctypes CUDA Driver API로 고정 PTX 연산 커널을 짧게(약 50ms) 반복 실행해 edge GPU를 사용하고, 실행 사이마다 SIGTERM·시간을 확인합니다(30–1800초). 프로세스 1개, Job은 edge inventory `allocatable`의 `nvidia.com/gpu.shared`(없으면 `nvidia.com/gpu`) 1개와 CPU 1·메모리 512Mi limit을 요청하며 runtimeClass·privileged·hostPath를 쓰지 않습니다. CUDA 드라이버·장치가 없으면 `gpu-load-failed`를 출력하고 1로 끝나 부하 Pod가 Failed로 보이며 CPU로 대체하지 않습니다. 모델은 계속 CPU 모델이므로 **GPU 사용률 기준 이동·복귀 시험이지 GPU 추론·성능 측정이 아닙니다.** |
| 판단 | Prometheus 노드 CPU·GPU 관측(약 30초 수집)과 노드 inventory. 관측이 없거나 오래되면 판단하지 않습니다. 화면의 CPU·GPU 값은 각자의 관측 시각과 함께 표시하고 없으면 —(0% 아님)입니다. |

## 사용 순서

0. 부하 종류를 고릅니다. **GPU 부하**는 정책을 GPU 기준만으로 보냅니다(과부하 GPU 50%, 복귀 GPU 20%, CPU 기준 없음, 나머지 시험값 동일).
   inventory에 NVIDIA GPU 자원이 없는 edge(예: Raspberry Pi)는 선택할 수 없고, API도 Run을 만들기 전에 409
   `OFFLOAD_LAB_GPU_UNSUPPORTED`로 거절합니다. CPU·GPU 기준을 섞은 정책은 400 `OFFLOAD_LAB_MODE_UNSUPPORTED`입니다.
   부하 종류는 별도 저장 없이 세션 Run의 고정 정책(`sourceGpuPercent` 유무)에서 정해지므로 새로고침해도 유지됩니다.
   GPU 부하는 프로세스 수 입력·권장 부하·CPU 추정치를 표시하지 않고 항상 1개(`workers=1`)로 요청합니다.
1. edge 노드와 시험값(선택한 CPU/GPU의 과부하 %, 복귀 %, worker 상한, 연속 표본, 대기 시간, 최대 전환 횟수)을 확인합니다.
   기본값은 시험용(과부하 50%, 복귀 20%, 연속 2개, 시작 후 20초·전환 후 30초 대기, 최대 2회, 부하 3프로세스·300초)이며
   운영 정책 기본값이 아닙니다.
2. **모델 실행**: 선택한 edge에 일반 Run을 만듭니다. Pod가 Running으로 관측되어야 배치에 “모델 실행 중”이 보입니다.
3. **부하 시작**: 같은 edge에 부하 Job을 만듭니다. CPU가 연속 표본 동안 과부하 이상이면 Run이 worker로 이동합니다.
   화면은 기존 Pod의 “종료 중”과 새 Pod 생성을 Kubernetes 관측으로 보여주고, Attempt 표에 Pod UID·노드 기록을 남깁니다.
4. **부하 중지**(또는 부하 시간 만료): edge CPU가 연속 표본 동안 복귀 기준 미만이면 원래 edge로 돌아갑니다.
5. **모델 중지**: Run을 취소하고(진행 중 전환 포함) 부하도 중지합니다. 모델·부하 Pod가 모두 사라진 것이 관측되면 종료됨입니다.
   모델 Run이 스스로 끝나거나 실패하면(부하 실행 중 포함) 같은 정리가 자동으로 진행되고, 화면은 실패 사유·시도·노드를 바로 표시합니다.
   끝난 Run에는 부하를 시작할 수 없습니다(409 `OFFLOAD_LAB_RUN_FINISHED`). 자동 재시작은 하지 않습니다.

서버 전체에서 열린 시험은 1개입니다. 새로고침·다른 브라우저는 같은 시험을 다시 보여줍니다. 같은 버튼 재시도는
같은 Idempotency-Key로 재전송되어 두 번째 Run·부하를 만들지 않고, 실행 중 부하가 있으면 새 부하 시작은 409입니다.

GPU 부하의 worker 이동 조건(`NODE_GPU`)과 복귀 조건(`NODE_RETURN`, 원래 edge의 관측된 모든 GPU가 복귀 기준 미만)은 기존
[노드 기반 전환](node-placement-offload.md) 정책을 그대로 사용합니다. 모델은 GPU를 요청하지 않으므로 worker의 GPU 여유는
이동 조건이 아니며, 화면의 worker는 CPU 사용률을 표시합니다.

2026-10-30 15:04–15:06 KST 배포 화면에서 GPU 왕복을 검증했습니다. 세션 `87ef1e88-df7e-49ab-af47-5f12320d1fb7`,
Run `e86a1c2b-b4d7-419e-9998-496965c4cc20`의 실제 전환 기록은 다음과 같습니다.

| 동작 | 실제 관측·결과 |
|---|---|
| GPU 부하 시작 | Orin에서 CUDA Job 실행. GPU 99.1%, 99.9% 연속 표본으로 `NODE_GPU` 판단, 15:05:17 전환 성공. 해당 CPU 표본은 약 9.5%. |
| 워커 이동 | `etri-dev0001-jetorn` → `etri-ser0003-cg0ms0`. 기존 모델 Pod 종료 후 새 모델 Pod Running, 부하 Pod는 원래 엣지에 유지. |
| GPU 부하 중지 | 부하 Pod Succeeded 후 삭제. 원래 엣지 GPU 0%, 0% 연속 표본으로 `NODE_RETURN` 판단, 15:06:16 복귀 성공. |
| 모델 Pod UID | 최초 `fc3af638-d966-4176-b72c-7bead231e885` → 워커 `bcfa27da-00c9-406f-8691-6a5ac85a8fa9` → 복귀 `cb1a4d19-66f1-47c9-ad78-1efcf12674b4`. |

GPU 관측 간격은 이 시험에서 15초였습니다. 새로고침 후 GPU 종류·정책 고정을 확인했으며 복귀한 모델의 추론 보고도 확인했습니다. 마지막으로 모델을 중지해 세션 STOPPED·Run CANCELLED·모델/부하 Pod 없음까지 확인했습니다.

## NPU 지원 경계

이 화면의 부하 종류는 CPU·GPU이며 NPU 선택은 없습니다. 전환 컨트롤러는 CPU·메모리·GPU 관측을 사용하고 `NPU_USAGE`는 아직 판단에 연결하지 않습니다.
현재 클러스터의 Hailo-8 엣지(`etri-dev0002-raspi5`, `etri-dev0006-raspi5`)는 `hailo.ai/h8` 자원과 별도 Hailo 추론 경로가 있지만 사용률 계측이 필요합니다.
워커의 Intel·Mobilint NPU는 [노드 메트릭](node-metrics.md)에서 사용률을 수집합니다. 장치 존재·사용률 수집·추론 실행·자동 오프로딩은 각각 다른 지원 범위입니다.
NPU 시험을 추가하려면 장치별 추론 부하, 실제 사용률 관측, NPU 이동·복귀 정책을 연결하고 실장비로 검증해야 합니다.

## 정리 보장

- 부하 Job은 프로그램 자체 종료(부하 시간) + `activeDeadlineSeconds`(부하 시간 + 30초) + `ttlSecondsAfterFinished=600`으로 제한됩니다.
- API의 조정 루프(기본 3초)가 만료 부하·중지된 시험·API 재시작으로 남은 소유 Job(`app.kubernetes.io/managed-by=edgeai-offload-lab`)을 삭제합니다.
  다른 라벨의 객체는 조회·삭제하지 않으며 삭제에는 UID 조건을 붙입니다.
- 모델 Run은 SERVICE `timeoutSeconds`(1800초)와 모델 `durationSeconds`(1500초)로 제한됩니다.

## 배포 구성

1. 모델 실행 이미지: `10.254.192.47:5000/edgeai/offload-lab@sha256:94a0fc502c99dd45f47bae3e4c0ac69dbd698b8b404c2c80c411e934317a48ef`
   (linux/amd64·arm64). `runner/Dockerfile.offload-lab`로 CI가 만든 multi-arch Runner index
   (`sha256:ed9a4c7dd286…`) 위에 모델·부하 스크립트와 모델 파일만 COPY했습니다(RUN 없음).
   CPU/GPU 부하용 이미지는 같은 기반에 `offload_lab_gpu_load.py`를 추가한 `10.254.192.47:5000/edgeai/offload-lab@sha256:bdbd7d9fa34edab47b142177db34bb2268d0392bb9a226b32ea42f93f145d146`입니다(amd64·arm64). 모델과 부하 이미지는 독립적으로 고정하며 모델 이미지 재발행은 필요하지 않습니다.
2. 워크플로 발행: `deploy/offload-lab/offload-lab.json`(profile image는 모델 실행용 `sha256:94a0fc50…`)으로
   `EDGEAI_API_URL=... EDGEAI_API_USER=... EDGEAI_API_PASSWORD=... python3 -I hawkeye-runtime/workflow/publish.py deploy/offload-lab/offload-lab.json`.
3. API 설정: `overlays/dev`가 `components/hawkeye-video` 뒤에 `components/offload-lab`을 포함합니다.
   `EDGEAI_RUNTIME_WORKFLOW_KEYS`는 `hawkeye-video-safety,offload-lab`, `EDGEAI_OFFLOAD_LAB_LOAD_IMAGE`는 위 CPU/GPU 부하용 digest입니다.
   실행 기능·Prometheus·Kubernetes 연결이 필요하며 부하 Job·Pod 조회는 기존 runtime 네임스페이스 RBAC(jobs·pods get/list/create/delete)를 사용합니다.
4. 설정 값: `EDGEAI_OFFLOAD_LAB_LOAD_IMAGE`(digest 고정 필수, 없으면 부하·시험 비활성), `EDGEAI_OFFLOAD_LAB_WORKFLOW_KEY`(기본 `offload-lab`),
   `EDGEAI_OFFLOAD_LAB_POLL_MS`(기본 3000).

## API

`GET /api/v1/offload-lab`, `POST /api/v1/offload-lab/sessions`(Idempotency-Key), `POST .../sessions/{id}/stop`,
`POST .../sessions/{id}/load`(Idempotency-Key), `POST .../sessions/{id}/load/stop`. 계약은 OpenAPI `OffloadLab` 태그를 따릅니다.

## 실제 왕복 검증 (2026-10-29 KST)

- 코드 `25209d9`, 배포 고정값 `36276aa`; Argo CD `Synced / Healthy`.
- Run `dfa69480-ecb2-4489-94d4-a64f30b2a426`, 세션 `6c80e2d0-265e-4ced-9491-9722e12101a6`.
- 23:43:03–23:43:10: edge CPU 64.4%, 77.8% 두 관측으로 `NODE_CPU` 성공.
- 23:44:34–23:44:41: 부하 중지 후 CPU 3.8%, 3.2% 두 관측으로 `NODE_RETURN` 성공.
- Pod UID: edge `310cbb60…` → worker `34a33b32…` → edge `4c79014e…`.
  edge는 `etri-dev0006-raspi5`, worker는 `etri-ser0003-cg0ms0`. 각 전환에서 이전 Pod가 사라진 뒤 새 Pod가 관측됐습니다.
- 복귀한 모델도 추론 지연 594–611 μs를 보고했습니다(개별 관측값이며 성능 벤치마크가 아닙니다).
- 모델 중지 후 세션 `STOPPED`, Run `CANCELLED`, runtime 네임스페이스에 시험 Pod·Job 0개를 확인했습니다.
