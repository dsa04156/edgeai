# 물리 디바이스 Ready 및 미사용 등록 삭제

## 확정 요구와 구현

- 사용자는 물리 장비를 기본으로 표시하고 CPU·메모리의 반복 설명을 제거하며,
  Kubernetes get nodes의 Ready 상태를 표시하도록 요청했다.
- kubectl get nodes 실제 조회는10대 모두Ready였다. 같은 Ready 조건을 수집하는
  kube_node_status_condition을 기존 Prometheus→API 경로에 추가했다.
  원본 timestamp와 kube-state-metrics up을 검사하고 상태 만료는 Unknown으로 표시한다.
- 수정한 Java adapter로 실제 Prometheus 조회·decode:10대/Ready10/매핑 실패0.
  물리 디바이스와 데이터 입력 Device 등록을 분리하고 /devices 기본 보기를 물리 디바이스로 바꿨다.
- GPU는 최신 gauge 사용을 유지하고 프론트 조회/백엔드 공유 캐시를5초로 줄였다.
  실제 active targets에서 GPU/NPU exporter15초, CPU node-exporter30초를 확인했다.
  수집기 설정이나 클러스터는 변경하지 않았다.
- 사용자가 ‘사용 중이면 막고 삭제’를 승인했다. 프로필·Device 목록에 삭제/확인/취소를 추가했다.
  모바일 표는 내부 가로 스크롤로 이름/버튼 폭을 유지한다.
- DELETE profiles/{kind}/{key}/versions/{version}: 참조 없는 버전만204삭제.
  V42에서 profile 내용 UPDATE/TRUNCATE 금지는 유지하고 DELETE만 허용한다.
  FK와 VD Profile JSON 참조가 있으면 PROFILE_IN_USE409. 다른 버전은 유지한다.
  삭제 트랜잭션의 profile table lock은 동시 publication과 직렬화하며3초 lock timeout을 둔다.
- DELETE devices/{deviceId}/registration: 활성 attachment/session 또는 VD 사용은409.
  과거 실행 참조가 남아 있으면 FK가 삭제를 막고 트랜잭션 전체를 되돌린다.
  미사용 등록과 닫힌 연결/세션/관측만 삭제한다. 기존 DELETE devices/{id} 해제 계약은 유지한다.
- 두 API의 CSRF·고정 감사 operation·프록시 허용 경로·OpenAPI와 생성 타입을 함께 갱신했다.

## 검증과 적용 범위

- 실제 PostgreSQL의 별도 임시 DB에서 삭제/FK 참조/활성 세션 거절/VD JSON 참조/다른 버전 보존,
  profile UPDATE/TRUNCATE 거절을 포함한5개 integration test 성공. 임시 DB는 종료 후 삭제했다.
- 계약 시험31개, NodeMetrics14 + ManagementAuditFilter7, 프론트 노드 단위6개 성공.
  타입 검사·변경 파일 ESLint·production build·diff check 성공.
- 실제 IP13080의 기존 개발 서버에서 standalone Playwright 사용.
  backend 재시작 없이 새 DTO 화면을 확인하기 위해 Java가 실제 조회한 JSON을 브라우저 응답에 주입했다.
  데스크톱1440/모바일390에서 Ready10과 간결한 CPU/메모리 표시 확인.
  증거: output/playwright/physical-nodes-ready-desktop.png 및 physical-nodes-ready-mobile.png.
- 삭제 UI는 브라우저에서만 응답을 대체했다. 실제 등록 데이터 변경 없이 취소 시 요청0,
  409시 행 보존/오류 표시,204시 행 제거/완료 안내 확인. Device 모바일 페이지 가로 넘침 없음.
  API 자체 동작은 위 실제 격리 DB 시험 및 MVC CSRF/응답 시험으로 검증했다.
- 브라우저 정상 메트릭 요청은 초기 Strict Mode 중복 후5,078ms 간격으로 재조회했다.
- 실행 중 개발 DB는Flyway41 유지. 작업 중 새로 등록된 test 프로필/장치1개씩도 수정하지 않았다.
  V42는 아직 개발 DB에 적용하지 않았다. 사용자 백엔드 재실행 시 Flyway와 새 API/Ready가 적용된다.
- 앱 시작/재시작/배포 없음. 전체 E2E 및 전체 통합시험은 수행하지 않았다.
