# 현재 상태

2026-10-06 확인. 전체 플랫폼 개발·수용 검증은 아직 완료되지 않았습니다.

## 개발 단계

- **완료된 단계:** M0–M4, M6의 해당 구현·검증 범위.
- **현재 우선순위:** Dashboard 프론트 다듬기와 관리 흐름 완성. Runner/STREAM 고도화와 종합 검증은 보류합니다.
- **남은 범위:** M5의 외부 시스템 계약·상태형 복원 수용, M8 성능 기준, M9 종합 운영/복구, M10 실장비·실모델 수용.
- 단계별 범위는 [PLAN.md](PLAN.md)를 확인하세요.

## 이번 작업

- 원 설계에 맞춰 VD 템플릿에 종류·원본 규격/허용 출처·SERVICE·상태·실행 정책 입력을 연결했습니다.
  인스턴스는 템플릿 슬롯별 호환 ACTIVE 장치만 선택하며, 원본·실행체 교체 후 VD ID와 이력을 유지합니다.
  기본 서비스 워크플로는 Spring DAG로 변경하고 기존 DDS·Buildx·Gitea·Argo CD 경로는 별도 탭으로 유지합니다.
  관측 장비 적용 시 노드 고정을 명시적으로 선택하도록 정리했습니다.
  [원 설계 대조](docs/reference/design-alignment.md)와 [검증 근거](docs/evidence/design-alignment-2026-10-06.md)를 확인하세요.
  실장비·실모델·외부 계약·상태형 복원·종합 운영 수용은 남아 있으며 앱 실행·재시작·배포는 하지 않았습니다.


- 문서를 공개 클라우드 네이티브 프로젝트의 시작하기·개념·사용 가이드·운영·참고·기여 구조로
  재구성했습니다. 주요32개를 읽는 순서로 표시하고 설계·검증·과거 기록은 별도 탐색합니다.
  현재 접근 정책, DDS/DAG 차이, 센서 등록, 관측·삭제·복구 조건을 코드에 맞춰 정리했습니다.
  문서 검사와 카탈로그 단위3개, 기존13080의 검색·목차·모바일 읽기 검증을 통과했습니다.
  [문서 작성 기준](docs/contributing/documentation.md)을 따릅니다.

- 센서 탭에 수동 등록 폼과 Spring 등록 API를 추가했습니다. 배포된 Arduino/Sense HAT 프로필을
  선택하고 연결 정보를 입력하면 EdgeX DB에 등록합니다. 동일 이름 재요청과 채널 중복을 검사합니다.
  실행 중 Spring에는 재실행이 필요합니다. [센서 등록 검증](docs/evidence/sensor-registration-2026-10-06.md)을 따릅니다.

- 사용자 참조 문서 사이트 디자인으로 `/docs`를 추가했습니다. 제목·본문 검색, 분야별 목록,
  Markdown/YAML 본문·목차·Mermaid와 모바일 화면을 제공하며 Dashboard 메뉴에서 연결됩니다.
  카탈로그 단위3개·lint·빌드 및 기존13080 브라우저 검증을 통과했습니다.
  standalone 문서 패키징과 원본 대조를 확인했으며 서버 실행·수동 재시작·배포는 하지 않았습니다.
  [웹 문서 사용법](docs/guides/web-documentation.md), [검증 근거](docs/evidence/web-documentation-2026-10-06.md).

- 센서 이름을 누르면 EdgeX Core Data의 최근 측정값·항목별 그래프·원본 표를 조회하고,
  Core Command의 지원 명령을 확인·실행하도록 API와 웹을 연결했습니다. 읽기 전용 명령은
  쓰기를 제공하지 않으며 쓰기는 확인 후 전송합니다. 실제 물리 장치 명령은 실행하지 않았습니다.
- VD 해제와 별도의 등록 영구 삭제 API·목록 버튼을 추가했습니다. 해제된 미사용 등록만
  삭제하고 원본 장치·프로필은 보존합니다. 실행·작업 참조가 있으면 API/DB에서 거절합니다.
  V43은 임시 검사 DB에서만 적용했으며 실제 앱 DB와 서버는 사용자 재실행이 필요합니다.
- 해당 범위의 단위164개·격리 PostgreSQL15개·프론트 단위4개·EdgeX 배포 설정6개,
  계약·타입·lint·빌드 및 브라우저 모의 검증을 확인했습니다.
  [사용 안내](docs/guides/sensor-controls-and-vd-deletion.md),
  [검증 근거](docs/evidence/sensor-controls-and-vd-deletion-2026-10-06.md).

- EdgeX를 `deploy/edgex` 구성으로 초기화·재배포했습니다. 기존 ArgoCD `edgex-telemetry`와
  Discovery/Adapter Controller/Ingest Gateway를 제거하고 상시 Pod8개로 정리했습니다.
- Arduino 채널별6 + Sense HAT 그룹별6 = 센서12행으로 분리했습니다. 모두 UP이며 신규 데이터 수신,
  Spring inventory AVAILABLE 및 실제 브라우저12행을 확인했습니다. 기존 DB는 백업 후 초기화했습니다.
- 관련 배포·운영·검증 파일을 주 작업 폴더 `/home/jinuk/codex-work/edgeai`에 반영했습니다.
  기존 Spring/UI 수정사항은 보존했습니다. Git push·새 ArgoCD 연결은 아직 실행하지 않았습니다.
  상세 결과와 한계는 [EdgeX 재구성 검증](docs/evidence/edgex-rebuild-2026-10-06.md)을 따릅니다.

- 물리 디바이스10대 기본 보기와 Kubernetes Ready 조건 연결, 반복 CPU/메모리 설명 제거, GPU 최신값5초 조회를 구현했습니다. 실제 kubectl/Prometheus Java decode 모두Ready10을 확인했습니다.
- 사용자 승인으로 미사용 Profile 버전/Device 등록 삭제 API·목록 버튼을 추가했습니다. 활성 연결·참조가 있으면409로 막습니다. 새V42 migration은 개발DB 미적용이며 사용자 백엔드 재실행이 필요합니다.
- 별도 임시 PostgreSQL 삭제시험5개, 계약31개, 메트릭/감사21개, 프론트 단위6개와 타입/lint/build 통과. 브라우저 실제응답·모의응답 검증 범위는 [작업 근거](docs/evidence/physical-devices-and-registry-deletion-2026-10-06.md)에 구분했습니다.

## 직전 테스트 데이터 정리

- 사용자 승인으로 테스트 Profile14,498개 버전과 연결된 장치·VD·Workflow·Run·감사 기록을 로컬 DB에서 정리했습니다. 실제 노드10개와 Flyway41개 행은 그대로 보존했습니다.
- 사전 DB 백업은 `/mnt/data3tb/edgeai/backups/before-test-cleanup-20261006`에 저장했습니다. API에서 프로필/장치/VD/감사0개와 메트릭10개를 확인했습니다.
- CPU를 최근 두 샘플의 irate로 변경(현재 약30초 수집)하고 ‘실행 상태’를 ‘노드 준비 상태’로 명확히 했습니다. 개발 서버 요청 요약 로그를 비활성화했습니다.
- 관련 백엔드18개 시험, 타입·변경 파일 lint·계약 생성 통과. 백엔드 변경 적용을 위한 실행은 사용자에게 맡깁니다. [작업 근거](docs/evidence/dashboard-test-cleanup-2026-10-06.md).

## 앞선 프론트 작업

- 사용자가 지정한 NEXUS 운영 화면을 데스크톱·모바일로 캡처하고 남색 사이드바/상단바, 파란 강조색, 요약 카드4개, 전체 노드 자원 표를 적용했습니다. 기존 EdgeAI 관리 기능은 유지합니다.
- ‘노드 없음’ 원인은 Prometheus가 아니라 별도 노드 DB였습니다. 실제 노드10개는 예전 REMOVED 기록이고 총2406개 중2396개 REMOVED, 나머지10개는 STALE 시험 기록이었습니다.
- 모니터링 노드의 목록은 Prometheus 응답에서 직접 구성하도록 수정했습니다. 최신 Kubernetes 기록이 없으면 실행 상태와 할당 가능량은 미확인입니다. 과거 DB 기록은 접힌 영역에 보존합니다.
- 사용자가 실행 중인 `http://localhost:13080`에서 실제10개 노드, GPU필터5개·NPU필터2개, 노드 검색1개, 모바일 메뉴·Escape 포커스 복귀를 확인했습니다.
- 320/390/768/1440px에서 페이지 가로 넘침 없음, 최종 브라우저 콘솔 오류·경고0개를 확인했습니다. 새 회귀 시험4개와 타입·lint·production build도 통과했습니다.
- IP 접속의 로딩 고정도 수정했습니다. Next16 개발 origin 차단으로127.0.0.1/192.168.0.56에서 HMR이 Unauthorized를 반환하고 API 조회가 시작되지 않았습니다. next.config.ts의 allowedDevOrigins에 두 주소를 추가했습니다.
- 수정 후192.168.0.56:13080에서 실제10개 노드·로딩 종료·메트릭 갱신·콘솔 오류/경고0개를 확인했습니다. localhost/두IP의 HMR은101, 허용하지 않은 호스트는 계속 차단됩니다.
- 앱 시작·재시작 명령·배포·DB 변경은 하지 않았습니다. 실행 중인 Next 개발 서버가 설정 변경을 자동 반영했습니다.
- 시각 비교는 [디자인 QA](design-qa.md), 작업 근거는 [NEXUS 참조 적용](docs/evidence/nexus-reference-2026-10-06.md)에 기록했습니다. 앞선 [메트릭 연결](docs/evidence/node-metrics-2026-10-06.md)의 백엔드는 이번에 변경하지 않았습니다.
- 기존 자동 E2E는 앞선 작업에서 이전 로그인 계약을 기대하는 audit 시험2개가 실패하고48개가 미실행됐습니다. 전체 E2E 성공으로 간주하지 않습니다.

## 직전 배포 기록

- 기관 간 통합 전까지 워크플로 메뉴·직접 URL·공개 API·Swagger 작업 목록을 제외합니다.
  기본값은 `EDGEAI_WORKFLOW_ENABLED=false`이며 API와 Dashboard에 함께 적용합니다.
- 공유 내부 실행 코드와 기존 DB 데이터는 보존합니다. 다른 Platform-Service 저장소는 검토만 했고 통합하지 않았습니다.
- Java 단위 테스트 124개·API 계약과 Dashboard 타입·lint·빌드 검사는 통과했습니다.
- 워크플로 제외 CI [37413088339](https://github.com/dsa04156/edgeai/actions/runs/37413088339)는 성공했고 실제 메뉴·직접 URL·API·프록시·Swagger 제외를 확인했습니다.
- API는 AMD64 이미지로 56번 서버 `etri-ser0001-cg0msb`에 고정했으며 해당 노드의 Pod Ready를 확인했습니다.
- 서버 변경 시 Runner 재빌드를 생략하고 검증된 기존 digest를 재사용하도록 CI를 분리했습니다.
  Runner 관련 변경·최초 발행·수동 전체 검증에만 AMD64/ARM64 빌드와 검사를 실행합니다.
  소스 버전 분리 및 이미지 재사용 검사 10개와 후속 CI [37414136528](https://github.com/dsa04156/edgeai/actions/runs/37414136528)가 통과했습니다.
  실제 CI에서 Runner native job은 생략됐고 기존 digest 재사용·서버 이미지 발행·GitOps 갱신이 성공했습니다.
- 자동 CI 간소화는 완료했습니다. 백업·복구·부하·Kubernetes 전체 검증은 `full_verification=true` 수동 실행입니다.

## DB와 배포

- GitOps `08224cd`의 소스 `42f7247` 배포에서 API·Dashboard·MinIO 실행 이미지 일치,
  Pod Ready·PVC Bound를 확인했고 후속 API 배치 변경 후에도 기존 Profile·Device 기능과 두 접속 주소의 HTTP 200 검증이 통과했습니다.
- 최신 서버 소스 `a250722`, Runner 소스 `42f7247`로 분리됐습니다. GitOps `b6982d4`의
  API·Dashboard·MinIO 실행 이미지 일치, Pod Ready·PVC Bound·Argo Synced를 확인했습니다.
  마지막 커밋 감지가 지연돼 ArgoCD에 일반 Git 새로고침을 요청했으며, 이후 자동 sync와 MinIO 교체가 완료됐습니다.
- ArgoCD는 Git 동기화 완료지만 기존 Ingress 주소 게시 문제로 전체 health는 Progressing입니다.
- Kubernetes DB는 V40 적용 성공을 확인했습니다. 이번 기능 제외에는 DB 변경이 없습니다.

## 다음 순서

Kubernetes 실행 노드 관측 설정과 개발 DB의 시험 데이터 분리를 후속으로 확인합니다.
현재 로그인 없는 관리 UI에 맞춰 기존 브라우저 시험을 정비하고, 프론트 확인 결과를 반영합니다.
실모델·성능·복구 검증은 이후 진행합니다. 공유 Ingress 상태 게시 문제와 MinIO 배포 대기는 남은 운영 개선 사항입니다.

과거 진행 이력은 [보관 기록](docs/history/progress-through-2026-10-06.md)에 있습니다.
이 파일은 최신 상태로 교체하며 과거 실행 로그를 계속 덧붙이지 않습니다.
