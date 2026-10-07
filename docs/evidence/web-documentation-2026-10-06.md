# 웹 문서 화면 검증 — 2026-10-06

## 구현

사용자가 저장소 문서를 웹에서 읽기를 요청하고 `http://docs.192.168.0.56.sslip.io/`를
디자인 참조로 지정했다. 해당 사이트의 홈·본문 화면을 Playwright CLI로 캡처해 확인했다.
기존 Dashboard 메뉴에서 `/docs`로 이동하며 문서 화면은 별도의 밝은 상단바·홈·3열 본문을 사용한다.

서버가 `docs/`의 Markdown/YAML 및 명시된 README·PLAN·PROGRESS를 읽는다. 검색·분야 필터,
GFM 표·코드·목차·Mermaid, 문서 간 상대 링크, 빈 검색 결과·없는 문서·읽기 오류를 처리한다.
원시 HTML 실행, 임의 저장소 파일 제공, 심볼릭 링크 추적은 하지 않는다.
현재 안내와 작성 당시의 설계·검증·과거 기록을 구분한다.

## 검증 결과

- 문서 카탈로그 단위 테스트3개 통과: 실제 파일 수정 반영, 본문·분야 검색,
  숨김 파일/AGENTS/심볼릭 링크 제외, 상대 링크·앵커 변환, 임의 파일/위험 URI 거절, 원본 누락 오류.
- Dashboard ESLint·TypeScript·production build 통과.
- 최초 Next 파일 추적 설정은 문서를 포함하지 않았고, 수정한 glob은 기존 `.tools`의
  순환 symlink에 걸렸다. 저장소 전체 추적 대신 빌드 후 문서 전용 packaging을 추가했다.
  standalone 산출물의 문서 내용과 원본을 SHA-256으로 대조했다. `.env`는 포함하지 않았다.
- 기존13080 서버에서 Playwright CLI 검증: 제목·본문 검색/분야 제한, 검색 결과 없음,
  README의 PROGRESS 링크와 표, 아키텍처 Mermaid SVG, 목차 앵커, 과거 기록 안내,
  YAML, 없는 문서 안내, Dashboard 메뉴 왕복을 확인했다. 브라우저 예외0.
- 모바일390px에서 홈·본문의 가로 넘침 없음. 접힌 문서 목록·목차를 열고 닫을 수 있다.
  데스크톱·모바일 캡처를 직접 확인했다.

캡처: `output/playwright/docs-home-desktop.png`, `docs-reader-desktop.png`,
`docs-search-desktop.png`, `docs-home-mobile.png`, `docs-reader-mobile.png`.

## 미수행 범위

앱 서버를 시작하거나 수동 재시작하지 않았다. 기존 개발 서버의 변경 감지로 확인했다.
Docker 이미지를 빌드하거나 클러스터에 배포하지 않았다. Dockerfile에 문서 복사와
빌드 후 패키징을 연결했으며 최종 컨테이너 실행은 별도 확인 대상이다.
문서 수정은 저장소에서 한다. 브라우저 편집 기능은 이번 범위에 포함하지 않는다.
