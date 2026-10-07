# EdgeX 센서 연결과 서버·디바이스 분리

## 확인한 원인

- 앱 DB의 Device 목록에는 사용자가 생성한 해제 상태 `test`1개만 있었다.
  Dashboard는 이 registry와 Prometheus 노드 목록만 읽고 있었으며 EdgeX 호출이 없었다.
- 사용자가 센서는 이미 EdgeX에 연결되어 있다고 정정했다. 기존 인접 프로젝트의
  edge-device/README.md도 Core Metadata를 inventory/state의 원본으로, Core Data Event를
  telemetry 원본으로 규정한다. KubeEdge Device/DeviceStatus는 legacy/reference다.
- 처음 조회한 KubeEdge Device10개를 현재 센서 목록으로 연결하려던 접근은 폐기했다.
  최종 코드에는 KubeEdge 센서 조회나 RBAC 추가가 없다.

## 실제 읽기 전용 조회

- Kubernetes10노드의 현재 라벨: environment=cloud 서버4, environment=edge 및
  edge role/class 장비6. ARM 서버도 서버로 분류하며 이름/아키텍처로 추측하지 않는다.
- EdgeX Core Metadata `/api/v3/device/all`, `/api/v3/deviceprofile/all`: 등록 장치13개 확인.
  Arduino 관련7개(6개 논리 항목+별도 물리 등록1개), Sense HAT 관련6개였다.
  등록 개수13은 물리 센서 보드13개라는 의미가 아니다.
- 당시 metadata operatingState는 UP12/DOWN1이었다. 해당 값은 Core Data Event 신선도나
  실제 센서 데이터 수신 성공의 증거가 아니므로 온라인 상태로 바꾸어 표시하지 않는다.
- 새 Java adapter 실제 조회로 서버4/엣지6/EdgeX13과 태그의 노드 연결을 확인했다.
  Prometheus adapter도 노드10/Ready10/미매핑0으로 재확인했다.

## 변경

- `GET /api/v1/infrastructure`: 노드와 센서 각각의 조회 상태·최소 필드만 제공한다.
  센서는 EdgeX 장치명, profileName, serviceName, tags.nodeName, adminState,
  operatingState, profile resource 이름만 반환한다. protocol 설정/자격/하드웨어 식별자는 제외한다.
- Kubernetes 조회와 EdgeX 조회가 독립적으로 실패 처리된다. EdgeX 장애 시 과거 KubeEdge
  Device나 앱 DB fixture로 센서 목록을 대체하지 않는다.
- 개요 요약4개와 자원 표를 서버/엣지/EdgeX센서/VD로 구분했다.
  장치 페이지는 서버/엣지/센서/앱 등록 장치 탭을 제공한다.
- 센서 모바일 표는 세로 카드로 전환했다. EdgeX 조회는 읽기 전용이며 목록을 앱 DB에
  복제하거나 기존 장치를 수정/삭제하지 않는다. 실측 센서값 연결은 이번 범위에 포함하지 않는다.
- 개발 실행 스크립트는 시작 시 선택된 kubectl context를 고정하고 기존 EdgeX metadata
  Service 주소를 조회한다. 명시 설정을 우선한다. Kubernetes 배포용 환경 변수만 추가했으며 미적용이다.

## 검증

- backend 새 Infrastructure 단위6+MVC1, 기존 NodeMetrics18 통과.
- 전체 관리 API 계약/MVC32 통과, 생성 타입과 패키징 OpenAPI 일치.
- 프론트 회귀11개, 전체 ESLint·TypeScript·프로덕션 빌드·셸 문법·diff 공백 검사 통과.
- 사용자 실행13080의 독립 Playwright 브라우저에 새 Java adapter의 실제 조회 응답만 주입했다.
  서버4행/엣지6행/EdgeX13행, 개요 그룹4·6, 검색, EdgeX 실패 시 이전 목록 제거를 확인했다.
- 데스크톱/390px 모바일 캡처를 직접 검토했다. 모바일 가로 표 문제를 카드 레이아웃으로
  수정하고 재캡처·재검증했다.
- 캡처: output/playwright/edge-ai-servers-desktop.png,
  edgex-sensors-desktop.png, edgex-sensors-mobile.png, infrastructure-overview.png.

앱 서버 시작·재시작·배포·DB/클러스터/EdgeX 변경은 수행하지 않았다.
최종 추가 조회에서 실행 중18080 API와13080 프록시의 infrastructure 경로는200이었다.
다만 nodesStatus/sensorsStatus가 모두 DISABLED였다. 코드 경로 반영과 조회 설정 활성화를
구분해야 한다. 사용자가 수정된 개발 스크립트로 백엔드를 재실행하거나 명시 환경 변수를
설정해야 실제 목록이 나타난다. 자세한 설정은 ../guides/infrastructure-inventory.md 참조.

## 13080 미표시 재확인: IDE 실행 환경

- 사용자 재신고 후 실제 Java 프로세스의 부모가 IDE remote-dev-server이며,
  `EdgeAiApplication` 직접 실행임을 확인했다. IDE 실행 구성은 루트 `.env`를 읽는다.
- 실행 환경에는 기존 Prometheus 변수가 있었지만 infrastructure/EdgeX 네 변수가 없었다.
  개발 스크립트에만 추가한 자동 탐색이 IDE 실행에는 적용되지 않는 것이 원인이었다.
- 로컬 `.env`에 현재 kubectl context를 고정한 조회 설정과 실제 EdgeX metadata Service URL을
  추가했다. 기존 설정과 자격 증명은 보존했으며 `.env`는 Git에서 제외된 상태를 유지한다.
- 실제 원본을 읽기 전용으로 다시 조회해 노드10개와 EdgeX 등록13개를 확인했다.
- 코드나 환경 파일 수정은 실행 프로세스의 환경을 갱신하지 않는다. 사용자 지시에 따라
  서버를 시작하거나 재시작하지 않았다. IDE에서 백엔드 재실행이 필요하다.
