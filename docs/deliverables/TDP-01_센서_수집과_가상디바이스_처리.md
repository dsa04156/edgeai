# 센서 수집 및 가상디바이스 처리 소프트웨어 패키지

문서 구분: 기술자료 패키지 TDP-01\
문서 버전: 1.0\
작성일: 2026년 10월 20일\
등록번호: ____________________\
과제명 및 작성 부서: ____________________\
작성자 및 인수 담당자: ____________________

## 1 패키지 개요와 인수 범위

본 기술자료는 센서 연동, 원본 수집과 저장, eKuiper 기반 가상디바이스 계산 소프트웨어를 설치하고 기능을 확인하기 위한 구성 및 인수 절차를 제공한다. TM-01, TM-02와 TM-03의 기술을 소프트웨어 구성, 인터페이스와 시험 기준으로 연결한다. DDS의 빌드와 GitOps 배포 소프트웨어는 등록 대상에서 제외한다.

대상 소스는 `dsa04156/edgeai` 저장소의 기준 커밋 1170c1e88667ea22bba2b21844456c18821e0213이다. 본 문서의 소스 목록은 해당 커밋을 기준으로 한다. 실행 바이너리와 실제 배포 이미지, private 설정과 토큰은 문서 파일에 포함하지 않으며 인수 환경에서 별도 준비한다. [1]

| 구분 | 인수 대상 |
|---|---|
| 센서 관리 | KubeEdge 측정 항목, 공유 Mapper, 프로토콜 설정과 연결 시험 |
| 원본 수집 | 노드 MQTT 관측 소비, 신원 보존과 InfluxDB 저장 |
| 연속 계산 | 입력 연결, 제한된 계산, Agent와 eKuiper 실행 조정 |
| 관리 인터페이스 | 센서 및 VD 등록, 상태와 원본 및 결과 이력 |
| 관련 기술메모 | TM-01 이기종 연동, TM-02 연속 계산, TM-03 저장 안정화 |

### 1 1 인수 완료의 의미

소스 확보와 기본 기동, 실제 관측의 저장, 선택한 VD 계산 및 보관 정책, 오류 및 복구 사례를 순서대로 확인한다. 장치 등록과 계산 엔진 설치만으로 실제 수집 또는 계산 성공을 판정하지 않는다. 장기 부하 및 종합 운영 수용은 별도 시험 항목으로 남긴다.

<!-- pagebreak -->

## 2 소스 구성과 외부 의존성

### 2 1 소스 인벤토리

| 위치 | 포함 내용 | 인수 시 확인 |
|---|---|---|
| mappers/internal/driver | 프로토콜별 값 읽기와 관측 변환 | 실제 장치와 매핑의 일치 |
| mappers/internal/dmi | DMI 통신과 보고 예산 | EdgeCore 및 노드 설정 |
| mappers/internal/runtime | 설정, 세션과 관측 실행 | 영속 카운터와 source generation |
| mappers/cmd | Mapper와 처리 Agent 진입점 | 선택한 실행 대상과 빌드 옵션 |
| deploy/kubeedge | RBAC와 장치 예제, 배포 안내 | 예제 주소를 실제 환경으로 변경 |
| backend/app | 센서와 연속 VD 관리, 저장 전달 | 설정과 비활성 기본값 |
| backend/adapters | KubeEdge, InfluxDB 및 PostgreSQL 어댑터 | 외부 서비스 연결과 권한 |
| dashboard/kestra-native | 센서 및 VD 관리 화면 | BFF와 기존 API 세션 |

원본 저장과 VD 처리 기능은 공통 backend 모듈을 공유한다. 인수 소스를 일부 디렉터리만 임의 복사하면 공통 모델과 migration이 빠질 수 있으므로 기준 저장소 전체를 확보한 뒤 실행 범위로 구분한다.

### 2 2 기반 구성 요소

KubeEdge의 CRD와 DMI, 노드별 NanoMQ, 중앙 eKuiper, PostgreSQL과 InfluxDB를 사용한다. 기록된 처리 시험은 eKuiper 2.4.2 full 배포와 InfluxDB OSS 2.9.1을 사용하며 실제 센서 노드의 NanoMQ는 0.25.6이었다. Go 모듈 및 Python 의존성은 저장소의 고정 파일을 따른다. [2][3]

외부 소스와 라이브러리의 LICENSE 및 NOTICE를 보존한다. `mappers/third_party/`의 KubeEdge 관련 원본도 포함하여 구분한다. 본 패키지의 개발 성과는 어댑터, 규격 및 실행 조정과 관리 연동이며 기반 제품의 독점 구현을 의미하지 않는다.

<!-- pagebreak -->

## 3 설치 환경과 설정 항목

### 3 1 실행 환경

관리 API는 JDK 21과 PostgreSQL을, 기존 BFF는 Node 22 및 pnpm 10.34.6을 사용한다. Kestra native 화면을 함께 사용할 경우 별도 원본 UI에 Node 24와 npm 11을 준비한다. Go Mapper는 기준 `mappers/go.mod`의 Go 및 toolchain 규격을 따른다. 실제 센서 노드는 지원 아키텍처 이미지와 장치 접근을 준비해야 한다. [3][4]

| 설정 | 용도 | 적용 원칙 |
|---|---|---|
| EDGEAI_RAW_HISTORY_ENABLED | 원본 MQTT 구독과 저장 | 원본 준비 뒤 명시적으로 활성화 |
| EDGEAI_INFLUX_URL | API에서 접근하는 저장소 origin | 브라우저에 토큰과 함께 전달하지 않음 |
| EDGEAI_INFLUX_ORG | InfluxDB 조직 | 인수 환경에 맞는 값 사용 |
| EDGEAI_INFLUX_TOKEN_FILE | read 및 write 전용 토큰 파일 | 실제 내용은 문서와 Git에서 제외 |
| EDGEAI_CONTINUOUS_ENABLED | 공유 엔진 연속 계산 활성화 | 기본 false, 준비 후 활성화 |
| EDGEAI_RAW_HISTORY_BROKER_NODE_OVERRIDES | 필요한 노드의 broker 주소 보정 | 실제 Node UID와 도달성 대조 |

관리 DB와 InfluxDB, broker와 Agent의 자격은 서버 경계에서 관리한다. 파일 권한 및 TLS, 네트워크 접근은 기존 운영 절차를 따른다. 기록된 로컬 InfluxDB 기본 포트는 18086이며 실제 배포 주소는 설치 환경에서 정한다.

### 3 2 용량과 준비 조건

원본 bucket은 기본 30일 보존이며 결과는 1일에서 30일 bucket으로 구분한다. 저장소 용량은 관측 크기와 빈도, 보존 기간 및 중복 조정 이후 잔존 필드를 포함하여 산정한다. 기존 가이드의 480Gi 사전 점검은 과거 PostgreSQL history 배포 기준이므로 새로운 InfluxDB 용량 보장으로 사용하지 않는다. [2]

<!-- pagebreak -->

## 4 설치 및 기동 절차

### 4 1 기준 소스와 기본 서비스

별도 작업 디렉터리에 기준 커밋을 확보하고 Makefile, `.env.example`과 설정 가이드를 확인한다. 새 설치 예시는 아래와 같다. 기존 사용자 환경에 적용할 때는 실행 중인 API와 데이터 경로를 먼저 확인한다.

```bash
make setup
make check
make up
make data-store-start
```

`make up`은 개발 PostgreSQL과 MQTT를 준비하며 실제 노드의 NanoMQ를 대체하지 않는다. Docker가 없는 환경의 `make db-start`는 PostgreSQL만 제공한다. `make data-store-start`로 준비한 연결 정보를 API의 Influx 설정과 토큰 파일에 연결한다. [3]

### 4 2 센서 및 엔진 준비

1. 대상 Edge Node의 Ready 상태와 EdgeCore DMI, 장치 경로를 확인한다.
2. 기준 Mapper 이미지와 RBAC, 노드 및 프로토콜 설정을 준비한다.
3. 보드의 실제 출력에 맞는 Property 규격을 저장하고 측정 항목 하나를 등록한다.
4. 연결 시험과 MQTT 관측의 시각 및 값을 확인한다.
5. 실제 `/capabilities`와 qualified 관측, 영속 세션 순서를 확인한다.
6. 처리 Agent와 eKuiper, history 및 권한을 준비하고 연속 처리 flag를 활성화한다.

중앙 엔진의 설치와 namespace 확인은 실제 규칙 및 결과의 수용을 대신하지 않는다. 운영 배포는 기존 `deploy/kubernetes/ekuiper`와 보안 및 되돌리기 절차를 사용하되, 실제 InfluxDB PVC와 전용 토큰 및 네트워크 설정을 별도로 확인한다. [2]

### 4 3 화면과 상태 확인

별도 터미널에서 `make backend`와 `make dashboard`를 실행한다. native 화면은 `bash scripts/dev/dev-kestra-native.sh`로 준비한다. readiness와 실제 센서 최신값, 원본 저장 증가를 함께 확인한 뒤 VD를 등록한다.

<!-- pagebreak -->

## 5 인터페이스와 데이터 규격

### 5 1 주요 인터페이스

| 경계 | 경로 또는 계약 | 용도 |
|---|---|---|
| 웹 센서 관리 프록시 | /api/devices | 측정 항목 기반 장치 관리 |
| 웹 규격 관리 프록시 | /api/device-profiles | 저장된 항목 규격 |
| 서버 원본 목록 | /api/v1/data-store | 저장된 센서 catalog |
| 서버 원본 이력 | /api/v1/data-store/sensors/{uid}/{property}/history | 기간과 cursor를 사용하는 원본 조회 |
| 서버 VD 관리 | /api/v1/virtual-devices | 연속 VD 목록 및 상세 |
| 서버 VD 등록 | /api/v1/virtual-devices/continuous | 입력과 계산 및 저장 정책 등록 |
| 서버 VD 출력 | /api/v1/virtual-devices/{id}/outputs | 현재 출력과 저장 상태 |

위 경계는 모두 같은 URL prefix를 사용하는 단일 API가 아니다. 웹 프록시, KubeEdge 관리 경로와 일반 UUID Device API를 구분하여 기준 컨트롤러 및 OpenAPI와 대조한다. 삭제와 실행 변경은 해당 revision 및 멱등 키, CSRF 조건을 유지한다. [5]

### 5 2 원본과 결과 신원

검증 원본은 UID, property, 설정 식별자, generation, session, sequence 및 observedAt을 보존한다. 같은 시각의 다른 sequence는 별도 관측으로 저장한다. 구형 원본은 설정 미검증으로 표시한다. VD 결과는 VD와 output, 등록 revision과 실행 generation을 사용하여 현재 계산과 연결한다.

NONE, SELECTED, ALL은 저장 정책이며 계산 결과를 생성하는 규칙과 별도이다. 결과의 SKIPPED, PENDING, STORED, EXPIRED를 구분한다. 원본 저장의 QoS 1 ACK는 저장 완료에 연결하고, 일반 작업의 STREAM 처리 ACK와 혼동하지 않는다.

원본 목록과 API는 논리 비표시 중복을 제외한다. InfluxDB를 직접 읽는 별도 소비자는 archive_visible과 빈 원본 문서 조건을 동일하게 적용한다.

<!-- pagebreak -->

## 6 사용 절차와 장애 대응

### 6 1 센서 및 가상디바이스 사용

센서는 새 등록에서 프로토콜, 대상 노드와 연결 및 측정 항목을 지정한다. 기존 연결의 항목 추가와 항목 수정은 해당 행에서 수행한다. 수정 후 다른 항목과 연결 설정이 유지되는지 확인한다. 등록 성공 뒤에도 실제 연결 상태와 관측 시각을 확인한다.

가상디바이스는 입력 센서와 역할, 출력 계산 및 저장 정책을 선택한다. 조합 10종은 동일 단위의 서로 다른 숫자 센서를 사용한다. 실행 후 현재 generation의 유효 출력과 저장 상태를 확인한다. 설정 변경은 새 revision으로 남기고 이전 정의를 조회한다. 등록 해제 시 원본 센서와 기존 이력의 보존 여부를 구분한다. [2]

### 6 2 오류별 점검

| 현상 | 우선 점검 | 처리 기준 |
|---|---|---|
| 등록 후 OFFLINE | Node, Mapper, 장치 경로와 DMI | 등록 결과로 ONLINE을 만들지 않음 |
| 값이 변하지 않음 | 측정 시각, heartbeat, 실제 MQTT 수신 | 같은 값의 Twin 갱신과 수집 중단 구분 |
| 원본 저장 정체 | collector, 저장 증가와 대기, Influx 쓰기 | health 성공만으로 정상 판정하지 않음 |
| VD 출력 없음 | 유효 입력과 신선도, Agent 규칙 및 lease | 빈 출력과 오래된 출력을 구분 |
| 결과 저장 대기 | PENDING 및 DB 대기열, 보존 기한 | 복구 후 전달과 만료 상태 확인 |

InfluxDB의 동기 DELETE와 TSI 대기로 수집이 멈춘 경우 동일 데이터와 PVC를 보존하는 복구 절차를 따른다. 본 수집 경로는 논리 비표시를 사용하므로 동기 중복 DELETE를 다시 추가하지 않는다. 재시작은 upstream 결함 자체의 영구 수정이 아니다. [3]

### 6 3 중지 및 되돌리기

연속 처리 flag를 false로 바꾸고 lease 만료 후 소유 규칙 정리를 확인한다. 이전 Agent 적용 전 배포로 되돌리더라도 다른 규칙과 물리 센서 CRD, VD 정의 및 history를 삭제하지 않는다. 원본 수집의 활성화는 연속 계산과 독립적으로 관리한다.

<!-- pagebreak -->

## 7 기능 시험과 인수 판정

### 7 1 재현 시험

격리 환경에서 `bash scripts/test/test-processing.sh`와 `make test-data-store`를 사용한다. 처리 시험에는 실제 broker와 eKuiper full 배포가 필요하며 저장 시험에는 실제 InfluxDB가 필요하다. 준비가 없을 때 통과로 표시하지 않는다. 기존 시험 결과와 새 인수 환경의 시험 결과는 환경과 날짜를 나누어 기록한다. [2]

| 시험 | 입력 또는 조치 | 통과 기준 |
|---|---|---|
| 센서 연결 | 지원하는 실제 항목 1개 등록 | 연결 시험과 실제 관측 일치 |
| 항목 보존 | 단일 항목 설정 변경 | 다른 항목 및 연결 유지 |
| 관측 중복 | 같은 신원의 메시지 재전달 | 조회에서 동일 원본 중복 없음 |
| 동시각 구분 | 다른 sequence를 같은 시각에 전달 | 두 관측의 신원 및 값 보존 |
| 세대 검사 | Mapper session 및 generation 변경 | 옛 원본이 새 계산을 덮지 않음 |
| 조합 계산 | 온도와 축별 조합의 알려진 입력 | 수식 및 출력 단위 일치 |
| 저장 정책 | NONE, SELECTED 및 ALL | 정책과 최신 계산을 독립 적용 |
| 저장소 중단 | 시험 저장소 중지 및 복구 | 미완료 승인 금지 및 대기 전달 확인 |

### 7 2 기존 검증 근거

2026년 10월 14일 실센서 수집 대조는 3분 3,055건의 발행과 저장 및 내용 일치를 확인하였다. 10월 16일 복구 후에는 짧은 구간에서 COLLECTING과 rejected 0, 약 0.65에서 1.02초의 관측 지연을 기록하였다. 10월 19일에는 조합 및 기록 UI 회귀를, 10월 20일에는 기존 native API 경계 시험 27개 통과를 확인하였다. 각 결과의 환경과 날짜를 유지하여 적용한다. [1]

현재 장비 복구 보류와 24시간 손실 및 중복, 최대 용량과 종합 복구의 잔여 조건은 인수 판정표에 별도 기록한다.

<!-- pagebreak -->

## 8 인수 자료와 적용 한계

### 8 1 인수 목록

| 인수 항목 | 전달 및 확인 내용 |
|---|---|
| 소스 | 기준 Git 커밋과 전체 저장소 접근, 변경 이력 |
| 규격 | Property, qualified 관측, 연속 VD와 저장 정책 |
| 설치 | 환경 변수 이름과 파일 경로, 이미지 digest 및 외부 서비스 버전 |
| 시험 | 입력, 기대값, 실제 결과와 로그 위치 및 날짜 |
| 운영 | 시작과 중지, 오류별 점검, 보존 및 되돌리기 절차 |
| 외부 구성 | KubeEdge, eKuiper 등 원본 LICENSE와 NOTICE |

기관 등록번호, 작성자와 인수 담당자는 표지에 기입한다. 실제 자격 증명과 private 인증서, 운영 원본 DB 및 센서 개인정보는 공개 소스나 본 문서에 포함하지 않는다. 실행 이미지와 대용량 원본은 인수 범위 및 전달 경로를 별도로 기록한다.

### 8 2 남은 수용 범위

전량 관측의 일반 STREAM feed, 중단된 윈도우의 자동 복원과 과거 원본 재생, 500 VD의 실측 동시 처리, 24시간 수집 및 전체 재시작 수용은 완료 범위에 포함하지 않는다. 실제 센서 프로토콜 인증 및 TLS 확장과 임의 보드의 호환성도 별도 시험한다. 현재 장비의 연결 회복은 과거 검증 기록과 구분한다. [6]

### 8 3 참고 자료

[1] [날짜별 구현과 검증 결과](../../PROGRESS.md)\
[2] [연속 계산 및 저장 운영 가이드](../guides/vd-processing.md)\
[3] [개발 환경 및 저장소 복구 설명](../guides/local-development.md)\
[4] [센서 등록](../guides/sensor-registration.md), [Mapper 구조](../../deploy/kubeedge/README.md)\
[5] [공개 API 참고](../reference/api.md)\
[6] [기능 및 운영 수용의 잔여 범위](../../PLAN.md)\
[7] [TM-01](TM-01_이기종_센서_연동.md), [TM-02](TM-02_가상디바이스_연속_처리.md), [TM-03](TM-03_센서_데이터_저장_안정화.md)

기술 기준일은 2026년 10월 20일이며 문서 버전은 1.0이다. 본 패키지 문서의 식별자 TDP-01은 기관 등록번호와 구분한다.
