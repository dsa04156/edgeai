# 프론트 다듬기 검증 — 2026-10-06

## 범위

사용자는 프론트 개선을 우선하도록 요청했다. 기존 미커밋 운영 콘솔 개편 위에서
레이아웃·가독성·모바일·등록 진입·빈 상태를 수정했다. 이 작업은 백엔드 및 인증 변경,
워크플로 재활성화, DB 변경, 원격 배포를 수행하지 않았다. 기존 작업 트리의 변경은 보존했다.
UI 토큰과 컴포넌트 기준은 `.interface-design/system.md`에 기록했다.

## 정적 검증

- `rtk proxy corepack pnpm --filter @edgeai/dashboard lint`: exit 0, 최종 경고 없음.
- `rtk proxy corepack pnpm --filter @edgeai/dashboard typecheck`: exit 0.
- `rtk proxy corepack pnpm --filter @edgeai/dashboard build`: exit 0, 최종 Next.js 16.3.8 production build.
- `rtk proxy git diff --check`: exit 0.

## 브라우저 확인

Playwright CLI 독립 세션 `frontend-polish`. 최종 production preview `127.0.0.1:13082`,
로컬 API `127.0.0.1:18080`, `EDGEAI_WORKFLOW_ENABLED=false`.
기존 13080 개발 서버는 HMR WebSocket 오류와 초기화 미완료가 관측되어 최종 확인에 사용하지 않았다.
개발 서버 문제의 원인 해결을 주장하지 않는다.

- 실제 API 조회로 `/`, `/profiles`, `/devices`, `/devices?view=nodes`, `/virtual-devices`, `/audit` 확인.
- 각 경로에서 320/390/768/1024/1440px 너비를 검사: 30개 조합 모두 페이지 가로 넘침 없음.
  위 검사 동안 `pageerror` 0. 데이터 변경 요청 없이 목록을 조회했다.
- 390px에서 Profile/Device/VD 등록 버튼 → details 열림, key 입력 포커스, 가로 넘침 없음.
- SERVICE Profile 상세에서 비활성 워크플로 링크 없음. 기존 규격 복사 → 폼 열림, JSON 유지.
- 모바일 메뉴 열기 → 링크 포커스 → Escape → 메뉴 닫힘 및 메뉴 버튼 포커스 복원.
- 브라우저에서 API 503을 모의: 개요 숫자 `—`, 오류 안내, 다시 조회 동작 확인.
- 브라우저에서 성공한 빈 목록을 모의: 개요 숫자 `0`, 노드 빈 상태와 다음 단계 링크 확인.
  모의 응답을 제거하고 실제 API 화면으로 복귀했다. 서버 장애/복구 검증을 의미하지 않는다.

스크린샷은 로컬 `output/playwright/`에 있다:

- `polish-overview-desktop.png`, `polish--profiles-desktop.png`
- `polish--devices-desktop.png`, `polish--devices-view-nodes-desktop.png`
- `polish--virtual-devices-desktop.png`, `polish--audit-desktop.png`
- `polish-profiles-form-mobile.png`, `polish-devices-form-mobile.png`, `polish-virtual-devices-form-mobile.png`
- `polish-overview-empty-mobile.png`, `polish-overview-error-mobile.png`

## 남은 검증 문제

기존 전체 자동 시험을 `test:e2e --max-failures=2`로 실행했으나 exit 1이다.
`audit.spec.ts`에서 제거된 사용자 이름 입력란을 기다려 timeout, 공개 관리 프록시에
이전 401을 기대해 실제 503(API 비연결 시험 설정)과 불일치했다. 2개 실패 후 중단되어
48개는 미실행이다. 기존 로그인/인증 계약을 전제로 한 시험의 정비가 남아 있으며,
전체 E2E 통과나 전체 플랫폼 수용 완료를 주장하지 않는다.
