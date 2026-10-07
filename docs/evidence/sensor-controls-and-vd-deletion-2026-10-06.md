# 센서 측정·제어 / VD 영구 삭제 검증 — 2026-10-06

## 구현과 범위

사용자가 기존 EdgeAI API·웹의 빠진 기능 추가를 요청했다. 기존 Spring domain/adapters/app,
Next.js 프록시, NEXUS 화면 스타일을 유지했다. 센서 이력·명령 API 3개와 VD 등록 삭제 API
1개를 계약·생성 타입·감사 식별자·웹까지 연결했다. 정상 GET 폴링은 감사 기록을 늘리지 않는다.
기존 누락된 infrastructure 감사 경로도 계약 일치 검사에서 발견해 등록했다.

센서 데이터는 EdgeX Core Data, 명령은 Core Command, 장치 잠금 상태는 Core Metadata에서
읽는다. 제공된 명령 URL을 따라가지 않고 설정된 서버 주소만 사용한다. 읽기 명령도 사용자
버튼으로 실행하며 쓰기는 값 확인과 CSRF가 필요하다. 클라이언트/서버 자동 재시도는 없다.
0·빈 이력·숫자가 아닌 값·조회 실패·오래된 측정을 구분하며 그래프는 실제 시각 간격을 쓴다.
UInt64/Uint64의 안전한 정수 범위를 벗어나는 값은 문자열로 보존하고 그래프로 반올림하지 않는다.

V43은 기존 VD INSERT/UPDATE/TRUNCATE 보호를 유지하고 DELETE에만 미사용 검사 트리거를
추가한다. RELEASED이고 실행·작업 참조가 없는 등록과 닫힌 원본 연결 기록만 제거한다.
완료된 runtime/operation 이력도 보존하며, 참조가 있으면409이다. 물리 장치/프로필은 보존한다.

## 실행 결과

- `:app:test`: 164개, 실패/오류/skip 0.
  센서 HTTP adapter 7개, 센서 MVC 3개, VD MVC 4개를 포함한다.
- `scripts/test/test-registry-deletion.sh`: 무작위 이름의 별도 PostgreSQL DB에 V43까지
  적용. VirtualDeviceIntegrationTest11 + RegistryDeletionIntegrationTest4 = 15개 통과.
  미해제 삭제 거절, 해제 후204/재삭제404, 닫힌 연결 삭제, 원본/프로필 보존,
  실행 이력의 API/직접SQL 삭제 거절과 기존 동시 등록·버전 보호를 검사했다.
  테스트 DB는 정상 제거했고 앱 DB에는 migration을 적용하지 않았다.
- 센서 그래프·값 표현 단위3개와 Next 프록시1개 통과. 0, NaN/문자열 제외, 시각 정렬,
  과거/미래 시각, 큰 정수 보존, API method/query/body/CSRF 전달과 다른 origin 거절을 확인했다.
- OpenAPI 생성 타입/패키징 계약 일치, Dashboard 타입·ESLint·프로덕션 빌드,
  셸 문법과 diff 공백 검사 통과.
- EdgeX 배포 설정 검사6개 통과. 추가 네트워크 정책은 edgeai namespace의 API Pod에만
  Core Data59880/Core Command59882 접근을 허용한다. 클러스터에는 적용하지 않았다.

## 브라우저

기존13080을 독립 Playwright CLI로 열고 센서 이력/명령과 VD 삭제 응답을 브라우저에서만
대체했다. 처음 검사는 실제 EdgeX 센서 목록에 대체된 상세 응답을 연결했다.
후속 검사에서 초기 연결 완료 전에 탭을 눌러 초기값에 덮인 타이밍 문제를 확인했고,
`view=sensors` 진입과 연결 완료 대기를 사용해 재검증했다.

- 측정0 표시, 읽기 전용의 쓰기 버튼 없음, 직접 읽기 응답, 쓰기 확인 전 요청0회/확인 후1회.
- 이력 조회502 후 마지막 데이터 유지와 오류 표시.
- VD 참조409 후 행 보존, 미사용204 후 행 제거. 실제 DELETE는 운영 API로 보내지 않았다.
- 모바일390px 문서 폭390px, 브라우저 예외0. 모바일 그래프 축과 VD 이름/삭제 버튼을
  함께 볼 수 있도록 반응형을 조정하고 캡처를 직접 검토했다.
- 캡처: `output/playwright/sensor-detail-desktop.png`, `sensor-detail-mobile.png`,
  `vd-permanent-delete-mobile.png`.

## 실제 환경과 미수행 범위

EdgeX Metadata/Command 목록과 Core Data 장치별·항목별 최근 측정값을 읽기 전용 GET으로
확인했다. 온도·단위·나노초 origin을 확인했으며 실제 읽기/쓰기 장치 명령은 실행하지 않았다.
현재 Arduino/Sense HAT 프로필은 모두 readWrite=R이며 쓰기 버튼이 없는 것이 정상이다.
쓰기 지원 명령 시나리오는 모의 EdgeX와 브라우저 응답으로 검증했다.

로컬 `.env`에는 Core Data/Command Service IP 설정을 준비했다. Spring/Next 시작·재시작,
운영 DB 변경, 물리 장치 제어, 클러스터 배포는 수행하지 않았다. 사용자의 백엔드 재실행 후
V43 및 새 API가 반영된다. 전체 플랫폼 E2E·실장비 모델·성능·복구 수용 완료를 의미하지 않는다.
