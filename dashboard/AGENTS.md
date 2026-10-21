# Dashboard

- 콘솔은 Kestra v2.0.6 원본 Vue 앱에 EdgeAI 화면을 붙인 `kestra-native/`뿐이다. 기존 Next.js 화면·BFF 소스는 제거했으며 다시 추가하지 않는다.
- 루트에서 `bash scripts/test/test-ui.sh`로 단위 시험·lint·typecheck·release 빌드를 검증한다. Kestra UI 소스는 `scripts/dev/prepare-kestra-native.sh`가 준비한다.
- 실행: 루트에서 `bash scripts/dev/dev-dashboard.sh`(13083 Vite). release는 `corepack pnpm build` 후 `corepack pnpm --filter @edgeai/dashboard start`(기본 13080, `output/kestra-native/current`)로 실행한다.
- 색·간격 기준은 `.interface-design/system.md`를 따른다. 원본 Kestra 구성 요소를 우선 사용한다.
- API 타입은 루트 `corepack pnpm contract:generate`로 생성한다. `lib/api-schema.d.ts` 직접 수정 금지.
- 서버 토큰·secret은 브라우저 bundle에 넣지 않는다. Kubernetes·DB를 UI에서 직접 제어하지 않는다.
- 단위 시험은 임시 로컬 upstream만 사용한다. 공유 `.env`나 운영 저장소에 연결하지 않는다.
- 초기 화면에서 미구현 기능을 실제 자원/성능으로 표시하지 않는다.
