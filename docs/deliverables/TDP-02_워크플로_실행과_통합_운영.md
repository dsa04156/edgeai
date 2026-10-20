# 엣지 워크플로 실행과 통합 운영 소프트웨어 기술자료

**TDP-02** · 2026-10-20

## 구성

SERVICE DAG 실행, 센서·VD 입력 연계, 결과 검증, Kestra 운영 콘솔의 소스와 설치·운영·시험 절차를 정리한다. 설계 설명은 [TM-04](TM-04_분산_작업_실행과_결과_검증.md), [TM-05](TM-05_실시간_워크플로_입력_연계.md), [TM-06](TM-06_엣지_자원_관측과_운영_콘솔.md)에 있다. 센서와 eKuiper 준비는 [TDP-01](TDP-01_센서_수집과_가상디바이스_처리.md)을 따른다.

| 구성 | 기능 |
|---|---|
| 실행 제어 | 모듈·DAG 버전, Run·Attempt, 배치·노드 이동 |
| Runner | 입력 검사, 계산, 출력 업로드, 결과 확정 |
| 실시간 입력 | 센서·VD 최신 유효값, 입력 식별 정보 고정, 정상 종료 |
| 운영 콘솔 | 자원 모니터링, 편집, 실행·결과·감사 조회 |

### 소스 위치

| 경로 | 내용 |
|---|---|
| `backend/domain` | 실행 모델·정책·외부 연동 인터페이스 |
| `backend/app` | DAG·Run·Task·위치 전환·입력 서비스 |
| `backend/adapters` | DB·Kubernetes·S3·메트릭 연동 |
| `runner/edgeai_runner` | claim·실행·STREAM·journal |
| `contracts/openapi` | 공개 API·내부 Runner API |
| `contracts/profiles` | SERVICE 실행·데이터 입력 예제 |
| `dashboard/kestra-native` | EdgeAI용 Vue 화면·API 연동 |
| `scripts/dev/dev-kestra-native.sh` | Kestra 원본 UI 준비·실행 |

Kestra는 v2.0.6 원본 revision `b59444f09be2e44a3b0738452b2e02fc4fe89505`를 사용한다. 실행 제어는 EdgeAI API가 담당하며 Kestra 서버·DB는 설치하지 않는다. 외부 소스의 라이선스와 고지를 유지한다.

## 실행 환경과 설정

| 구성 요소 | 로컬 기본 포트 | 역할 |
|---|---|---|
| 기존 웹·BFF | 13080 | API 프록시·문서 |
| Kestra native 콘솔 | 13083 | Vue 운영 화면 |
| 관리 API | 18080 | 센서·VD·저장 관리 |
| 별도 워크플로 API | 18082 | 모듈·DAG·실행 제어 |
| InfluxDB | 18086 | 원본·VD 결과 이력 |

관리 API는 JDK 21, 기존 BFF는 Node 22·pnpm 10.34.6을 사용한다. Kestra UI는 Node 24·npm 11로 별도 실행한다. Runner 의존성은 Dockerfile과 `requirements-stream.txt`, UI 의존성은 lockfile을 따른다. [2][5]

관리·워크플로 API를 분리하면 같은 control DB를 쓰되 원본 수집은 관리 인스턴스 한 곳에서만 실행한다. 세션은 `EDGEAI_SESSION`과 `EDGEAI_WORKFLOW_SESSION`으로 구분하고 각각 CSRF를 사용한다.

| 설정·조건 | 용도 |
|---|---|
| `EDGEAI_API_BASE_URL` | BFF가 연결할 관리 API 주소 |
| `EDGEAI_WORKFLOW_API_BASE_URL` | 별도 워크플로 API 주소 |
| `EDGEAI_RUNTIME_ENABLED` | 실제 작업 실행 활성화 |
| `EDGEAI_PROMETHEUS_ENABLED`, `EDGEAI_PROMETHEUS_URL` | 메트릭 조회 활성화·대상 주소 |
| Runner TLS·Pod 인증 | 현재 Attempt의 실행·결과 제출 권한 |
| 결과 저장소 versioning | 고정 객체 버전·digest 검증 |

대상 노드와 이미지의 아키텍처, broker·결과 저장소를 준비해야 한다. 예제 SERVICE의 0 digest는 실제 이미지 digest로 바꾼다.

## 설치와 실행

새 개발 환경에서는 소스를 준비하고 `.env.example`, Makefile, 설정 가이드를 확인한다. [2]

```bash
make setup
make check
make up
make storage
```

아래 명령은 각각 별도 터미널에서 실행한다.

| 대상 | 명령 |
|---|---|
| 관리 API | `make backend` |
| 기존 BFF | `make dashboard` |
| native 콘솔 | `bash scripts/dev/dev-kestra-native.sh` |

워크플로 API를 분리할 경우 포트·세션 이름·DB 연결·원본 수집 비활성 설정을 확인해 별도로 실행한다. 기본 명령만으로 18082 인스턴스가 자동 생성되지는 않는다.

`make health`와 API readiness로 DB·서버 연결을 확인한다. `http://127.0.0.1:13083/ui/main/dashboards/default`에서 실제 노드·센서·실행 목록을 조회한다. 원본 UI 준비 스크립트는 revision과 lockfile을 확인하며 다른 checkout을 덮어쓰지 않는다.

## API와 데이터

### 주요 서버 경로

| 경로 | 기능 |
|---|---|
| `/api/v1/profiles/SERVICE` | 실행 모듈의 고정 버전 |
| `/api/v1/workflows` | 워크플로·DAG 버전 |
| `/api/v1/workflow-runs` | 실행 요청·목록·상세 |
| `/api/v1/workflow-runs/{id}/drain` | 새 입력을 끝내는 정상 종료 |
| `/api/v1/tasks/{id}` | Task·Attempt·전환 이력 |
| `/api/v1/tasks/{id}/results` | 검증된 결과 |
| `/api/v1/workflow-data-sources` | 센서·VD 입력 목록 |
| `/api/v1/node-metrics` | 노드 자원 메트릭 |

메서드·요청 형식·revision·멱등 조건은 OpenAPI와 컨트롤러를 따른다. 브라우저는 `/api/control-plane/`의 허용된 경로로 접근한다. lossless JSON으로 큰 정수를 보존하고, 관리·워크플로 API의 세션과 CSRF를 따로 사용한다. [2][4]

### 실행과 파일

Run은 WorkflowVersion과 입력을 고정한다. Task는 논리 작업이며 재시도·전환마다 Attempt를 새로 만든다. 결과는 현재 실행 권한, object version, 크기, SHA-256을 검사한 뒤 확정한다.

편집 파일은 `edgeai.workflow-editor/v1` 형식으로 DAG와 좌표를 저장한다. 브라우저 초안은 워크플로별 로컬 저장이며 발행 버전과 별개다. 가져오기에 실패하거나 취소해도 현재 작업을 유지한다.

실시간 입력은 센서 UID·설정·generation 또는 VD revision·실행 generation을 Run에 고정한다. 1초마다 최신 유효값을 읽고 sampleId 중복을 제거한다. 모든 중간 측정값의 전달·재생을 보장하지 않는다.

## 사용과 운영

### 실행과 정상 종료

1. SERVICE의 입출력, 이미지 digest, 실행 규격을 확인한다.
2. DAG에 모듈을 추가하고 포트를 연결한다.
3. 센서·VD 입력을 선택하고 후속 작업·연결을 확인한다.
4. 새 버전을 발행하고 배치 정책을 지정해 실행한다.
5. Run·Task·Attempt와 실제 배치를 확인한다.
6. 정상 종료는 drain으로 요청하고 DATA·END 처리와 FINALIZE 후 Result를 확인한다.

같은 요청을 다시 보낼 때는 같은 멱등 키를 사용한다. 강제 취소와 정상 종료는 구분한다. 입력 식별 정보가 달라지면 기존 Run에 새 원본을 자동 연결하지 않는다. [3]

### 자원과 이력

운영 현황은 서버 우선 목록과 더보기, CPU·메모리 막대, GPU·NPU 공통 열을 제공한다. 실제 0과 미수집·만료를 구분하고 장착 정보와 사용률도 따로 확인한다. 공유 슬롯을 물리 GPU 여러 개로 세지 않는다. Intel NPU는 운영 표에서 숨기되 원본 지표는 유지한다.

실행 목록은 3초, 감사 목록은 5초마다 갱신한다. 펼친 STREAM 첫 페이지는 3초마다 갱신하며 접기·일시정지·과거 페이지에서는 멈춘다. 조회에 실패하면 마지막 기록과 오류를 함께 표시한다.

### 오류 점검

| 오류 | 확인할 내용 |
|---|---|
| claim 거절 | Attempt·epoch·Pod 인증 |
| 결과 검증 실패 | 고정 object version·크기·digest |
| 위치 전환 실패 | 이전 실행 종료·권한 회수, 새 Attempt·Operation |
| 입력 중단 | 데이터 유효 시간, 고정한 UID·revision·generation |

전환 Operation의 `SUCCEEDED`와 최종 Result 성공은 별개다. 합성 데이터와 Remote 참조 계산은 실장비 시험과 구분해 표시한다.

## 시험 절차와 기존 결과

기본 시험은 `make test`, Runner는 `bash scripts/test/test-runner.sh`, PostgreSQL 통합은 `bash scripts/test/test-integration.sh`를 사용한다. 통합 시험에는 격리 DB를 사용한다. [2][5]

native API 시험은 다음 명령으로 실행한다.

```bash
node --experimental-strip-types --test \
  dashboard/tests/kestra-console.unit.mjs \
  dashboard/tests/kestra-device-registration.unit.mjs \
  dashboard/tests/kestra-overview.unit.mjs
```

| 시험 | 확인할 결과 |
|---|---|
| DAG·버전 | 잘못된 포트·순환 거절, 발행 버전 유지 |
| 중복 요청 | 같은 키·내용으로 중복 Run을 만들지 않음 |
| Runner 결과 | 파일 byte와 Result digest 일치 |
| 권한·취소 | 이전 Attempt의 claim·commit 거절 |
| 실시간 입력 | 고정한 입력, sampleId, 선택 표본 건수 일치 |
| 정상 종료 | 마지막 DATA·END, FINALIZE, Result 확인 |
| 이력 갱신 | 선택·과거 페이지 유지, 조회 오류 후 복구 |
| 메트릭 | 0·미수집·만료·장착 정보 구분 |

2026-10-14 센서 39건·VD 85건의 전달과 정상 종료, 결과 파일을 대조했다. 10월 16일에는 NODE 계산 결과 24와 센서 60건·VD 8건의 연계 종료를 확인했다. 10월 20일 Vue 타입·native API 시험 27개·문서 검사와 수정 후 리뷰가 통과했다. [1]

실제 조회한 작업에는 전환 이력이 없어 최신 전환 화면의 상태 변화는 HTTP fixture로 시험했다. 이 결과와 실제 노드 이동·모델 실행 시험은 구분한다.

## 배포 확인과 남은 작업

배포할 때는 EdgeAI·Kestra 소스 버전, lockfile, 실제 이미지 digest·아키텍처, TLS, 결과 저장소 versioning을 확인한다. 적용한 migration은 수정하지 않는다. 소스와 실행 규격, 설정·오류 대응·시험 결과를 함께 관리한다.

모든 측정값의 STREAM 전달, 임의 상태형 모델의 checkpoint 복원, 실제 외부 Remote·GPU·NPU 모델 실행, 사용자별 인증·권한, 장기 성능·전체 장애 복구는 남아 있다. 전체 과거 UI의 기능 비교, 기본 포트 통합, 자동 시작도 별도 작업이다. [6]

## 참고 자료

1. [구현·시험 기록](../../PROGRESS.md)
2. [개발 환경과 native 콘솔](../guides/local-development.md)
3. [DAG·실시간 입력 사용법](../guides/dag-execution.md)
4. [API 참고](../reference/api.md), [노드·가속기 메트릭](../guides/node-metrics.md)
5. [Runner 구현과 시험](../../runner/README.md)
6. [남은 개발·검증 항목](../../PLAN.md)
