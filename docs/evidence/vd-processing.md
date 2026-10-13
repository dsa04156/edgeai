# 사용자 정의 VD 공통 처리 검증

## main 통합 검증 (2026-10-13)

기존 VD·DEVICE 정리를 보존한 연속 처리 전용 통합이다. 백엔드 단위 180개, 실제 PostgreSQL 통합 175개, 관련 PC·모바일 웹 14개, 실제 eKuiper 연동·공개 프록시·Go race와 웹 빌드 검사를 통과했다. 최대 200개 실행의 중지 대기 슬롯을 종료 확인까지 예약하며, 미실행 장치의 중지 요청은 즉시 완료한다. 201개 미실행 중지 요청 및 용량 경계의 중지·새 실행 거부·종료 확인 후 새 실행을 공개 HTTP와 실제 DB로 검증했다. 검사별 기능 경로와 운영 미검증 항목을 구분한다.

독립 Standards·Spec 리뷰에서 발견한 잘못된 FFT 안내와 중지 대기 용량 문제를 수정했다. 비필수 create/update 구성 중복은 남아 있다. 최종 CI와 병합 상태는 PR #5를 기준으로 확인한다. 실제 처리 Agent·이력 저장소 배포와 활성화는 수행하지 않았다.

## 통합 전 프로토타입 검증 기록 (2026-10-13)

사용자의 웹 반영 요청으로 활성 호스트 `192.168.0.56:13080`의 Dashboard와 필요한 관리 API를 최신 검증 빌드로 교체했다.
이전 배포에는 가상디바이스 페이지·controller가 없어 404였고, 교체 후 페이지와 catalog는 200으로 응답한다.
별도 loopback 포트에서 실제 API/DB·desktop/mobile 화면을 먼저 확인했으며 기존 프로세스 환경·이전 실행 파일과 권한 제한 DB 백업을 보존했다.
기존 물리 센서 17개의 ID/UID가 유지되고 연속 catalog는 `enabled=false`다. 현재 조회된 물리 CRD namespace는 `edgeai-devices`다.
Kubernetes 리소스·Mapper·NanoMQ·eKuiper·history 배포는 변경하지 않았다. 연속 엔진 운영 수용은 여전히 미완료다.

아래 개발 기록은 main 통합 전 프로토타입의 검증이다. main 통합에서는 기존 VD BATCH/supervisor를 복원하지 않고 연속 처리만 연결하며 별도 회귀를 수행한다. 이전 운영 웹은 통합 전 빌드이므로 최종 병합 소스와 구분한다.

- 실제 Mosquitto → Go agent → 실제 full eKuiper 2.4.2 → 결과 API 테스트에서 scalar·AVG·MIN·MAX·boolean·filter와 다른 VD 입력의 창 격리를 확인했다. race detector를 사용했다. 실제 broker와 공개 경로로 Mapper 세션 70회 교체 후 이전 세션의 새 수신 시각 재전송이 새 값을 오염시키지 않는 회귀도 통과했다.
- Mapper의 노드 영속 카운터를 재열기·시계 역행·동시 32개 할당·잘못된 상태로 검증했다. 실제 HTTP 센서 → Mapper → 실제 Mosquitto/gRPC DMI 경로에서 기존/qualified/저장 상태 손상/잠금 점유 네 경우 모두 물리 읽기 횟수와 raw 발행 횟수가 같았고 DMI Twin 보고는 1회였다. qualified 원래 관측 시각·적용 설정·보정 값·단위·세션·순서를 확인했다.
- 실제 별도 PostgreSQL에서 이력 저장·중복 억제·이력 테이블 장애 중 최신 결과 유지·복구 뒤 소급 저장 없음·누락 구간·세대별 그래프 집계를 확인했다. Kubernetes 인증 gateway는 실제 HTTP TokenReview/Pod 응답 fixture로 현재 Pod UID·소유권·audience·계정 불일치 거부를 확인했다.
- continuous 센서 목록 GET은 CRD를 변경하지 않는다. 명시적 등록·수정 POST/PATCH만 트랜잭션과 입력 잠금 뒤 적용 식별자를 준비한다. route가 없는 센서도 기존 UID·model·측정 항목과 대기 사유를 목록에 보존한다.
- 공개 Dashboard desktop/mobile 테스트에서 입력·출력 여러 개 등록, 오류 후 초안 유지, 독립 출력 3행, 실제 출력 상태와 저장 누락, 그래프 구간, 시작·중지 UI를 확인했다. 전체 UI suite에는 현재 인증 화면과 맞지 않는 기존 테스트 실패가 있어 전체 통과로 기록하지 않는다.
- 실제 Helm release는 `my-ekuiper`, namespace는 `ekuiper`이다. 공식 chart 1.4.0을 대상으로 post-renderer의 고정 버전·노드·이름, 기존 PVC 보존, localhost 관리 포트, 전용 agent 인증·probe·Service와 반복 렌더 결과를 확인했다. 운영에는 적용하지 않았다.
- 최종 백엔드 단위 204개, 실제 PostgreSQL 통합 262개는 실패·오류·skip 없이 통과했다. 관련 웹 desktop/mobile 16개와 lint·타입 검사·production build, 내부/공개 OpenAPI 계약 검사도 통과했다. Component 렌더링과 임시 비밀자료 생성 CLI의 파일 권한·TLS hostname·기존 값 덮어쓰기 거부를 확인했으며 임시 자료는 제거했다.

운영 사전 점검은 CG002 저장 경로의 실제 디스크 여유를 읽을 SSH 경로가 없어 BLOCKED(exit 2)였다. Docker daemon 접근도 없어 새 컨테이너 image build/push 및 운영 rollout은 수행하지 않았다. 실제 17개 qualified 입력, 프로토콜별 전체 수용, 500/200/500 부하, 저장 예산 보정과 실제 24시간 soak는 미검증이다. 운영 기능 기본값은 비활성으로 유지한다. 최신 개발 검사 결과와 task 기준 파일 목록은 `output/continuous-vd/implementation-status.md`와 `task-changed-paths.json`에 기록한다.
