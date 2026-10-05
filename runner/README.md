# Runner

Python으로 Attempt claim → 입력 다운로드·SHA-256 확인 → 실제 자식 프로세스
실행 → 출력 업로드 → Result commit 요청을 수행한다. 내부 HTTP 인증/API, DB 상태 전이,
검증된 결과 확정, Kubernetes worker를 구현했다. 실행은 `EDGEAI_RUNTIME_ENABLED=true`로
명시적으로 활성화해야 한다. 현재 전용 배포는 활성화되어 있으며 M4 전체 경로와 M5 재시도·명시적
노드 전환은 실제 kind·CI·배포 검증을 통과했다. 측정 수집의 최신 검증 상태는 M5 증거를 따른다.

계약은 [Runner OpenAPI](../contracts/openapi/runner-api.yaml), 실행 규격은
[SERVICE schema](../contracts/profiles/service-execution.schema.json), 설계는
[ADR 0005](../docs/adr/0005-runtime-result.md)를 따른다. SERVICE 예시 이미지의 0으로 채운
digest는 자리표시자이며 실행 가능한 이미지 주소가 아니다.

## 실행 환경

| 변수 | 용도 |
|---|---|
| `EDGEAI_CONTROL_PLANE_URL` | 내부 API의 HTTP(S) origin |
| `EDGEAI_ATTEMPT_ID` | 실행할 Attempt UUID |
| `EDGEAI_ATTEMPT_EPOCH` | 현재 Attempt epoch |
| `EDGEAI_POD_UID` | 실행 Pod UUID |
| `EDGEAI_CLAIM_FILE` | Attempt 전용 인증 토큰 파일 |
| `EDGEAI_POD_TOKEN_FILE` | audience=edgeai-runner인 Pod-bound token 파일; 요청마다 다시 읽음 |
| `EDGEAI_WORK_DIR` | 비어 있는 쓰기 가능한 작업 디렉터리 |

워크로드에는 `EDGEAI_INPUT_DIR`, `EDGEAI_OUTPUT_DIR`, `EDGEAI_PARAMETERS_FILE` 경로를 전달한다.
입출력 파일명은 선언한 포트명이다. Runner는 shell 없이 command/args를 실행하며 timeout과
SIGTERM 때 프로세스 그룹을 종료한다. stdout/stderr에는 고정된 단계·오류 코드만 기록한다.
workload stdout/stderr는 저장하지 않는다. 출력은 일반 파일만 허용하며 심볼릭 링크를 거절한다.

이미지는 Python 3.13의 고정 digest를 사용하며 UID 10001로 실행한다. Kubernetes compiler는
읽기 전용 root filesystem, 작업 volume, 제한된 권한과 token 파일을 구성한다. 실제 Kubernetes
Runner→Control Plane→MinIO→Result의 AUTO/NODE·BATCH·취소·재시작·전환 경로를 실제 kind에서 검증했다.

## 구성 요소 시험

```bash
bash scripts/test-runner.sh
docker build -f runner/Dockerfile -t edgeai-runner:verify .
EDGEAI_RUNNER_IMAGE=edgeai-runner:verify bash scripts/test-runner.sh
```

첫 명령은 호스트 Python 자식 프로세스를, 마지막 명령은 실제 컨테이너를 사용한다.
둘 다 격리된 HTTP 프로토콜 fixture를 사용하므로 실제 Control Plane/MinIO/kind 연결 시험과
구분한다. `examples/linear.py`는 합성 입력으로 CPU 계산을 수행하는 참조 workload이며
학습 모델·GPU/NPU·실장비 성능 수용시험이 아니다.
참조 계산의 선택적 `parameters.simulationDelayMillis`는 정수0~60000이며 Remote 참조 제공자와 같은
장애/전환 시험 대기다. 실제 계산 전 기다리고 Runner timeout·취소가 그대로 적용된다. 성능 지표가 아니다.

시험은 결과 byte·digest, commit 재전송, 큰 정수/소수 보존, 잘못된 입력, 누락/링크 출력,
timeout, SIGTERM, 거절된 claim을 다룬다. 현재 증거는 [M4 기록](../docs/evidence/m4-runtime.md)을 따른다.

## 실행 측정

claim 응답의 telemetry.intervalSeconds가 있을 때 Runner는 실행 중 cgroup v2 CPU 사용 시간·quota,
메모리 사용량·제한을 읽어 내부 telemetry API로 보낸다. CPU/메모리 통계는 Runner와 workload가
속한 cgroup의 값이다. cgroup 미지원·측정 불가·무제한 quota는 null이며 Node 잔여량이나 GPU/NPU
사용량으로 해석하지 않는다. 프로덕션 sampler는 자기 cgroup membership을 해석하고 host root로 대체하지 않는다.
STREAM도 계산 시작부터 최종 파일 생성까지 측정하며 한 Attempt의 표본 sequence를 이어간다.
측정 권한이 차단되면 스트리밍 계산도 종료한다. [STREAM 자동 전환 검증 범위](../docs/evidence/m7-stream-automatic-offload.md).

파일 생성 workload에는 `EDGEAI_TELEMETRY_FILE`도 전달한다. 서비스가 직접 측정한 지연을 보고하려면
다음 형태의 JSON을 임시 파일에 쓴 뒤 해당 경로로 원자적으로 rename한다.

```json
{"sequence":1,"observedAt":"2026-10-02T10:00:00.000000Z","latencyMicros":1250}
```

sequence는 workload 안에서 증가시키고 observedAt은 실제 UTC 측정 시각을 사용한다. 예시는 형식만
보여주며 오래된 시각을 그대로 전송하면 버린다. 파일은 일반 파일·4KiB 이하, 지연은0–600,000,000μs다.
같은 지연 샘플을 여러 번 전송하지 않는다. 이 값은 개별 서비스 측정이며 실행 전체 시간이나 p95/p99가 아니다.
서버는 현재 producer만 받고 Attempt별 최신64개를 보존한다. Task 상세와 Dashboard에 최신 Attempt의
측정을 표시한다. 측정 전송은 best effort이며 손실 가능성이 있다. API 장애나 측정 시각 거절로 계산을
실패시키지 않지만, producer 인증/claim이 차단되면 workload를 중단한다.

참고: [ADR 0008](../docs/adr/0008-runtime-telemetry.md), [측정 증거](../docs/evidence/m5-runtime-telemetry.md).

## 지속 VD supervisor (M6 구성 요소)

같은 이미지의 `python3 /opt/edgeai/vd.py`는 지속 Pod 안에서 독립 Runner session을 실행한다.
`python3 /opt/edgeai/vd.py --ready`는 유효한 runtime lease의 준비 상태만 확인한다.
[내부 poll 계약](../contracts/openapi/vd-runtime-api.yaml), [ADR0014](../docs/adr/0014-virtual-device-runtime.md),
[구성 요소 검증](../docs/evidence/m6-vd-runtime.md)을 따른다. 서버의 runtime 저장·poll·Pod 인증·VD Task
배정 연결까지 [M6 수용](../docs/evidence/m6-completion-audit.md)을 통과했다.
공개 VD 등록 후 별도 실행 시작 API로 기동한다.

| 변수 | 용도 |
|---|---|
| `EDGEAI_VD_ID` / `EDGEAI_VD_RUNTIME_ID` | 영속 VD와 해당 실행 세대의 서로 다른 UUID |
| `EDGEAI_VD_GENERATION` | 1부터 증가하는 실행 세대 |
| `EDGEAI_VD_MAX_CONCURRENT_TASKS` | 같은 Pod 자원을 공유하는 1–16개 동시 작업 slot |
| `EDGEAI_VD_STARTUP_SECONDS` / `EDGEAI_VD_DRAIN_SECONDS` | 준비·drain 제한 시간, 각각 1–600초 |
| `EDGEAI_VD_CLAIM_FILE` | 요청마다 읽는 runtime credential 파일 |
| `EDGEAI_POD_TOKEN_FILE` | audience=edgeai-vd인 projected Pod token 파일 |
| `EDGEAI_POD_UID` / `EDGEAI_CONTROL_PLANE_URL` / `EDGEAI_WORK_DIR` | 실제 Pod 신원, 내부 origin, 전용 쓰기 가능한 volume |

작업별 임시 claim과 디렉터리는 private mode로 만들고 종료 시 제거한다. 같은 volume의 supervisor
재시작은 거절하며 새 runtime generation과 새 Pod를 만들어야 한다. 같은 Attempt 재배정은 실행하지
않는다. 일시 장애는 readiness를 제거하고, lease 만료·인증 거절·STOP은 작업을 종료한다. DRAIN은
신규 작업 없이 완료 보고의 확인을 기다리고, 작업이 없어도 서버의 STOP까지 poll을 계속하며 제한 시간을 적용한다. 한 작업 취소는 해당 session만
정리하며 다른 작업은 유지한다. Linux의 subreaper/pidfd를 사용해 Runner 강제 종료 후 자식도 정리한다.
100,000개 실행 이력 상한에 도달하면 마지막 수락 작업부터 drain을 알리고 새 배정을 받지 않는다.

동시 작업의 cgroup 측정은 공유 VD 컨테이너 전체 사용량이다. 서비스 지연 파일만 작업별 측정이며
CPU/GPU 자원을 각 작업에 독점 할당하거나 서로 신뢰하지 않는 workload를 격리하는 기능은 아니다.
`test-runner.sh`는 기존 Runner와 VD 시험을 함께 수행하며 CI에서는 UID10001의 실제 이미지도 시험한다.

## 스트림 전달 구성 요소 (M7 진행 중)

`stream_protocol`은 [frame](../contracts/streams/frame.schema.json)과
[처리 확인](../contracts/streams/ack.schema.json)을 검증한다. `stream_journal`은 제한된 로컬
SQLite에 입력·계산 상태·출력을 원자 저장한다. `stream_mqtt`는 고정 Paho MQTT2.1.0을 사용하며
실제 MQTT5 전달·경로별 ACL·TLS·재연결을 담당한다. 제어 서버의 인증된 binding과 broker ACL
설정이 전제다. 서버의 권한 worker·인증 배정은 별도로 구현했고 공개 STREAM Run·Runner workload
연결은 아직 미구현이다.

```bash
python3 -m venv .tools/stream-venv
.tools/stream-venv/bin/python -m pip install --require-hashes --only-binary=:all: -r runner/requirements-stream.txt
EDGEAI_STREAM_PYTHON=.tools/stream-venv/bin/python bash scripts/test-stream.sh
```

실제 `mosquitto`, `mosquitto_passwd`, `openssl` 실행 파일을 요구하며 없으면 성공으로 건너뛰지 않는다.
별도 경로는 `EDGEAI_MOSQUITTO_BINARY`, `EDGEAI_MOSQUITTO_PASSWD_BINARY`로 지정한다.
시험은 임의 loopback 포트에 private credential/정확한 topic ACL을 가진 전용 broker를 만들고
종료 시 정리한다. TLS 시험도 포함하며 기존 Compose broker 설정은 사용하거나 수정하지 않는다.

`test-runner.sh`의100개에는 SDK·codec·실제 SQLite/프로세스 강제 종료·외부 checkpoint 시험이 포함된다.
HTTPS/MQTT/계산/Session 통합은 별도50개다. S3 새 볼륨 복원·Attempt 인계 구성 요소도 검증했으며
실제 Kubernetes의 Pod/Node 전환 연결은 남았다. [설계 경계](../docs/adr/0022-stream-processing-journal.md),
[실제 검증 기록](../docs/evidence/m7-stream-transport.md).

`stream_assignment.BindingClient`는 검증된 HTTPS API에서 Device/Runner 배정을 조회한다.
응답의 전체 주체·generation·topic·payload 규격을 확인하고 MQTT 자격은 메모리에만 유지한다.
`Link.from_assignments(journal, assignments)`로 journal의 정확한 모든 경로를 연결해야 한다.
step·journal 쓰기에서 요청 경과 시간을 차감한 monotonic 기한을 검사해 만료한 socket을 닫고 쓰기를 거절하며,
트랜잭션 도중 만료되면 전체 변경을 롤백한다. 이 조회는 lease를 연장하지 않는다.
기존 저수준 `Link(journal, endpoint, client_id)`에는 인증 배정/lease 계약이 없으므로 운영 스트림
실행의 대체 경로로 사용하지 않는다. 계산 프로세스 watchdog은 아래 자동 Session에서 연결한다.
[설계](../docs/adr/0027-stream-client-lease.md), [검증과 fixture 경계](../docs/evidence/m7-stream-client.md).

`BindingClient.heartbeat(generation_id, sequence)`는 `{sequence, assignment}` 결과를 반환한다.
처음에는 `sequence=0`으로 서버의 현재 순번을 읽고 다음 요청에 `+1`을 사용한다. 응답을 잃으면
같은 순번을 재전송한다. 재전송과 0 조회는 생존 시각을 갱신하지 않는다. producer와 consumer가
각자 새 순번으로 응답해야 양쪽 중 더 오래된 생존 시각+고정 window까지 기한이 늘어난다.
`Link.refresh(assignments)`에는 모든 경로의 최신 배정을 전달한다. 호출은 `step`과 같은 스레드에서
기존 기한 전에 완료해야 한다. 같은 주체·세대·broker·규격만 허용하며, 이미 만료된 연결은 새
배정으로 되살릴 수 없다. VD drain으로 짧아진 기한도 적용한다. HTTP 조회 중에도 원래 기한은
계속 흐르므로 갱신 루프는 충분한 여유를 둬야 한다. 자동 갱신 스케줄러나 계산 프로세스 종료를
이 API 자체가 제공하는 것은 아니다. [설계와 검증](../docs/evidence/m7-stream-heartbeat.md).

## 자동 세션과 외부 체크포인트

`stream_session.Session`은 인증 배정·자동 heartbeat·MQTT·지속 계산 프로세스/watchdog를
소유한다. 포트별 generation ID와 Run은 인증된 제어 배정에서 받아야 한다.
`durability='EXTERNAL'`은 서버 확정 전 입력 처리 ACK와 출력을 보류한다.

같은 `BindingClient`, Run과 전체 입력/출력 generation ID로 `CheckpointClient`를 만든 뒤
`Session(..., durability='EXTERNAL', checkpoint_client=checkpoints)`에 전달하면 `step()`이
latest → uploads → S3 PUT → commit을 자동 수행한다. 저장소 사설 CA는
`CheckpointClient(..., storage_ca_file=...)`로 지정한다. 원격 통신은 검증된 TLS를 요구하며
`allow_http_loopback=True`는127.0.0.1 시험 전용이다. API 자격은 S3에 전달하지 않는다.

Session은 같은 SQLite 후보로 오류/응답 유실을 재시도하고 인증된 receipt를 대조한 뒤에만
확인 위치를 전진시킨다. 같은 볼륨에서 재시작하면 서버 latest와 로컬 확인본/후보를 대조한다.
다른 최신 상태·빈 볼륨·새 Attempt/세대로 자동 복원하지 않는다. 권한이 만료되면 모델을 종료한다.
`checkpoint_client`를 생략한 EXTERNAL 모드는 호출자가 신뢰된 서버 receipt를 확인하여
`confirm_checkpoint`를 호출해야 한다. S3 PUT 성공만 전달하면 안 된다.
로컬 `settled`는 Task/Run의 성공이 아니며 세션은 종료 명령까지 heartbeat를 유지한다.
[자동 저장 설계](../docs/adr/0033-stream-automatic-checkpoint-publisher.md)와
[실제 검증 및 남은 연결](../docs/evidence/m7-stream-checkpoint-publisher.md)을 따른다.

새 private 세션 디렉터리를 준비하고 `Session(..., create=True, durability='EXTERNAL',
checkpoint_client=checkpoints, restore_latest=True)`를 사용하면 인증 latest의 고정 S3 version을
검증한 뒤 상태·커서·미확인 출력/END를 복원한다. 실행 digest·전체 binding이 같아야 하고
기존 journal은 덮어쓰지 않는다. 확정본이 없거나 손상·권한 만료·이력 변경이 있으면 실패하며
빈 상태로 시작하지 않는다. 이 옵션은 동일 Attempt/세대만 지원하며 새 Pod/Attempt의
권한 인계는 별도다. [복원 설계·검증](../docs/evidence/m7-stream-checkpoint-recovery.md)을 따른다.

제어 서버가 새 Attempt·전체 활성 경로를 준비했다면 위 옵션에 `handover_latest=True`를 추가한다.
서버가 이전 실행 종료·옛 경로 권한 회수·같은 실행 digest를 확인하고 최신 상태를 현재 배정으로
인계한 뒤 SDK가 새 고정 version을 복원한다. Device Session 교체는 별도의 순번 연속성 계약이
필요해 거절한다. 이 옵션은 Task 생성이나 peer journal 전환을 수행하지 않는다.
[인계 설계·검증](../docs/evidence/m7-stream-checkpoint-handover.md)을 따른다.

## Device 송신과 소비 경로 전환

Run 생성자가 전달한 `run_id`로 `client.device_routes(run_id, limit=100, offset=0)`를 호출하면
현재 Device 세션에 고정된 경로와 최신 세대를 조회한다. `nextOffset`이 있으면 다음 페이지를
읽는다. 준비 전 generation은null이며, 필요한 전체 경로가 ACTIVE인지 확인한 뒤 DeviceSource를
연다. SDK가 실제 배정 API에서 전송 자격과 기한을 다시 확인하므로 조회 응답의 leaseUntil만으로
송신하지 않는다. 종료된 Run/세대도 읽을 수 있고 이 호출은 lease를 연장하거나 journal을 바꾸지 않는다.
세션을 교체하면 이전 Run을 승계할 수 없다. [조회 계약·검증](../docs/evidence/m7-device-route-discovery.md).

`stream_source.DeviceSource`는 같은 Device Session의 `BindingClient`, Run과 generation ID 목록을
받아 인증 배정·heartbeat·TLS MQTT·LOCAL 송신 journal을 관리한다. 0700 디렉터리를 준비하고
처음에는 `create=True`, 같은 볼륨 재시작은 기본값으로 연다. `emit([Emission(route_id, payload,
media_type)], state=adapter_cursor_bytes)`는 샘플·순번·adapter 상태를 원자적으로 기록한다.
`step()`을 계속 호출해야 전송과 heartbeat가 진행된다. Backpressure면 샘플과 adapter 커서를
보관한 채 처리 확인 후 재시도한다. MQTT PUBACK은 송신 기록을 삭제하지 않는다.

제어 서버가 소비 경로를 전환했다면 이전 owner를 닫고 현재 generation ID 목록으로
`DeviceSource(..., create=False, handover=True)`를 연다. 같은 Device Session·논리 경로만
허용하며 미확인 DATA/END·순번·adapter 상태를 보존한다. 장치 세션 변경이나 볼륨 손실을
자동 재시작으로 대체하지 않는다. `settled`는 END의 처리 확인이며 플랫폼 Run 성공을 뜻하지 않는다.
자세한 실제/fixture 범위는 [Device source 검증](../docs/evidence/m7-device-source-handover.md)을 따른다.

자동 전환은 `stream_device_run.DeviceRunSource(client, run_id, route_ids, directory,
create=True)`로 선택한다. generation ID 대신 고정 논리 route ID를 전달하며, 같은 볼륨 재시작은
`create=False`다. `step()`을 호출해 `ready`가 된 뒤 `checkpoint().state`의 센서 커서부터 이어간다.
배정 회수·lease 만료 시 이전 transport를 닫고 같은 세션의 새 경로를 조회·인계한다.
재연결 대기 중 emit/checkpoint의 `SourceReconnecting`은 Backpressure 하위 타입이다.
샘플을 보관한 채 step을 계속 호출하고 준비 후 재시도한다. 다른 세션이나 없는 볼륨을 자동
승계하지 않으며, 취소·신원·TLS·잘못된 계약은 종료한다.
`ready` 확인과 `emit` 사이에도 lease가 만료될 수 있으므로 실제 `emit`의 Backpressure를
처리해야 한다. 여러 장치를 한 루프에서 다룰 때는 성공한 샘플을 대기 목록에서 제거하고,
아직 저장되지 않은 샘플만 원래 제한 시간 안에서 재시도한다. END도 같은 규칙을 따른다.
대기 중에는 모든 장치의 `step()`을 호출해 다른 장치의 heartbeat를 막지 않는다.
[자동 재연결 설계·실제 시험](../docs/evidence/m7-device-reconnect.md).

기본값은 서버 공동 완료 대기다. END를 보낸 뒤 `while not source.completed: source.step()`으로
진행한다. WAITING 동안 heartbeat를 유지하고 모든 송신 경로의 FINALIZE를 받으면 MQTT를 닫는다.
`completed`는 스트림 그룹의 종료 허가이며 최종 파일 결과 저장 성공은 별도다.
0700 source 디렉터리의 0600 `completion.json`은 종료 순번과 범위만 보존하며 자격·샘플·허가는
저장하지 않는다. 같은 볼륨 재시작은 서버 허가를 다시 조회하고, 이미 닫힌 경로에 재연결하지
않고 끝낼 수 있다. 서버가 WAITING이면 현재 배정을 새로 받아 heartbeat를 재개한다.
기존 전달 계층만 시험하는 경우에만 `completion=False`를 명시한다.
[완료 설계](../docs/adr/0039-device-source-completion.md)와
[실제 Spring/S3 통합 범위](../docs/evidence/m7-device-source-completion.md)를 따른다.

## SERVICE 스트림 실행 owner

`contracts/profiles/service-stream.example.json`은 지속 계산과 최종 파일 생성을 구분한다.
`stream.command/args`는 프레임을 처리하고 루트 `command/args`는 확정된 상태 파일
`EDGEAI_STATE_FILE`에서 최종 artifact를 만든다. 파일/스트림 포트는 각 방향에서 이름이
달라야 한다. stream에는 CHECKPOINT 복구 모드와 명시적 journal 한도를 함께 선언한다.

Runner는 인증 배정의 NEW/RESTORE/HANDOVER를 따르고, 마지막 END/ACK를 포함한
외부 checkpoint와 서버의 해당 checkpoint 완료 허가를 모두 확인한 뒤 최종 파일을 만든다.
기존 파일 업로드/결과 commit을 재사용한다. 모델 실패·취소·복원본 누락·잘못된 허가로
성공 결과를 만들지 않는다. 제어 API/저장소 TLS의 사설 CA는 컨테이너 신뢰 저장소 또는
Python SSL_CERT_FILE로 제공한다. broker CA는 인증 배정에서 받는다.

공동 완료 허가 후 재시작한 현재 Runner는 실행 배정의 FINALIZE와
`streams/checkpoints/finalized`로 정확한 checkpoint를 조회한다. 논리 포트/실행 digest·한도·
고정 S3 version·종료 커서를 검증하고 다운로드 후 허가를 재조회한다. 새 빈 작업 디렉터리에
0600 상태 파일을 저장해 최종 파일 명령만 실행하며 MQTT·Session·지속 모델은 열지 않는다.
서버가 명시적으로 기록한 최종 처리 재시도는 이전 실행 종료·경로 회수 후 새 Attempt로 복구할 수 있다.
이때 `checkpointActor`는 원래 허가된 checkpoint의 Attempt/epoch이며 현재 요청 인증과 구분한다.
일반 checkpoint 업로드·latest·handover는 현재 actor 검증을 유지한다. 임의 기존 작업 폴더를 재사용하지 않는다.
[복구 설계](../docs/adr/0040-stream-finalizer-recovery.md)와
[새 Attempt 복구 설계·검증](../docs/adr/0045-stream-finalizer-attempt-recovery.md)을 따른다.

인증된 배정·공동 완료 서버와 DeviceSource를 연결했다. 공개 Run 생성·그룹별 동시 시작 및
AUTO/NODE Kubernetes 데이터 흐름은 검증했다. 공개 STREAM은 명시적 opt-in이며 기본501이다.
장치 자동 재연결과 공개 retry를 연결했고 실제 Kubernetes 그룹 장애 복구까지 검증했다.
계산 중에는 연결된 그룹을 함께 재시도하고 완료 허가 뒤에는 실패한 작업만 복구한다.
offload/REMOTE/VD는 계속 거절한다. 최종 처리도 실제 Job 유실·새 Pod 복구·기존 결과 보존을
[Kubernetes에서 검증](../docs/evidence/m7-finalizer-kubernetes.md)했다.
[공개 재시도 검증](../docs/evidence/m7-public-stream-retry.md)을 따른다.
[실행 설계](../docs/adr/0037-service-stream-runner-execution.md),
[Runner 검증 범위](../docs/evidence/m7-service-stream-runner.md),
[서버 완료 검증](../docs/evidence/m7-stream-execution-completion.md)을 따른다.
