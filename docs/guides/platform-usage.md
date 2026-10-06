# Profile 사용

`/profiles`에서 DEVICE / SERVICE / VD를 선택하고 키, `1.0.0` 형태의 버전,
비어 있지 않은 JSON 규격을 입력합니다. 같은 내용의 재등록은 기존 버전을 반환하고,
같은 버전의 다른 내용은 409로 거절합니다. 변경은 새 버전으로 발행합니다.
목록에서 버전을 누르면 저장된 내용과 digest를 조회할 수 있습니다.

규격은 현재 JSON 문서로 보관합니다. 장치 프로토콜·이미지·서비스 참조의 실행 호환성은
각 소비 기능을 구현할 때 검증합니다. 비밀번호·토큰은 규격에 넣지 않습니다.
직접 API를 호출할 때는 Basic 인증으로 `GET /api/v1/csrf`를 먼저 호출하고,
응답의 `EDGEAI_SESSION` 쿠키와 토큰(`X-CSRF-TOKEN`)을 POST에 함께 보냅니다.
상세 계약: [OpenAPI](../../contracts/openapi/platform-api.yaml), [M1 결정](../adr/0002-profile-registry.md).

Profile 통합 시험은 고유 `test-*`/`browser*` 키를 사용합니다. 발행 불변성 때문에
시험 행도 개발 DB에 보존합니다. 반복 시험에는 전용 개발 DB를 사용하세요.


## 장치·노드 사용

`/devices`에서 DEVICE Profile 버전 UUID를 선택하고 장치 키·이름·데이터 출처를 등록합니다.
Profile UUID는 `/profiles`의 상세에서 확인할 수 있습니다. 장치 등록 후 연결 상태는 보고 없음입니다.
장치 에이전트는 `/api/v1/devices/{id}/sessions`에 재접속마다 새 `bootId`를 보내고,
발급된 sessionId와 증가 sequence로 `/observations`에 상태를 보고합니다.
실제·재생·합성 데이터는 sourceMode로 구분합니다. Swagger에서 각 입력·응답·오류를 확인하세요.
API/UI 실행 후 `bash scripts/demo/demo-device-lifecycle.sh`는 합성 장치를 등록·보고·재접속·해제합니다.

로컬 Node 관측은 기본 비활성입니다. Kubernetes 배포에서는 전용 ServiceAccount로
15초마다 실제 Node 목록을 읽습니다. 목록은 UID·Ready 상태·CPU/메모리 allocatable·아키텍처를
보여주며 마지막 관측이 60초를 넘으면 만료로 표시합니다. CPU/메모리는 현재 잔여량이 아닙니다.
로컬 설정은 `.env.example`의 `EDGEAI_KUBE_*`, 권한 구성은 [배포 문서](../../deploy/kubernetes/README.md),
장치/세션/이력 규칙은 [ADR 0003](../adr/0003-device-node-observation.md)을 따릅니다.


## 워크플로·실행 요청 사용

`/workflows`에서 워크플로 키·이름을 등록하고 SERVICE Profile 버전 UUID를 참조하는 DAG를
발행합니다. 작업 간 포트 연결과 BATCH/STREAM 모드를 정의하며 순환·잘못된 참조·중복 입력 포트는
거절합니다. 같은 버전의 같은 내용은 기존 버전을 반환하고, 내용 변경은 새 버전이 필요합니다.

발행한 버전을 선택해 AUTO, 관측된 Node UUID의 NODE 또는 서버에 설정된 제공자의 REMOTE 정책으로 실행 요청을 저장합니다.
Remote 활성화·제공자·파일 설정은 [Remote 실행 문서](../operations/remote.md)를 따릅니다.
`Idempotency-Key`는 요청을 재전송해도 실행을 중복 생성하지 않게 합니다. 다른 실행을 만들 때는
**새 실행 키 만들기**를 누릅니다. 작업별 Attempt와 상태를 조회하고 작업 또는 실행을 취소할 수 있습니다.
작업 취소는 같은 스트림 그룹과 후속 의존 그룹도 정리하며 별도 분기는 유지합니다.

BATCH 실행에서는 **작업별 실행 위치**로 AUTO/NODE/VD/REMOTE 최초 대상을 지정할 수 있습니다.
API는 `taskExecutions`에 발행된 DAG 작업 키를 사용합니다. 예:
`{"decode":{"mode":"AUTO"},"infer":{"mode":"NODE","nodeId":"<Node UUID>"}}`.
생략한 작업은 Run의 `execution`을 따릅니다. BATCH 하위 작업과 STREAM 그룹 모두 적용되며,
Task의 `initialMode`/`initialNodeId`/`initialVdId`/`initialRemoteTarget`은 대기 중인 작업의
최초 배치도 보여 주며 Attempt는 현재 실행 위치를 보여 줍니다. VD는 해당 작업과 같은 SERVICE
버전의 Ready VD UUID, Remote는 서버에 설정된 제공자 키를 사용합니다. Run 기본 정책을
바꿔도 작업별 선택을 유지하며 **기본 실행 정책 따름**을 선택한 작업만 Run 정책을 따릅니다.
전환 뒤 재시도는 전환된 위치를 유지합니다. STREAM은 AUTO/NODE만 지원하며 VD/REMOTE를
포함한 실행의 자동 전환은 지원하지 않습니다.
상세 계약·검증은 [혼합 배치](../adr/0054-mixed-task-targets.md)를 따릅니다.

실행 기능이 비활성이면 root 작업은 READY/QUEUED, 나머지는 WAITING으로 요청을 보관합니다.
활성 배포에서는 실제 Runner가 작업을 수행하고 검증된 결과만 하위 작업에 전달합니다.
Run 생성의 선택적인 `retry`로 최대 시도 횟수·대기 시간·허용 기간·오류를 지정합니다.
재시도는 같은 Task에서 새 Attempt/epoch를 만들며 상세 계약은 Swagger RetryPolicy를 따릅니다.
실행 중인 작업을 선택하면 **실행 위치 전환**에서 NODE 또는 Remote를 요청할 수 있습니다. 일반 SERVICE는
`recovery.mode=RESTART`를 선언하고 이전 실행 종료 뒤 고정 입력으로 다시 시작합니다. CHECKPOINT
STREAM은 NODE 전환을 지원하며 연결된 그룹 전체가 외부 체크포인트에서 재개합니다.
전환 상태는 Task 상세와 `GET /api/v1/operations/{operationId}`에서 확인합니다.
전환 성공은 새 실행 시작을 의미하며 결과 성공은 별도로 확인합니다([ADR 0007](../adr/0007-running-offload.md)).
최신 Runner의 측정은 선택한 작업의 **실행 측정**에서 확인합니다. CPU·메모리의 제한이 없거나 측정하지
못한 값은 미확인/미수집으로 표시하고, 새 Attempt에 이전 값이 이어지지 않습니다. 서비스 지연 보고
방식은 [Runner 문서](../../runner/README.md)를 따릅니다. 현재 Remote는 자원·지연 측정을 지원하지 않습니다.
STREAM은 기본 비활성501이며 운영 broker·TLS·runtime·bindings를 설정한 환경에서 별도로 활성화합니다.
실행 폼의 **장치 스트림 입력 추가**로 장치 ID·출력 포트·받는 작업/포트·메시지 한도를 지정합니다.
활성 세션은 Run에 고정되며 같은 그룹은 모든 BATCH 선행 결과를 받은 뒤 함께 배정됩니다.
AUTO/NODE에서 선택적인 `retry`를 설정하면 계산 중에는 연결된 그룹 전체를 재시도하고,
완료 허가 뒤에는 실패한 작업의 최종 처리만 복구합니다. 장치는 같은 세션·송신 볼륨을 유지해야 합니다.
최대 재시도 횟수는 최초 실행을 포함합니다. STREAM의 REMOTE/VD 실행은 아직 거절합니다.
[공개 재시도 계약과 검증 범위](../evidence/m7-public-stream-retry.md)를 확인하세요.
실행 상세의 **스트림 경로 조회**로 실제 경로·고정 세션·출처·세대 상태를 확인합니다.
연결 ACTIVE와 작업/Result 성공은 별도 상태입니다([ADR0041](../adr/0041-public-stream-runs.md)).
상세 계약은 [ADR 0004](../adr/0004-workflow-run-task.md)와 Swagger의 Workflow/실행/작업 태그를 따릅니다.


## 가상 장치 등록·원본 연결·실행 (M6)

`/virtual-devices`에서 VD Profile 버전과 원본 Device를 연결합니다.
Profile 형식은 [VD 규격](../../contracts/profiles/vd-profile.schema.json)과
[예시](../../contracts/profiles/vd-profile.example.json)를 따릅니다. 예시 UUID는 실제 발행된
DEVICE/SERVICE Profile ID로 바꿔야 하며 SERVICE는 실행 규격을 충족해야 합니다.

원본 교체·표시 이름·배치 의도 수정은 revision을 검사하며, VD ID와 연결 이력을 보존합니다.
활성 VD 원본으로 쓰는 장치는 바로 해제할 수 없습니다. 먼저 원본 연결을 바꾸거나 VD를 해제하세요.
VD 해제는 원본 Device를 삭제하지 않고, 생성 재전송도 해제된 VD를 다시 활성화하지 않습니다.

등록 상태 REGISTERED/RELEASED와 실제 runtime의 Ready 상태는 구분합니다. 기동·교체·종료는
Operation으로 추적하며 Pod의 실제 종료를 확인한 후 다음 세대를 시작합니다.
`/workflows`에서 VD 정책과 Ready VD ID를 선택하면 같은 SERVICE 버전의 작업들을 해당 VD의
빈 실행 자리에 배정합니다. retry·하위 작업은 VD ID를 유지하고, 작업 취소는 해당 자식 실행만 중단합니다.
기능은 `EDGEAI_RUNTIME_ENABLED=true`, `EDGEAI_VD_ENABLED=true`와 실행·저장소 설정을 요구합니다.
CPU·메모리는 공유 VD 컨테이너 측정이므로 작업별 자동 offload는 허용하지 않습니다.
검증 범위와 남은 수용 게이트는 [M6 작업 실행 기록](../evidence/m6-vd-task-execution.md)을 따릅니다.

`bash scripts/demo/demo-vd.sh <명시적 Kubernetes context>`는 실제 VD 수명과 자식 작업·S3 결과를
검증합니다. 실행이 활성화된 API의 `EDGEAI_SMOKE_API_URL`, `EDGEAI_API_USER`,
`EDGEAI_API_PASSWORD`와 결과 저장소의 `EDGEAI_STORAGE_URL`, `EDGEAI_MINIO_USER`,
`EDGEAI_MINIO_PASSWORD`를 환경에 설정하세요. 이 명령은 시험 VD만 정리하며 기존 데이터를
삭제하지 않습니다. API Pod 재시작 시험은 별도 격리 `test-vd-kubernetes.py`/CI kind에서 수행합니다.
이전 producer 차단 검사에 내부 API도 필요하므로 API origin은 필요 시 별도 loopback
`kubectl port-forward service/edgeai-api`로 연결하세요. `EDGEAI_SMOKE_PROXY_URL`에 공개
Dashboard origin을 주면 CRUD/Run은 실제 Next.js proxy를 통과하고 내부 신원 검사는 직접 API를 씁니다.
