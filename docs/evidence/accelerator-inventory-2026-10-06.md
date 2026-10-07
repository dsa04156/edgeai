# 가속기 표시와 API 연결 확인 — 2026-10-06

## 원인과 수정

- 상세 표가 GPU UUID/PCI 식별자를 본문과 title에 출력했다. 표와 NodeUsage에서 식별자를
  제거하고 장치 모델 또는 GPU/NPU 순번만 표시한다. 식별자는 내부 연결 키로 유지한다.
- 기존 화면은 사용률·온도 등의 측정값에서만 장치를 추출했다. 사용량 exporter가 없는
  Hailo가 누락됐다. 기존 Prometheus `node_accelerator_info`를 동일 API의 accelerators로
  전달하고, 사용률과 독립적으로 노드 목록·검색·GPU/NPU 필터에 포함한다.
- 장착 정보는 원본 timestamp, exporter up, node_hardware_inventory_success로 검증한다.
  만료·실패한 장착 정보만으로 장치를 표시하지 않으며 미수집 사용량은 0이 아닌 `—`다.
- DCGM 및 Intel NPU는 정규화한 PCI 주소로 지표와 장착 정보를 연결한다.
  주소가 없는 Jetson/Spark/Mobilint는 노드/종류/벤더의 후보와 측정 장치가 각각 하나일 때만
  연결한다. 같은 가속기의 inventory/usage를 두 장치로 세지 않는다.

## 읽기 전용 실제 관측

- 수정한 Java adapter로 기존 Prometheus 조회: 노드10, 최신 Ready10, 미매핑0,
  최신 가속기16. dev0002와 dev0006의 Hailo-8 각각1 확인.
- dev0002 Hailo Kubernetes allocatable=0, dev0006=1. 할당 가능량0은 물리 장치 부재가 아니다.
- inventory Pod에 마운트된 호스트 sysfs의 PCI/USB 메타데이터 조회:
  라즈베리파이 dev0002/dev0006은 Hailo PCI 장치를 확인했고 dev0003에는 PCI bridge/RP1만 있었다.
  세 노드의 USB에는 root hub만 있었다. 당시 DeepX를 확인할 수 없었다.
- 기존 클러스터 inventory 코드에는 DeepX 전용 분류도 없다. 실제 호스트 인식 여부와
  수집기 지원을 확인해야 하며 이번 UI 수정으로 DeepX 지원 완료를 주장하지 않는다.
- 실행 중인13080 프록시: node-metrics(노드10), nodes, devices, csrf,
  profiles/DEVICE·SERVICE·VD 모두200. 실제 node-metrics에는 아직 accelerators 필드가 없다.
- 실행 중인18080 OPTIONS: 프로필 버전 DELETE 및 devices/{id}/registration DELETE 모두
  Allow에 존재. 이번 확인에서 실제 삭제 요청은 보내지 않았다.

## 검증

- NodeMetricsTest18 + NodeMetricsControllerTest2 통과. 별도 계약 스크립트 통과.
- 프론트 회귀9개 통과: UUID/PCI 본문·툴팁 비노출, Hailo 무사용량 표시와 NPU 분류,
  장착 정보 만료 제외, 같은 장치의 지표 병합, 기존 노드/Ready 회귀.
- TypeScript, 전체 ESLint, 프로덕션 빌드, diff 공백 검사 통과.
- 기존 사용자 실행13080에 독립 Playwright 접속. 새 Java adapter가 조회한 실제 응답을
  해당 브라우저의 node-metrics 응답에만 주입해 화면 검증했다. 백엔드 배포 검증과 구분한다.
  데스크톱10행, Hailo2행, ser0002의 Intel GPU/NVIDIA GPU/Intel NPU 분리,
  UUID/PCI title 없음, NPU 필터5행,390px 문서 가로 넘침 없음 확인.
- 캡처: output/playwright/accelerator-inventory-desktop.png,
  output/playwright/accelerator-inventory-mobile.png. 직접 이미지 검토 완료.
- 첫 페이지 로딩 시 연결 안내는 재조회에서 해소됐다. 수동 확인 중 잘못 요청한
  profiles?kind=DEVICE 경로만404였으며 앱이 사용하는 profiles/DEVICE는200이었다.

앱 시작·재시작, 배포, 클러스터/DB 변경은 하지 않았다. 새 장착 정보 응답은 사용자가
수정된 백엔드를 실행해야 반영된다.
