# NEXUS 참조 기반 EdgeAI 화면 QA

final result: passed

범위: 기존 EdgeAI의 운영 화면·공통 테마·노드 자원 표에 사용자 지정 디자인을 적용한 결과.
참조 사이트 전체의 기능 복제가 아니라 현재 API와 메뉴를 유지하는 디자인 적용이다.

## 비교 근거

- source visual truth: `output/playwright/nexus-desktop.png`, `nexus-mobile.png`, `nexus-mobile-table.png`.
- implementation: `output/playwright/edgeai-nexus-desktop-final.png`, `edgeai-nexus-mobile-final.png`,
  `edgeai-nexus-mobile-table-final.png`.
- full-view comparison: source와 구현 이미지를 같은 도구 응답에 함께 열어 비교했다.
- focused comparison: `output/playwright/nexus-row.png`와 `edgeai-nexus-row.png`를 함께 열어
  수치·meter·보조 글씨·배지·행 간격을 비교했다.
- viewport/pixels: 데스크톱1440×1000, 모바일390×844. CSS pixel screenshot이며 리사이즈 보정 없음.
  행 비교는 같은1440px 화면의 첫 데이터 행이다. 모바일 표는 해당 영역으로 스크롤한 상태다.
- state: 실제 메트릭10개 노드. 서로 다른 API/DB를 사용하는 만큼 KPI 수치와 실행 준비 상태는 다르다.
  최종 데스크톱은 목록 초기 조회 완료 후 캡처했다.

## 비교 및 수정 이력

1. P2: 상단 검색창에 기존 input 스타일이 우선해 밝은 배경이 나오고 검색 버튼이 줄바꿈됐다.
   `input[type=search]` 명시 선택자와 버튼 nowrap으로 수정했다. 최종 캡처는 남색 바 안의 일관된 검색창이다.
2. P2: 데스크톱 카드가 참조보다 높아 자원 표가 아래로 밀렸다.
   1250px 이상에서 아이콘/수치/설명을3열로 배치하고 최소 높이120px, 표 행 padding9px로 조정했다.
   최종 전체 이미지와 행 비교에서 밀도·정렬을 재확인했다.
3. P2: 검색 아이콘의 너비만 줄어 비율 경고가 발생했다. 너비/높이15px를 함께 적용했다.
   최종 localhost 페이지 콘솔은 오류·경고0개다.

## 필수 표면 점검

- 글꼴: 참조의 Pretendard/시스템 fallback 순서, 제목29px/750, 패널15px/700,
  표12px와 수치16px 계층을 반영했다. 별도 웹폰트를 요청하지 않는 참조와 같은 방식이다.
- 간격/배치: 사이드바200px, 상단64px, 본문20×22px, 흰 패널과7px radius, 카드4열/모바일2열.
  노드 표는 모바일에서 각 행을2열로 펼쳐 페이지 가로 넘침을 방지한다.
- 색: 남색 `#102131`, 배경 `#f2f6fb`, 강조 `#1479ed`, 경계 `#dfe8f3`를 반영했다.
  수집 성공과 실행 상태 미확인은 서로 다른 배지로 구분한다.
- 자산: 참조에서 실제 제공하는 Bootstrap SVG6개와 MIT LICENSE를 로컬로 복사했다.
  NEXUS 브랜드를 복제하지 않고 기존 EdgeAI 브랜드를 유지한다.
- 내용: 준비 상태·예약량·성능을 임의로 채우지 않는다. 서비스 규격·장치·VD는 기존 DB 응답,
  모니터링 노드는 Prometheus 응답이다. 참조의 별칭 대신 현재 노드 식별자를 보여준다.

## 동작 검증

사용자 실행 서버 `http://localhost:13080`에서 수행했으며 새 앱 서버는 띄우지 않았다.

- 노드10개, GPU필터5개, NPU필터2개, 전체 복귀10개를 조건 assertion으로 확인.
- 모바일 메뉴 열기·Escape 닫기·메뉴 버튼 포커스 복귀 확인.
- 상단 노드 검색 `jetorn` → devices의 일치 노드1개 확인.
- 320/390/768/1440px 페이지 가로 넘침 없음.
- 타입/lint/build 및 별도 회귀 시험4개 통과.

## 의도된 차이와 후속 범위

기존 관리 메뉴·모바일 접힘 메뉴·EdgeAI 브랜드를 유지한다. API가 없는 참조 기능인
서비스 지연 차트·네트워크 열·예약량은 표시하지 않는다. 이 범위에서 남은 P0/P1/P2는 없다.
P3: 최신 Kubernetes 관측을 연결하면 실행 상태/할당 가능량의 미확인 영역을 줄일 수 있다.
후속 검증: IP 주소의 HMR 문제는 Next16 origin 차단으로 확인됐다. allowedDevOrigins에
127.0.0.1과192.168.0.56을 추가한 뒤 실제 사용자 주소192.168.0.56:13080에서
10개 노드 표시·로딩 종료·콘솔 오류/경고0개를 확인했다. 근거: `output/playwright/node-ip-origin-fixed.png`.
