# M10 공개 API에서 GPU·NPU 자원 실행

2026-10-05 KST. `scripts/test-hardware-runtime.py`의 실제 배포 시험
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
python3 scripts/test-hardware-runtime.py \
  --context kubernetes-admin@kubernetes \
  --cases cuda-amd64 aries-access \
  --report .tools/hardware-runtime.json
```

실제 GPU 계산은 [독립 장치 probe](m10-hardware-components.md)의 같은 CUDA driver/PTX를
사용한다. 실행 직전 건강·자원 점유와 고정 이미지의 architecture를 확인한다. API/스토리지
연결은 기존 CA로 검증한 TLS이며 자격은 private subprocess pipe와 메모리로만 전달한다.
원본 API/broker/storage를 재시작하거나 공유 설정을 변경하지 않는다.
