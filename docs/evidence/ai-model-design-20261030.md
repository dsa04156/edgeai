# AI 모델 관리·자원 측정 화면 (2026-10-30, 최종 상태)

사용자가 첫 배포 화면을 거절하고 "모델 등록 → 자원 요구량 측정 → 측정 완료 후 사용 가능" 흐름을 요구했다. 이 문서는 프런트엔드의 최종 상태와 검증 결과를 정리한다. 화면 기준은 `.interface-design/system.md`의 "2026-10-30 AI 모델 관리" 절이다. API·DB 계약은 backend 작업(`contracts/openapi/platform-api.yaml`, 생성 `dashboard/lib/api-schema.d.ts`)이 소유한다.

## 이전 화면의 문제(운영 13080 실측)

등록 양식이 목록 아래에 펼쳐져 PC 약 1,700px, 모바일 약 5,000px였다. 저장소·태그 입력이 중복되고 버튼이 5개였으며, 안내 문단이 7개였다. 선택 항목인 실행 구성이 이미지와 요구량 사이를 끊었다. 모바일 요구량 표에서는 '최소' 칸만 보였다. 이전 화면은 `before-pc-register.png`·`before-mobile-register.png`에 있다.

## 최종 구성

- **목록**: 상단바 `모델 등록`, 검색, NativeRecords 표. `확정 CPU · 메모리 요청`·`확정 가속기`는 `availability.profile.requirements`만 쓴다. 미확정이면 `—`이며, 등록 시 선언값은 섞지 않는다. 빈 상태에는 등록 버튼이 있다. 사이드바의 `자원 프로파일`은 메뉴에서 빼고 경로를 유지한다(툴바 보조 링크).
- **등록 Drawer**: 기본 정보 → 실행 이미지(저장소·태그/digest KsSelect allowCreate, 확정 즉시 digest 확인, 플랫폼) → 접힌 실행 조건·설명. 요구량은 입력하지 않으며 `resources`를 보내지 않는다. footer는 고정이고, 닫아도 초안과 재시도 키를 유지한다.
- **상세 `자원 요구량`**(`ModelProfilingPanel.vue`): availability 배지와 주 액션 하나.
  - 측정 시작 Drawer: 노드, 시험 할당, HTTP 추론 요청 또는 벤치마크 명령, 300초 기본.
  - 진행: 3초 폴링, 취소.
  - 실패: 사유를 한국어로 표시.
  - 성공: 추천값으로 요청·최대만 채우고 최소는 빈칸. 수정하면 사용자 지정이 된다.
  - 확정 revision 표. 재측정이 진행 중이거나 실패해도 기존 revision을 유지한다.
  - 종단 run의 cleanup이 DONE이 될 때까지 시작 버튼을 비활성화하고 정리 중임을 표시하며, DONE까지 폴링한다.
- **가속기 단위**: 수는 그 자원 이름의 할당 단위다. `nvidia.com/gpu.shared`는 "공유 슬롯"으로 표기하고 물리 GPU 개수라고 쓰지 않는다.
- **BFF**(`device-registration.ts`): 모델별 고정 경로만 허용한다(GET options·runs·run·profiles, POST runs·cancel·profiles). 측정 요청 envelope은 512 KiB로 제한한다.

## 검증

| 항목 | 결과 |
|---|---|
| `pnpm typecheck` / `lint` / `test` / `build` | 0 / 0 / 159 통과 / 성공 |
| Playwright `ai-model` + `resource-profile` + `model-profiling`(로컬 13190, fixture) | desktop·mobile 28/28 |

주요 브라우저 시험(`tests/model-profiling.integration.spec.ts`, `tests/ai-model.integration.spec.ts`)은 다음을 확인한다.

- 측정 전 → Drawer(할당·포트 빈 시작, 아키텍처가 다른 노드 비활성) → 409 후 같은 멱등 키로 재전송 → 진행 요청 수 → 성공 후 cleanup PENDING 동안 `다시 측정` 비활성·정리 문구 → DONE 폴링 후 활성.
- 추천 채움과 최소 빈칸, 메모리 수정 시 사용자 지정 → 확정 POST 본문 일치 → 사용 가능.
- 요구량 없이 등록한 모델의 목록에 확정 `460m · 768Mi`와 `nvidia.com/gpu.shared` `1 공유 슬롯` 표시.
- 재측정 실패 시 r1 유지, 서버 비활성 사유와 시작 비활성·포커스 복귀, 390px에서 확정 3칸 가독.
- 등록: 요구량·가져오기 입력 없음, 실패 시 초안·키 유지, 응답 역전 방지, 상세 닫기.

단위 시험은 run 요청 검증, basis 규칙, cleanup 판정, 가속기 단위 문구, 확정 요약, BFF 경로·메서드·크기 제한을 다룬다.

## 검토 1차 반영 (2026-10-30, UI)

`/tmp/edgeai-profiling-spec-review.md`의 UI 지적 두 건을 반영했다. backend 계약(`ModelProfilingInput`, OpenAPI)은 바꾸지 않았다.

- **HTTP 요청 형태**(`model-profiling.ts` `runRequest`): 이제 GET 요청에는 `contentType`·`body`·`bodyBase64`를 넣지 않는다. 앞서 POST 본문을 입력했더라도 마찬가지다. POST의 `application/json`·`text/plain` 본문은 `body`로 보낸다. 빈 본문과 NUL 문자는 거절하고, UTF-8 64 KiB를 넘어도 거절한다. `application/octet-stream`은 `bodyBase64`로만 보낸다.
- **바이너리 입력**: 전용 `추론 요청 본문(Base64)` 칸을 두었다. `파일에서 불러오기`(64 KiB 초과 파일은 읽지 않음)로 고르면 표준 Base64로 인코딩해 채운다. 칸 아래에 파일명과 디코딩 후 바이트 수를 표시한다. 붙여 넣은 줄바꿈은 제거한다. 표준 Base64가 아니거나(`-_`, 길이 오류 등) 디코딩 후 64 KiB를 넘으면 전송 전에 거절한다. 텍스트 본문과 바이너리 본문은 초안에서 서로 다른 필드라, 본문 형식을 바꿔도 다른 형식의 값을 잘못 보내지 않는다.
- **가속기 관측 상태**(`deviceStatus()`): 확정 전 결과에 `result.deviceStatus`의 상태와 서버가 준 사유를 그대로 표시한다. `devices: []`인 `UNSUPPORTED`도 포함한다(예: "가속기 장치 지표 없음 — 시험 노드에서 기존 exporter가 … 시험 할당값만 기록합니다."). 그래서 가속기 수량을 관측한 요구량으로 오해하지 않는다. `NOT_REQUESTED`(CPU 전용 시험)일 때는 아무것도 표시하지 않아 가속기 측정이 실패한 것처럼 보이지 않는다. 이 필드가 생기기 전에 저장된 GPU 결과는 `UNSUPPORTED`로 만들어 표시하지 않고 "관측 상태 미기록"으로 표시한다.
- **결과 안내**: 이전에는 `result.notices`(예: CPU p95가 시험 할당량의 90% 이상)와 `limitations`가 접힌 "추천 근거·한계" 안에 있었다. 이제 notices는 경고 목록 `측정 결과 주의`로, limitations는 `관측 한계` 목록으로 항상 보인다. 접힌 영역에는 추천 근거만 남겼다. 초기 부하 구간 제외 안내는 아직 backend 응답에 없다. backend가 이를 notices·limitations로 주면 그대로 표시한다(별도 UI 변경 불필요).
- 등록 → 측정 → 확정 → 사용 가능 흐름, cleanup 대기 중 시작 비활성, 목록의 확정 요구량, 공유 슬롯 용어는 바꾸지 않았다.

### 계약 대조 시험

- `tests/model-profiling-contract.ts`: backend `ModelProfilingInput.run/load`의 거절 규칙을 옮긴 것이다(필드 집합, 정수 범위, 경로, GET 본문 금지, body/bodyBase64 배타, octet-stream↔bodyBase64, `java.util.Base64` 형식, 64 KiB). 단위 시험과 Playwright mock이 같이 쓴다. Playwright mock은 이 규칙을 통과하지 못한 시작 요청에 400을 돌려준다. 그래서 잘못된 형태를 mock이 받아 주는 일이 없다.
- 단위(`model-profiling.unit.ts`): GET·JSON·text·binary 요청이 mirror를 통과하는지와 정확한 형태를 확인한다. mirror가 검토에서 지적된 두 형태(GET+contentType, octet-stream+body)를 거절하는지도 확인한다. 그 밖에 Base64 형식·크기 경계(65,533 bytes 허용, 65,538 bytes 거절), `encodeBase64`가 Node Buffer와 일치하는지, deviceStatus 4가지 경우를 다룬다.
- Playwright(`model-profiling.integration.spec.ts`): 폼을 실제로 조작해 GET, text/plain, octet-stream 파일 선택(`AAEC/w==`, "digit.bin · 디코딩 후 4 bytes")을 보내고 요청 본문 전체를 대조한다. 잘못된 Base64는 전송 전에 거절된다(POST 0건). GPU `UNSUPPORTED`+`devices: []`이면 사유·notice·한계가 보이고 가로 넘침이 없다. CPU 전용 결과에는 "가속기" 문구가 나오지 않는다.
- 변이 확인: `bodyBase64`를 `body`로 되돌리면 octet-stream 시험이 400으로 실패하는 것을 확인했다. 확인 후 원복했다.

| 항목 | 결과 |
|---|---|
| `vue-tsc --noEmit`(kestra-native) / `eslint kestra-native tests lib` | 0 / 0 (경고 0) |
| `pnpm test`(unit 전체) | 162/162 통과 |
| Playwright `model-profiling` + `ai-model` + `resource-profile`(로컬 vite 13190, fixture) | desktop·mobile 40/40 |

## 스크린샷

경로는 `output/evidence/ai-model-design-20261030/`(git 제외, 로컬 fixture)이다. PC 1440×1000과 모바일 390×844로 찍었고, 모든 화면에서 가로 넘침 0, JS 오류 0이었다.

- 목록·등록: `*-1-empty`, `*-2-register-top`, `*-3-register-image`, `*-4-register-optional`, `*-7-list`, `*-8-detail`, `*-9-detail-requirements`, `*-10-detail-unmeasured`
- 검토 1차: `test-results/…UNSUPPORTED-reason-and-the-notices-{desktop,mobile}/unsupported-device.png`(Playwright 산출물, 확정 폼 요소 캡처)
- 측정: `*-12-measure-none`, `*-12-measure-start-drawer`, `*-13-measure-running`, `*-14-measure-confirm`, `*-15-measure-ready`, `*-16-measure-cleanup`, `*-17-list-confirmed`

## 남은 사항

- 초기 부하 구간 제외(warm-up 제외) 안내 문구는 backend가 notices/limitations에 넣어야 화면에 나타난다. UI는 받은 문자열을 그대로 표시한다.
- 계약 mirror(`tests/model-profiling-contract.ts`)는 backend 파서를 손으로 옮긴 것이다. backend 검증 규칙이 바뀌면 함께 갱신해야 한다.

- 프런트엔드는 backend API와 함께 배포해야 한다. 운영 API는 아직 `resources`가 필수라서, 이 화면만 배포하면 등록이 400으로 실패한다.
- 실제 Registry·클러스터를 거친 등록·측정은 하지 않았다(운영 쓰기 금지). 배포 후 GET 화면을 다시 확인하고, edge Pod IP 도달성(`DRIVER_UNREACHABLE`)도 확인해야 한다.
- `test-docs.sh`의 API index 차이는 backend OpenAPI 변경 몫이다(`--write-api-index`).
- 기존 `ResourceProfilesPage`의 `--ks-content-*` 상태 색은 런타임에 정의되어 있지 않다. 소유 밖이라 수정하지 않았다.
