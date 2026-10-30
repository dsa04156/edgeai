# AI 모델 자원 측정 운영 배포·검증 (2026-10-30)

모델 등록 → 웹 측정 시작 → 등록 이미지의 임시 Job/Pod 실행 → 실제 추론 요청 → 사용량 수집 → 웹 요구량 확정 → READY와 자동 회수까지 운영 환경에서 확인했습니다.

## 배포와 검토

- 기능 소스 `ac8fd13`, 최신 main 문서 병합 후 빌드 소스 `d12a4c68c2276dec8012f5929bdfcdb2d0869a96`, GitOps 배포 `776653f03ddf63adb1fc099443c52668ab7cc6e5`.
- Claude Code / Opus `claude-opus-5-5`가 UI와 백엔드를 구현·수정했고, Codex / GPT-6 Astra가 두 차례 독립 검토했습니다. [최종 검토](ai-model-profiling-review-20261030.md): Standards 4건·Spec 5건 해결, 남은 차단 결함 0건. 조정자는 구현 파일을 변경하지 않고 문서 병합·빌드·배포·운영 검증을 수행했습니다.
- API: `10.254.192.47:5000/edgeai-api@sha256:3c6e37c69f07d34e97efcd7e2e75955c606fde8301a321deecc4b50354a4d3f9`.
- 콘솔: `10.254.192.47:5000/edgeai-dashboard@sha256:98480c4c14b4107193450b3c1085ebb9fe113384e3ee89f0e510cdeba49a6e49`.
- 두 이미지의 Registry manifest/config에서 `linux/amd64`와 빌드 소스 revision label을 다시 확인했습니다. API JAR·콘솔 production build 성공 후 실행 명령 없는 FROM/COPY 패키징을 사용했습니다.
- `model-profiling-rbac.json`을 적용해 전용 namespace·Pod SA·quota·namespaced Role/Binding을 생성했습니다. API SA의 Job patch 권한을 확인했습니다.
- 렌더링 전후 비교 결과 기존 리소스 변경은 API/콘솔 Deployment 둘뿐이었습니다. 모델 측정 component와 이미지 digest를 GitOps로 반영했고, Argo CD `Synced/Healthy` 및 두 Deployment rollout 성공을 확인했습니다. 사용자 요청으로 중단한 GPU Deployment 세 개의 replicas=0은 유지했습니다.

## 실제 웹과 Kubernetes 검증

- 모델: `손글씨 숫자 분류 · CPU` (`digits-centroid`, version `20260915`, config `cpu-amd64`). 모델 ID `eb7e3aa0-b2e5-45ab-8116-65105046bc32`.
- 이미지: `10.254.192.47:5000/digits-model-amd64@sha256:f1e97a098403e830f18a39ad92f94b29dab057e38c11da6961a8170c45a6697f` (`linux/amd64`). 실제 centroid 숫자 분류 모델의 OIP V2 추론 endpoint를 사용했습니다.
- 자원 요구량 없이 실제 API에 등록한 뒤 `NOT_READY/NO_MEASUREMENT`를 확인했습니다. 운영 웹 `http://10.254.192.47:13080/ui/main/ai-models`에서 측정 시작 폼을 채우고 버튼을 눌렀습니다. API mock이나 로컬 fake는 사용하지 않았습니다.
- 노드 `etri-ser0001-cg0msb`, 시험 할당 CPU `1000m`, 메모리 `256Mi`, 가속기 없음. HTTP POST `:8080/v2/models/digits-centroid/infer`, 실제 FP32 `[1,64]` 입력, 동시 요청 1, 설정 시간 300초.
- run ID `d1c5d114-4944-4efe-9593-4ce7f1e088bb`. Job UID `b6eea374-f493-4f94-ade6-10f8d50deccb`, Pod UID `8905145e-dfa6-4186-9032-5893a90c84e4`. Job의 suspend=false, Pod Running·restart=0과 고정 이미지 digest를 확인했습니다. 실제 API-server 기본값을 거친 Job 채택·resume과 API→Pod 연결이 동작했습니다.
- 관측 구간 `2026-10-30T07:48:16.890Z`–`07:53:18.703Z` (KST 16:48:16–16:53:18). 준비 후 부하 및 종료 확인을 포함한 기록 구간은 약 301.8초입니다.
- 요청 451,384건: HTTP 200 성공 451,324, IO 실패 60, 성공률 99.9867%. 실패가 전혀 없었다고 주장하지 않습니다. 저장된 통과 기준(성공률 95% 이상)을 충족했습니다.
- cAdvisor/Prometheus에서 정확한 시험 Pod의 CPU 표본 20개, 메모리 표본 21개를 확보했고 둘 다 AVAILABLE. CPU p95 `787.45m`, 최대 `788.203m`; 메모리 working set 최대 `19,087,360 bytes`.

| 확정 항목 | 요청 | 최대 | 근거 |
|---|---:|---:|---|
| CPU | 910m | 1190m | 관측 p95/최대와 고정 여유율 정책의 추천 |
| 메모리 | 22Mi | 28Mi | working set 최대와 고정 여유율 정책의 추천 |
| 가속기 | 없음 | — | 시험 미할당, deviceStatus NOT_REQUESTED |

- 운영 웹의 **요구량 확정** 버튼으로 revision 1, profile ID `6d46ff4b-47af-4d49-9490-72c42b709d1c`를 생성했습니다. 상세와 API의 `availability.status=READY`, 목록에 쓰는 profile requirements가 CPU 910m·메모리 22Mi임을 확인했습니다. 최소 요구량은 단일 시험으로 입증할 수 없어 null입니다.
- 데스크톱(1440×1000)에서 시작·결과 확정, 모바일 폭(390×844)에서 사용 가능 상태·확정 요구량을 확인했습니다. 실제 페이지의 JavaScript 오류 0건.
- 최종 run SUCCEEDED, cleanup DONE(`07:54:21.998Z`). 전용 namespace의 `kubectl get jobs,pods` 결과는 **No resources found**였습니다. 모델·측정 이력·확정 결과는 DB에 남습니다.
- 스크린샷·실제 API 응답·실행 중 Job/Pod·Prometheus 응답은 로컬 `output/evidence/ai-model-profiling-live-20261030/`에 보관했습니다(생성 산출물, Git 미포함). 임시 로컬 개발 서버는 종료됐으며 이 작업의 격리 PostgreSQL(55480)도 데이터 삭제 없이 종료했습니다.

## 시험 근거와 남은 제한

- 개발자 시험: backend unit 276, UI unit 162, 관련 Playwright desktop/mobile 40, 기존 integration 28+새 profiling 6 통과. 최종 독립 검토에서 backend 핵심 14와 UI 핵심 10을 새로 실행해 통과했습니다. [로컬 시험 근거](ai-model-profiling-20261030.md), [UI 시험 근거](ai-model-design-20261030.md).
- [배포 commit CI](https://github.com/dsa04156/edgeai/actions/runs/41022223427): scaffold(기본 unit/API contract/콘솔 검사), storage, data-store, influxdb, runner-plan 통과. 전체 workflow는 기존 Runner native 증빙 단계의 `ValueError: Native Runner tests must execute without skips`로 amd64/arm64 모두 실패했고 images/gitops job은 건너뛰었습니다. [변경 전 CI](https://github.com/dsa04156/edgeai/actions/runs/41012200801)에서도 같았던 오류입니다. 이 변경에서 Runner를 수정하지 않았으며 전체 CI 통과로 표현하지 않습니다. 위 별도 검증 이미지와 GitOps 반영으로 운영 배포했습니다.
- 운영 재조회 시 cAdvisor `up=1`은 서버 네 노드, `up=0`은 등록된 edge 여덟 노드였습니다. 이 상태의 edge 노드는 CPU·메모리 표본을 확보하지 못하므로 측정 성공을 보장하지 않습니다. exporter·edge 네트워크 수정은 수행하지 않았습니다.
- 이번 운영 실측은 amd64 CPU 모델 하나입니다. GPU/NPU별 실제 추론 측정은 수행하지 않았습니다. GPU/NPU 수량은 시험 할당량이며 사용률로 자동 산정하지 않습니다.
- HTTP 첫 성공 이후의 측정이므로 초기 모델 적재 피크는 포함하지 않습니다. 추천값이 최소 자원이나 다른 노드·부하에서의 성공을 보장하지 않음을 UI에 표시합니다.
- 워크플로에서 모델을 선택해 가져오는 연동은 후속 범위입니다. HPA/VPA/HAMi나 device plugin은 설치·변경하지 않았습니다.
