# Runner — M4 구성 요소 구현 중

Python 표준 라이브러리로 Attempt claim → 입력 다운로드·SHA-256 확인 → 실제 자식 프로세스
실행 → 출력 업로드 → Result commit 요청을 수행한다. 현재 독립 Runner와 프로토콜 시험을
구현했으며 Control Plane의 claim/commit API, DB 상태 전이, Kubernetes 실행 연결은 남아 있다.
따라서 현재 Dashboard에서 만든 Run이 이 Runner를 실행하지는 않는다.

계약은 [Runner OpenAPI](../contracts/openapi/runner-api.yaml), 실행 규격은
[SERVICE schema](../contracts/profiles/service-execution.schema.json), 설계는
[ADR 0005](../docs/adr/0005-runtime-result.md)를 따른다. SERVICE 예시 이미지의 0으로 채운
digest는 자리표시자이며 실행 가능한 이미지 주소가 아니다.

## 실행 환경

| 변수 | 용도 |
|---|---|
| `EDGEAI_CONTROL_PLANE_URL` | 내부 API의 HTTP(S) origin |
| `EDGEAI_ATTEMPT_ID` | 실행할 Attempt UUID |
| `EDGEAI_ATTEMPT_EPOCH` | 현재 Attempt epoch |
| `EDGEAI_POD_UID` | 실행 Pod UUID |
| `EDGEAI_CLAIM_FILE` | Attempt 전용 인증 토큰 파일 |
| `EDGEAI_WORK_DIR` | 비어 있는 쓰기 가능한 작업 디렉터리 |

워크로드에는 `EDGEAI_INPUT_DIR`, `EDGEAI_OUTPUT_DIR`, `EDGEAI_PARAMETERS_FILE` 경로를 전달한다.
입출력 파일명은 선언한 포트명이다. Runner는 shell 없이 command/args를 실행하며 timeout과
SIGTERM 때 프로세스 그룹을 종료한다. stdout/stderr에는 고정된 단계·오류 코드만 기록한다.
workload stdout/stderr는 저장하지 않는다. 출력은 일반 파일만 허용하며 심볼릭 링크를 거절한다.

이미지는 Python 3.13의 고정 digest를 사용하며 UID 10001로 실행한다. Kubernetes compiler는
읽기 전용 root filesystem, 작업 volume, 제한된 권한과 token 파일을 구성한다. 실제 Kubernetes
실행·claim fencing의 종단 검증은 아직 수행하지 않았다.

## 구성 요소 시험

```bash
bash scripts/test-runner.sh
docker build -f runner/Dockerfile -t edgeai-runner:verify .
EDGEAI_RUNNER_IMAGE=edgeai-runner:verify bash scripts/test-runner.sh
```

첫 명령은 호스트 Python 자식 프로세스를, 마지막 명령은 실제 컨테이너를 사용한다.
둘 다 격리된 HTTP 프로토콜 fixture를 사용하므로 실제 Control Plane/MinIO/kind 연결 시험과
구분한다. `examples/linear.py`는 합성 입력으로 CPU 계산을 수행하는 참조 workload이며
학습 모델·GPU/NPU·실장비 성능 수용시험이 아니다.

시험은 결과 byte·digest, commit 재전송, 큰 정수/소수 보존, 잘못된 입력, 누락/링크 출력,
timeout, SIGTERM, 거절된 claim을 다룬다. 현재 증거는 [M4 기록](../docs/evidence/m4-runtime.md)을 따른다.
