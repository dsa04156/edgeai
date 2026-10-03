# ADR 0037: SERVICE 스트림 계산과 Runner 최종 파일 생성

상태: Runner 소비 경로 구현. 서버 배정·완료 장벽과 공개 실행은 후속 연결 대상.

## 결정

SERVICE의 선택적 `stream`에 지속 계산 command/args, 필수 입력 포트, 출력 포트,
stepTimeoutSeconds와 journal 한도를 선언한다. `edgeai.stream-workload/v1`을 사용한다.
루트 command/args와 inputs/outputs는 파일 계약을 유지한다. 루트 명령은 최종 상태를
`EDGEAI_STATE_FILE`로 받아 사용자 결과 파일을 생성한다. 루트 파일 입력은 이 최종 명령에
제공하며 스트림 모델의 초기 입력으로 암묵적으로 주입하지 않는다. 각 방향의 파일/스트림
포트 이름은 겹칠 수 없다. 스트림 없는 기존 SERVICE의 동작과 work volume 계산은 같다.

`stream`과 `recovery.mode=CHECKPOINT`는 함께 선언한다. RESTART 자동 offload를
CHECKPOINT에 허용하지 않는다. 새 상태형 offload 수용은 M5의 별도 연결·검증을 요구한다.
스트림 work reserve는 `6*(maxBufferBytes+maxStateBytes)+32MiB`이며 기존 파일 예산과
합쳐 1GiB 이하다. journal/WAL·snapshot 후보·복원 staging·모델 상태를 위한 예약이다.
실제 메모리·디스크 최대 사용량과 부하 수용은 M8의 측정 대상이다.

## Runner 순서

1. 기존 producer claim으로 SERVICE 규격과 파일 입력을 받는다.
2. 인증 `POST /internal/v1/attempts/{attemptId}/streams/execution`의 READY를 기다린다.
   서버는 현재 Run/Task/Attempt/epoch, 모든 포트의 generation과 NEW/RESTORE/HANDOVER를
   반환한다. Runner는 정확한 포트 집합, 신원, 배정 media type/크기 한도를 검증한다.
   RESTORE/HANDOVER에 확정본이 없으면 빈 상태로 시작하지 않는다.
3. Session이 실제 모델 subprocess·MQTT·heartbeat·EXTERNAL checkpoint를 소유한다.
   API·저장소·broker는 검증된 TLS를 사용한다. 자격을 최종 파일이나 로그에 쓰지 않는다.
4. 모든 입력 END와 모든 출력의 마지막 처리 ACK가 **최신 서버 확정 checkpoint**에
   포함되어야 `terminal_receipt`를 얻는다. 로컬 settled만으로 최종 명령을 실행하지 않는다.
5. `POST .../streams/complete`에 그 checkpoint ID를 보내고 동일 ID의 FINALIZE를 기다린다.
   대기 중 Session을 계속 유지한다. peer의 확정 후 경로 회수와 경합하면 동일한 영속 허가를
   다시 확인한다. 취소·기한 만료·잘못된 checkpoint 응답은 허가를 대신하지 않는다.
6. Session을 정리하고 상태 hash를 대조한 0600 파일을 최종 명령에 전달한다.
   기존 output 검증·S3 업로드·Result commit 경로로 사용자 결과를 확정한다.

배정·완료 응답은 [소비 계약](../../contracts/streams/execution-control.schema.json)에 정의한다.
두 새 경로의 서버 구현은 아직 없으며 공개 Swagger의 실행 가능 API로 노출하지 않는다.
Runner claim에는 선택적 stream 직렬화를 추가했지만, RuntimeLifecycle의 실제 dispatch는
STREAM_NOT_IMPLEMENTED로 차단하여 스트림 SERVICE를 BATCH 최종 명령으로 오실행하지 않는다.

## 서버 연결의 남은 필수 작업

- Device 입력 binding과 SERVICE live port로 DataRoute를 구성하고 현재 generation을 배정한다.
- STREAM 연결 구성 요소의 Task를 동시에 시작하며 BATCH 선행 조건을 지킨다.
- Task terminal checkpoint와 Device END 처리 확인을 영속화한다. 구성 요소 전체의 완료
  장벽을 통과하기 전에는 어느 Task도 Result 성공으로 peer 권한을 끊지 않는다.
- 완료 허가는 일부 peer가 성공·경로 회수된 뒤에도 해당 현재 producer가 조회할 수 있어야
  한다. Run 전체 장벽은 BATCH 하위 실행을 막으므로 사용하지 않는다.
- Task/Device/이웃 journal 인계, 운영 TLS broker·control/storage 신뢰 설정, API/UI,
  실제 Kubernetes 다중 장치 종단 수용을 연결한다.

시험 서버의 완료 허가·checkpoint object 저장은 fixture다. 실제 Runner/model 프로세스와
HTTPS/TLS MQTT 시험을 서버 장벽·실제 S3·Kubernetes 전체 수용 근거로 대체하지 않는다.
상세 증거는 [실행 연결 검증](../evidence/m7-service-stream-runner.md)을 따른다.
