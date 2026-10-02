# M4 실행 구성 요소 — 진행 중

2026-10-02. M0–M3는 구현·검증 완료이며 M4는 아직 미완료다.
이 기록은 실행 규격·Job compiler·S3 adapter·독립 Runner의 구현 및 구성 요소 시험에 한정한다.
Dashboard의 Run 요청은 아직 실제 Kubernetes workload를 시작하지 않는다.

## 구현

- SERVICE 실행 JSON Schema와 엄격한 소비 parser: digest 이미지, command/args, 입출력,
  자원/QoS, Linux amd64/arm64, node selector/tolerations/runtime class, 시간·작업 공간 제한.
- 순수 Kubernetes Job compiler: AUTO 요구조건, NODE required affinity, scheduler bind 유지,
  Attempt별 결정적 이름, backoff 0, timeout, 비루트·읽기 전용 root·token 자동 마운트 금지.
- MinIO SDK 9.0.3 기반 artifact adapter: 고정 bucket/key, 필수 versioning, 서명 PUT,
  실제 object version의 길이·형식·내용 SHA-256 검증, version 고정 다운로드, 오류 내용 비식별화.
- 독립 Python Runner: claim/입력 검증/실제 자식 프로세스/출력 전송/commit 요청,
  timeout·SIGTERM 취소, 고정 오류 코드, 큰 정수·소수 보존, 누락·링크 출력 거절.
- 별도 내부 OpenAPI와 참조 Runner 이미지. SERVICE 예시의 0 digest는 자리표시자다.
  CPU 참조 계산은 SYNTHETIC이며 학습 모델·실장비 성능 증거가 아니다.

## 확인한 로컬 증거

모든 행은 `docs/evidence/runs/<testRunId>/result.json`의 PASS/exit 0이다.
원시 로그는 Git에 올리지 않으며 검증 환경에 보존한다.

| testRunId | 범위 |
|---|---|
| 20261002T062558Z-d9d7ed6c | 단위/MVC 30개, 실행 규격·자원·AUTO/NODE compiler 포함 |
| 20261002T063221Z-b085584c | 공개 OpenAPI 생성 타입·패키징 일치, 내부 Runner OpenAPI 파싱 |
| 20261002T063221Z-0f99b9cf | 호스트 Python Runner 7개: 실제 subprocess와 격리 HTTP fixture |
| 20261002T062841Z-2e4cca4b | 실제 Kubernetes API의 AUTO/NODE Job server dry-run |
| 20261002T064010Z-1aeb05aa | 실제 MinIO 4개: 버전 고정·변조·형식·위조 metadata·체크섬 거절 |
| 20261002T064042Z-fbbfc1a4 | 새 SDK 의존성을 포함한 기존 실제 PostgreSQL 통합 회귀 |
| 20261002T064241Z-4f1b6aad | 실제 DB/API·PC/모바일 8개와 DB 중단 503·동일 앱 프로세스 복구 |

server dry-run은 단위 시험이 생성한 Job JSON을 소유 label이 확인된 edgeai namespace에
제출했다. 리소스는 생성하지 않았으며 실제 scheduler bind나 workload 실행의 증거가 아니다.
Runner HTTP fixture의 claim 거절·commit 재전송은 실제 Control Plane의 fencing·멱등성을
증명하지 않는다. 그 구현과 실제 DB/클러스터 시험은 후속 작업이다.

## 실패 원인과 수정

- MinIO SDK 9.0.3의 API와 import/버전 설정/업로드 입력/close 계약을 맞추고 dependency lock을 갱신했다.
  최초 저장소 실행은 lock 누락으로 실패했고 이후 빌드·실제 저장소 시험이 통과했다.
- 최초 presigned PUT은 `x-amz-meta-sha256`을 서명하지 않아 400 AccessDenied였다.
  해당 헤더와 SHA-256 checksum 헤더를 서명한 뒤 실제 PUT/검증/다운로드가 통과했다.
- 변조 PUT은 처음부터 HTTP 400으로 거절됐으나 시험이 `BadDigest`를 예상해 실패했다.
  실제 응답의 비밀정보 없는 코드 `XAmzContentChecksumMismatch`를 확인해 assertion을 수정했다.
  최종 4개 시험에서 정상 업로드와 변조 거절을 함께 확인했다.

## 남은 M4 작업과 제한

1. 새 migration의 RuntimeInstance·producer claim·outbox 및 기존 Run/Task/Attempt 전이 연결.
2. 전용 runtime namespace·RBAC, 실제 Job/Secret 생성·UID 대조·watch/relist·재시작 reconciliation.
3. 내부 인증·실제 Pod UID/epoch 검사·claim/업로드/결과 API 및 검증 후 원자적 Result commit.
4. BATCH 입력 전달, 취소 시 producer 차단과 실제 종료, 늦은/중복 결과 검증.
5. Result API·UI·Swagger와 실제 kind의 scheduler→Runner→MinIO→Result 수용시험.

presigned PUT의 checksum 헤더는 서명하지만 SDK는 Content-Length/Content-Type을 서명에서
제외한다. 현재 내용·크기·형식 검증은 commit 전에 수행하며, 업로드 전 정확한 크기 제한을
보장하지 않는다. 저장소 quota 또는 별도 업로드 제한은 후속 보강 대상이다.

CI에 실제 MinIO artifact 검증과 Runner 컨테이너 job을 추가했다. 로컬 Docker 권한 제한은
유지하며 새 CI의 통과·이미지 발행·배포는 아직 이 기록의 확인 범위에 포함하지 않는다.
`test-kind.sh`와 `demo-workflow.sh`는 전체 실행 경로가 없어 계속 BLOCKED다.
전체 플랫폼의 LOCAL_VERIFIED/FULL_ACCEPTANCE를 주장하지 않는다.
