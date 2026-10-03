# M7 SERVICE 규격·Runner 스트림 실행 검증

2026-10-03 KST. [ADR0037](../adr/0037-service-stream-runner-execution.md).
공개 STREAM 실행은 여전히501이다. 아래는 Runner 소비 경로와 SERVICE 계약의 검증이다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| SERVICE 파서·claim 직렬화 포함 서버 단위 | 20261003T061629Z-26796dd3 | PASS/0, 94개·실패/skip0 |
| 실제 PostgreSQL 전체·STREAM 오실행 차단 | 20261003T061815Z-6954a8e3 | PASS/0, 154개·실패/skip0 |
| OpenAPI 5종 생성·MVC/Swagger·패키징 | 20261003T062441Z-d7ae6f36 | PASS/0 |
| 전체 Runner·신호 잠금 회귀 포함 | 20261003T062516Z-d24b0832 | PASS/0, 101개·87.288초 |
| 전체 HTTPS/TLS MQTT·Runner 실행8개 포함 | 20261003T062516Z-8b1096ae | PASS/0, 58개·97.777초 |
| 후속 VD 신호 수정·실제 감독 프로세스 회귀 | 20261003T062746Z-ec9e2eec | PASS/0, 16개·39.244초 |

전체 Runner101개 뒤 같은 결함을 VD에서 재현하고 수정했다. 마지막16개에는 양쪽 신호 시험2개와
VD 실제 프로세스14개가 포함된다. 현재 전체 Runner 시험 수는102개이며 로컬 전체102개를
한 번에 실행했다는 뜻은 아니다. HTTPS/MQTT58개 이후의 VD 수정은 해당16개로 다시 검증했다.
파일/스트림 예제2개와 실행 제어 schema는 로컬 Draft202012Validator 검사도 통과했다.
Flyway migration과 제품 UI는 변경하지 않았다.

## 실제로 실행한 경로

- 독립 Runner가 HTTPS claim/배정을 받고 인증된 Session과 실제 TLS MQTT broker에 연결한다.
  실제 모델 subprocess가 두 입력4/5,2/3을 처리해9,14를 계산하고 END를 기록한다.
- 소비자가 ACK하지 않으면 입력 END가 처리되어도 완료 요청을 보내지 않는다. 마지막 출력
  ACK까지 외부 checkpoint에 확정되면 완료 요청을 보내되 WAITING 동안 결과 파일은 없다.
- 같은 checkpoint ID의 FINALIZE 이후에만 별도 `stream_result.py`가 최종 JSON을 만들고
  기존 Runner의 SHA/크기 검사·HTTP 업로드·commit으로 `{"sum":14}`를 전달한다.
- 503 재시도, 잘못된 완료 checkpoint, SERVICE보다 큰 배정 payload, 복원본 부재, 취소,
  모델 프로세스 실패, 최종 파일 생성 명령 실패에서 잘못된 성공 commit이 없다.
- 첫 Runner를 상태9 확정 후 SIGKILL하고 작업 볼륨을 삭제한다. 동일 Attempt/경로의 명시적
  RESTORE로 고정 checkpoint를 내려받은 새 Runner가2/3을 이어 처리해 최종14를 만든다.
  별도 실행 `20261003T061937Z-5fecd8a6`도 PASS/0이며 이후 전체58개에 포함했다.

HTTPS 배정/완료 장벽/checkpoint 객체 저장은 명시적인 **시험 서버 fixture**다.
실제 Spring 완료 장벽·실제 S3·Kubernetes Pod의 종단 시험이 아니다. MQTT·SQLite·프로세스·
TLS·최종 파일·업로드 데이터 검증은 실제다. 실제 Spring/DB/S3 checkpoint API의 선행 증거는
[인계 검증](m7-stream-checkpoint-handover.md)에 보존한다.

## 발견한 취소 잠금 문제

`061815Z-9692e87d` 전체57개 중 취소 시험이15초 안에 종료하지 못했다. 같은 시험의 앞선
단독 통과를 근거로 무시하지 않았다. Main thread가 Event.wait의 condition 잠금을 가진 채
SIGTERM을 받으면 signal handler의 Event.set이 같은 잠금을 다시 획득하려 한다.
실제 stack은 threading.py의 `__enter__`→`set`→signal handler에서 멈췄다.

실제 Runner main 진입점에서 잠금 소유 중 SIGTERM/SIGINT를 전달하는
`062300Z-96b1302e`는 두 신호 모두 timeout으로 재현했다. signal callback이 잠금 없이
취소 flag만 남기게 바꾸고, 제한 시간 있는 wait·watchdog이 flag를 관측하도록 했다.
`062410Z-8e6d8faa` 신호 시험과 `062410Z-b08e23ee` 실제 스트림 취소 시험이 PASS다.
위 전체101/58개도 수정 뒤 통과했다. SDK 안에서 발생한 취소·전체 기한 만료는 Runner의
CANCELLED/TIMEOUT 사유로 유지한다.

같은 코드를 쓰던 VD supervisor도 `062659Z-98d50cea`에서 두 신호의 잠금 재진입을 재현했다.
동일한 Cancellation owner를 적용한 뒤 마지막16개 회귀가 통과했다. 실패 증거는 삭제하거나
성공으로 재분류하지 않는다. 새 신호 시험은 CI의 실제 Runner 이미지에서도 실행된다.

## 남은 연결

새 STREAM live port에 대한 DataRoute·Device 입력, Task 동시 시작, 서버 배정·구성 요소 완료
장벽, peer journal 인계, 운영 TLS/CA 설정, 스트림 단계 측정, 공개 API/UI와 실제 Kubernetes
다중 장치 수용이 남는다. M5 상태형 offload·실제 외부 계약, M8 부하, M9 운영/복구/보안,
M10 실제 장비 수용도 미완료다. 이번 결과로 전체 목표나 M7을 완료 처리하지 않는다.
