# M7 인증 Device 송신·journal 인계 검증

2026-10-03 KST. [ADR0036](../adr/0036-device-source-journal-handover.md)의 범위다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 SQLite·원자적 인계·동시 소유·SIGKILL | 20261003T053614Z-1098e358 | PASS/0, 7개·1.041초 |
| 실제 HTTPS·TLS MQTT DeviceSource | 20261003T054334Z-c801639a | PASS/0, 7개·9.330초 |
| 실제 Spring/PG/권한 worker/TLS broker·SDK | 20261003T053844Z-ff37e1c0 | PASS/0, XML23개·실패/skip0 |
| Session 만료 위치 회귀 | 20261003T054133Z-640b3764 | PASS/0, 2개·6.107초 |
| 전체 Runner | 20261003T054448Z-329b107a | PASS/0, 99개·84.879초 |
| 전체 HTTPS/MQTT·Session·DeviceSource | 20261003T054448Z-ee43e225 | PASS/0, 50개·79.739초 |
| fanout 추가 후 source journal 전체 | 20261003T054801Z-371d0b74 | PASS/0, 8개·1.417초 |
| 기존 lease 이후 생존·트랜잭션 도중 취소 | 20261003T054801Z-0db6d172 | PASS/0, 2개·3.739초 |

전체 Runner99개 실행 뒤 fanout 보존 시험1개를 추가하고 source journal8개를 재시험했다.
현재 Runner 시험은100개다. 전체 MQTT50개 뒤 같은 두 source 시험의 조건을 강화해 재시험했다.
제품 코드는 그 사이 변경하지 않았다. PostgreSQL migration/API 계약/UI 구현은 이번에 변경하지 않는다.

후속 source98ea1dc의 CI37101222581은5jobs success이며 다운로드한 결과JSON17개가 모두PASS/0이다.
실제 Runner 컨테이너100개(055239Z-96a69cec), MQTT50개(055433Z-9b4cdb31)와
실제kind BATCH/Retry/Offload/TLSRemote/VD(060547Z-4731ef67)를 확인했다.
고정 S3 결과20+5개와 소유cluster edgeai-ci-0733a6ca30ec 삭제도 로그에서 대조했다.
GitOps1bc44392a627fc4f4ef1bd12f2f8737902faa23b 배포는062725Z-a19b3b3f에서
정확한 API/dashboard/MinIO imageID·Ready·PVCBound·ArgoSynced·VD활성화가PASS다.
공유Ingress의 aggregate health는Progressing이다. 공개 STREAM 실제 종단 완료는 아니다.

## 실제 데이터 경로

실제 Spring이 발급한 Device 토큰·현재 배정과 TLS broker를 사용해 DATA4/5를 보낸다.
옛 소비 journal에는 두 frame이 도착했지만 처리하지 않아 송신 journal에 그대로 남는다.
두 클라이언트를 닫고 Java 제어 서비스가 옛 generation을 fence한다. 실제 권한 worker가
broker ACL을 회수하고 CLOSED를 기록한 뒤 새 generation을 생성·활성화한다.
옛 ACK topic 구독은 broker에서135로 거절되는지 직접 확인한다.

SDK는 옛 generation 배정 요청 거절과 명시적 인계 없이 journal 재개 거절을 확인한다.
현재 배정으로 인계하면 Device Session·adapter 상태·DATA4/5와 순번이 그대로이며 generation과
snapshot serial만 바뀐다. 실제 새 MQTT topic으로 재전송해 독립 `stream_sum.py`가9를 계산한다.
이어2/3을 보내14를 계산하고 END/처리 ACK까지 확인한다. 양쪽 cursor는5이며 동일 세대 재시작에서
adapter 상태·END·serial이 그대로다. broker 비밀번호가 journal 파일에 없는지도 대조한다.

이 시험의 Task Attempt는 동일하며 이전 소비자는 계산을 확정하지 않았다. 따라서 미확인
입력의 새 generation 재전송 시험이며 Task checkpoint 복원 시험으로 해석하지 않는다.
Pod 신원은 기존 RuntimeGateway fixture이고 실제 Kubernetes Pod 실행은 아니다.
옛/새 broker 권한·DB 상태·Device 인증·Python SDK·MQTT 데이터·자식 계산은 실제다.

## 실패와 경계 검사

`053449Z-3fad7488`의6개 동작은 통과했지만 후보 거절 시험 준비가 실패했다. 기존 `capture`는
LOCAL journal의 외부 후보 생성을 거절하므로, 비정상 잔여 후보를 시험하려면 명시적 DB fixture가
필요했다. 후보 행을 시험 fixture로 넣은 뒤7개를 통과했다. 제품의 LOCAL 경계는 완화하지 않았다.

실제 SQLite 인계 전후 guard 검사, 세대 숫자 증가에 따른 byte 용량 초과, route/limits/producer
변경·세대 역행·Task/입력/EXTERNAL/잔여 후보 거절을 확인했다. rewrite 도중 SIGKILL은 이전
manifest/frames/serial 전체로 복원하고 commit 뒤 SIGKILL은 새 상태를 한 번만 적용한다.

HTTPS/MQTT 시험은 응답 유실·503의 같은 heartbeat 순번 재시도, 실제 수신과 처리 ACK 구분,
backpressure 시 adapter 상태/순번 rollback과 재시도, 취소·wire 크기 제한·잘못된 Run,
heartbeat를 통한 암묵 세대 변경 차단과 peer 부재 시 만료를 포함한다. 이 시험의 제어 응답은
명시적 fixture이며 위 Spring 시험과 구분한다.
추가로 fanout의 한 경로만 세대가 바뀌어도 다른 경로와 각자의 처리 확인 위치는 그대로다.
실제 송신 owner가 원래 배정 기한 이후에도 heartbeat로 살아 있고, SQLite 트랜잭션 도중
취소되면 새 frame·adapter 상태·순번 전체가 rollback되는 것도 확인했다.

## 선행 CI 만료 경합 수정

선행 소스 `ba3b2ac`의 CI `37099723313`은 scaffold/storage가 성공했지만 runner의 MQTT 시험이
실패했고 images/gitops는 skipped였다. Runner 컨테이너92개는 `052450Z-c0195c5a` PASS이나
MQTT42개 중 peer 만료 시험이 `052634Z-51300d83`에서 오류가 났다. lease 만료를 MQTT poll 내부에서
먼저 감지하면 MqttError가 세션 밖으로 그대로 나와 감지 위치에 따라 오류 종류가 달랐다.

실제 MQTT poll 안에서 owner를 기한 이후까지 대기시키는 `054038Z-5ff67bc0`은 같은 오류를
재현했다. child watchdog은 owner가 기다리는 동안 모델을 종료했으며 데이터 commit은 없었다.
Session이 자신의 현재 배정 기한을 확인해 어느 단계에서든 `STREAM_ASSIGNMENT_EXPIRED`로
정리하도록 수정했다. 의도적 내부 만료와 원래 peer 부재 시험 모두 위2개 실행에서 통과했다.
선행 CI를 성공으로 재분류하거나 해당 소스의 신규 배포를 주장하지 않는다.

## 남은 전체 수용

Device 볼륨 손실·새 Device Session의 원본 재생 계약, Task/인접 Task의 인계 orchestration,
SERVICE/Runner·공개 STREAM·실제 Kubernetes 다중 장치 흐름은 남는다. LOCAL source는
살아 있는 장치 볼륨을 요구하며 물리 센서·실제 모델·성능 수용의 증거가 아니다.
