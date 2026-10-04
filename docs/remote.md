# Remote 실행 설정

현재 구현은 `edgeai.remote.reference/v1` 계약을 사용하는 제공자 하나다. 저장소의 Python 참조 제공자는
SYNTHETIC 계산용이며 실제2세부 API·OCI 실행·실장비를 대신하지 않는다. 사용 가능한 연산과 수동 시작은
[simulator README](../simulator/README.md)를 따른다.

제공자 전체의 복구 차단·계산 종료 확인은 별도 운영 자격을 쓰는
[참조 Remote 복구 절차](recovery-remote-fence.md)를 따른다. 기본 비활성이며 일반 API 자격으로 실행하지 않는다.

## 환경 설정

기존 PostgreSQL, 실행 기능, 영속 Runner 서명 키와 versioned MinIO 설정이 먼저 필요하다.
`.env.example`에 있는 `EDGEAI_RUNTIME_ENABLED=true`와 저장소 설정을 준비하고 다음 값을 넣는다.
스케줄러의 `edgeai.runtime.worker-enabled` 기본값은 true다. 현재 실행 설정은 Kubernetes worker도
함께 구성하므로 Kubernetes 연결·ServiceAccount 설정도 기존 [배포 문서](../deploy/kubernetes/README.md)를 따른다.

| 환경 변수 | 값과 의미 |
|---|---|
| EDGEAI_REMOTE_ENABLED | true로 활성화. 기본 false |
| EDGEAI_REMOTE_PROVIDER_KEY | API가 선택하는 key, 기본 reference |
| EDGEAI_REMOTE_URL | 제공자 origin. HTTPS 또는 로컬 시험용 literal loopback HTTP |
| EDGEAI_REMOTE_TOKEN_FILE | 600 권한의 비공개 bearer 파일 절대 경로 |
| EDGEAI_REMOTE_CA_FILE | 필요할 때 사용하는 CA PEM 경로. 생략하면 시스템 신뢰 저장소 |
| EDGEAI_REMOTE_SOURCE_MODE | 참조 제공자는 SYNTHETIC. EXTERNAL 표기 자체가 실장비 검증을 보장하지 않음 |
| EDGEAI_REMOTE_TIMEOUT_SECONDS | 요청 제한1~30초, 기본10초 |

제공자 URL·CA 내용·프로토콜·sourceMode는 최초 요청 시 고정한다. 변경 후 기존 실행을 이어가려면
원래 설정을 복구해야 한다. 같은 파일의 bearer 교체는 허용한다. 토큰 값·서명 URL은 Git·명령 인자·로그에
넣지 않는다. 원격 서버와 플랫폼의 파일·계산 계약 및 sourceMode가 일치해야 한다.

## 공개 API와 화면

`POST /api/v1/workflow-runs`의 `execution`에 다음을 지정한다. 나머지 workflowVersionId, parameters,
선택적 retry, Basic 인증·CSRF·Idempotency-Key는 기존 Run 계약과 같다.

```json
{"mode":"REMOTE","providerKey":"reference"}
```

`/workflows`의 실행 위치 정책에서 Remote 제공자를 선택할 수 있다. Task/Run 상세에는 고정 제공자와
SYNTHETIC/EXTERNAL 구분이 표시된다. Remote는 현재 자원·지연 측정과 자동 offload 정책을 지원하지 않는다.
Run을 새로 만들지 않는 재전송에는 같은 Idempotency-Key를 유지한다. 실행이나 Remote 기능이 비활성이면
새 REMOTE 요청은503, 미설정 key는404다.

실행 중 **실행 위치 전환**에서는 NODE 또는 Remote를 선택한다. API는
`POST /api/v1/tasks/{taskId}/offload`이며 targetNodeId 대신 targetProviderKey를 보낼 수 있다.
SERVICE에 `recovery.mode=RESTART`가 필요하고 sourceAttemptId/drainTimeoutSeconds/startTimeoutSeconds도
필수다. 전환 성공은 새 producer 시작을 뜻한다. 결과 성공은 Result에서 별도로 확인한다.
하위 BATCH는 최초 Run의 제공자 정책을, 재시도는 이전 Attempt의 정책을 유지한다.

## 검증과 현재 제한

`scripts/test-runtime-results.sh`는 실제 PostgreSQL/MinIO를 요구하고 임시 Python/SQLite 제공자를
기동해 공개 HTTP→스케줄러→Remote→S3→Result 경로, 장애·취소·재시도·동시 처리를 검증한다.
기존 Kubernetes↔Remote 서비스 전환 시험의 Kubernetes 부분은 fixture다. 실제 클러스터에서
양방향 전환과 API 프로세스 재시작을 포함한 종단 시험, 실제 외부 API와 상태형 복원은 남는다.
증거·실패 이력·남은 게이트는 [M5 worker 기록](evidence/m5-remote-worker.md)을 따른다.
