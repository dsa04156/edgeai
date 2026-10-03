# M7 인증된 스트림 배정 검증

2026-10-03 KST. [ADR0026](../adr/0026-stream-authenticated-bindings.md)의 Device 세션 토큰과
현재 Device/Runner에 대한 MQTT 배정 조회를 구현했다. 실제 HTTP·PostgreSQL·TLS Mosquitto를
연결했고 기존 worker 시험도 재실행했다. Kubernetes Pod 신원 확인은 이 시험에서 명시적
RuntimeGateway fixture이며 실제 Pod 스트림 처리나 S3 checkpoint 복원 수용을 뜻하지 않는다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 HTTP/DB/TLS broker: 배정5개 + broker/worker14개 | 20261002T233324Z-eba4bc70 | PASS/0,19개·실패/skip0 |
| 단위/MVC | 20261002T233845Z-5739ca26 | PASS/0 |
| 기존 전체 PostgreSQL | 20261002T233759Z-224be33a | PASS/0,148개·실패/skip0 |
| OpenAPI5개·생성 타입·패키징·MVC26개 | 20261002T234339Z-355b82f4 | PASS/0 |
| 실제 API/DB→관리 화면 PC·모바일10개 | 20261002T234042Z-e216e91f | PASS/0 |
| 최종 Swagger 관리 API40개·실제 등록/자동 CSRF, PC·모바일2개 | 20261002T235454Z-257998d9 | PASS/0 |
| 최종 스트림 Swagger2개·실제 요청·PC/모바일 CLI | 20261002T235604Z-00ab889c | PASS/0 |
| VD lease/drain 기한 제한·만료 및 기존 Task12개 | 20261003T000319Z-49233286 | PASS/0,실패/skip0 |

## 확인한 경계

- 실제 HTTP Basic/CSRF로 현재 세션 토큰을 발급하고 Device Bearer·Runner HMAC/Pod 증명으로
  각각 배정 정보를 받았다. 응답의 CA·접속 정보·각 주체의 자격만으로 TLS broker에 연결해
  실제 payload를 전달했다. 관리 topic 구독은 거절됐고 응답에 관리 비밀번호/개인 키가 없었다.
- Device 토큰은 현재 Device/session/epoch/boot에 묶인다. 다른 장치/세션/route, 잘못된 epoch,
  세션 교체·장치 해제·Run 취소·만료 세대는 거절한다. 오래된 인증 principal의 직접 서비스 호출도
  잠금 안에서 재검사한다. Device 토큰으로 관리 API에 접근할 수 없다.
- Runner는 Attempt HMAC과 Pod 증명을 모두 요구한다. 다른 Attempt·다른 Pod·잘못된 세대와
  MQTT 비밀번호를 Runner Bearer로 사용한 요청은 거절한다. DB route/현재 실행 주체를 함께 검사한다.
- 실제5초 lease 만료 뒤 배정을 거절하고 이 조회가 lease를 연장하지 않는 것을 확인했다.
  VD는 실제 DB에서 runtime 만료·supervisor lease·drain 중 더 이른 기한으로 제한되며 만료 후
  인증을 거절했다. drain 직후와 후속 poll이 lease를 더 짧게 만든 경우를 모두 확인했다.
  Device 요청은16KiB, 응답은256KiB로 제한한다. 알려지지 않은 필드와 잘못된 UUID를 거절한다.
- 별도 Device 서명 키의 파일 권한·symlink·크기/형식을 검사하고, 서비스 재생성 뒤 같은 세션의
  토큰 유지와 키 교체·주체 변경 시 거절을 단위 시험했다. CA는 인증서만 재인코딩해 응답한다.
- 관리 문서는40개, 스트림 문서는2개 operation을 표시한다. 최종 관리 Swagger로 실제 Profile을
  등록하며 CSRF가 유지되는 것을 확인했다. 스트림 문서의 Try it out은 실제 비활성 경로에서403을
  받고 관리용 CSRF 요청을 보내지 않았다. 두 문서의 전환 링크·역할/오류 설명을 확인했다.
- Playwright CLI의1280×960·390×844 화면을 직접 확인했고 모바일 가로 넘침·page error·브라우저
  저장소에 남은 자격은 없었다. favicon404와 의도한 비활성 요청403은 HTTP 응답이며 JS 오류와 구분한다.
  로컬 화면 증거는 `output/playwright/stream-docs-{desktop,mobile}.png`다. 임시 브라우저 자격 파일을
  삭제하고 시험 API/브라우저를 종료했다. 시험 소유 broker 잔여0, 기존 V1–V20 변경 없음을 확인했다.

최초 단위 실행 `20261002T233611Z-5548fe29`는 새 MVC 시험의 개발 비밀번호 property 누락으로
context 초기화에 실패했다. 기존 MVC 시험과 같은 시험 전용 property를 추가하고 위 최종 실행이
통과했다. 운영 설정이나 기본 인증을 완화하지 않았다.

추가 VD 시험 `20261003T000001Z-6a8cf08a`는 poll 이후의 배정 기한이 drain 종료 시각과 정확히
같다는 시험 가정 때문에 실패했다. 기존 VDPollService는 drain까지 남은 초를 내림하여 더 짧은
lease를 저장한다. drain 직후에는 종료 기한, poll 이후에는 더 짧은 lease를 적용하는 것을 각각
검증하도록 시험만 수정했고 위 최종12개가 통과했다. 운영 기한 계산은 변경하지 않았다.

## 배포와 남은 범위

선행 worker `cd61529`의 CI37077442217은5 jobs·결과JSON17개를 모두 통과했다.
실제 kind BATCH/Remote/VD·복구·S3 결과20+5개와 생성한 클러스터 정리 로그를 확인했다.
GitOps `2410f10`의 실제 배포 검증은 `20261002T235701Z-9ed76739` PASS/0이다.
API/Dashboard/MinIO의 정확한 imageID·Ready, PVC Bound, Argo Synced를 확인했다.
전체 Argo health는 기존 Ingress 상태 때문에 Progressing이다.
이 선행 CI에는 이번 인증 배정 변경이 없으며 새 소스 CI·배포 확인은 별도로 수행한다.

인증 배정 소스 `337abb3`을 main에 push했다. CI37080508316의 scaffold/runner/storage는
success이며 내려받은 scaffold 결과JSON9개가 모두 PASS/0이다. 실제 broker/배정은
`20261003T000548Z-ceeddf2d`이고 XML의 새 배정5개·실패/skip0을 확인했다.
현재 images job은 실제 Kubernetes 수용을 실행 중이다. 전체 CI·새 이미지 배포 완료 판정은 보류한다.

`EDGEAI_STREAM_BINDINGS_ENABLED`와 stream worker는 기본 비활성이다. 배정은 기존 ACTIVE 세대의
현재 유효기간을 조회할 뿐 생성/갱신하지 않는다. SDK의 lease 준수·양쪽 생존 확인/갱신,
운영 broker·실제 Runner/SERVICE의 스트림 인터페이스, checkpoint/새 Pod 복원,
공개 실행 API/화면과 실제 Kubernetes 다중 장치 수용은 남아 있다. 공개 STREAM 실행501을 유지한다.

재현: 실제 PostgreSQL과 Mosquitto/OpenSSL을 준비하고 `bash scripts/test-stream-broker.sh`를 실행한다.
이 명령은 CI scaffold에도 포함된다. 기존 스크립트로 unit/integration/contract를 각각 실행하고
`bash scripts/test-profiles-stack.sh none`으로 실제 관리 화면 회귀를 확인한다.
