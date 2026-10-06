# Dashboard

- 루트에서 `bash scripts/test/test-ui.sh`로 lint/typecheck/build/브라우저 검증.
- 실행: 루트에서 `bash scripts/dev/dev-dashboard.sh`.
- Next.js App Router + TypeScript strict. 색·간격 기준은 `.interface-design/system.md`를 따른다.
- API 타입은 루트 `corepack pnpm contract:generate`로 생성한다. `lib/api-schema.d.ts` 직접 수정 금지.
- secret은 `NEXT_PUBLIC_*`에 넣지 않는다. Kubernetes·DB를 UI에서 직접 제어하지 않는다.
- 초기 화면에서 미구현 기능을 실제 자원/성능으로 표시하지 않는다.
- Playwright 테스트 전 `corepack pnpm --filter @edgeai/dashboard exec playwright install chromium` 필요.
