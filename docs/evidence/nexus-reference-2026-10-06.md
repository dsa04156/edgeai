# NEXUS 참조 적용 및 노드 표시 수정

사용자 지정 참조: http://aggregator.192.168.0.56.sslip.io/
사용자는 앱 실행을 직접 담당하며, 별도 Playwright 브라우저를 통한 참조 캡처와 검증을 승인했다.

## 원인

13080의 `/api/control-plane/node-metrics`는 AVAILABLE과 실제10개 노드를 반환했다.
반면 `/api/control-plane/nodes`를 전체 페이지 조회한 결과2406개 중2396개는 REMOVED,
10개는 STALE 시험 기록이었다. 실제 장비 이름의 기록도2026-10-02 시점의 REMOVED였다.
기존 개요는 REMOVED를 제외한 DB 목록에 메트릭을 붙여 실제 노드가 표시되지 않았다.

## 수정

- Prometheus 응답을 기준으로 모니터링 노드를 표시한다. 제거된 기록·빈 DB·별도 시험 기록이
  실제 메트릭을 가리지 않는다. DB 데이터는 수정하거나 삭제하지 않았다.
- 최근60초의 유일한 동일 이름 Kubernetes 기록만 실행 상태에 사용한다. 관측이 없으면 미확인이다.
- NEXUS의200px 남색 사이드바, 남색 상단바, 파란 선택 상태, 연한 배경, 요약 카드4개,
  전체 자원 표, 모바일2열 행 구성을 적용했다. 기존 메뉴·프로필·VD·장치 기능은 유지했다.
- 아이콘6개는 참조 화면이 실제 사용한 Bootstrap SVG를 로컬에 저장했고 MIT LICENSE를 포함했다.
- 서비스 규격 목록은 개요 진입/수동 새로고침 때 조회해 자동10초 조회마다 전체 페이지를 다시 읽지 않는다.

## 검증

- 실제 사용자 실행 서버 `http://localhost:13080`에서 노드10개와 정상 메트릭 표시를 확인했다.
- GPU필터5개, NPU필터2개, 전체10개 복귀를 DOM 행 수로 검증했다.
- 모바일 메뉴 열기, Escape 닫기와 버튼 포커스 복귀, 상단 `jetorn` 검색 → 노드 목록1개를 검증했다.
- 320/390/768/1440px에서 document scrollWidth가 viewport를 넘지 않았다.
- 마지막 localhost 페이지 콘솔: Errors0, Warnings0.
- `tsc --noEmit`, lint, production build, diff 검사는 exit0.
- Node 내장 테스트4개: 빈/제거된 노드 DB, 대량 시험 기록, 최신/중복/만료 실행 기록, 메트릭 실패.

회귀 시험 재실행(모든 셸 명령은 `rtk proxy` 사용):

```bash
# dashboard 디렉터리
corepack pnpm exec tsc tests/node-monitoring.unit.ts --outDir ../.tools/node-monitoring-tests --module commonjs --moduleResolution node --target ES2022 --esModuleInterop --skipLibCheck --strict --jsx react-jsx
# 저장소 루트
node --test .tools/node-monitoring-tests/tests/node-monitoring.unit.js
```

시각 근거와 비교 이력은 [design-qa.md](../../design-qa.md)에 있다.

## 경계

앱 서버를 시작/재시작하거나 배포하지 않았다. 기존 브라우저 E2E 전체를 실행한 결과가 아니다.
최초 검증에서는127.0.0.1:13080의 HMR 오류가 있었으며 localhost에서만 성공했다.
후속 사용자 신고로192.168.0.56에서도 같은 증상을 확인하고 아래와 같이 수정했다.
로컬 DB의 많은 등록 건수는 실제 응답 값이며 시험 데이터를 임의로 숨기거나 삭제하지 않았다.
참조 사이트의 네트워크 지표·예약량·서비스 지연 차트는 이번 API가 제공하지 않아 복제하지 않았다.

## 후속: IP 접속 로딩 고정 해결

사용자의 실제 주소192.168.0.56:13080에서 표 행0개, 로딩 중, API 요청0개를 재현했다.
기존 메트릭 API 자체는10개 노드를 반환했다. 동일 서버에 WebSocket Upgrade를 요청하면
localhost는101,127.0.0.1과192.168.0.56은 `Unauthorized`였다.
설치된 Next16의 `block-cross-site-dev.js`와 `allowedDevOrigins` 문서에서 origin 차단을 확인했다.

`dashboard/next.config.ts`의 `allowedDevOrigins`에127.0.0.1과192.168.0.56을 추가했다.
별도 시작/재시작 명령 없이 실행 중인 개발 서버가 설정을 자동 반영했다.
수정 뒤 localhost와 두IP의 Upgrade는101, untrusted.invalid는 계속 Unauthorized였다.
192.168.0.56 화면은10개 행·loading=false·메트릭 요청2개 및 콘솔 오류/경고0개를 확인했다.
화면 근거: `output/playwright/node-ip-origin-fixed.png`. 타입·해당 파일 lint·diff 검사 exit0.
