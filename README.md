# EdgeAI

엣지 AI 서버·엣지 디바이스·측정 센서를 관리하고 연속 가상 센서와 서비스 실행을 구성하는 플랫폼입니다.
Kestra 원본 Vue 콘솔, Spring Boot와 PostgreSQL을 기반으로 Kubernetes·Prometheus·KubeEdge Mapper와 연결합니다.

## 2차년도 개발목표와 현재 시스템 비교

**확인 기준일: 2026-10-29.** 협약 계획서의 세부1 연구개발 항목 **2-1~2-4, 3-1~3-4**를 기준으로 비교합니다.
2차년도 목표는 **요소기술 개발 및 프로토타입 제작**이며, 최종 연차의 통합 실증·성능 수용과 구분합니다.
아래 현황은 이 루트 checkout만이 아니라 최신 기능 브랜치의 구현·검증 기록과 당시 실제 운영 상태를 대조한 결과입니다.
다른 기관의 담당 목표는 연계 범위이며 이 저장소만으로 전체 과제의 완료를 판정하지 않습니다.

출처: [2차년도 협약용 연구개발계획서](https://github.com/dsa04156/edge-ai-workspace/blob/75cd54fce506e2dd1ebe0c98ce84603f17edd9a6/docs/%282%EC%B0%A8%EB%85%84%EB%8F%84%ED%98%91%EC%95%BD%EC%9A%A9%29%20%EC%97%B0%EA%B5%AC%EA%B0%9C%EB%B0%9C%EA%B3%84%ED%9A%8D%EC%84%9C-%EC%97%A3%EC%A7%80%20%EC%BB%B4%ED%93%A8%ED%8C%85%20%EC%8B%9C%EC%8A%A4%ED%85%9C%EC%9D%84%20%EC%9C%84%ED%95%9C%20%EB%8C%80%EA%B7%9C%EB%AA%A8%20%ED%98%BC%ED%95%A9%20%EB%94%94%EB%B0%94%EC%9D%B4%EC%8A%A4%20%EC%A0%9C%EC%96%B4%C2%B7%EA%B4%80%EB%A6%AC%20%ED%94%8C%EB%9E%AB%ED%8F%BC%20%EA%B0%9C%EB%B0%9C_0415.pdf), 본문 49쪽·53~58쪽(PDF 58쪽·62~67쪽).

| 항목 | 2차년도 목표 | 현재 구현·검증 근거 | 남은 차이·판단 |
|---|---|---|---|
| **2-1. AI서비스 분할 및 동적 오프로딩 기술** | 실시간 배치·이동·부하경감, 사전 예약 배치, 사용자 우선순위 전환, 이상 시 자동 재배포·롤백; 오프로딩 결과 영속성을 위한 스토리지 증강 | DAG 작업 분리·최초 배치·실행 위치 전환, CPU·메모리·지연 임계값 기반 자동 전환, 재시도·결과 영속화 | **부분 구현.** 실제 GPU 부하 기반 이동, 모델 사전 예약 배치, 사용자 우선순위 전환, 이상 감지→자동 롤백의 실장비 근거 부족. 결과 저장과 동적 볼륨 증강·축소는 별도 |
| **2-2. AI 서비스 배포 및 통합 자원 관리 워크플로우 기술** | 분산 자원 배치 전략, 배포·실행·자원 제어 수명주기, 워크플로우 통합 개발도구 | SERVICE 불변 버전→DAG→Run/Task→Runner→Result, 편집 UI·자원 관측, 측정 기반 BATCH 배치·프로파일 화면, DDS 배포 경로 | **핵심 프로토타입 확보.** DDS 배포와 Spring DAG 실행의 책임·상태·데이터 계약 통합 및 측정 기반 배치의 실제 성능 검증 필요 |
| **[2-3. 실시간 데이터 전송 지원 개방형 미들웨어 기술](docs/deliverables/R2-03_실시간_데이터_전송_개방형_미들웨어.md)** | 등록·탐색·메타데이터 전송, 계층 기반 관리자·기본 데이터 경로, 개방형 API·이기종 연동 | KubeEdge CRD 등록·등록 장치 탐색, 노드별 Mapper 수집·취합·API, MQTT/NanoMQ 전송 경로 설정, 중앙 관리자의 입력 바인딩·처리 계획, 원본 저장·eKuiper 집계·연속 처리, HTTP/JSON API·프로토콜별 드라이버. [수행 내용·구성도 대응·코드·검증 근거](docs/deliverables/R2-03_실시간_데이터_전송_개방형_미들웨어.md) | **2차년도 핵심 기능·기본 경로 구현.** 탐색은 등록 원본 조회·선택, 통계 집계는 중앙 수행. 실장비와 시험 서버 검증을 구분하며, 전체 관측 운영 수용은 별도 |
| **2-4. 고가용성 보장 기술** | 장애 인지·자동 복구, 디바이스 인증, 컨테이너 이중화·작업 재실행 | 상태·heartbeat·lease, 재시도·오래된 실행 차단, 내부 Runner/Device 인증, 일부 TLS·백업·복구 | **부분 구현.** 당시 운영 API·콘솔은 단일 replica. 센서 프로토콜 인증 범위, 실제 장애 자동복구·이중화 검증 부족 |
| **3-1. 온디바이스 가상화 기술** | 가상 AI 가속기 에뮬레이션 엔진, 물리·가상 디바이스 동시·병렬 실행, 개발도구 연동 | 컨테이너 실행 기반과 eKuiper 연속 가상디바이스 | **목표와 큰 차이.** eKuiper VD는 센서 계산 정의이며 가상 AI 가속기 에뮬레이터가 아님. 해당 엔진과 워크플로 연동의 구현 근거 미확보 |
| **3-2. 멀티 디바이스 오케스트레이션 기술** | 컨테이너 단위 가상디바이스, 물리·가상노드 연동·제어·통신 | Kubernetes/KubeEdge 다중 노드, DAG 작업별 실행·데이터 전달·상태 관리 | **부분 대응.** 다중 물리 노드 실행 기반은 확보했으나 3-1의 가상 실행체를 포함한 공통 제어·통신 연결은 부족 |
| **3-3. 가상 디바이스 성능 강화 기술** | CPU/GPU/메모리/NPU 자원 세분화·가상화 인터페이스, 서비스별 최소·최대 자원 프로파일·증강 | 자원 요구량·호환성 조건, 가속기 관측, 측정 프로파일, HAMi GPU 공유 실험 | **설계·연구 및 일부 구현.** 측정 프로파일은 최소·최대 자원 보장 계약과 다르며 프로파일 범위 내 자동 자원 증강은 별도 |
| **3-4. 대규모 워크로드 실행 기술** | 상태 기반 스케줄링, 컨테이너 분산 배포·병렬 실행 | DAG 의존성 실행, Kubernetes 배치, 측정 기반 BATCH 노드 선택 | **기본 실행 기반 확보.** 대규모 동시 워크로드의 처리량·경합·실패 대응 근거 부족. 등록 자원 수로 동시 실행 규모를 대신 입증하지 않음 |

### 우선 보완 항목

1. **2-1 세부 기능:** 사전 예약 배치·사용자 우선순위 전환·GPU 부하 시 이동·서비스 이상 시 자동 롤백을 각각 구현과 실장비 검증으로 구분합니다. 현재 자동 전환 정책의 명시적 판단 지표는 CPU·메모리·지연입니다.
2. **3-1 구현 공백:** 현재 eKuiper 가상디바이스와 계획서의 가상 AI 가속기·실행환경을 구분하고 에뮬레이션 엔진 및 개발도구 연동 범위를 구체화합니다.
3. **2-2·2-3 성과 연결:** 확보한 프로토타입을 계획서 세부 요구와 시험 근거에 연결하고, 기관 간 인터페이스와 실제 연동 범위를 정리합니다.

비교 근거: [자동 전환 정책](docs/adr/0009-automatic-offload-policy.md), [STREAM 그룹 전환](docs/adr/0052-automatic-stream-group-offload.md),
[측정 기반 배치·웹 운영 기록](https://github.com/dsa04156/edgeai/blob/f09ac4c/docs/evidence/scheduling-profile-web-deploy-20261029.md),
[실제 영상·DAG 검증](https://github.com/dsa04156/edgeai/blob/101cc56/docs/scenarios/hawkeye-warehouse-safety.md),
[GPU 공유 실험](https://github.com/dsa04156/edgeai/blob/1421f6f/experiments/hami-gpu-sharing-load-20261029/README.md),
[보안·복구 구현과 수용 경계](docs/requirements/m9-requirements.md).

## 시작하기

- [EdgeAI 소개](docs/getting-started/overview.md): 구성 요소와 사용 경로
- [로컬 빠른 시작](docs/guides/local-development.md): 준비·실행·연결 확인
- [첫 프로필 등록](docs/getting-started/first-profile.md): 등록·조회·정리 실습
- [연속 가상 센서](docs/guides/vd-processing.md): 입력·계산·출력 등록과 운영 활성화 조건
- [서비스 자원 프로파일](docs/guides/resource-profiling.md): 3-3 CPU·메모리·GPU/NPU 요구량 분석·저장과 2-1 전달 계약
- [AI 모델 관리](docs/guides/ai-model-catalog.md): 기존 Registry 이미지 digest·플랫폼과 자원 요구량의 모델 버전별 등록·목록(워크플로 연동은 후속)
- [문서 안내](docs/README.md): 개념·사용·운영·API·기여 문서

Kestra 원본 콘솔은 `bash scripts/dev/dev-kestra-native.sh`로 실행하며
`http://127.0.0.1:13083/ui/main/dashboards/default`에서 사용합니다. Kestra 서버·DB 설치 없이
기존 EdgeAI API를 연결합니다. 운영 release는 같은 콘솔을 `server.mjs`로 13080에서 제공합니다.
실행 조건·메뉴별 기능은 [로컬 빠른 시작](docs/guides/local-development.md#kestra-원본-vue-운영-콘솔)에 있습니다.
기존 Next.js 화면·BFF·`/docs` 사이트 소스는 저장소에서 제거했습니다. 이미 실행 중인 호스트 13080 프로세스의
교체·중지는 배포 작업에서 별도로 수행하며 소스 제거만으로 완료되지 않습니다. 문서는 저장소 `docs/`에서 읽습니다.

Swagger는 `http://127.0.0.1:18080/swagger-ui.html`입니다.

```bash
make setup
make up
# 각각 별도 터미널에서 실행
make backend
make dashboard
```

사전 도구·종료·Docker 없는 DB 경로는 빠른 시작을 확인합니다.
기본 관리 실행과 실제 장비·워크플로 실행의 준비 조건은 다릅니다.

## 구조와 지원 범위

| 경로 | 역할 |
|---|---|
| `backend/` | Spring 관리 API·도메인·adapter |
| `dashboard/` | 관리 화면과 웹 문서 |
| `platform-service/` | DDS 편집·Buildx·Gitea·Argo CD 배포 API |
| `runner/`, `simulator/` | 작업 실행과 참조 구현 |
| `contracts/` | OpenAPI와 규격 |
| `deploy/`, `scripts/` | 배포 구성·개발·검증·운영 명령 |
| `docs/` | 사용자 안내와 설계·검증 기록 |

DDS 배포와 Spring DAG는 [서로 다른 경로](docs/concepts/workflows.md)입니다.
새 콘솔은 Kestra의 표·드로어·폼·그래프/코드 패널을 사용합니다. 센서는 측정 항목별 Device·DeviceStatus CRD로 분리하고, 중앙 eKuiper의 연속 VD는 여러 입력과 출력별 결과를 관리합니다. 구형 VD BATCH·supervisor와 DEVICE 신규 발행은 종료됐습니다. 연속 처리 기본값은 비활성이며 eKuiper 설치만으로 실제 계산·이력 저장을 시작하지 않습니다.
현재 일반 관리 API는 로그인 없이 접근하며 쓰기에는 CSRF가 필요합니다.
[접근 경계](docs/reference/access.md)와 [지원 범위](docs/reference/support.md)를 확인합니다.

최신 개발 상태는 [PROGRESS.md](PROGRESS.md), 단계별 잔여 범위는 [PLAN.md](PLAN.md)에 있습니다.
개별 기능 구현과 전체 플랫폼 실장비·성능·복구 수용을 구분합니다.

기여하려면 [개발 참여](docs/contributing/development.md)와 [문서 작성 기준](docs/contributing/documentation.md)을 읽으세요.
