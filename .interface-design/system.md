# EdgeAI M0 화면 기준

개발자가 플랫폼의 최소 실행 상태와 아직 구현하지 않은 범위를 확인하는 화면이다.
장치·Profile·Workflow·Attempt·Runtime·Result의 흐름을 중심에 두고 가상의 자원 통계는 표시하지 않는다.

- 색상: 장비 랙의 밝은 회색 `#f4f6f7`, 패널 흰색 `#ffffff`, 그래파이트 `#1b2933`, 보조 텍스트 `#425461`, 메타 텍스트 `#61717b`, 신호 녹색 `#166b59`.
- 깊이: 얇은 경계선만 사용, 장식용 그림자·그라데이션 없음.
- 간격: 4px 배수, 본문 최대 960px, 데스크톱 32px/모바일 16px 여백.
- 계층: 상태/본문 14–16px, 섹션 18px, 제목 28–36px. 시스템 한글 폰트, 흐름에 monospace.
- 특징: Profile → Device → Workflow → Runtime → Result 흐름 띠.
- 상태: API readiness 조회 성공만 연결 확인됨; 실패·timeout은 연결 대기 중. 실시간 클러스터 관측은 아직 없음.
- KPI 카드, 가짜 사이드바 메뉴, 구현 전 실행 버튼은 추가하지 않는다.


## M1 Profile 화면 확장

개발자가 규격을 발행하고 정확한 버전·digest를 확인하는 화면. 등록→목록→불변 버전
흐름을 중심으로 기존 회색/녹색, 960px 폭, 경계선 깊이 체계를 유지한다.
종류는 native radio, 목록은 semantic table, 등록은 label이 연결된 form을 사용한다.
패널 padding 24px(모바일16px), 입력/버튼 최소44px, 필드 gap20px, 버튼 radius4px.
오류 토큰 --error #a03030 / --error-surface #fff1f0; 입력 경계 #b7c3ca,
focus 녹색2px outline+3px offset. JSON/digest는 monospace와 overflow 처리.
인증 전/대기/빈 목록/오류/발행/충돌/상세 상태를 각각 실제 응답에 연결한다.
