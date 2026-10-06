# M10 공개 API에서 GPU·NPU 자원 실행

## 새 native ARM Runner 실행

`20261004T182404Z-6448ff9a`에서 공개 Profile→Workflow/Version→NODE Run으로
ARM 서버 `etri-ser0003-cg0ms0`의 GB10 GPU를 실행했다. `nvidia.com/gpu=1`과
기존 `nvidia-spark` RuntimeClass, 고정 Runner index·Node/Attempt/Pod UID를 확인했다.
고정 S3 결과의 계산 `[5,9,33,257]`, machine `aarch64`, UID10001과 bytes/SHA-256이
일치했다. 이 case는 PASS이고 생성한 runtime Job/Pod/Secret은 모두 제거됐다.

Runner 소스는 `60c8be3e5bd0970ea83d15941ba3ec6761b8a240`, image는
`ghcr.io/dsa04156/edgeai-runner@sha256:8a2f8067c0bd3d5a10e76b2c46e145b045d8a0789c3f01c10984e1af915ac2ff`다.
[두 native 플랫폼 발행 검증](m10-native-runner-ci.md)을 마친 뒤 실행했다.
API는 기존 배포이며 새 API 이미지 배포를 검증한 결과는 아니다.

같은 실행의 `cuda-edge-arm64`는 건강한 가용 노드가 없어 BLOCKED다. 직전 Kubernetes
조회에서 엣지6대가 Ready=Unknown 및 unreachable taint 상태였다. 따라서 **전체 명령은
BLOCKED/exit2**이며 ARM 서버 case의 성공을 전체 하드웨어 수용 완료로 확대하지 않는다.
공유 장치/taint/서비스 설정은 변경하지 않았다. SYNTHETIC 계산이며 실제 모델 정확도,
NPU 추론, 엣지 실행/단절 복구 검증은 남는다.

엣지 노드가 Ready=True/reason=EdgeReady로 복귀한 것을 확인한 뒤, 별도 실행
`20261004T182709Z-151e67f8`의 `cuda-edge-arm64`는 PASS/exit0이었다.
`etri-dev0005-jetagx`의 `nvidia.com/gpu.shared=1` 배정으로 실제 CUDA 계산을 수행했고,
Run1개·Runner1Pod·고정 S3결과1개의 `[5,9,33,257]`/`aarch64`/UID10001과 bytes/SHA를
확인했다. 같은 native index를 사용했으며 생성한 runtime 자원도 모두 제거됐다.
앞선 BLOCKED 기록은 당시 장치 상태의 근거로 보존한다. 실제 모델 및 단절 중 복구 수용은
여전히 별도이며, 장치 연결 자체를 이 시험에서 강제로 변경하지 않았다.

## 선행 amd64 공개 실행

2026-10-05 KST. `scripts/internal/test-hardware-runtime.py`의 실제 배포 시험
`20261004T175547Z-fb453bb8`에서2개가 PASS다.

| 실행 | 요청한 자원 | 실제 노드 | 고정 S3 결과 |
| --- | --- | --- | --- |
| CUDA 계산 | nvidia.com/gpu=1 | etri-ser0002-cgnmsb | `[5,9,33,257]`, x86_64, UID10001 |
| ARIES 접근 | mobilint.com/npu=1 | etri-ser0004-cgnms0 | non-root open/close, inferenceVerified=false |

각 시험은 공개 Profile→Workflow/Version→NODE Run 요청을 만들고, 플랫폼의 실제
Kubernetes Job/Pod·extended resource request/limit·Node/Attempt/Pod UID와 고정 Runner
imageID를 관측했다. 실제 Runner가 계산/장치 접근을 마친 뒤 S3 version의 bytes/SHA-256과
JSON을 내려받아 Result와 대조했다. 총 Run2개·Pod2개·고정 파일2개이며 생성된 runtime
Job/Pod/Secret은 모두 정리했다. 공개 Profile/Workflow/Run/Result 이력은 보존한다.

입력은 SYNTHETIC이며 실제 모델 정확도/성능 수용은 아니다. NPU 추론도 수행하지 않았다.
Runner는 CI 검증된 기존 c133315 소스의 digest42256d...를 사용했다. 새 ARM index·새 API
배포를 검증한 것은 아니다. ARM 서버 CUDA RuntimeClass와 KubeEdge GPU shared 경로도
선택할 수 있지만 새 index 발행 이후 실제 실행 검증이 필요하다.

첫 시험 `175421Z-49fca30d`는 Run HTTP400으로 실패했다. 시험 코드가 SERVICE의 단일
argument4096자 상한을 넘었다. API 계약은 유지하고 시험 명령을3000자 이하 argv 조각으로
나누어 실행한 뒤 통과했다. 첫 실패는 Run/Pod를 생성하지 않았으며 소유 Run 부재도 확인했다.

```bash
python3 scripts/internal/test-hardware-runtime.py \
  --context kubernetes-admin@kubernetes \
  --cases cuda-amd64 aries-access \
  --report .tools/hardware-runtime.json
```

실제 GPU 계산은 [독립 장치 probe](m10-hardware-components.md)의 같은 CUDA driver/PTX를
사용한다. 실행 직전 건강·자원 점유와 고정 이미지의 architecture를 확인한다. API/스토리지
연결은 기존 CA로 검증한 TLS이며 자격은 private subprocess pipe와 메모리로만 전달한다.
원본 API/broker/storage를 재시작하거나 공유 설정을 변경하지 않는다.
