# 기존 가상 디바이스 제거 검증

최초 기준 commit: `6fe4429`. 배포 준비 시 최신 `main`의 `da08af9`로 병합했다. 요구사항은 [ADR0115](../adr/0115-retire-legacy-virtual-devices.md)의
“기존 가상 디바이스 기능 전체만 삭제”이며 다른 플랫폼 기능은 보존한다.

## 변경 범위

- VD 등록·프로필·실행·poll API, 전용 서비스·worker·Kubernetes gateway·Runner supervisor를 제거했다.
- 대시보드 메뉴·현황 카드·VD 등록 화면·템플릿과 워크플로의 VD 실행 선택을 제거했다.
- VD OpenAPI·프로필 schema·데모·배포 feature flag와 현재 VD 수용시험을 제거했다.
- DEVICE/SERVICE 프로필, 일반 장치·센서·노드·워크플로·결과 관리와 AUTO/NODE/REMOTE 실행은 유지했다.
- 적용된 Flyway migration은 변경하지 않았다. 기존 DB와 결과 파일은 삭제하지 않았다.
  과거 VD 식별자는 조회 응답의 읽기 전용 이력으로 유지하고 신규 요청 계약에서는 VD를 제외했다.
- 과거 VD 배정·Runner 인증·재시도·offload·결과 발행 큐를 현재 실행 경로에서 차단했다.
  일반 스트림 수용시험에서 필요한 공통 helper는 VD fixture에서 분리했다.

## 로컬 검증

| 검증 | 결과 | 확인 범위 |
|---|---|---|
| `backend/gradlew :app:test` | 155개 통과, 실패·오류·skip 0 | 관리 API, 일반 Runner와 공유 서비스 단위 회귀 |
| 격리 PostgreSQL `:app:integrationTest` 5개 클래스 | 63개 통과, 실패·오류·skip 0 | Workflow, RegistryDeletion, StreamRun, StreamRoute, VirtualDeviceRemoval |
| `scripts/test/test-contract.sh` | 통과 | 남은 OpenAPI 4개, 생성 타입, 패키징된 Swagger 및 관련 MVC 계약 |
| `scripts/test/test-runner.sh` | 95개 통과 | 일반 Runner, SDK·codec·SQLite·계산 프로세스 회귀 |
| `scripts/internal/test-stream-drivers.py` | 10개 통과 | 실제 Device SDK, 격리 HTTPS·SQLite·TLS MQTT; 서버 권한은 명시적 fixture |
| `dashboard/tests/sensor-proxy.unit.ts` | 3개 통과 | 일반 센서 프록시·CSRF, 제거된 VD 경로의 upstream 차단 |
| 대시보드 lint·TypeScript·production build | 통과 | 삭제된 메뉴·페이지·입력 타입 정합성과 남은 앱 빌드 |
| Playwright `virtual-device-removal.spec.ts`, `task-placement.spec.ts` | PC·모바일 총 6개 통과 | VD 화면/API 404, 현황 3개·프로필 2종·실행 정책 3종, NODE/REMOTE 배치와 실패 입력 보존 |
| `scripts/test/test-docs.sh` | 통과 | 문서 링크·탐색·API 색인 |
| 변경된 Python AST·shell 구문, `git diff --check` | 통과 | Python 16개·shell 2개, 공백 오류 없음 |

PostgreSQL은 작업 전용 데이터 디렉터리와 임의 loopback 포트에 직접 띄운 실제 서버다.
사용자 DB에는 연결하지 않았고 종료 후 이 서버도 정리했다.
VD 제거 통합 시험은 신규 VD 등록·실행 요청 거절, 일반 AUTO 요청의 멱등성,
SQL로 보존한 과거 VD 프로필·실행·시도 이력 조회와 재배정 차단을 확인했다.
브라우저 시험의 HTTP 응답은 fixture이며 실제 클러스터 실행 증거를 대신하지 않는다.
PC·모바일 현황과 작업 배치 스크린샷을 생성해 확인했다.

## Standards

독립 리뷰에서 신규 offload의 과거 VD 경로, 사용하지 않는 VD 조회·배정 hook,
기존 회귀 시험의 오래된 입력 selector를 지적했다. 해당 경로·hook을 제거 또는 차단하고
selector와 성공 후 실행 목록으로 이동하는 검증을 현재 UI에 맞췄다.
기존 migration 보존, 도메인 경계, OpenAPI 생성 타입, 아이콘 이름과 VD flag 제거를 재검토했다.
최종 증분 리뷰에서 남은 구체적 표준 위반을 발견하지 못했다.

## Spec

독립 리뷰의 다섯 수정 사항을 반영했다. 수동 BATCH offload와 기존 VD offload 진행을 차단하고,
과거 VD 시도를 일반 배정·결과 발행 큐에서 제외했다. 신규 입력과 과거 읽기 응답을 분리해
역사적 `VD` mode와 식별자를 조회 가능하게 유지했다. 일반 스트림·Runner 진단 helper를 추출했다.
실패 입력·요청 키 보존, NODE/REMOTE 표시와 버전 전환 초기화도 추가 증분 리뷰를 통과했다.
최종 검토에서 남은 구체적 요구사항 불일치를 발견하지 못했다.

## 검증 경계와 남은 전제

최초 로컬 검증 단계에서는 실제 Kubernetes·S3·배포 환경과 설치 중인 eKuiper를 변경하지 않았다.
후속 사용자 승인에 따른 배포 준비·적용 기록은 아래에 추가한다.
새 eKuiper 가상 디바이스 구현은 아직 만들지 않았다.
기존 활성 VD supervisor가 있는 환경은 업그레이드 전에 기존 버전에서 종료해야 한다.
과거 VD 저장 형식을 읽는 오프라인 복구 지원은 유지한다.
이전 VD 공개 API를 호출하는 Kubernetes 복구 수용시험은 기존 버전용으로 보존했으며
현재 `test-kind.py` 실행 목록에서는 제외했다. 전체 Kubernetes 수용시험 통과로 보고하지 않는다.


## 배포 준비와 최신 코드 보존

사용자의 후속 “ㄱㄱ”에 따라 실제 반영 범위를 추가 승인받았다. 최신 main의 Mapper 센서 등록·측정 항목 CRUD를 유지하고,
주 작업 공간에서 이미 구현한 일반 Result JSON 본문 조회도 보존했다. 주 작업 공간의 수정 중인 소스는 덮어쓰지 않는다.
실제 DB에 V49~53이 이미 적용돼 있어 해당 파일을 원본 그대로 포함했다. 적용 migration 53개의 Flyway 체크섬은 모두 일치했다.
사전 조회: 센서 17개, VD 0개, 활성 VD runtime·VD-origin task 0개, 프로필 14개, Run 6개, Task 6개, Result 0개.
eKuiper Pod 1개와 센서·broker 상태를 UID와 spec SHA-256로 기록했다. 실제 적용 결과는 후속 검증으로 기록한다.

최신 main 병합 후 백엔드 단위 165개, 격리 PostgreSQL 5개 클래스 63개,
일반 센서 프록시 6개, PC·모바일 VD 제거·배치·센서 항목 CRUD 20개를 통과했다.
과거 VD→NODE 재개 방지와 Result 본문 보존에 대한 마지막 증분 검증을 별도로 추가한다.

### Standards 증분

DEVICE/SERVICE 버전 발행·삭제 후 전체 버전 캐시 갱신을 복원했다.
STOPPED NODE runtime의 늦은 Job 관측·삭제 재발행과 Remote 종료 관측을 유지했다.
이미 적용한 V49~53은 원본과 바이트 단위로 동일하다. Result 본문 경로의 감사 vocabulary와 오류 enum을 계약에 반영했다.

### Spec 증분

과거 VD에서 NODE/REMOTE로 전환된 Task도 초기 배정 신원으로 재개·권한 부여·retry·offload를 차단했다.
기존 물리 실행의 종료·Operation 취소 완료와 결과 replay·조회는 유지했다.
신규 처리 기능을 복원하지 않고, 저장된 과거 processing 입력은 읽기 전용 schema와 원본 JSON으로 보존했다.
Runner의 폐기된 VD 센서 신규 실행 안내를 제거했다.

최종 증분 백엔드 단위 167개, 격리 PostgreSQL 재개·retry·offload·stream recovery 29개가 통과했다(실패·오류·skip 0).

마지막 증분에서 계약·생성 타입·Swagger 4개 검증, lint·production build, PC·모바일 22개(결과 JSON 조회 포함)가 통과했다.
Standards와 Spec 독립 리뷰는 각각 남은 구체적 결함 없음으로 완료했다.


## 실제 화면·API 적용

`5fab7a3`의 검증된 JAR와 Next standalone 빌드를 주 작업 공간의 `.tools/releases/`에 별도로 배치했다.
기존 실행 프로세스의 설정과 포트를 보존해 API 18080·대시보드 13080을 교체했다. 수정 중인 주 작업 공간 소스는 변경하지 않았다.
두 readiness는 UP이며 VD API·프록시·화면은 404다. 플랫폼 capability와 운영 현황 메뉴에서 VD를 제거했다.
실제 브라우저 운영 현황·센서 화면을 확인했고 page error는 0이었다.
센서 17개와 Run 6개는 조회 가능하며 DB 프로필14·Run6·Task6·Result0 및 migration53개는 배포 전후 동일하다.
eKuiper Pod·sensor Device 17개·broker Pod6개의 UID와 spec SHA-256이 같고 해당 Pod는 Ready다.
S3·MQTT·PostgreSQL Pod UID도 유지했다. API Pod는 Argo가 VD 설정 제거를 동기화하며 재생성됐다.

CI [38239601328](https://github.com/dsa04156/edgeai/actions/runs/38239601328)의 native amd64·arm64 각각
Runner95·stream MQTT112 시험은 PASS/exit0/skip0이었다. provenance 기록의 삭제 전111 고정값으로만 실패했다.
두 기대값과 fixture를95로 갱신했고 오래된111 provenance 거부를 유지했다. 관련10개 단위 시험과 다운로드한
두 실제 CI artifact 판독이 통과했다. Standards·Spec 증분 리뷰도 완료했으며 배포 CI를 새 commit에서 다시 실행한다.
