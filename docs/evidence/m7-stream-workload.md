# M7 지속 계산 프로세스·journal·watchdog 검증

2026-10-03 KST. [ADR0029](../adr/0029-stream-workload-process.md)의 구성 요소를 실제 Linux
프로세스·TLS Mosquitto·SQLite와 Spring 인증 경로로 검증했다. 공개 STREAM 실행은 아직501이며
이 시험을 실제 Kubernetes 스트림 workload·물리 센서·모델 정확도 수용으로 확대하지 않는다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| Runner·VD·SDK·journal·실제 프로세스 전체 | 20261003T015358Z-5934ba4f | PASS/0,69개·72.863초 |
| 실제 프로세스·pipe·watchdog·VD session·반복 종료 | 20261003T015807Z-b89c26a1 | PASS/0,8개 |
| 실제 TLS MQTT 기존14개+계산 통합12개 | 20261003T015610Z-bbcac546 | PASS/0,26개·31.789초 |
| 실제 Spring 인증·DB·broker·Python 계산 및 기존 worker | 20261003T015042Z-1edb329c | PASS/0,22개·실패/skip0 |
| JSON Schema 및 실제 참조 모델 WAIT/COMMIT/COMPLETE | 20261003T015823Z-69a20347 | PASS/0 |

## 확인한 동작

- 같은 모델 PID가 두 입력을 기다린 뒤4+5=9, 이어2+3을 더해14를 계산했다. WAIT는 동일
  checkpoint/입력으로 재호출하지 않는다. join 의미는 SERVICE 계산 프로그램이 결정한다.
- journal 출력 공간이 가득 차면 확정 전 후보 하나만 유지한다. 입력 위치·상태는 그대로이고
  계산 호출 횟수도 증가하지 않는다. 소비자 처리 ACK로 공간이 생기면 같은 후보를 확정한다.
- 동일 볼륨으로 프로세스를 다시 열면 확정된 상태9와 미확인 출력을 복구한다. 기존 입력을
  재계산하지 않고 이후1+2를 더해12를 계산한다. 다른 parameters로 재개하면 digest 검사에서 거절한다.
  이는 동일 볼륨 복원이며 S3 checkpoint·새 Attempt/generation/Pod 복원의 증거는 아니다.
- 하나의 출력 port를 실제 두 MQTT route로 분기했고 마지막 DATA와 END를 한 checkpoint에
  저장했다. 별도 journal 시험은16개 출력 route의 마지막 DATA+END32개를 원자적으로 저장한다.
- 로컬 계산과 출력 ACK가 끝나도 MQTT Link를 유지한다. END ACK가 유실된 upstream이 재연결해
  재전송하면 같은 처리 위치를 답하며 checkpoint와 계산 호출 횟수는 바뀌지 않는다.
- 잘못된 revision·소비 순번·다른 출력 port·과대 payload·조기 COMPLETE·상태를 바꾸는 WAIT·
  중복 JSON 속성은 입력/상태/출력을 확정하기 전에 거절하고 계산 프로세스를 종료한다.
- 부모가 실제 HTTP 응답에 대기하는 동안에도 독립 watchdog이 lease 만료 시 모델을 종료한다.
  늦은 refresh로 살리지 못하며 checkpoint는0, source 미확인 입력은 보존한다.
- 계산 timeout·취소·stdout 초과·프로세스 오류와 pipe 용량을 넘는 응답을 검증했다. 계산에는
  control-plane/S3 환경 자격을 전달하지 않는다. 표준 오류와 원문 응답도 증거에 기록하지 않는다.
- 실제 별도 process group 자손을 종료·회수하고 무관한 다른 session은 유지했다. VD Task처럼
  Runner 자신이 session leader인 실제 fixture에서도 Runner는 살아 있고 모든 계산 자손만 종료됐다.
- Spring이 실제 발급한 Device/Runner 배정으로 원래8초 기한을 넘긴 뒤 지속 Python 모델에서
  4+5=9와 END를 처리했다. checkpoint revision3·계산 요청3·source 처리 ACK·자격 미저장을 확인했다.
  이 Spring 시험의 Kubernetes Pod 신원 확인은 명시적 RuntimeGateway fixture다.

## 실패와 수정

`20261003T014342Z-5c3a98b4`는 반복 close 시 이미 종료한 PID/PGID로 SIGKILL을 다시 보내는
경로를 재현했다. PID 재사용 뒤 다른 프로세스에 영향을 줄 수 있으므로 종료 소유권을 한 번만
사용하도록 수정했다. 최초7개 재검증014454Z-4362043d와 VD session을 추가한 최종8개가 통과했다.

schema 로컬 검사015715Z-c46a7e16은 기존 jsonschema 환경에 `referencing` 모듈이 없어 실패했다.
설치된 RefResolver로 바꾼015807Z-4454f785는 상대 schema 파일명만 등록하고 canonical `$id`를
등록하지 않아 참조 해결에 실패했다. 두 식별자를 로컬 resolver에 등록해 최종 검사를 통과했다.
제품 계약이나 검증 조건을 완화하지 않았다.

## 재현과 남은 연결

Linux에서 `bash scripts/test/test-runner.sh`로 실제 프로세스/VD/journal 시험을 실행한다.
해시 고정 `runner/requirements-stream.txt` 환경을 `EDGEAI_STREAM_PYTHON`으로 지정하고,
실제 Mosquitto/password/openssl을 준비해 `bash scripts/test/test-stream.sh`를 실행한다.
해당 스크립트와 기존 CI job은 이제 `test_stream_*.py` 전체를 선택한다.
실제 PostgreSQL·Mosquitto control/dynamic-security 환경의 `bash scripts/test/test-stream-broker.sh`는
Spring→Python 지속 계산 probe를 포함한다. 요청/응답 schema와 합성4+5 예제는 `contracts/streams/`다.

운영 broker·SERVICE Profile/Runner entrypoint·공개 Run/API/Swagger/UI, S3 checkpoint와 새 Pod 복원,
실제 Kubernetes의 다중 Device BATCH/STREAM 데모는 계속 남는다. M5 잔여와 M8–M10도 미완료다.
이후 확인한 CI·이미지·배포는 아래 기록을 따른다.

## 후속 CI·배포 확인

소스cf4876c의 CI37088407643은5 jobs 모두 success이며 내려받은 JSON17개가 PASS/0이다.
Runner container69개020316Z-e1847a4e·실제MQTT26개020502Z-45539873과 실제 kind
021515Z-7da2c6e7의 BATCH/Remote/VD·복구·S3 결과20+5개·생성한 클러스터 삭제를 확인했다.
kind는 기존 실행 회귀이며 공개 STREAM의 Kubernetes 수용이 아니다.

GitOps7fad529의 실제 배포023616Z-25449368은 sourcecf4876c의 정확한 API/dashboard/MinIO
imageID·Ready·PVCBound·ArgoSynced와 VD 활성화를 확인했다. 전체 Argo health는Progressing이다.
후속 자동 Session 변경의 CI·배포와는 구분한다.
