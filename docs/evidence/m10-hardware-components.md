# M10 실제 CPU·CUDA·ARIES 접근 검증

2026-10-05 KST. 공개 플랫폼 API를 통한 실제 모델 수용은 아직 남는다.
아래는 실제 Kubernetes/KubeEdge가 배정한 컨테이너와 장치의 구성 요소 검증이다.
모든 입력은 `SYNTHETIC`, 전체 판정은 `fullHardwareAcceptance: false`다.

최종 전체7개 `20261004T174327Z-583d0e77` PASS다. 실제 종료 코드0·소유 namespace와
PriorityClass 제거를 확인했다. 아래는 최초 경로별 검증 기록이다.

| 검사 | 실제 실행 위치/장치 | 근거 | 결과 |
| --- | --- | --- | --- |
| amd64 CPU | etri-ser0004-cgnms0 | `20261004T173534Z-ccbf4ed5` | PASS |
| arm64 CPU | etri-ser0003-cg0ms0 | 같은 시험 | PASS |
| amd64 CUDA | etri-ser0002-cgnmsb / RTX 5080 | 같은 시험 | PASS |
| arm64 CUDA | etri-ser0003-cg0ms0 / GB10, `nvidia-spark` | 같은 시험 | PASS |
| ARIES NPU 접근 | etri-ser0004-cgnms0 / aries0 | 같은 시험 | PASS |
| KubeEdge arm64 CPU | etri-dev0004-tedger / EdgeReady | `20261004T173721Z-fcb13cc9` | PASS |
| KubeEdge CUDA | etri-dev0005-jetagx / Orin, GPU shared 1개 | 같은 시험 | PASS |

CPU/CUDA는 `[2,4,16,128]`에 `y=2*x+1`을 적용한 `[5,9,33,257]`를 확인한다.
CUDA는 실제 driver 초기화·장치 1개·primary context·GPU 메모리 할당·HtoD·PTX kernel
실행·동기화·DtoH·해제를 수행한다. GPU마다 메모리 32바이트를 할당한다.
ARIES는 배정된 character device를 UID10001에서 열고 닫는다. NPU 추론은 실행하지 않았으며
`inferenceVerified: false`를 유지한다. CUDA 계산도 실제 모델의 정확도·성능 수용은 아니다.

`scripts/internal/test-hardware-components.py`는 시험 직전 Ready·압박·taint·등록 자원·사용 중 요청을
조회한다. ARM 서버 CUDA의 RuntimeClass는 명시적으로 받는다. 실제 Pod/Node UID,
고정 Python 이미지 digest, 컨테이너 machine, UID10001과 종료 결과를 대조한다.
이미지는 `python:3.13-slim-bookworm@sha256:5024f48ba9441d4b13a95d3945abc6365538e3a31109833367a1923523c6efed`다.
특권·hostPath·서비스 계정 토큰을 사용하지 않으며 root filesystem은 읽기 전용이다.

고유 namespace와 동일 시험 label의 PriorityClass만 생성한다. PriorityClass는 우선순위0,
globalDefault=false, preemptionPolicy=Never이며 namespace를 owner로 갖는다.
기존 작업을 선점하지 않고 UID·label 확인 후 생성 자원을 삭제한다.
두 성공 시험 모두 namespace/우선순위 클래스 제거 및 남은 시험 Pod 0개를 확인했다.

첫 시험 `172747Z-b4209a69`와 진단 재현 `173410Z-85eb4cb0`은 FAIL이었다.
원인은 Pod에만 Never를 지정하여 priority admission에서 거절한 것이었다. 오류 위치를 기록하고
[Kubernetes의 non-preempting PriorityClass 계약](https://kubernetes.io/docs/concepts/scheduling-eviction/pod-priority-preemption/#non-preempting-priorityclass)에
맞게 전용 클래스를 연결한 뒤 동일 경로를 통과했다. 실패 시험의 namespace도 정리했다.
상세 Kubernetes 오류는 권한0600의 `.tools/hardware-errors/`에만 저장한다.

전체 실행 `174032Z-0599ca0a`에서는 etri-ser0001-cg0msb의 amd64 CUDA Pod가
Evicted되어 FAIL이었다. 노드 DiskPressure=True와 전환 시각17:41:09 UTC를 확인했다.
후보 정렬이 첫 condition인 오래된 NetworkUnavailable만 사용했던 점을 고쳐 Ready와
세 압박 condition의 최근 변경 시각을 기준으로 안정적인 후보를 먼저 선택한다.
최종 전체 시험은 amd64 GPU를 etri-ser0002-cgnmsb에 배정해 통과했다. 실패한 노드의
디스크 문제 자체를 해결한 것은 아니며, 공유 노드 설정·데이터는 변경하지 않았다.

```bash
python3 scripts/internal/test-hardware-components.py \
  --context kubernetes-admin@kubernetes \
  --cuda-arm64-runtime-class nvidia-spark \
  --report .tools/hardware-components.json
```

장비가 없는 case는 BLOCKED/exit2, 실행·신원·정리 실패는 FAIL/exit1이다.
`--cases`는 특정 장치 경로를 재현할 때 사용한다. 전체 M10 명령 `test-hardware.sh`는 아직
BLOCKED/exit2다. 공개 Profile→Run→결과의 ARM/GPU/NPU 실제 모델 실행, 장치 단절/재연결,
외부 2세부 계약 및 합의한 성능 수용은 별도로 남는다.
