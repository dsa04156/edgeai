# 엣지 워크플로 실행 및 통합 운영 소프트웨어 패키지

문서 구분: 기술자료 패키지 TDP-02\
문서 버전: 1.0\
작성일: 2026년 10월 20일\
등록번호: ____________________\
과제명 및 작성 부서: ____________________\
작성자 및 인수 담당자: ____________________

## 1 패키지 개요와 인수 범위

본 기술자료는 SERVICE DAG 실행, 센서 및 가상디바이스 입력 연계, 결과 검증과 Kestra 기반 통합 운영 콘솔의 구성 및 인수 절차를 제공한다. TM-04, TM-05와 TM-06의 기술을 실제 소스와 환경, API, 사용 및 시험 방법에 연결한다. 센서 및 공유 eKuiper 준비는 TDP-01과 연계한다.

기준 소스는 `dsa04156/edgeai`의 커밋 1170c1e88667ea22bba2b21844456c18821e0213이다. Kestra UI는 v2.0.6의 별도 고정 원본을 사용한다. 소스와 외부 의존성을 기준으로 준비하며 실행 바이너리, 실제 이미지와 서버 private 설정은 문서 파일에 포함하지 않는다. [1][2]

| 구성 | 인수 범위 |
|---|---|
| 실행 제어 | 불변 모듈과 DAG, Run 및 Attempt, 배치와 전환 |
| Runner | 입력 검증, 실제 계산과 출력 업로드 및 결과 확정 |
| 데이터 입력 | 센서와 VD의 최신 유효값, pin 및 정상 종료 |
| 운영 콘솔 | 자원 관측, 편집, 상태와 결과, 감사 및 이력 |
| 제외 범위 | DDS 자체의 이미지 빌드 및 GitOps 배포 기술 |

### 1 1 인수 판단

기본 기동과 화면 열림, 요청 접수, 실제 실행과 검증된 결과를 각각 확인한다. UI는 기존 EdgeAI 서버와 연결하며 Kestra 서버나 DB를 설치하지 않는다. 실제 모델 및 외부 Remote, 전체 성능과 종합 복구의 미완료 범위는 마지막 장에서 구분한다.

<!-- pagebreak -->

## 2 소스 및 계약 인벤토리

| 위치 | 주요 내용 | 대응 기술 |
|---|---|---|
| backend/domain | 실행 모델, 정책, 저장소와 외부 경계 | TM-04 |
| backend/app | DAG, Run과 Task, 위치 전환 및 입력 서비스 | TM-04 및 TM-05 |
| backend/adapters | DB, Kubernetes, S3와 메트릭 연동 | TM-04 및 TM-06 |
| runner/edgeai_runner | claim, 실행, STREAM 및 journal | TM-04 및 TM-05 |
| contracts/openapi | 공개 및 Runner API | 인터페이스 인수 |
| contracts/profiles | SERVICE 실행 및 데이터 입력 예제 | 모듈 발행 |
| dashboard/kestra-native | EdgeAI용 Vue 화면과 API 경계 | TM-06 |
| scripts/dev/dev-kestra-native.sh | 고정 Kestra 원본 UI 준비 및 실행 | TM-06 |

### 2 1 기준 버전

Kestra 원본은 v2.0.6의 revision b59444f09be2e44a3b0738452b2e02fc4fe89505를 준비한다. EdgeAI 콘솔은 위 기준 커밋을 사용하며 원본 checkout은 Git에서 제외된 output 경로에 둔다. 현재 API 연결은 관리 API와 워크플로 API의 역할을 구분한다. [2]

Runner의 Python 및 스트림 의존성은 Dockerfile과 requirements-stream.txt를, 프론트엔드 의존성은 각 lockfile을 따른다. 예제 SERVICE 이미지의 0으로 채운 digest는 자리표시자이므로 실제 준비한 이미지 digest로 바꾸어야 한다.

### 2 2 소스 경계

본 등록 대상은 EdgeAI 실행 및 관측 어댑터, Runner, 입력 계약과 UI 확장이다. Kestra의 원본 디자인 시스템 및 라우터, Kubernetes, PostgreSQL, S3 호환 저장소와 ECharts 자체의 구현은 외부 기반 기술이다. 소스와 라이선스 고지를 함께 보존하며 DDS 경로를 별도 개발 성과로 포함하지 않는다.

<!-- pagebreak -->

## 3 실행 환경과 연결 설정

### 3 1 서비스 구성

| 구성 요소 | 로컬 기본 포트 | 역할 |
|---|---|---|
| 기존 웹 및 BFF | 13080 | 기존 API 프록시와 문서 |
| Kestra native 콘솔 | 13083 | 원본 Vue 기반 EdgeAI 화면 |
| 관리 API | 18080 | 센서, VD와 저장 관리 |
| 별도 워크플로 API | 18082 | 모듈, DAG와 실행 제어 |
| InfluxDB | 18086 | TDP-01의 원본 및 결과 이력 |

포트는 현재 로컬 통합 구성의 구분이며 새 환경에서는 설정과 기존 점유를 대조한다. 관리와 실행 API를 분리할 경우 같은 control DB를 사용하되 원본 수집은 기존 관리 인스턴스 한 곳에서만 실행한다. 관리 EDGEAI_SESSION과 워크플로 EDGEAI_WORKFLOW_SESSION, 각 CSRF 경계를 유지한다. [2][3]

### 3 2 주요 설정

| 설정 또는 조건 | 용도 |
|---|---|
| EDGEAI_API_BASE_URL | BFF에서 연결하는 관리 API의 고정 origin |
| EDGEAI_WORKFLOW_API_BASE_URL | BFF의 별도 실행 API origin |
| EDGEAI_RUNTIME_ENABLED | 실제 Runtime 실행의 명시적 활성화 |
| EDGEAI_PROMETHEUS_ENABLED 및 URL | 서버 메트릭 조회의 활성화와 고정 대상 |
| 내부 Runner TLS 및 Pod 신원 | 현재 Attempt의 claim과 결과 권한 |
| 결과 저장소 versioning | 고정 객체 버전과 digest 검증 |

공통 개발은 JDK 21, Node 22와 pnpm 10.34.6을 사용한다. Kestra 원본 UI는 Node 24와 npm 11을 별도로 사용한다. Kubernetes와 Runner 이미지의 아키텍처, 결과 저장소 및 broker 조건을 확인한 뒤 실행을 활성화한다. 공개 쓰기의 CSRF는 사용자별 identity 또는 RBAC 수용을 대신하지 않는다.

<!-- pagebreak -->

## 4 설치와 기동 확인

### 4 1 새 개발 환경 준비

기준 소스를 별도 작업 경로에 확보하고 `.env.example`, Makefile 및 로컬 가이드를 확인한다. 아래 절차는 새 환경의 준비 예시이며 실행 중인 기존 서비스를 임의로 교체하는 명령으로 사용하지 않는다. [2]

```bash
make setup
make check
make up
make storage
```

별도 터미널에서 관리 API와 기존 BFF를 시작한다. 별도 실행 API를 사용할 경우 서버 설정의 포트와 세션 이름, 같은 control DB와 수집 비활성 조건을 확인하여 기존 실행 절차에 따라 준비한다. 이 문서는 18082 인스턴스를 자동 생성하는 새 설치 스크립트를 제공하지 않는다.

```bash
make backend
```

```bash
make dashboard
```

```bash
bash scripts/dev/dev-kestra-native.sh
```

### 4 2 기동 확인

`make health`와 API readiness로 DB 및 서버 연결을 확인한다. `http://127.0.0.1:13083/ui/main/dashboards/default`에서 콘솔을 열고 실제 노드, 센서 및 실행 목록의 조회와 오류 구분을 확인한다. 화면만 열리고 API가 연결되지 않는 경우를 기동 성공으로 완료하지 않는다.

원본 UI 준비는 고정 revision과 lockfile을 검사한다. 다른 기존 checkout을 덮어쓰지 않는다. 개발 서버 기동은 운영 자동 시작 서비스나 기본 웹 포트의 교체를 의미하지 않는다.

<!-- pagebreak -->

## 5 API와 데이터 계약

### 5 1 실행 API

| 서버 경로 | 주요 기능 |
|---|---|
| /api/v1/profiles/SERVICE | 실행 모듈의 불변 버전 |
| /api/v1/workflows | 워크플로와 DAG 버전 |
| /api/v1/workflow-runs | 실행 요청 및 목록과 상세 |
| /api/v1/workflow-runs/{id}/drain | 신규 입력을 끝내는 정상 종료 요청 |
| /api/v1/tasks/{id} | Task, Attempt와 전환 이력 |
| /api/v1/tasks/{id}/results | 검증된 결과와 파일 조회의 기준 |
| /api/v1/workflow-data-sources | 센서 및 VD 입력 catalog |
| /api/v1/node-metrics | 서버가 집계한 노드 자원 관측 |

HTTP 메서드, 요청 schema와 revision 및 멱등 조건은 기준 OpenAPI와 컨트롤러를 따른다. 브라우저는 `/api/control-plane/`의 허용된 경계로 접근한다. 관리와 실행 API의 세션 및 CSRF를 교차 사용하지 않는다. 일반 JSON과 DAG의 큰 정수를 처리할 때 lossless 정밀도를 보존한다. [2][4]

### 5 2 객체와 파일

Run은 특정 WorkflowVersion과 입력을 고정한다. Task는 논리 작업이며 Attempt는 재시도와 전환을 구분한다. 결과는 현재 producer, object version과 크기 및 SHA-256을 확인한 뒤 확정한다. Pod 종료나 저장소 PUT 성공은 결과 성공의 충분조건이 아니다.

편집 파일은 `edgeai.workflow-editor/v1` 형식으로 DAG와 좌표를 보존한다. 브라우저 초안은 워크플로별 로컬 저장이며 발행된 버전과 별도이다. 가져오기 실패와 취소는 현재 작업을 보존한다. API는 임의 프록시 쓰기와 종료된 VD Runner 경로를 다시 열지 않는다.

### 5 3 입력 원본

센서 UID와 설정 및 generation, 또는 VD revision과 실행 generation을 Run에 고정한다. 최신 유효 표본을 1초마다 읽고 sampleId 중복을 제거하며 전량 관측의 구독 및 재생과 구분한다.

<!-- pagebreak -->

## 6 사용 및 운영 절차

### 6 1 실행과 정상 종료

1. SERVICE 모듈의 입출력, 이미지 digest와 실행 규격을 확인한다.
2. DAG에 모듈을 놓고 포트를 연결하여 순환과 잘못된 연결을 검사한다.
3. 센서 또는 VD 입력을 선택하고 후속 작업과 연결이 보존되는지 확인한다.
4. 새 불변 버전을 발행하고 지원 정책으로 실행한다.
5. Run과 Task, 실제 배치 및 Attempt를 조회한다.
6. 입력 종료는 drain으로 요청하고 END, 처리 확인 및 FINALIZE 후 Result를 확인한다.

강제 취소와 정상 종료를 구분한다. 재요청은 같은 멱등 키를 사용하고 새 실행은 새 키를 사용한다. 원본 pin이 달라지면 기존 Run을 조용히 새 원본에 연결하지 않는다. [3]

### 6 2 관측과 이력

운영 현황은 서버 우선 표와 더보기, CPU 및 메모리와 GPU 및 NPU 열을 사용한다. 미수집과 만료는 실제 0과 구분한다. Intel NPU는 운영 표에서 숨기되 원본 계측은 유지한다. 장착 정보와 사용률은 독립적으로 확인하며 공유 슬롯으로 물리 장치 수를 늘리지 않는다.

실행은 3초, 감사 목록은 5초 갱신한다. 열린 STREAM 첫 페이지는 3초 갱신하고 접기와 일시정지를 따른다. 이전 페이지는 고정하며 최신 보기로 재개한다. 오류 시 마지막 기록을 유지하고 실패를 표시한다. 상태 조회의 성공을 새로운 실제 실행의 성공으로 바꾸지 않는다.

### 6 3 오류와 전환

claim 거절은 Attempt와 epoch, Pod 신원을 확인한다. 결과 검증 실패는 고정 object version, 크기와 digest를 대조한다. 전환은 이전 producer 종료와 권한 회수, 새 Attempt 및 Operation 상태를 확인한다. SUCCEEDED 전환은 최종 결과 성공과 별도이다. 합성 원본과 Remote 참조 계산은 실장비 검증이 아니라는 표시를 유지한다.

<!-- pagebreak -->

## 7 시험 구성과 기존 검증

### 7 1 시험 명령

기본 시험은 `make test`, Runner는 `bash scripts/test/test-runner.sh`, PostgreSQL 통합은 `bash scripts/test/test-integration.sh`를 사용한다. 운영 DB를 시험 DB로 재사용하지 않는다. native 콘솔 API 경계는 아래 명령으로 확인한다. [2][5]

```bash
node --experimental-strip-types --test \
  dashboard/tests/kestra-console.unit.mjs \
  dashboard/tests/kestra-device-registration.unit.mjs \
  dashboard/tests/kestra-overview.unit.mjs
```

| 시험 항목 | 통과 기준 |
|---|---|
| DAG 및 버전 | 잘못된 포트와 순환 거절, 불변 발행 유지 |
| 멱등 실행 | 같은 키와 요청으로 중복 Run 생성 방지 |
| Runner 결과 | 실제 출력 byte와 API Result의 digest 일치 |
| 권한과 취소 | 옛 Attempt의 claim 및 commit 거절 |
| 실시간 입력 | pin, sampleId 및 선택된 표본 건수 일치 |
| 정상 종료 | 마지막 DATA와 END, FINALIZE 및 Result 확인 |
| 콘솔 기록 | 선택 보존, 과거 페이지 고정과 오류 복구 |
| 자원 표시 | 0, 미수집과 만료 및 장착 정보를 구분 |

### 7 2 확인된 결과와 경계

2026년 10월 14일 센서 39건과 VD 85건을 같은 처리기에 전달한 별도 실행에서 source 및 처리 건수, END와 결과 파일을 대조하였다. 10월 16일 NODE 참조 계산 결과 24와 센서 60건 및 VD 8건의 연계 종료를 확인하였다. 10월 20일 Vue 타입 및 native 경계 시험 27개, 문서 검사와 두 축 리뷰 수정 후 재검토가 통과하였다. [1]

실제 조회 표본의 전환 이력은 없었으므로 최신 전환 화면의 생명주기는 HTTP fixture로 시험하였다. 위 시험을 모든 실제 모델 및 가속기의 신규 수용으로 해석하지 않는다.

<!-- pagebreak -->

## 8 인수와 유지보수 기준

### 8 1 인수 체크 항목

| 항목 | 인수 시 보존할 자료 |
|---|---|
| 소스 및 버전 | EdgeAI 기준 커밋, Kestra 원본 revision과 lockfile |
| 실행 규격 | SERVICE 및 DAG, API와 Runner 계약 |
| 배포 자원 | 실제 이미지 digest, 아키텍처, TLS와 저장소 versioning |
| 시험 근거 | 환경, 입력, 실제 결과와 검증 날짜 |
| 상태 해석 | Run, Task, Attempt, Operation과 Result 구분 |
| 유지보수 | 설정 이름, 오류 대응, migration 및 이전 버전 조건 |

기관 행정 항목은 표지에 기입한다. 자격 증명, private key와 실제 센서 원본 데이터는 문서와 공개 소스에 넣지 않는다. 설치 및 시험 명령은 인수 환경의 권한과 격리를 갖춘 뒤 수행한다. 기존 사용자 데이터와 실행 중인 프로세스는 인수 작업의 소유 범위와 구분한다.

### 8 2 미완료 기능

전량 관측 STREAM, 임의 상태형 모델의 checkpoint 복원, 실제 외부 Remote 제공자, GPU 및 NPU 모델 실행, 사용자별 identity와 RBAC, 장기 성능 및 종합 복구가 남아 있다. 전체 과거 UI 기능의 동등성도 완료로 표시하지 않는다. 기본 포트 통합과 운영 자동 시작은 별도 작업이다. [6]

### 8 3 참고 자료

[1] [날짜별 구현 및 검증 기록](../../PROGRESS.md)\
[2] [환경과 native 콘솔 실행](../guides/local-development.md)\
[3] [DAG 및 실시간 입력 사용법](../guides/dag-execution.md)\
[4] [공개 API](../reference/api.md), [가속기 관측](../guides/node-metrics.md)\
[5] [Runner 구성과 시험](../../runner/README.md)\
[6] [잔여 개발 및 운영 수용](../../PLAN.md)\
[7] [TM-04](TM-04_분산_작업_실행과_결과_검증.md), [TM-05](TM-05_실시간_워크플로_입력_연계.md), [TM-06](TM-06_엣지_자원_관측과_운영_콘솔.md)

기술 기준일은 2026년 10월 20일이며 문서 버전은 1.0이다. TDP-02는 문서 묶음의 식별자이며 기관 등록번호는 별도로 기입한다.
