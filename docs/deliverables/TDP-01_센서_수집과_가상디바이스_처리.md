# 센서 수집과 가상디바이스 처리 소프트웨어 기술자료

**TDP-01** · 2026-10-20

## 구성

센서 연동, 원본 저장, eKuiper 연속 계산의 소스와 인터페이스, 설치·운영·시험 절차를 정리한다. 기술 설명은 [TM-01](TM-01_이기종_센서_연동.md), [TM-02](TM-02_가상디바이스_연속_처리.md), [TM-03](TM-03_센서_데이터_저장_안정화.md)에 있다.

| 기능 | 구성 |
|---|---|
| 센서 관리 | KubeEdge 측정 항목, 공유 Mapper, 프로토콜 설정, 연결 시험 |
| 원본 수집 | 노드 MQTT 데이터 수신, 식별 정보 유지, InfluxDB 저장 |
| 연속 계산 | 입력 연결, 계산식 검사, Agent·eKuiper 실행 관리 |
| 관리 화면 | 센서·VD 등록, 상태·원본·결과 이력 조회 |

### 소스 위치

| 경로 | 내용 | 확인할 사항 |
|---|---|---|
| `mappers/internal/driver` | 프로토콜 드라이버와 데이터 변환 | 실제 장치의 값·매핑 |
| `mappers/internal/dmi` | DMI 통신과 보고 주기 관리 | EdgeCore·노드 설정 |
| `mappers/internal/runtime` | 설정·세션·수집 실행 | 저장된 카운터·source generation |
| `mappers/cmd` | Mapper·Processing Agent 실행 진입점 | 빌드 대상·옵션 |
| `deploy/kubeedge` | RBAC·장치 예제·배포 설정 | 실제 주소·장치 정보 |
| `backend/app` | 센서·VD 관리와 저장 전달 | 설정·활성화 조건 |
| `backend/adapters` | KubeEdge·InfluxDB·PostgreSQL 연동 | 연결·권한 |
| `dashboard/kestra-native` | 센서·VD 관리 화면 | BFF·API 연결 |

공통 모델과 migration을 함께 사용하므로 기준 저장소 전체를 준비한다. KubeEdge, NanoMQ, eKuiper, PostgreSQL, InfluxDB는 기존 소프트웨어를 사용하며 원본 라이선스와 고지를 유지한다.

## 실행 환경과 설정

관리 API는 JDK 21과 PostgreSQL, 기존 BFF는 Node 22·pnpm 10.34.6을 사용한다. Kestra native UI는 Node 24·npm 11로 별도 실행한다. Mapper는 `mappers/go.mod`의 Go 버전과 대상 노드 아키텍처를 따른다. [2][3]

기존 시험에 사용한 버전은 eKuiper 2.4.2 full, InfluxDB OSS 2.9.1이며, 센서 노드의 NanoMQ는 0.25.6이었다.

| 설정 | 용도 |
|---|---|
| `EDGEAI_RAW_HISTORY_ENABLED` | 원본 MQTT 수집·저장 활성화 |
| `EDGEAI_INFLUX_URL` | API에서 접근할 InfluxDB 주소 |
| `EDGEAI_INFLUX_ORG` | InfluxDB 조직 |
| `EDGEAI_INFLUX_TOKEN_FILE` | bucket 읽기·쓰기 토큰 파일 |
| `EDGEAI_CONTINUOUS_ENABLED` | 연속 계산 활성화, 기본 false |
| `EDGEAI_RAW_HISTORY_BROKER_NODE_OVERRIDES` | 필요한 노드의 broker 주소 보정 |

토큰은 서버에서 파일로 읽는다. 브라우저나 Git에 전달하지 않는다. 원본 bucket의 기본 보관 기간은 30일이며 결과 bucket은 1~30일이다. 저장 용량은 데이터 크기·주기·보관 기간을 기준으로 계산한다.

## 설치와 실행

### 기본 서비스

기준 소스를 준비하고 `.env.example`과 설정 가이드를 확인한다. 새 개발 환경에서는 다음 명령을 사용한다. [3]

```bash
make setup
make check
make up
make data-store-start
```

`make up`의 개발 MQTT는 실제 노드 NanoMQ와 별개다. Docker 없이 `make db-start`를 사용할 수도 있지만 이 명령은 PostgreSQL만 준비한다. 로컬 InfluxDB는 기본 18086을 사용하며 생성한 연결 정보와 토큰 파일을 API 설정에 연결한다.

관리 API와 BFF는 각각 별도 터미널에서 `make backend`, `make dashboard`로 실행한다. native UI는 `bash scripts/dev/dev-kestra-native.sh`로 실행한다.

### 센서와 연속 계산

1. 대상 노드의 Ready 상태, EdgeCore DMI, 장치 경로를 확인한다.
2. 아키텍처에 맞는 Mapper 이미지와 RBAC, 노드·프로토콜 설정을 준비한다.
3. 보드 출력에 맞는 Property 규격을 저장하고 측정 항목을 등록한다.
4. 연결 시험과 실제 MQTT 데이터의 시각·값을 확인한다.
5. Mapper의 `/capabilities`, 검증된 데이터 형식, 세션 순서를 확인한다.
6. Agent·eKuiper·저장소·권한을 준비한 뒤 연속 계산을 켠다.

Kubernetes 배포는 `deploy/kubernetes/ekuiper`와 기존 운영 가이드를 따른다. 실제 InfluxDB PVC·전용 토큰·네트워크는 따로 준비해야 한다. eKuiper 설치 후에는 규칙이 생성되고 결과가 나오는지 확인한다. [2]

## 인터페이스와 데이터

### 주요 경로

| 구분 | 경로 | 기능 |
|---|---|---|
| 웹 센서 관리 | `/api/devices` | 측정 항목 관리 |
| 웹 규격 관리 | `/api/device-profiles` | 저장된 항목 규격 |
| 서버 원본 목록 | `/api/v1/data-store` | 저장된 센서 목록 |
| 서버 원본 이력 | `/api/v1/data-store/sensors/{uid}/{property}/history` | 기간·cursor로 원본 조회 |
| 서버 VD 관리 | `/api/v1/virtual-devices` | 연속 VD 목록·상세 |
| 서버 VD 등록 | `/api/v1/virtual-devices/continuous` | 입력·계산·저장 정책 등록 |
| 서버 VD 출력 | `/api/v1/virtual-devices/{id}/outputs` | 현재 결과·저장 상태 |

웹 프록시와 서버 API의 경로는 다르다. HTTP 메서드·요청 형식은 해당 컨트롤러와 OpenAPI를 따른다. KubeEdge 센서 관리와 일반 UUID Device API를 구분하고, 쓰기 요청의 CSRF·revision·멱등 키 조건을 유지한다. [5]

### 데이터 식별과 저장 상태

원본 데이터는 UID, property, 수집 설정, generation, session, sequence, `observedAt`을 유지한다. 같은 시각이라도 sequence가 다르면 별도로 저장한다. 구형 형식은 ‘설정 미검증’으로 표시한다.

VD 결과는 VD·output·revision·실행 generation으로 구분한다. NONE·SELECTED·ALL은 계산과 별개인 저장 정책이다. 결과 상태는 저장 제외 `SKIPPED`, 대기 `PENDING`, 완료 `STORED`, 대기 중 만료 `EXPIRED`로 표시한다.

원본 수집의 QoS 1 ACK는 저장이 끝난 뒤 보낸다. 일반 STREAM의 처리 ACK와는 다르다. InfluxDB를 직접 조회할 때도 `archive_visible`과 빈 문서 제외 조건을 적용해야 한다.

## 사용과 장애 대응

센서는 프로토콜·대상 노드·연결·측정 항목을 지정해 등록한다. 같은 연결의 항목 추가와 단일 항목 수정을 지원한다. 수정 후 다른 항목과 연결 설정이 유지되는지 확인한다.

VD는 입력, 계산식, 출력, 저장 정책을 선택한다. 조합 10종은 같은 단위의 서로 다른 숫자 센서를 사용한다. 시작 후 현재 generation의 유효 결과와 저장 상태를 확인한다. 설정 변경은 새 revision으로 남기며 등록 해제 시 원본 센서와 기존 이력은 보존한다. [2]

| 현상 | 점검할 내용 |
|---|---|
| 등록 후 OFFLINE | 노드·Mapper·장치 경로·DMI 상태 |
| 값이 바뀌지 않음 | 측정 시각·heartbeat·MQTT 실제 수신 |
| 원본 저장 정체 | collector 상태·저장 건수 증가·대기량·Influx 쓰기 |
| VD 결과 없음 | 입력 유효 시간·Agent 규칙·lease |
| 결과 저장 대기 | PENDING·PostgreSQL 대기열·보관 기한 |

InfluxDB DELETE·TSI 대기로 수집이 멈추면 기존 데이터와 PVC를 보존하는 복구 절차를 따른다. 수집 경로는 중복 데이터를 조회에서 제외하는 방식을 사용하며 동기 DELETE를 다시 넣지 않는다. [3]

중지·되돌리기는 연속 계산 flag를 false로 바꾸고 lease 만료 후 소유 규칙이 정리됐는지 확인한다. 다른 사용자 규칙, 센서 CRD, VD 정의, 이력은 유지한다. 원본 수집은 연속 계산과 독립적으로 켜고 끈다.

## 시험 절차와 기존 결과

격리 환경에서 `bash scripts/test/test-processing.sh`와 `make test-data-store`를 사용한다. 실제 broker·eKuiper full·InfluxDB가 필요하다. [2]

| 시험 | 입력·조치 | 확인할 결과 |
|---|---|---|
| 센서 연결 | 실제 항목 등록 | 연결 시험과 실제 값 일치 |
| 항목 수정 | 한 항목의 설정 변경 | 다른 항목·연결 유지 |
| 중복 수신 | 같은 식별 정보로 재전송 | 조회에 같은 원본이 중복되지 않음 |
| 동시각 데이터 | 다른 sequence로 같은 시각에 전달 | 두 데이터의 식별 정보·값 보존 |
| 세션 변경 | session·generation 교체 | 이전 데이터가 새 결과를 덮지 않음 |
| 조합 계산 | 결과를 아는 입력 전달 | 수식·단위 일치 |
| 저장 정책 | NONE·SELECTED·ALL 적용 | 최신값과 이력에 정책별 동작 반영 |
| 저장소 장애 | 시험 저장소 중지·복구 | 저장 전 ACK 금지, 복구 후 재전달 |

기존 시험에서는 2026-10-14 실센서 발행·저장 3,055건을 3분 동안 대조했다. 10월 16일 복구 후에는 짧은 구간에서 COLLECTING, rejected 0, 약 0.65~1.02초 지연을 확인했다. 10월 19일 조합·이력 UI와 20일 native API 시험 27개도 통과했다. 세부 조건은 시험 기록을 참고한다. [1]

24시간 손실·중복 검증, 최대 동시 처리량, 전체 재시작·복구는 남아 있다. 모든 원본의 STREAM 전달, 중단된 윈도우 복원, 과거 데이터 전체 재생, 임의 보드·인증 방식도 별도 검증이 필요하다. [6]

## 참고 자료

1. [구현·시험 기록](../../PROGRESS.md)
2. [연속 계산·저장 운영 가이드](../guides/vd-processing.md)
3. [개발 환경과 저장소 복구](../guides/local-development.md)
4. [센서 등록](../guides/sensor-registration.md), [Mapper 구조](../../deploy/kubeedge/README.md)
5. [API 참고](../reference/api.md)
6. [남은 개발·검증 항목](../../PLAN.md)
