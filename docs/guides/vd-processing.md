# 연속 센서 계산으로 가상 디바이스 만들기

## 연속 센서 계산

웹의 **가상 센서** 화면에서 센서 노드 → 중앙 계산 → 결과 저장 → 항목별 조회 흐름을 확인할 수 있습니다.
화면의 ‘등록 가능’은 관리 API 설정이며 실제 계산 실행 상태와 다릅니다. ‘비활성’이면 중앙 처리 연결 준비가 남아 있고,
‘상태 확인 필요’이면 조회 실패 상태입니다. 현재 센서 목록을 유지합니다. 이전 표본·FFT VD 등록은 종료됐습니다.

`CONTINUOUS_EKUIPER`는 등록한 센서의 최신 유효값을1–3600초 간격으로 조합합니다.
웹의 **가상 장치 등록 → 연속 처리 장치 등록**에서 입력 센서와 별칭을 고르고 출력 항목을 추가합니다.
예를 들어 온도 센서 하나를 별칭 `a`로 연결하고 `average`, `maximum`, `alarm`을 등록하면
VD 1개와 결과 3행이 생깁니다. 각각 독립 VD로 등록하면 VD 3개가 됩니다. 입력·출력은 VD당 최대 16개이고
연속 VD를 다른 연속 VD의 입력으로 연결하는 기능은 이번 버전에 포함하지 않습니다.

처리는 계산식·이동 평균·창 최솟값/최댓값·참/거짓 임계값·조건 필터를 지원합니다.
식은 `(a + b) / 2`, `a > 30 && b < 80`처럼 별칭을 사용합니다. 창은1–1024개의 연속 유효 계산 표본을 모읍니다.
입력 유효 시간은 기본30초, 보관 기간은 기본7일(최대30일)입니다. 모든 필수 입력이 유효해야 계산합니다.
센서를 새로 등록하면 선택 목록에 자동으로 추가되며, 기존 연결은 사용자가 설정을 저장할 때 바뀝니다.
원본 MQTT/ModbusTCP/OPCUA/SerialJSON/SenseHAT/HTTPJSON 수집 프로토콜은 계산 설정과 분리됩니다.

장치 상세에서 **계산 시작**을 요청한 뒤 실제 계산 엔진 보고를 기다립니다. 출력마다 최신값·계산 시각·
유효 상태가 별도 행에 표시됩니다. 각 행의 그래프로 기록을 조회합니다. 누락·세대 변경 구간은 선을 연결하지
않으며 저장 중단은 따로 표시합니다. 저장 장애 중에도 최신 결과는 표시하지만 그 기간은 복구 뒤 채우지 않습니다.
원본 센서 CRD는 측정 항목별로 유지하고 계산 결과의 CRD는 만들지 않습니다.

### 개발 검증

프로젝트 로컬 PostgreSQL이 준비된 뒤 `bash scripts/dev/dev-processing-history.sh`로 별도 개발/시험 DB를 준비합니다.
실제 Mosquitto와 [eKuiper2.4.2 full release](https://github.com/lf-edge/ekuiper/releases/tag/v2.4.2)가 필요합니다.
`kuiper-2.4.2-linux-amd64-full.tar.gz` SHA256은
`b1e5ca2ba483e253329744fa3c0e76c2391a7fb5f062dffd1554019873fc14ea`입니다.
해당 배포를 `.tools/ekuiper-test-2.4.2-full/`에 풀거나 `PROCESSING_TEST_EKUIPER_HOME`을 지정하세요.
core 배포에는 agent가 사용하는 metadata REST가 포함되지 않습니다.
`MAPPER_TEST_MOSQUITTO`로 이미 설치한 브로커 실행 파일을 지정할 수 있습니다.

`bash scripts/test/test-processing.sh`는 실제 브로커→agent→실제 eKuiper→결과 전달과 창 분리,
70회 Mapper session 교체/옛 session 재전송 거절, 인증 callback 및 공개 proxy 경로를 검사합니다.
전체 backend PostgreSQL 검증은 `bash scripts/test/test-integration.sh`, UI는
`bash scripts/test/test-ui.sh`입니다. 개발 테스트를 실센서·운영 부하/24시간 검증으로 해석하지 않습니다.

### 운영 준비와 되돌리기

기능 flag 기본값은 false입니다. 병합은 운영 엔진 활성화를 뜻하지 않습니다.
새 agent·history·Mapper·TLS 설정의 Kubernetes 적용은 아직 수행하지 않았습니다.
`bash scripts/dev/build-processing-agent.sh`로 AMD64 정적 binary를 만들고
`mappers/processing-agent.Dockerfile`로 이미지를 빌드해 immutable digest를 확정합니다.
`bash scripts/ops/prepare-processing-material.sh /absolute/new/private/directory`는
CA·TLS·공유 credential·history 암호를 권한0700/0600의 로컬 파일로 준비합니다. 기존 값을 덮어쓰거나
클러스터에 전송하지 않습니다. 생성한 `secrets.yaml`은 공개 Git/evidence에 저장하지 마세요.

`bash scripts/ops/preflight-processing.sh <명시적-context> <CG002-SSH-alias>`는 승인된 worker·Helm
release 소유·local-path와 실제 `/opt/local-path-provisioner` 파일시스템의480Gi 이상 여유를 읽기만 합니다.
기존 PVC가 별도 파일시스템 경로를 사용한다면 그 실제 경로의 용량 확인 후 배포 설정을 검토해야 합니다.
Mapper의 노드별 HTTP `/capabilities`에서 `edgeai.mapper-capabilities/v1`, 실행 protocol,
`observationApiVersion=edgeai.observation/v1`, `sessionOrdering=PERSISTED_MONOTONIC`,
`qualifiedObservationEnabled=true`를 확인하고 원본 qualified 패킷을 실제로 받아야 합니다.
이전 image를 Pod Running만 보고 새 observation 지원으로 표시하지 않습니다.
Mapper의 기존 노드 callback hostPath에는 `dmi-budget.observations`와 lock 파일이 저장됩니다.
세션 카운터는 재시작과 시계 조정 뒤에도 증가합니다. 저장 실패 시 기존 raw MQTT·DMI는 유지하고
qualified 발행만 대기·재시도합니다. sourceGeneration을 유지한 채 카운터를 삭제하지 마세요.
노드 저장소를 잃은 경우 해당 센서의 sourceGeneration을 명시적으로 새로 발급해야 합니다.

기존 application overlay에 `deploy/kubernetes/ekuiper` component를 추가하면 history와 backend
TLS/credential 설정을 합칠 수 있습니다. overlay가 기존 release image를 유지하는지 먼저 렌더링해 검토하세요.
namespace `ekuiper`의 `processing-rbac.yaml`/`network-policy.yaml`은 별도로 적용해 namespace 변환을 방지합니다.
upstream Helm chart1.4.0을 기존 `my-ekuiper` release/`ekuiper` namespace와 기존 values로 렌더링하며,
`EDGEAI_PROCESSING_AGENT_IMAGE=<repository@sha256:digest>` 환경 및
`--post-renderer scripts/ops/processing-post-renderer.sh`로 agent를 붙입니다. 데이터/plugin PVC는 유지합니다.
REST·CLI·입력·callback은 loopback이고 agent health10083만 Service에 노출됩니다.
flag가false인 준비 단계는 readiness를 얻지 못하므로 Helm `--wait`로 활성화 완료를 주장하지 않습니다.

활성화는 실제 qualified source, 전용 history와 소유/보안 검증 뒤 backend의
`EDGEAI_CONTINUOUS_ENABLED=true`로 진행합니다. 되돌릴 때는 먼저 기존flag를false로 바꾸고
15초 lease 만료 뒤 agent가 `edgeai_vd_<VD UUID>_<generation>_<output>` 소유 규칙만 제거한 것을 확인합니다.
그 뒤 agent 적용 전 Helm revision으로 되돌립니다. 다른 사용자 규칙·물리 센서 CRD·VD 등록·history/PVC는 삭제하지 않습니다.
24시간 실제 soak는 p95 결과 전달≤2초, 정상 입력의 손실/중복 없음, queue/메모리 상한 유지와
실제 DB 저장량을 확인해야 합니다. local-path는 자동 확장을 가정하지 않습니다.
