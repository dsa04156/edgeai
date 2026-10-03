# M7 인증 체크포인트 다운로드·새 볼륨 복원 검증

2026-10-03 KST. [ADR0034](../adr/0034-stream-authenticated-volume-recovery.md)의 범위다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 HTTP client/publisher/인증 복원 | 20261003T044212Z-a7ccbd77 | PASS/0, 12개·7.259초 |
| 실제 TLS MQTT Session 복원·손상 거절·소켓 정리 | 20261003T044517Z-927da96e | PASS/0, 3개·5.284초·ResourceWarning 없음 |
| 실제 Spring/HTTP/PostgreSQL/MinIO·Python publisher/복원 | 20261003T044327Z-aec660ec | PASS/0, XML7개·실패/skip0 |
| 전체 Runner/VD/SDK 회귀 | 20261003T044548Z-98f28536 | PASS/0, 91개·83.612초 |
| 전체 실제 HTTPS/MQTT/Processor/Session 회귀 | 20261003T044548Z-516c0c41 | PASS/0, 42개·67.865초·ResourceWarning 없음 |
| 실제 Spring/PostgreSQL/TLS broker/Session 회귀 | 20261003T044548Z-e110e1e4 | PASS/0, XML22개·실패/skip0 |

## 직접 검증한 동작

- 소유 Session 디렉터리를 통째로 삭제한 뒤 인증된 최신 파일을 받아 계산 상태9를 복원한다.
  같은 두 입력 경로의2+3을 이어 처리해14를 전달한다. 복원 snapshot에 남은9 출력은 실제
  MQTT로 재전송되지만 sink의 저장된 커서로 중복 처리되지 않는다.
- 모든 입력의 END를 처리한 뒤 다시 볼륨을 삭제·복원해 완료 상태와 미확인 END 출력을
  이어간다. 모델을 다시 기동하지 않고 sink 처리 위치는3이며 END도 중복 처리되지 않는다.
- 응답 version·길이·MIME·압축·실제 SHA 오류와 redirect를 거절한다. API 자격이 GET/PUT
  저장소 요청에 전달되지 않는다. 빈 versionId를 추가한 중복 query도 거절한다.
- latest 없음, 실행 digest 변경, 저장소 다운로드 중 권한 만료, 다운로드 전후 latest 변경은
  새 journal을 만들기 전에 실패한다. 손상 파일은 workload 디렉터리도 생성하지 않는다.
- 실제 Spring/PG/MinIO 시험에서는 Python publisher가 상태9를 확정한 뒤 로컬 publisher
  디렉터리를 삭제한다. 같은 SDK가 실제 latest·S3 고정 version GET·latest 재검사를 수행하고
  새 journal의 상태9·확정 serial/SHA·입력 처리 위치1을 대조한다. 소유 MinIO와 임시 저장소를 정리했다.

## 실패와 수정 근거

최초 Session 시험 `20261003T044251Z-dd340613`은 복원 journal의 bootstrap guard와
Link guard 소유가 충돌해 실패했다. 같은 스레드에서 복원 완료 후 권한을 재검사하고
Link로 소유를 넘기도록 수정했다. 다음 `044320Z-fa116219`는2개 동작 시험은 통과했지만
프로세스 종료에서 소켓 ResourceWarning이 남아 종료 검증 완료로 판정하지 않았다.
각 시험 단독은 통과했고 두 시험을 함께 실행한 `044442Z-79e562d2`의 allocation trace가
Paho `loop()`의 내부 TCP socketpair를 가리켰다. 명시적 자원 정리를 추가한 위3개 시험은
경고 없이 통과했고 실제 fd 기준값 복귀도 대조했다.

## 수용 경계

TLS MQTT/계산 Session 시험의 API와 S3 응답은 명시적 fixture다. Spring/PG/MinIO 시험은
실제 SDK publisher/복원이지만 Pod 신원과 broker 활성화는 fixture이며 전체 Session은 아니다.
이 둘을 실제 Kubernetes STREAM 종단으로 합쳐 판정하지 않는다. 동일 Attempt·binding의
새 볼륨 복원이며 새 Pod 신원·Attempt·generation 인계, 운영 broker/SERVICE/Runner·공개 실행,
다중 장치 수용과 M5 잔여/M8–M10은 계속 남는다. 이 변경의 원격 CI/배포는 별도 확인한다.
