# M6 지속 VD runtime — 구성 요소 검증

ADR0014의 독립 VD Pod compiler, Python supervisor, 내부 poll 계약을 추가했다.
이는 실제 제어 서버·영속 runtime·VD Task 연결 전 구성 요소다. 등록된 VD를 Ready로 바꾸지 않으며
M6 전체 또는 실제 VD Task/Result 수용 완료로 판정하지 않는다.

## 구현과 확인 범위

- `VDRuntimeLaunch`와 `KubernetesVDPodCompiler`는 VD/runtime/generation의 신원을 가진 지속
  Pod를 만든다. Task/Run/Attempt·Job 신원을 만들지 않는다. AUTO/NODE hard affinity는 scheduler를
  사용하고 SERVICE의 digest 고정 이미지·자원·arch·selector·toleration을 유지한다.
- projected Pod token의 audience는edgeai-vd다. runtime 자격 파일, 비루트10001·읽기 전용 root,
  제한된 volume, startup/readiness probe를 구성한다. 자원은 동시 작업 전체가 공유한다.
- supervisor는 실제 자식 Runner와 workload를 실행한다. 여러 작업이 하나의 supervisor session을
  공유하되 각 Runner는 별도 POSIX session이다. 같은 Attempt 재배정은 중복 실행하지 않는다.
- 실제 HTTP fixture에서 순차2개 계산의 결과값3·각 claim/commit1회, 동시2개 중 하나만 취소하고
  나머지는 결과 확정, DRAIN 동안 기존 작업 완료·종료 보고 확인을 검증한다.
- 같은 sequence/요청 bytes 재시도, runtime 토큰 파일 교체, 잘못된 신원·중복 배정·초과 배정·
  알 수 없는 ack 거절, lease 만료·401·SIGTERM·drain timeout의 실제 작업 종료를 검증한다.
- 실제 `--ready` probe로 정상→일시 장애→복구→DRAIN 변화를 확인한다. Runner를SIGKILL하고
  별도 process group에 남은 자식을 제거·회수하며 다른 동시 작업이 계속 계산하는 것도 확인한다.
- 같은 volume의 supervisor 재시작을 거절한다. 이력 상한은100,000개이고 시험에서는 같은 코드의
  상수를2로 낮춰 마지막 수락 작업의 완료·drain과 다음 작업 미실행을 실제 프로세스로 확인한다.
- `vd-runtime-api.yaml`을4번째 내부 계약으로 추가하고 기존 계약 생성·MVC 검사를 유지한다.
  서버 미구현 상태를 문서에 명시하며 기존 Swagger에 구현된 API처럼 노출하지 않는다.

## 로컬 증거 (2026-10-03 KST)

| testRunId / 실행 | 범위 | 결과 |
|---|---|---|
| 20261002T150333Z-dfac8fdf | Java 단위/MVC, 신규 Pod compiler2개 포함 | PASS/0 |
| 20261002T151803Z-300569b0 | 실제 supervisor12개, 느린 취소 후 만료된 배정 거절 포함 | PASS/0 |
| 20261002T152013Z-d1434d2c | OpenAPI4개 생성·기존 MVC·공개 계약과 패키징 원본 일치 | PASS/0 |
| 20261002T152039Z-d70b3b7d | 기존 Runner/telemetry14개+supervisor12개 | PASS/0 |
| 20261002T152131Z-07c1d3e3 | 이력 상한의 마지막 작업 완료 후 drain | PASS/0 |
| 20261002T152616Z-7751c580 | 최종 supervisor13개. 준비 probe·자식 회수·lease 회귀·상한 포함 | PASS/0 |
| 실제 Kubernetes server dry-run | unit이 생성한 `vd-auto-pod.json`·`vd-node-pod.json`2개, 기존 전용 namespace 소유 확인 | PASS/0 |

실서버 검사는 context `kubernetes-admin@kubernetes`에서 `kubectl create --dry-run=server`로 수행했다.
실제 Pod를 생성하거나 다른 클러스터 설정을 변경하지 않았다. 생성 fixture는
`backend/app/build/runtime-fixtures/`에 있고 `test-unit.sh`로 재생성할 수 있다.

시험 `20261002T151642Z-d745dd0d`는 느린 취소가 새 lease를 소모한 뒤 다음 작업의 claim이 발생하는
오류를 재현했다. 신규 child 시작 직전에 lease를 다시 검사하도록 수정했고 원래 조건을 통과했다.
`20261002T151600Z-99b76931`의401 하위 시험은 종료 보고 누락이1회 있었다. finally가 자식에
SIGTERM을 두 번 보내는 경로를 제거했고 후속 supervisor 및 전체 Runner 회귀는 통과했다.
이 누락의 시간 순서를 직접 추적한 것은 아니므로 반복 신호가 확정 원인이라고 단정하지 않는다.
인증 실패 시 프로세스 종료와 결과 미확정이 필수이며, 종료 보고 자체는 서버 장애·fence 시
거절될 수 있는 best effort다. 서버의 관측·lease 만료 처리가 별도로 필요하다.

## CI·서버 연결의 남은 범위

로컬은 실제 Linux Python 프로세스와 HTTP 프로토콜 fixture다. HTTP 서버는 실제 Spring/DB가
아니며 업로드 대상도 실제 MinIO가 아니다. 컨테이너 시험 fixture는 production UID10001·읽기 전용
root를 유지하고, 비공개 작업 volume 검사·정리를 같은 UID의 일회성 컨테이너에서 수행한다.
kind의 VD 전체 경로는 아직 확인 대상이다.

## CI·기존 실행 회귀·배포 확인

코드e442a5c의 [CI37027216582](https://github.com/dsa04156/edgeai/actions/runs/37027216582)는
5 jobs 모두 success다. 검증 artifact4개 그룹의 결과JSON15개 모두 PASS/0를 내려받아 확인했다.
실제 Runner 이미지에서 UID10001·읽기 전용 root·private volume으로27개 시험을 통과했다
(`20261002T152845Z-01966298`, 84.499초). 호스트도27개 통과했다.
기존 실제 kind22Run과 고정 S3결과20개의 내용/버전/계산값 검증·클러스터 삭제도 통과했다.
새 VD supervisor의 kind 제어 서버 연결 시험은 아니다.

GitOps28a6b42 pin과 실제 API/Dashboard/MinIO의 imageID·Ready·PVCBound·ArgoSynced를
`20261002T155929Z-50929ad5` PASS/0로 확인했다. 공유 Ingress status로 aggregate health는Progressing이다.

V14의 VDRuntime·명령 lease·Operation·runtime binding 이력과 registry 수정/해제 hook은
[후속 lifecycle 기록](m6-vd-lifecycle.md)을 따른다. 다음은 실제 Pod 생성/관측/UID 삭제,
runtime/Pod 신원 인증과 poll 서버다. 이어 공개 교체·drain Operation 조회와 Run VD 정책,
고정 SERVICE에 맞는 Task 배정·Result producer·재시작/취소·demo-vd를 연결한다.
M5 상태형 복원·외부 실제 계약, M7–M10도 별도로 남는다.
