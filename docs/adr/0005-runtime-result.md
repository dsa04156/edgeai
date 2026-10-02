# ADR 0005 — Kubernetes 실행과 검증된 결과

2026-10-02. M4 구현 계약. 구현·검증 상태는 PROGRESS 및 evidence를 따르며 이 문서 자체는 실행 성공 증거가 아니다.

## 실행 규격과 배치

SERVICE Profile spec의 `apiVersion=edgeai/v1` 실행 형식을 별도 JSON Schema로 정의한다.
기존 발행 Profile은 변경하지 않는다. registry의 JSON 보관과 소비 시 실행 검증을 구분한다.
이미지는 sha256 digest로 고정하고 workload command/args, 입출력 포트, CPU/memory와
선택적 GPU/NPU extended resource, arch/OS, timeout, node selector/tolerations/runtime class를 명시한다.
M4 Runner 프로토콜은 Linux amd64/arm64에서 제공한다. 다른 OS/architecture는 조용히 대체하지 않고 거절한다.
QoS는 자원 요구에서 계산하며 명시한 QoS가 있으면 일치해야 한다. GPU/NPU는 정수 자원 요청만 표현하며 공유·분할을 주장하지 않는다.

AUTO는 요구조건을 Kubernetes Job에 표현한다. NODE는 관측된 Node 이름의 required node affinity
`matchFields: metadata.name`을 추가하며 최종 bind는 kube-scheduler가 수행한다.
Job은 attempt UUID에서 결정한 이름, completions/parallelism=1, backoffLimit=0, restartPolicy=Never,
activeDeadlineSeconds를 사용한다. Kubernetes 자체 재시도로 Attempt 이력을 숨기지 않는다.
container는 non-root, read-only root, no privilege escalation, drop ALL, RuntimeDefault seccomp,
ServiceAccount token 미마운트, 제한된 작업 volume을 사용한다. hostPath/hostNetwork/임의 Secret 참조를 받지 않는다.
작업 이미지는 `/opt/edgeai/runner.py`와 Python3을 포함해야 하며 workload는 shell 없이 command/args로 실행한다.

## 실행 신원과 조정

전용 runtime namespace와 소유 labels/UID로 리소스를 구분한다. Control Plane namespace의 Secret은
작업에 전달하지 않는다. runtime별 claim Secret과 제한된 서명 URL만 전달하고 S3 관리 자격 증명은 넘기지 않는다.
RuntimeInstance, producer claim/epoch, 영속 실행 명령은 새 Flyway migration에서 관리한다.
Run 행 잠금·현재 Attempt·claim 검사를 모든 실행/취소/결과 전이에서 유지한다.
결정적 Job 이름과 실제 UID 대조로 네트워크 timeout·API 재시작 후 중복 생성을 조정한다.
Job의 중복 Pod 가능성에 대비해 실제 Pod UID에 producer claim을 결합하며 다른 Pod의 결과는 거절한다.
Job/Pod는 list/watch/relist와 주기적 reconciliation으로 관측한다. watch410은 재목록으로 복구한다.

## Artifact와 Result

Runner는 입력의 고정 object version을 내려받아 size/SHA-256을 확인하고 workload를 실행한다.
출력 포트마다 파일 크기·SHA-256을 계산하고 Control Plane이 허용한 버킷/키의 presigned PUT으로 업로드한다.
저장소는 versioning이 켜진 전용 bucket을 요구한다. Result는 object version ID를 고정해 나중 업로드로 바뀌지 않는다.
서명 URL과 인증 토큰은 API 공개 조회·로그·evidence·DB metadata에 남기지 않는다.

Result commit은 허용한 포트·크기·media type·키·version을 검사하고 해당 버전을 실제 읽어
SHA-256과 길이를 검증한다. ETag나 사용자 metadata를 내용 검증으로 취급하지 않는다.
외부 I/O 후 Run 잠금을 다시 잡아 현재 claim/epoch·취소 상태를 확인한 뒤 Result/Artifact와
Task/Attempt를 원자적으로 확정한다. 같은 commit은 멱등, 다른 내용·과거 producer는409다.
Job Complete와 ResultCommitted는 서로 다르며 commit 없는 종료를 성공으로 표시하지 않는다.
BATCH 하위 작업은 검증된 선행 출력만 입력으로 받는다. 취소 시 producer를 먼저 차단하고 실제 종료를 확인한다.

## 검증 범위

컴파일러 단위 시험 및 실제 Kubernetes server dry-run은 배치 규격의 증거이며 실행 완료 증거가 아니다.
실제 MinIO 버전·byte 검증, Runner 프로토콜, PostgreSQL 경쟁/취소 fence, 격리 kind의 scheduler bind→
Job→artifact→Result, BATCH·부족한 자원·NODE 제약·API 재시작·잘못된 결과를 별도로 검증한다.
기존 demo-workflow/test-kind 기준은 실제 전체 경로를 유지한다. 실장비/실모델 수용은 M10이다.

근거: Notion 전체 설계도와 API/도메인 문서, Kubernetes
[노드 지정](https://kubernetes.io/docs/concepts/scheduling-eviction/assign-pod-node/),
[Job](https://kubernetes.io/docs/concepts/workloads/controllers/job/),
[watch](https://kubernetes.io/docs/reference/using-api/api-concepts/),
[자원](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/),
[QoS](https://kubernetes.io/docs/concepts/workloads/pods/pod-qos/),
[S3 무결성](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html).
