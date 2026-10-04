# M9 원본 MQTT 권한 차단 검증

2026-10-04, ADR0070. `20261004T075002Z-74d8ac1a`의 실제 TLS Mosquitto2.0.18/Paho 검증
15개가 PASS다. 전용 로컬 브로커·합성 Device/Task 신원과 role을 사용했다. 관리 API와 장치
SDK가 발급한 신원 또는 실제 모델의 종단 시험으로 확대하지 않는다.

1. 실제 TLS에서 Device/Task가 인증하고 합성 payload를 전송·수신했다.
2. 잘못된 leaf 인증서 SHA를 거절하고 기존 연결을 유지했다.
3. 다른 broker digest를 거절하고 기존 연결을 유지했다.
4. 신뢰하지 않는 CA를 거절하고 기존 연결을 유지했다.
5. 무관한 client를 보존하고 관리자 교체/marker 생성 전에 거절했다.
6. 무관한 group을 보존하고 변경 전에 거절했다.
7. 기본 allow 정책을 임의 변경하지 않고 거절했다.
8. 다른 복구 marker를 보존하고 관리자 교체를 거절했다.
9. 관리자 비밀번호를 실제 교체한 뒤 첫 disableClient 직전 오류를 주입했다. 기존 관리자
   연결 종료·이전 비밀번호 거절·비공개 복구 상태를 확인했고, 두 실행 연결은 아직 살아 있었다.
10. 같은 복구 ID로 재개하여 실제 Device/Task 연결2개를 끊었다.
11. 이전 관리자·Device·Task 자격으로 새 연결을 시도하여 인증 거절을 확인했다.
12. 세대 role metadata/ACL 전체를 원래 값과 대조하고 새 자격 파일의0600을 확인했다.
13. 32개씩 조회하는 첫 페이지를 넘어 비접속33개를 포함한35개 계정의 차단을 개별 조회로 확인했다.
14. 다른 복구 ID가 이미 존재하는 비공개 state를 가져가지 못했다.
15. broker SIGKILL/재시작 뒤 같은 작업의 재확인, 비밀번호 교체·계정 차단·role 보존을 확인했다.

최종 보고서의 `disabledPrincipals=35`, `livePrincipalDisconnects=2`,
`controllerDisconnectConfirmed`, `originalRolePreserved`, `restartPreserved`,
`ownedBrokerStopped`, `ownedClientsStopped`를 확인했다. 시험 프로세스와 Paho 연결을 정리했다.
공개 요약에는 관리자/실행 비밀번호가 없음을 검사했다. 비공개 상태·TLS 개인 키·예외 원문은
`.tools`에 남기고 Git/CI artifact에서 제외한다.

초기 `20261004T073528Z-d6a5031b`는 mosquitto_ctrl 경로 누락으로 브로커 시작 전 FAIL이었다.
기존 로컬 경로를 명시한 `073635Z-2d54f778`의12개가 PASS했고, 공통 deadline 및 최종 관리자
거절 재검사·정책/group 거절을 추가한 `073951Z-181ce39e`의14개도 PASS다.35개 계정의
페이지 경계를 포함한 위15개가 최종 근거다. 실행 스크립트는 필수 브로커
도구가 없으면 BLOCKED로 알린다.

CI scaffold에 동일15개 게이트와 비밀값 없는 요약 수집을 추가했다. 이 새 게이트는 완료된
선행5ed7b03 CI37185328851에 포함되지 않으며 후속 CI에서 검증해야 한다. 권한 파일의 프로세스 재시작
보존을 확인했으며 payload/journal·디스크 유실·인증서 교체·여러 복구 관리자의 동시 변경 수용은
검증하지 않았다. 원본 DB/Kubernetes·Remote·S3 writer와 종합 복구 활성화, M9 전체·RPO/RTO
수용은 남는다.
