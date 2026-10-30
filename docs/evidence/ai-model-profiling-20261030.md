# AI 모델 자원 측정 근거 (2026-10-30)

가이드: [AI 모델 자원 측정](../guides/ai-model-profiling.md). 아래 시험은 격리 PostgreSQL과 로컬 fake만 사용했습니다.
이 로컬 시험 단계에서는 운영 클러스터를 읽기 전용으로만 조회했습니다. 후속 배포와 실제 모델 측정·READY·자동 회수는 [운영 검증 근거](ai-model-profiling-live-20261030.md)에서 확인할 수 있습니다.

## 공식 근거

Kubernetes Job 문서(kubernetes/website `content/en/docs/concepts/workloads/controllers/job.md`, SHA `e01eed76aec22b8fed72af4c20194f4ec8bfc12b`, GitHub MCP로 확보. Context7은 월 한도 오류):

| 문서 내용 | 설계 반영 |
|---|---|
| `activeDeadlineSeconds`는 Job 전체 기간에 적용되며 도달하면 실행 중 Pod를 종료하고 `Failed`/`DeadlineExceeded`. `backoffLimit`보다 우선 | `backoffLimit 0` + `activeDeadlineSeconds = 2×준비 대기 + 측정 + 120`. `DeadlineExceeded`는 `DEADLINE_EXCEEDED` 실패 |
| `restartPolicy`는 Pod에 적용되고 `Failed` 이후 Job은 자동 재시작하지 않음 | `restartPolicy Never`, 재시작·Pod 교체는 `RESTARTED` 실패(한 실행만 측정) |
| 종단 조건은 `Complete`·`Failed`. v1.31+는 Pod가 모두 종료된 뒤 종단 조건을 붙이며, `FailureTarget` 시점에 대체 Job을 만들면 이전·새 Pod가 겹칠 수 있음 | Job 성공 조건을 측정 성공으로 쓰지 않음. 새 측정은 이전 Job·Pod가 Kubernetes에서 사라진 뒤(`cleanup DONE`)에만 같은 모델·노드 슬롯을 받음 |
| `spec.suspend: true`인 Job은 Pod를 만들지 않고, `false`로 바꾸면 그때 Pod를 만든다(Suspending a Job, v1.21+ GA). 재개 시 `activeDeadlineSeconds` 타이머는 다시 시작 | 생성 펜스: suspended 생성 → UID·`STARTING` 기록 → UID·resourceVersion 조건부 merge patch로 resume. RBAC `jobs patch` 추가 |
| `ttlSecondsAfterFinished`는 끝난 Job을 Pod와 함께 cascade 삭제 | `ttlSecondsAfterFinished 300`은 API 장애 시의 보조 회수. 정상 경로는 UID precondition foreground 삭제 후 Job·Pod 부재 확인. TTL로 사라진 Job은 `JOB_MISSING` 실패로 기록 |

기존 프로젝트 선례(재사용): 오프로딩 시험 콘솔 `KubernetesOffloadLabGateway`의 결정적 Job 이름, label·namespace 소유 확인, `nodeAffinity matchFields metadata.name` 배치,
UID precondition `DeleteOptions`, Job·Pod 부재 확인 후 완료 판정(운영 Jetson GPU roundtrip 검증, [오프로딩 시험](../guides/offload-lab.md)).
Prometheus 조회는 기존 `PrometheusWorkloadMetrics`의 고정 PromQL(cAdvisor·kube-state-metrics, `WorkloadTarget(Job, jobName, model, pod, podUid)`)과 장치 exporter 조회를 그대로 씁니다.
Kubernetes는 저장 시 quantity를 정규형으로 바꿀 수 있으므로(예: `2000m`→`2`) 채택 비교는 기본 단위 값으로 합니다.

## 클러스터 읽기 전용 확인 (kubectl, 2026-10-30)

| 항목 | 결과 | 영향 |
|---|---|---|
| kube-state-metrics(`kube-system/prometheus-kube-state-metrics`) 인자 | `--namespaces` 제한 없음, `jobs`·`pods` 수집 | 새 namespace의 `kube_pod_owner`·`kube_pod_info` 조회 가능 |
| kubelet ServiceMonitor | `/metrics/cadvisor`, namespace 제한 없음, scrape 30초 | `container_cpu_usage_seconds_total`·`container_memory_working_set_bytes`는 drop 대상 아님 |
| `up{metrics_path="/metrics/cadvisor"}` | ser0001·ser0002·ser0003·ser0004 = 1, dev0001–dev0009(edge) = 0 | edge 노드 측정은 표본 부족으로 실패. 서버 노드에서 측정 가능 |
| `edgeai-model-profiling` namespace | 없음 | bootstrap 적용은 조정자 단계 |
| 기존 Job 저장본(`kubectl get jobs -A -o json`, 1535개, 서버 v1.31.14) | API가 붙인 필드: `completionMode`·`manualSelector false`·`podReplacementPolicy TerminatingOrFailed`·`selector.matchLabels.batch.kubernetes.io/controller-uid`·template label 4종·template `creationTimestamp null`·`dnsPolicy ClusterFirst`·`schedulerName default-scheduler`·`serviceAccount`·`terminationMessagePath/Policy` | 채택 비교의 기본값 허용 목록과 일치 |
| Kueue 설정(`kueue-manager-config`, `mlops-kueue-manager-config`) | `batch/job` 통합 사용, `manageJobsWithoutQueueName` 기본(false)·mlops는 namespace label 선택 | queue label 없는 측정 Job은 Kueue가 suspend를 관리하지 않음. 이 설정이 바뀌면 측정 Job이 Kueue에 잡혀 시작되지 않을 수 있음 |
| 시험 이미지 | `digits-model-amd64:generic-model-20260915`(linux/amd64, USER 65532, HTTP 8080, OIP V2 `/v2/models/digits-centroid/infer`) — 조정자 확인 | Entrypoint·Cmd 기동만으로는 부하가 아니므로 HTTP 샘플 요청 방식으로 측정 |

## 1차 리뷰 수정 (2026-10-30)

| 지적 | 수정 | 시험 |
|---|---|---|
| 다른 프로세스의 늦은 Job 생성이 회수 완료·슬롯 해제 뒤 도착 | Job은 `suspend: true`로 생성, UID·`STARTING` 기록 후 다시 읽은 resourceVersion·UID 조건 merge patch로만 resume. resume 직전에 DB에서 run이 그 UID의 `STARTING`인지 확인 | `ModelProfilingIntegrationTest.createOrResumeDelayedPastCancelAndCleanupNeverRunsBesideTheNextRun`: 두 reconciler, A의 create를 barrier로 붙잡은 채 B가 취소·회수·같은 모델/노드 새 측정 승인·MEASURING → A의 create 도착: suspended Job, 이전 run Pod 없음, A의 상태 기록 거부, 고아 회수로 삭제. 이어서 A의 PATCH를 fake API에서 붙잡은 채 취소·삭제 → PATCH 404, Job 재생성·Pod 없음. suspend 없이 만들도록 되돌리면 실패 확인 |
| spec digest가 JVM마다 다름(`Map.of` 순서) | 모든 중첩 map을 키 정렬한 정규형으로 SHA-256 | `specDigestIsTheSameInFreshProcesses`: 새 JVM 4개와 시험 JVM의 digest 동일. 정규화를 빼면 실패 확인 |
| Kubernetes 응답 본문 정지로 reconcile 무한 대기 | 공용 `KubernetesRuntimeHttp`가 `sendAsync` + 전체 교환 deadline + 취소, `close()`는 `shutdownNow` 후 최대 2초 대기 | `everyCallIsBoundedAsAWholeExchangeEvenWhenTheBodyStalls`: 헤더와 1바이트 후 멈추는 loopback 서버에 ensureJob·observe·resume·delete·ownedRuns가 각각 deadline(0.8초) 직후 `UNAVAILABLE`, 진행 중 호출이 있어도 close 3초 미만 |
| 채택이 digest annotation과 일부 필드만 확인 | 저장된 `spec` 전체를 의도 spec과 재귀 비교. quantity는 기본 단위, Kubernetes 생성 시 기본값(selector·template label·completionMode·podReplacementPolicy·dnsPolicy·schedulerName·terminationMessage·suspend)만 허용 | `adoptionComparesEveryDeclaredFieldNotJustTheDigestAnnotation`: 기본값이 붙은 저장본은 채택, digest를 유지한 채 completions·parallelism·TTL·deadline·env·capabilities·seccomp·runAsUser·tmp 크기·mount·ephemeral-storage·GPU·hostNetwork·runtimeClass·toleration·dnsPolicy·pullPolicy·template label·selector·Job label·initContainer 변경 21종 모두 `OWNERSHIP_CONFLICT` |
| (점검) HTTP driver stop/close | stop은 구간 전후 모두 worker를 interrupt해 교환을 취소하고, 전체 worker가 2초 예산을 공유. close는 `shutdownNow` 후 최대 2초 | `HttpInferenceLoadDriverTest.stopAndCloseAreBoundedWhileResponsesStall`: 헤더 정지·본문 정지 서버, 요청 timeout 60초, 동시 4. 구간 종료 후·전 stop 3초 미만·미완료 요청 미집계, close 3초 미만. 이전 stop으로 되돌리면 실패 확인 |

HTTP 측정 구간은 첫 2xx 이후이므로 모델 적재 시 최대 메모리를 포함하지 않습니다. HTTP run 결과 `limitations`에 이 사실과 "시험한 부하에 대한 권장값이며 최소 자원 증명이 아님"을 추가했습니다.

## 로컬 시험

| 시험 | 범위 | 결과 |
|---|---|---|
| `KubernetesProfilingGatewayTest`(9) | suspended 생성, Job 형태(고정 이미지, nodeName 없음, affinity·arch, non-root·seccomp·capability drop·읽기 전용 FS·emptyDir만, request=limit), 정규화된 quantity를 가진 동일 Job만 채택·명령/노드/hostNetwork 변조 거부, Job UID 소유 Pod만 관측, UID precondition 삭제와 Pod 소멸 대기, 미확인 응답 예외. 1차 리뷰: 전체 spec 비교(21종 변조), 새 JVM digest 동일, UID·resourceVersion 조건 resume, 본문 정지 시 전체 교환 deadline·bounded close | 통과 |
| `HttpInferenceLoadDriverTest`(5) | 실제 로컬 HTTP 서버: Pod IP literal만 대상, 2xx 집계·중지 후 요청 없음, 5xx·redirect 미추종·1 MiB 초과·timeout은 성공 아님, 닫힌 포트는 연결 실패, 고정 histogram quantile, 헤더·본문 정지 응답에서 stop(구간 전·후)·close가 3초 미만 | 통과 |
| `ModelProfilingIntegrationTest`(6, 격리 PostgreSQL) | 실제 Kubernetes·Prometheus adapter와 상태 있는 loopback API fake, 실제 driver→로컬 모델 endpoint: 요구량 없는 등록→측정→SUCCEEDED→회수→확정→READY, 멱등·충돌, 실패 재측정이 READY 유지, 준비 실패·5xx 부하·짧은 COMMAND·무표본·이미지 pull 실패는 성공 아님, 취소 시 부하 중지와 회수 전 노드 점유·Kubernetes 미확인 시 회수 미완료, 타 프로세스 lease 만료 후에만 `DRIVER_INTERRUPTED`, 종단 기록 불변·삭제 금지·실패 run 근거 확정 DB 거부, 두 reconciler의 늦은 create/resume이 새 측정과 겹쳐 실행되지 않음, HTTP 결과 limitations에 적재 최대값 제외 문구 | 통과 |
| 기존 회귀 | `AiModelIntegrationTest`(7)·`ResourceProfileIntegrationTest`(6)·`OffloadLabIntegrationTest`(11)·`ResourceAnalysisDeadlineIntegrationTest`(1)·`RegistryDeletionIntegrationTest`(3), `test-unit.sh`(276), `test-contract.sh`, `test-docs.sh` | 통과 (1차 리뷰 수정 후 재실행). 공용 `KubernetesRuntimeHttp` 변경은 단위 `KubernetesRuntimeGatewayTest`·`KubernetesOffloadLabGatewayTest`·`KubernetesProcessingEngineGatewayTest`·`InfrastructureTest`와 `OffloadLabIntegrationTest`로 회귀 확인. 실제 클러스터가 필요한 `KubernetesRuntimeIntegrationTest`는 실행하지 않음 |

## 한계

- 후속 [운영 검증](ai-model-profiling-live-20261030.md)에서 amd64 CPU 모델의 실제 Pod 실행, API→Pod 도달성, 제한된 Pod Security 하 실행과 자동 회수를 확인했습니다. 다른 이미지·장치의 실행까지 보장하지는 않습니다.
- 단일 측정의 관측·고정 정책 추천이며 이론적 최소 요구량·처리량 목표를 증명하지 않습니다. CPU limit 때문에 관측 CPU가 잘릴 수 있습니다.
- GPU/NPU 요구 수량은 시험 할당값이며 장치 지표는 장치 전체 관측입니다.
- HTTP 측정은 첫 2xx 이후 구간만 보므로 모델 적재 시 최대 메모리·CPU를 포함하지 않습니다. 추천은 시험한 부하의 권장값이며 최소 자원 증명이 아닙니다.
- 생성 직후에도 저장된 Job을 다시 읽어 전체 spec을 비교하므로, 실제 클러스터가 허용 목록 밖의 필드를 붙이면 모든 측정이 `OWNERSHIP_CONFLICT`로 실패합니다(안전 측). 허용 목록은 아래 읽기 전용 확인과 일치하지만, 측정 Job 자체의 저장본은 조정자 클러스터 단계에서 확인합니다.
- resume의 UID·resourceVersion 조건 거부(409)는 fake API로 시험했습니다. 실제 API server의 merge patch 낙관적 동시성은 Kubernetes 문서 동작에 의존합니다.
