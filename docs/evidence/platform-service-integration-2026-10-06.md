# Platform-Service 원본 구조 연동 검증 — 2026-10-06

## 확정 범위

사용자의 “같이 연동되도록”, “원래구조 그대로” 요청에 따라 원본 DDS ReactFlow 화면과
FastAPI → 로컬 YAML → Gitea → Argo CD 경로를 연결했다. 수동 Buildx 명령은
배포 이전의 별도 API 단계로 추가했다. 기본 `/workflows`는 이 화면이며 기존 Spring
DAG 편집기와 API는 별도 진입 경로에서 유지한다.

`/home/jinuk/codex-work/jinuk/Platform-Service`는 읽기만 했다. 해당 소스의 DDS
Dockerfile·IDL·Python·XML·Buildkit 문맥을 가져왔다. 기존 인증 설정은 무시되는
로컬 `.env`에서 서버만 읽도록 옮겼으며 문서·브라우저·계약에는 비밀값을 넣지 않았다.

## 실행한 검사

- `scripts/test/test-platform-service.sh`: Python 모의 연동 검사 11개, OpenAPI/생성 타입
  일치 검사, Next.js 프록시 검사 1개 통과.
- Python 검사는 최초 생성과 SHA 갱신, 외부 단계 순서, Git 실패 시 Argo 중단,
  Git 성공 후 Argo 부분 실패, 다른 대상 앱 덮어쓰기 거절, YAML·경로 검증,
  Buildx 고정 명령·digest·실패·시간 제한·동시 요청 거절, 삭제 순서를 확인했다.
- 프록시는 원본 요청 method/body 전달, 서버 토큰 주입, 브라우저 응답에 토큰 없음,
  허용 경로, origin·JSON·크기 제한, 비활성화와 미설정 응답을 확인했다.
- Dashboard ESLint, TypeScript, 프로덕션 빌드 통과. 새 셸 진입점 문법 검사와
  `git diff --check` 통과.
- 기존 `192.168.0.56:13080`을 독립 Playwright 브라우저로 열어 **브라우저 내부에서만
  Platform-Service API 응답을 대체**했다. 센서 노드 3개 + Discovery 드래그 추가,
  노드 이름 수정, Buildx → save-and-push 호출 순서와 YAML digest를 확인했다.
- YAML 다운로드 → 페이지 새로고침 → 다시 가져오기에서 노드 4개·연결 2개 복원.
  이후 센서 파이프라인 추가 시 노드 ID와 이름 충돌 없이 7개가 됐다.
- Buildx 실패 시 Git 저장 호출이 발생하지 않았고, Delete App이 DELETE로 전달됐다.
  네이티브 확인 대화상자의 발생을 먼저 확인하고 자동 시나리오에서는 확인 응답을 대체했다.
- 브라우저 예외 0개. 모바일 390px에서 문서 폭 390px. 최소 줌을 조정해 전체 노드가
  모바일 캔버스 안에 들어오도록 했다. 데스크톱·모바일 캡처를 직접 검토했다:
  `output/playwright/platform-service-desktop.png`, `platform-service-mobile.png`.

## 실제 환경 확인 및 남은 범위

읽기 전용 Gitea·Argo OpenAPI 조회로 기존 서비스의 API 경로를 확인했다.
이는 인증된 Git 쓰기나 Argo 배포 성공을 의미하지 않는다.
현재 사용자로 `docker buildx ls`를 실행하면 Docker 소켓 접근이 거부된다.
실제 빌드에는 Docker 접근 가능한 실행 계정과 기존 레지스트리용 builder 설정이 필요하다.

사용자가 앱을 실행한다는 지시를 유지했다. FastAPI·Dashboard·Spring 시작/재시작,
Docker 빌드·이미지 푸시, Gitea 저장소 변경, Argo 앱 생성·삭제와 클러스터 변경은
수행하지 않았다. 실제 연동 완료 여부는 사용자가 API를 실행하고 Dashboard가 새 환경을
읽은 뒤 확인해야 한다. 기존 DDS 템플릿의 합성 센서 데이터, 토픽 기반 통신,
노드 배치·호스트 포트 제약은 원본대로이며 물리 EdgeX 센서 연결로 주장하지 않는다.
