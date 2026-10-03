# ADR 0041: 공개 STREAM 실행 요청과 그룹 동시 배정

2026-10-03. 상태: 구현·로컬 검증. 새 코드의 CI·배포 및 전체 M7 수용 판정과 구분한다.

## 결정

`POST /api/v1/workflow-runs`의 선택 필드 `streamInputs`로 Device ID·원본 포트·받는 작업/포트·메시지
한도를 지정한다. 현재 활성 Device Session/epoch와 broker 구성 digest·runtime namespace·lease 길이는
서버가 생성 시 고정한다. 클라이언트는 Session/epoch나 임의 MQTT 주소를 지정하지 않는다.
입력은 대상 작업/포트 순서로 정규화하여 배열 순서를 바꾼 같은 요청도 같은 Run을 반환한다.
동일 요청 키의 입력 변경은409이며 잘못된 전체 포트 구성은 Run/Task/route/command를 함께 롤백한다.

V25는 `stream_run_configuration`, `stream_device_binding`과 불변 제약을 추가한다.
구성은 AUTO/NODE의 PENDING Run에서 최초 Attempt 및 membership 고정 전에만 생성한다.
Device pin은 정확한 route·Run·Device 및 실제 Session/epoch를 참조하고 membership 고정 뒤 추가하지 못한다.
기존 V1–V24는 수정하지 않는다. V25도 최초 격리 DB 적용 후 아래 bytes를 유지한다.

```text
6fa10585147dded8c395a4ec07f1d62552a92399c2026adddba187ddc73174cf
```

ADR0038의 STREAM 연결 및 같은 Device fanout 구성 요소를 그대로 사용한다.
한 그룹의 **모든 BATCH 선행 결과가 봉인**된 뒤 모든 구성원을 같은 트랜잭션에서 READY/Attempt로 해제하고
각 작업의 CREATE 명령을 저장한다. STREAM 선행 작업의 Result를 기다리지 않는다.
그룹 내부 BATCH나 그룹 축약 그래프의 순환은 교착이므로 거절한다.
Kubernetes의 실제 동시 스케줄 완료를 보장한다는 뜻은 아니며 각 Pod의 시작/claim은 비동기다.

영속 Run 설정을 keyset scan하는 `StreamRunWorker`가 그룹의 모든 현재 producer claim을 확인한 뒤
첫 route generation을 준비한다. broker grant/revoke는 기존 권한 worker가 수행한다.
따라서 BATCH 대기나 아직 시작하지 않은 peer 때문에 route lease를 미리 소비하지 않는다.
재시작한 worker는 동일한 세대를 재사용한다. 기존 세대가 만료·차단·종료되면 자동 새 세대를 만들지 않는다.
Device 세션이 바뀌거나 닫히면 고정 입력을 다른 세션으로 재해석하지 않고 그룹을 실패 처리한다.

실패/취소는 같은 스트림 그룹과 그 후속 의존 그룹으로 전파한다. 따라서 소비 작업 취소가 upstream
스트림 producer도 종료시킨다. 독립 분기와 이미 봉인된 결과는 보존한다. 기존 Result 확정 시
그룹 단위 BATCH 해제 경로를 사용하고 BATCH 파일 입력 처리에서 STREAM edge는 제외한다.

## 공개 조회와 화면

`GET /api/v1/workflow-runs/{runId}/streams`는 관리 인증으로 같은 DB snapshot의 route·component·
sourceMode·고정 Device Session/epoch·최신 generation 상태를 페이지 단위로 반환한다.
MQTT 자격 증명·장치 토큰·서명 URL은 포함하지 않는다. ACTIVE는 전송 권한 상태이고 Result 성공은 아니다.
`/workflows`는 장치 입력 행, 입력을 보존하는 재전송, 경로 조회·오류·다음 페이지·상세를 제공한다.
기존 회색/녹색·native form·history-list/dl과 모바일 규칙을 사용한다.

## 활성화와 남은 범위

`EDGEAI_STREAM_RUNS_ENABLED`는 기본false다. true이면 runtime, stream authority, bindings와 유효한
broker digest 및5–120초 lease 설정이 모두 필요하다. 기존 authority 플래그 하나로 공개 실행이 켜지지 않는다.
활성화된 공개 실행은 AUTO/NODE를 지원한다. 현재 그룹의 재시도·오프로딩 인계 연결 전까지 custom retry,
자동 offload 및 REMOTE/VD 실행은409로 거절한다. 미활성 환경에서는 STREAM 요청501을 유지한다.

격리 실제 PostgreSQL 시험, 공개 MVC→실제 TLS API/S3/MQTT·두 DeviceSource·단일 Runner 결과 시험과
PC/모바일 UI 시험을 구분하여 기록한다. Pod 생성/신원은 해당 통합시험의 fixture다.
실제 다중 Runner의 Task→Task 데이터, peer checkpoint 인계, 운영 TLS 배포와 실제 Kubernetes 종단 수용은
후속 작업이며 전체 M5 잔여/M7–M10 목표를 축소하지 않는다.

검증 근거: [공개 실행 검증](../evidence/m7-public-stream-runs.md).
