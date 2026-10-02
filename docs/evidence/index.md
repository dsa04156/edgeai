# 검증 증거

2026-10-01 M0 초기 환경 검증. 로컬 Linux x86_64 / JDK 21 / Node 22 / PostgreSQL 16.15.
원시 로그·JSON은 `docs/evidence/runs/<testRunId>/`에 있으며 자격 증명을 제거하고 Git에서는 제외한다.
공개 저장소에는 아래 결과 요약만 보존한다. CI 원시 증거는 해당 Actions run artifact에서 확인한다.

| testRunId | 명령 | exit | 결과 |
|---|---|---|---|
| 20261001T074249Z-49975f51 | bash scripts/test-unit.sh | 0 | API 비인증 401/인증 metadata, 2 tests |
| 20261001T074758Z-ef139f25 | bash scripts/test-integration.sh | 0 | 실제 PostgreSQL 16.15 + Flyway, 1 test |
| 20261001T074854Z-0b774af7 | bash scripts/test-contract.sh | 0 | OpenAPI 생성 타입 일치 + API tests |
| 20261001T074850Z-b27fea4b | bash scripts/test-ui.sh | 0 | lint/typecheck/build + desktop/mobile 2 tests, 연결 실패 503 |
| 20261001T074945Z-eabe3444 | bash scripts/test-health-stack.sh | 0 | DB→Spring→Next.js UP, 인증 200/비인증 401 |

추가 확인: shell 구문, Compose config, 비밀/로컬 파일 Git 제외, 미구현 스크립트 8개의 exit 2,
desktop/mobile screenshot 직접 확인. 최초 M0 시점에는 MinIO 빌드·runtime을 검증하지 않았으며 후속 감사에서 아래와 같이 보완했다.

수정 이력: 첫 단위시험은 test 전용 password property가 없어 실패했고 설정 분리 후 통과했다.
첫 UI 시험(20261001T074758Z-9a59c73d)은 전역 pnpm shim 부재로 webServer 시작이 실패했다.
Node로 설치된 Next CLI를 직접 호출하도록 고친 뒤 UI 전체 재실행이 통과했다.

Docker 소켓 접근은 현재 호스트에서 차단됐다. 실제 kind·hardware 시험은 미구현이다.
이 결과는 플랫폼 전체의 `LOCAL_VERIFIED` / `FULL_ACCEPTANCE`를 의미하지 않는다.

## GitHub Actions 검증

[Run 36832834758](https://github.com/dsa04156/edgeai/actions/runs/36832834758),
코드 커밋 `f265c04ddf407950baa76f68d83beb59ef87dae8`, 결과 **success** (2분 50초).
Ubuntu 24.04 hosted runner / JDK 21 / Node 22 / Compose PostgreSQL 17 / MQTT.
Artifact `m0-verification-36832834758`의 결과 JSON도 내려받아 확인했다.

| testRunId | 시험 | exit | 결과 |
|---|---|---|---|
| 20261001T075239Z-0d254de4 | PostgreSQL ready + MQTT pub/sub | 0 | PASS |
| 20261001T075302Z-d385d565 | unit | 0 | PASS |
| 20261001T075341Z-f84f9215 | contract | 0 | PASS |
| 20261001T075354Z-4c80f49d | UI lint/types/build + desktop/mobile | 0 | PASS |
| 20261001T075412Z-4eeef430 | PostgreSQL 17 Flyway integration | 0 | PASS |
| 20261001T075427Z-afd2b114 | PostgreSQL → Spring → Next.js health | 0 | PASS |

이후 증거·진행 문서만 갱신하는 커밋은 동일 코드에 대한 위 검증을 재사용한다.

## 초기 환경 완료 감사 보완

- `20261001T080004Z-0963a846`: `test-health-stack.sh local`, exit 0.
  실제 프로젝트 PostgreSQL 중지 → API readiness 503/DOWN → Next health 503/DOWN,
  DB 재시작 후 동일 앱 프로세스가 UP으로 복구되는 경로를 확인했다.
- `20261001T080058Z-3a5d7aab`: `test-storage.sh`, exit 0.
  공식 MinIO release commit의 native build를 실제 실행했다. 인증된 S3 PUT/stat/GET,
  무작위 256 KiB 파일 byte 일치, SHA-256 metadata, 비인증 GET 403을 검증했다.
  시험은 고유 probe bucket/object만 만들고 제거했으며 기존 버킷은 건드리지 않았다.
- MinIO 소스 tar SHA-256을 Dockerfile ADD에 고정했다. native build와 Docker source는 동일 commit이다.
- [CI 36833935958](https://github.com/dsa04156/edgeai/actions/runs/36833935958)의 storage job이
  컨테이너 빌드·기동·실제 S3 시험을 2분 35초에 완료했다.
- 같은 CI의 앱 검사에서는 DB 중지 후 API/UI 503까지 통과했으나 CI Compose가 `start --wait`를
  지원하지 않아 재기동 단계에서 실패했다. 기본 `start`와 기존 bounded health polling을 사용하도록 수정했다.

## 최종 M0 검증

[CI 36834353000](https://github.com/dsa04156/edgeai/actions/runs/36834353000),
코드 `b469f622a785aefc0c5759e329eba1a85e9b30e4`: **scaffold/storage 모두 success**.
두 artifact를 내려받아 모든 결과 JSON과 health/storage의 실제 PASS 로그를 확인했다.

| testRunId | 시험 | exit | 결과 |
|---|---|---|---|
| 20261001T080729Z-4073540f | MinIO 공식 source container build/start | 0 | PASS |
| 20261001T080937Z-da1d5cb9 | 실제 S3 PUT/stat/GET·metadata·403·probe 정리 | 0 | PASS |
| 20261001T080754Z-e7e1f843 | PostgreSQL/MQTT | 0 | PASS |
| 20261001T080817Z-5e96e4d2 | unit | 0 | PASS |
| 20261001T080903Z-aef6d36c | contract | 0 | PASS |
| 20261001T080913Z-1887767d | UI lint/types/build/desktop/mobile | 0 | PASS |
| 20261001T080927Z-3d38056a | PostgreSQL/Flyway integration | 0 | PASS |
| 20261001T080939Z-0098619b | DB→API→UI 정상/DB 중지 503/DB 재시작 복구 | 0 | PASS |

[완료 감사](m0-completion-audit.md)는 원래 요청의 각 항목과 증거를 연결한다.


## M1 Profile (2026-10-02)

[상세 결과](m1-profile.md): Profile 등록·목록·버전 조회, digest·동시 등록·불변성,
실제 PostgreSQL/API/desktop·mobile UI 검증. M0 완료 기록은 이전 상태의 역사적 증거다.

M1 최종 코드 20a8d6c의 [CI 36950519908](https://github.com/dsa04156/edgeai/actions/runs/36950519908):
scaffold/storage success, artifact result.json 8개 모두 PASS/0. 최초 fresh DB Flyway
history schema 문제는 별도 새 DB에서 재현한 뒤 명시적 schema 설정으로 수정·검증했다.


## Swagger UI (2026-10-02)

[구성과 검증](../swagger-ui.md): 계약/자산 인증 및 원본 일치, 실제 desktop/mobile
Swagger 렌더와 자동 CSRF Profile 등록을 통과했다. GitHub CI에 같은 검증을 포함한다.

## M2 Device/Node (2026-10-02)

[검증 결과](m2-device-node.md): 장치 lifecycle·동시성·세션 fence·관측·이력,
실제 PostgreSQL·PC/모바일 UI 및 Kubernetes Node 10개 대조. CI와 배포 검증 상태를 구분한다.

## M3 Workflow / Run / Task (2026-10-02)

[검증 결과](m3-workflow.md): 불변 DAG·동시 발행/실행·중복 방지·취소 전파,
실제 PostgreSQL·PC/모바일 UI·DB 장애 복구. 실제 Kubernetes 작업 실행은 M4다.

## M4 실행·Result (2026-10-02, 완료)

[완료 증거](m4-runtime.md): 실행 규격·Job compiler·실제 MinIO byte/version 검증·독립 Runner,
DB 실행 상태·Kubernetes 생성/관측·내부 API·Result·실제 kind 및 기존 클러스터 종단 시험을 통과했다.

## M5 Retry / Offload / Remote (진행 중)

[재시도 진행 근거](m5-retry-offload.md). ADR0006·V6·공개 정책/API/UI·실DB 경합을 구현하며
실제 kind 재시도 검증과 실행 중 offload·Remote 연동을 이어간다. 전체 수용 완료를 의미하지 않는다.

후속 검증: [자동 전환](m5-automatic-offload.md), [Remote adapter](m5-remote-adapter.md),
[Remote 영속 상태/결과](m5-remote-runtime.md), [Remote worker/공개 API](m5-remote-worker.md).
[실제 kind Remote 종단](m5-remote-kind.md)은 추가4개 포함22Run·S3결과20개와 CI·배포까지 통과했다.
외부 수용·상태형 복원은 남는다.


## M6 VD 등록·원본 연결·실행 (2026-10-03, 완료)

[완료 감사](m6-completion-audit.md)는 실제 VD Task·S3 결과·CI·배포·PC/모바일 화면까지
요구사항과 증거를 연결한다. 아래는 구성 요소별 검증 이력이며 후속 게이트는 최종 감사에서 확인한다.

[등록 검증 기록](m6-vd-registry.md): V13·공개5 API·원본 호환성/교체 이력·revision·논리 해제,
Device 해제 보호와 PC/모바일 관리 화면. 실제 VD runtime/Operation/Task 실행은 후속 게이트다.
[지속 runtime 구성 요소](m6-vd-runtime.md)는 Pod compiler·감독 프로세스와 종료/lease/중복 방지
시험을 다룬다. 등록 코드0b4693c는 CI37022079299와 실제 이미지/Ready/ArgoSynced까지 확인했다.
[영속 lifecycle](m6-vd-lifecycle.md)은 V14의 실행 세대·명령 lease·Operation·교체/종료·실제 DB 경합을
검증한다. Kubernetes 관측은 fixture이며 실제 gateway·VD Task 실행 수용은 후속이다.
[실제 Pod gateway와 worker](m6-vd-gateway.md)는 실제 Kubernetes 신원·배치·UID 삭제와
HTTP 장애/영속 DB 명령 복구를 구분해 검증한다. 감독 프로세스의 poll 서버·VD Task 연결은 남는다.
[인증된 poll과 영속 순번](m6-vd-poll.md)은 실제 서버/DB/감독 프로세스의 idle lifecycle 연결을
검증한다. 실제 Kubernetes Pod와의 전체 연결 및 VD Task 배정·Result는 후속이다.

[공개 VD 실행 관리](m6-vd-public-execution.md)는 시작/교체/종료·실행 스냅샷·Operation 조회와
화면을 검증한다. 실제 PostgreSQL/MVC와 UI fixture·실제 API stack의 범위를 구분한다.

## M7 다중 장치·스트리밍 (진행 중)

[전달 구성 요소](m7-stream-transport.md): frame/처리 확인·실제 SQLite 프로세스 복구와
MQTT 두 입력/계산/결과·backpressure·broker 재시작·ACL/TLS를 검증한다.
[DataRoute 제어 상태](m7-stream-routes.md): V19·동시 요청·재접속/재시도·취소/lease·세대 회수·잠금 순서를
실제 PostgreSQL에서 검증했다. 실제 broker 권한·인증 배정·Runner 스트림 workload·S3 checkpoint/
새 Pod 복원·공개 실행 연결은 남는다.

[실제 broker 권한](m7-stream-broker.md): 전용 Mosquitto의 동적 ACL·반복/경합·회수 이력·SIGKILL 복구·
TLS/timeout/소켓 정리를 실제 연결로 검증했다. DB worker와 공개 STREAM 실행 연결은 남는다.

[영속 권한 worker](m7-stream-worker.md): 실제 DB/TLS broker·Spring scheduler와 응답 유실·새 worker/
두 worker 경합·느린 발급 중 fence·lease 만료를 검증했다. 인증 배정·Runner/공개 STREAM 연결은 남는다.
