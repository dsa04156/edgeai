# 연속 센서 계산으로 가상디바이스 만들기

## 연속 센서 계산

인프라 관리 상단에서 엣지 AI 서버·엣지 디바이스·센서·가상디바이스의 전체 등록 개수를 확인하고 해당 목록을 엽니다. 가상디바이스 개수는 해제 이력을 제외하며 검색 결과 수와 별개입니다. 조회 실패는0개 대신 —로 표시합니다. 관리 메뉴에는 프로젝트 문서만 표시합니다.

웹의 **인프라 관리 → 가상디바이스** 화면에서 센서 노드 → 중앙 계산 → 결과 저장 → 항목별 조회 흐름을 확인할 수 있습니다.
화면의 ‘등록 가능’은 관리 API 설정이며 실제 계산 실행 상태와 다릅니다. ‘비활성’이면 중앙 처리 연결 준비가 남아 있고,
‘상태 확인 필요’이면 조회 실패 상태입니다. 현재 센서 목록을 유지합니다. 이전 표본·FFT VD 등록은 종료됐습니다.

`CONTINUOUS_EKUIPER`는 등록한 센서의 최신 유효값을1–3600초 간격으로 조합합니다.
웹의 **가상디바이스 등록**은 조합 종류·센서·계산 방식·장치 이름을 고르면 됩니다. **단일 센서** 외에 통합 온도·온도 편차·3축 회전/가속도 제곱합과 회전/가속도 XY·XZ·YZ 평면 제곱합10종을 이름으로 선택할 수 있습니다. 필요한 측정 항목을 직접 선택하면 입력 별칭·계산식·단위가 자동 구성됩니다. 같은 단위의 서로 다른 숫자 센서만 조합하며 축별로 해당 측정 항목을 선택합니다. 입력이 누락되거나 호환되지 않으면 간편 등록을 막습니다.

통합 온도는 선택한 두 온도의 평균, 온도 편차는 **온도1 − 온도2**입니다. 제곱합은 선택한 축의 값을 제곱해 더합니다. 가속도 원시값은 물리 단위로 환산하지 않으며 결과는 `raw²`, 회전 단위가 `rad/s`이면 결과는 `(rad/s)²`입니다. **조합값 그대로 / 최근 값 평균 / 최근 값 최솟값 / 최근 값 최댓값**을 고를 수 있습니다. 기본값은1초 계산, 평균/최솟값/최댓값에 최근5개 표본, 전체 결과7일 보관이며 고급 설정에서 바꿀 수 있습니다. 장치 키는 자동 생성됩니다. 다른 조합·직접 식·여러 출력·선택 저장은 **여러 센서·직접 계산식 설정**에서 지정하며 선택한 입력·계산은 이어집니다. ESC·취소·닫기로 돌아가도 등록 초안을 유지하며, 저장 실패는 같은 모달 안에 표시합니다.
예를 들어 온도 센서 하나를 별칭 `a`로 연결하고 `average`, `maximum`, `alarm`을 등록하면
VD 1개와 결과 3행이 생깁니다. 각각 독립 VD로 등록하면 VD 3개가 됩니다. 입력·출력은 VD당 최대 16개이고
연속 VD를 다른 연속 VD의 입력으로 연결하는 기능은 이번 버전에 포함하지 않습니다.

처리는 계산식·이동 평균·창 최솟값/최댓값·참/거짓 임계값·조건 필터를 지원합니다.
식은 `(a + b) / 2`, `a > 30 && b < 80`처럼 별칭을 사용합니다. 창은1–1024개의 연속 유효 계산 표본을 모읍니다.
입력 유효 시간은 기본30초, 보관 기간은 기본7일(최대30일)입니다. 모든 필수 입력이 유효해야 계산합니다.
센서를 새로 등록하면 선택 목록에 자동으로 추가되며, 기존 연결은 사용자가 설정을 저장할 때 바뀝니다.
원본 MQTT/ModbusTCP/OPCUA/SerialJSON/SenseHAT/HTTPJSON 수집 프로토콜은 계산 설정과 분리됩니다.

가상디바이스 이름을 누르면 **가상디바이스 그래프** 모달이 열리고 첫 출력의 저장된 이력을 자동 조회합니다. 출력마다 최신값·계산 시각·유효 상태가 별도 행에 표시됩니다. 각 행의 **그래프**로 출력을 전환하고 **조회 기간**을 선택합니다. 최신 결과는3초, 그래프는5초마다 갱신하며 ESC·닫기로 목록에 돌아갑니다. 기록 저장소가 연결되지 않았다면 최신 결과와 미연결 안내를 표시합니다.

ECharts의 툴팁으로 계산 시각·값·단위를 확인하고 시간축 슬라이더와 **확대·축소·전체 구간**으로 현재 조회한 기록을 탐색합니다. 확대 중에도 자동 갱신과 기록 표를 유지합니다. 누락 구간은 배경으로 구분하고 처리 세대 변경·미수신 구간의 선은 연결하지 않습니다.

장치 행의 **관리**에서 **시작 / 중지**를 누르고 확인한 뒤 실제 계산 엔진 보고를 기다립니다. **삭제**는 확인 모달을 거쳐 계산 중지와 원본 연결 종료를 요청하고 활성 목록에서 제거합니다. 원본 센서와 저장된 결과·정의 이력은 보존합니다. 삭제 실패는 모달에서 재시도하며 삭제 후 목록 조회가 실패해도 삭제 완료와 조회 실패를 구분합니다. **설정**은 별도 모달로 열려 입력 연결·계산 정의·저장 정책을 수정합니다. 등록 완료 시에는 목록으로 돌아오며 상세를 자동으로 펼치지 않습니다. 그래프 모달에서는 계산을 시작하거나 중지하지 않습니다. 누락·세대 변경 구간은 선을 연결하지
않으며 저장 중단은 따로 표시합니다. 저장 장애 중에는 최신 결과를 표시하고 선택된 결과를 PostgreSQL 임시 대기열에 둡니다. 복구 후 InfluxDB로 다시 전달하고 완료한 대기열 항목은 제거합니다. 대기열 기본 상한은 100,000개이며 보존 기간이 지난 대기 결과는 만료됩니다.
원본 센서 CRD는 측정 항목별로 유지하고 계산 결과의 CRD는 만들지 않습니다.

### 원본·정의·처리 결과 저장

센서 원본과 정책에 해당하는 VD 결과 데이터는 **InfluxDB OSS2.9.1**에 보관합니다.
VD 처리 정의·입력 연결·실행 설정·불변 프로필·생성 SQL 템플릿·실행 명령과 오류 이력은 **PostgreSQL**에 유지합니다.
연속 설정을 수정하면 이전 정의를 덮어쓰지 않고 `처리 정의·버전 조회`에서 새 등록 revision과 이전 설정을 확인합니다.
SQL은 서버가 생성한 실행 generation 부여 전 템플릿입니다. 공개 API는 임의 SQL·sink 실행 요청을 받지 않습니다.

VD 생성/수정의 **결과 저장 정책**은 `저장 안 함 / 선택 저장 / 전체 저장`입니다.
선택 저장은 출력 항목과 항목별 조건을 지정합니다. 조건의 `value`는 계산된 결과이며,
예를 들어 `value >= 10`, boolean 출력은 `value == true`로 작성합니다. 비우면 선택한 항목의 모든 값을 저장합니다.
이 정책은 계산식·엔진의 출력 조건·최신값을 바꾸지 않습니다. 정의에 정책이 없던 기존 VD는 ALL로 해석합니다.

**데이터 저장소** 화면은 저장 중인 원본 항목과 측정 시각·값·UID·설정 식별자·원래 MQTT JSON을 조회합니다.
최신 twin을 주기적으로 읽는 BATCH `sensor_sample`과 별개인 전용 MQTT 구독 경로입니다.
수집 식별자가 적용된 Mapper의 원본은 source generation/session/sequence를 유지하고 재전송을 같은 point로 처리합니다.
같은 원본 시각의 다른 sequence는 별도 보관하며, InfluxDB 내부 point 시각의 1ms 미만 부분은 식별용입니다.
API의 `observedAt`은 원래 측정 시각입니다. 날짜 범위 조회는 시작 포함·종료 미포함이며 최대 31일/2,000개입니다.
CRD에 수집 식별자가 있어도 기존 DataModel 출력과 qualified 출력을 모두 구독합니다. 같은 UID·항목·설정·원본 시각·타입·값의 두 형식은 qualified 기록을 우선해 합치며 ACK 전에 완료합니다. sequence가 없는 DataModel의 개별 측정 식별에는 한계가 있습니다.
이전 DataModel Mapper 원본은 명시적으로 **설정 미검증**으로 표시하며 같은 UID·항목·시각의 재보고는 point를 갱신합니다.
이 데이터만으로 UID/수집 설정 적용을 검증하거나 연속 처리의 유효 입력으로 승격하지 않습니다.

원본 bucket은 기본30일입니다. 결과는 `vd-results-1d`부터 `vd-results-30d`까지 보존 기간별 bucket을 사용하므로
VD별 기간이 실제 bucket 만료 정책에 연결됩니다. 조회에서도 만료된 결과를 제외합니다.
최신 결과 상태의 `SKIPPED / PENDING / STORED / EXPIRED`는 저장 정책 제외·전달 대기·저장 완료·대기 중 보관 기간 만료를 구분합니다.
MQTT 구독자는 저장을 마친 뒤 QoS1 ACK를 보냅니다. 지속 세션은 하루로 설정하며 브로커의 큐 크기·영속 설정은 별도 운영 항목입니다.
설정 복원은 규칙 재생성이며, 중단된 윈도우 상태를 자동 복원하거나 과거 원본을 재생하는 기능은 아닙니다.

### 로컬 InfluxDB 준비

`make data-store-start`는 검증된 공식 Linux amd64 binary를 설치하고 프로젝트 소유의
`127.0.0.1:18086` 저장소와 bucket을 준비합니다. `make data-store-stop`은 데이터·자격 증명을 남기고 소유 프로세스만 종료합니다.
`.tools/influx-data-store/connection.json`의 URL·org·tokenFile을 서버 전용 설정
`EDGEAI_INFLUX_URL`, `EDGEAI_INFLUX_ORG`, `EDGEAI_INFLUX_TOKEN_FILE`에 연결하고
`EDGEAI_RAW_HISTORY_ENABLED=true`로 원본 구독을 활성화합니다. 토큰은 출력하거나 Git에 저장하지 않습니다.
기본 토큰은 측정 bucket의 read/write 권한이며 초기 admin token은 API 프로세스에 전달하지 않습니다.
원본 수집은 중앙 eKuiper 실행 활성화와 독립적입니다. broker가 node loopback이면 Node InternalIP로 해석하며
필요한 경우 `EDGEAI_RAW_HISTORY_BROKER_NODE_OVERRIDES`에 Node UID별 도달 가능한 호스트를 지정합니다.

`make test-data-store`는 격리 PostgreSQL, 실제 InfluxDB2.9.1과 소유 MQTT 브로커로
원본·중복·같은 시각의 별도 sequence·기존 Mapper/두 형식 조정·값/조건 정책·numeric/boolean 결과·구간 집계·실제 저장소 중단/복구를 검증합니다. 새로 시작한 시험 저장소는 종료 시 정리하고 재사용한 인스턴스는 유지합니다.
일반 PostgreSQL integrationTest와 외부 시간계열 저장소 시험은 별도 실행하며 준비가 없으면 성공으로 skip하지 않습니다.
기존 `EDGEAI_HISTORY_DB_URL`은 이전 PostgreSQL 이력 호환 설정이며 새 Influx URL이 우선합니다.
이전 이력 DB·volume은 자동으로 삭제하거나 데이터 이전을 수행하지 않습니다.
공유 엔진의 현재 동시 실행 제한(200 VD/500 출력)과 별도로 등록된 정의·버전은 관리할 수 있습니다.
500 VD 동시 실행, 실센서 장기 손실률, 저장 공간/부하는 별도 수용 시험 대상입니다.

### 개발 검증

운영 센서용 broker는 Jetson·Pi 노드의 NanoMQ입니다. 중앙 eKuiper는 별도 `ekuiper` namespace의 worker에 배치합니다. 일반 SERVICE STREAM의 Mosquitto broker는 별도 구성입니다. 원본 MQTT의 영속 저장과 계산 결과 보관 이력도 구분합니다. eKuiper 설치·Helm 배치 확인은 Agent의 실제 규칙·입력·결과 수용을 대신하지 않습니다.

### 격리된 개발 검증

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
통합 시험 스크립트의 `isolated_test_db`를 사용합니다. 운영/개발 관리 DB의 이름과 자격을 그대로 사용해 integrationTest를 직접 실행하지 않습니다. 시험용 등록과 결과를 사용자 센서 데이터로 표시하지 않습니다.

### Kubernetes 처리 엔진 운영 준비와 되돌리기

아래 기존 `deploy/kubernetes/ekuiper` component와 preflight의 history PVC/480Gi 기준은 이전 PostgreSQL 이력 배포용입니다. 이번 InfluxDB 로컬 웹 반영을 Kubernetes InfluxDB 배포 완료로 해석하지 않습니다. 클러스터 적용 시 InfluxDB PVC·bucket·전용 토큰·네트워크 설정 및 실제 부하별 용량 산정을 먼저 준비해야 합니다.

기능 flag 기본값은 false입니다. 병합은 운영 엔진 활성화를 뜻하지 않습니다.
2026-10-14 호스트 운영 API·실제 센서의 qualified 입력·Kubernetes의 중앙 processing Agent/eKuiper 연결과 결과 저장은 확인했습니다. 아래 절차는 별도 Kubernetes 관리 API·DB·보안·복구 수용을 위한 운영 준비 기준이며 호스트 활성 상태와 구분합니다. 최신 검증 범위는 [PROGRESS](../../PROGRESS.md), 남은 게이트는 [PLAN](../../PLAN.md)을 확인합니다.
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
post-renderer 적용 후 REST·CLI·입력·callback은 loopback이고 agent health10083만 Service에 노출됩니다.
flag가false인 준비 단계는 readiness를 얻지 못하므로 Helm `--wait`로 활성화 완료를 주장하지 않습니다.

활성화는 실제 qualified source, 전용 history와 소유/보안 검증 뒤 backend의
`EDGEAI_CONTINUOUS_ENABLED=true`로 진행합니다. 되돌릴 때는 먼저 기존flag를false로 바꾸고
15초 lease 만료 뒤 agent가 `edgeai_vd_<VD UUID>_<generation>_<output>` 소유 규칙만 제거한 것을 확인합니다.
그 뒤 agent 적용 전 Helm revision으로 되돌립니다. 다른 사용자 규칙·물리 센서 CRD·VD 등록·history/PVC는 삭제하지 않습니다.
24시간 실제 soak는 p95 결과 전달≤2초, 정상 입력의 손실/중복 없음, queue/메모리 상한 유지와
실제 DB 저장량을 확인해야 합니다. local-path는 자동 확장을 가정하지 않습니다.
