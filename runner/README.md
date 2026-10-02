# Runner

Python 표준 라이브러리로 Attempt claim → 입력 다운로드·SHA-256 확인 → 실제 자식 프로세스
실행 → 출력 업로드 → Result commit 요청을 수행한다. 내부 HTTP 인증/API, DB 상태 전이,
검증된 결과 확정, Kubernetes worker를 구현했다. 실행은 `EDGEAI_RUNTIME_ENABLED=true`로
명시적으로 활성화해야 한다. 현재 전용 배포는 활성화되어 있으며 M4 전체 경로와 M5 재시도·명시적
노드 전환은 실제 kind·CI·배포 검증을 통과했다. 측정 수집의 최신 검증 상태는 M5 증거를 따른다.

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
| `EDGEAI_POD_TOKEN_FILE` | audience=edgeai-runner인 Pod-bound token 파일; 요청마다 다시 읽음 |
| `EDGEAI_WORK_DIR` | 비어 있는 쓰기 가능한 작업 디렉터리 |

워크로드에는 `EDGEAI_INPUT_DIR`, `EDGEAI_OUTPUT_DIR`, `EDGEAI_PARAMETERS_FILE` 경로를 전달한다.
입출력 파일명은 선언한 포트명이다. Runner는 shell 없이 command/args를 실행하며 timeout과
SIGTERM 때 프로세스 그룹을 종료한다. stdout/stderr에는 고정된 단계·오류 코드만 기록한다.
workload stdout/stderr는 저장하지 않는다. 출력은 일반 파일만 허용하며 심볼릭 링크를 거절한다.

이미지는 Python 3.13의 고정 digest를 사용하며 UID 10001로 실행한다. Kubernetes compiler는
읽기 전용 root filesystem, 작업 volume, 제한된 권한과 token 파일을 구성한다. 실제 Kubernetes
Runner→Control Plane→MinIO→Result의 AUTO/NODE·BATCH·취소·재시작·전환 경로를 실제 kind에서 검증했다.

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
참조 계산의 선택적 `parameters.simulationDelayMillis`는 정수0~60000이며 Remote 참조 제공자와 같은
장애/전환 시험 대기다. 실제 계산 전 기다리고 Runner timeout·취소가 그대로 적용된다. 성능 지표가 아니다.

시험은 결과 byte·digest, commit 재전송, 큰 정수/소수 보존, 잘못된 입력, 누락/링크 출력,
timeout, SIGTERM, 거절된 claim을 다룬다. 현재 증거는 [M4 기록](../docs/evidence/m4-runtime.md)을 따른다.

## 실행 측정

claim 응답의 telemetry.intervalSeconds가 있을 때 Runner는 실행 중 cgroup v2 CPU 사용 시간·quota,
메모리 사용량·제한을 읽어 내부 telemetry API로 보낸다. CPU/메모리 통계는 Runner와 workload가
속한 cgroup의 값이다. cgroup 미지원·측정 불가·무제한 quota는 null이며 Node 잔여량이나 GPU/NPU
사용량으로 해석하지 않는다. 프로덕션 sampler는 자기 cgroup membership을 해석하고 host root로 대체하지 않는다.

workload에는 `EDGEAI_TELEMETRY_FILE`도 전달한다. 서비스가 직접 측정한 지연을 보고하려면
다음 형태의 JSON을 임시 파일에 쓴 뒤 해당 경로로 원자적으로 rename한다.

```json
{"sequence":1,"observedAt":"2026-10-02T10:00:00.000000Z","latencyMicros":1250}
```

sequence는 workload 안에서 증가시키고 observedAt은 실제 UTC 측정 시각을 사용한다. 예시는 형식만
보여주며 오래된 시각을 그대로 전송하면 버린다. 파일은 일반 파일·4KiB 이하, 지연은0–600,000,000μs다.
같은 지연 샘플을 여러 번 전송하지 않는다. 이 값은 개별 서비스 측정이며 실행 전체 시간이나 p95/p99가 아니다.
서버는 현재 producer만 받고 Attempt별 최신64개를 보존한다. Task 상세와 Dashboard에 최신 Attempt의
측정을 표시한다. 측정 전송은 best effort이며 손실 가능성이 있다. API 장애나 측정 시각 거절로 계산을
실패시키지 않지만, producer 인증/claim이 차단되면 workload를 중단한다.

참고: [ADR 0008](../docs/adr/0008-runtime-telemetry.md), [측정 증거](../docs/evidence/m5-runtime-telemetry.md).
