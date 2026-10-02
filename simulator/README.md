# Remote 참조 계산 시뮬레이터

`remote_server.py`는 별도 Python 프로세스·실제 HTTP·SQLite·파일로 RemoteGateway 계약을 시험한다.
자동 시험은 저장소 루트에서 `bash scripts/test-remote.sh`로 실행한다. Python3와 JDK21이 필요하다.
시험은 임시 디렉터리/임의 포트/임시 자격을 만들고 종료 시 프로세스를 정리한다. PostgreSQL은 필요 없다.

프로토콜은 [remote-reference-api.yaml](../contracts/openapi/remote-reference-api.yaml),
경계와 후속 연결은 [ADR0010](../docs/adr/0010-remote-adapter-boundary.md)을 따른다.
실제 2세부 API·OCI 실행·GPU/NPU·하드웨어 성능 수용을 대신하지 않는다. `sourceMode=SYNTHETIC`이다.

수동 실행이 필요하면 `.tools` 안의 비공개 파일에32~256자 base64url bearer를 준비하고 실행한다.
자격 파일은600, 상태 디렉터리는700 권한으로 관리한다. 토큰 값을 명령 인자나 로그에 넣지 않는다.

```bash
python3 simulator/remote_server.py \
  --state-dir .tools/remote/state \
  --token-file .tools/remote/provider.token \
  --ready-file .tools/remote/ready.json
```

항상127.0.0.1의 임의 포트에서 수신하며 `ready.json`에 포트만 기록한다. 같은 상태 디렉터리의 동시
서버는 파일 잠금으로 거절한다. SQLite에는 identity/spec/digest/상태/출력 metadata만 저장하고
실제 입출력 bytes는 전용 파일에 저장한다. 상태·완료 파일·취소 tombstone은 재시작 후 유지한다.
재시작 시 RUNNING은 FAILED/PROVIDER_RESTART, CANCELLING은 CANCELLED가 된다.

지원 계산은 `python3 /opt/edgeai/examples/linear.py` 계약의 `features·weights+bias`뿐이다.
이미지를 pull하거나 해당 경로의 임의 코드를 실행하지 않고 내장 합성 계산을 수행한다.
입력은 선택적 input/application/json1개, 출력은 output/application/json1개, 각1MiB 이하,
벡터1~4096개, 동시 계산8개까지다. SERVICE에 선언하지 않은 입력, 필수 입력 누락,
다른 명령·args·runtimeClass·nodeSelector·tolerations는 거절한다. resource/platform 필드는
요청 digest에 보존하지만 시뮬레이터가 해당 자원/아키텍처를 예약하거나 실행했다고 해석하지 않는다.

parameters의 `simulationDelayMillis`는0~60000의 장애 시험 전용 대기다. 성능 측정값이 아니다.
실행 마감은 예약 때의 `expiresAt`으로 고정하고 제공자 시각으로 강제한다. 이 참조 구현은
SERVICE.timeoutSeconds를 별도 타이머로 시행하지 않으므로 플랫폼 연결 시 더 짧은 마감을 예약해야 한다.
플랫폼의 공개 REMOTE Run과 RemoteWorker를 통한 연결은 [설정 문서](../docs/remote.md)를 따른다.
공개 API→실제 제공자→MinIO→Result는 로컬 검증했으며 실제 클러스터 배포에서는 Remote가 기본 비활성이다.

시험 전용 `--fault-file`은 비공개 로컬 JSON 파일로 `reserve_timeout_once`를 주입한다.
예약을 DB에 확정한 뒤 응답만 지연해 timeout→GET/replay 복구를 확인한다. 네트워크 장애 주입 API는 없다.
