# 실제 MQTT 관리 응답 경합

branch 소스d2c250b의 CI37221887410에서 `competingLateGrantsCannotRestoreRevokedGeneration`
시험이 예상 REVOKED 대신 INVALID_RESPONSE로 실패했다. 다른 native Runner job 성공과
구분하며 이 branch 전체를 통과로 판정하지 않는다.

로컬 재현 첫 실행 `175751Z-49875be6`은 Mosquitto 실행 경로 누락으로 fixture 시작에
실패했으므로 경합의 재현 근거가 아니다. 로컬 추출 바이너리/라이브러리 경로를 사용한
12회 `175854Z-67b5ba1f`는 PASS였다. 격리 PostgreSQL·실제 TLS Mosquitto에서80회 반복한
`180109Z-585a08ed`의 JUnit 결과도 PASS였지만, 임시 진단으로 다음 콜백 예외1개를 확인했다.

```text
NullPointerException — MosquittoStreamBroker$Connection.lambda$new$0(MosquittoStreamBroker.java:154)
```

해당 줄은 `expected.get()!=null` 확인과 두 번째 `expected.get().equals(...)`를 연속
수행했다. 콜백 외 호출 스레드의 finally가 중간에 expected를 null로 바꿀 수 있다.
첫 검사 결과만으로 두 번째 참조의 생존을 보장할 수 없다. 회수 직후 콜백이 실행되면
테스트 본문의 최종 결과가 성공이어도 예외가 남는 경계도 확인했다.

ADR0096의 요청별 correlation/큐와 단일 snapshot 적용 후 같은80회
`180704Z-af18f0f5` PASS, 콜백 진단 예외0개다. 수정 전후 동일한 진단을 사용했으며
전체 응답·메시지·자격은 출력하지 않았다. 독립 DB/브로커 종료·정리도 확인했다.
임시 진단을 제거하고 회귀의 반복을12회로 정한 최종 코드의 전체 broker14개
`181144Z-36506086`도 PASS(failure/error/skip0)다. 실제 TLS·권한 회수·broker 재시작·
실DB 권한 전환·SDK 연결을 포함하며 소유 DB/브로커 정리를 확인했다.
최종 단위122개 `181439Z-4e038d42`도 failure/error/skip0으로 통과했다.

CI 실패의 원인이 된 전체 권한 정책을 완화하지 않았다. 각 응답은 원래 명령/correlation에만
사용되며 늦은 응답은 다음 요청의 큐에 들어가지 않는다. 전체 M9 수용이나 새 이미지 배포
완료를 이 로컬 구성 요소 결과로 판정하지 않는다.
