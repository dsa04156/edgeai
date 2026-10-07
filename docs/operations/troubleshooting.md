# 문제 해결

먼저 증상을 API 연결, 원본 데이터 조회, 등록 상태, 실행 상태로 나눕니다.
오류 메시지와 확인 시각을 보존하고, 변경 요청의 결과가 불명확하면 재실행 전에 기존 상태를 조회합니다.

## 화면은 열리지만 서버 연결 실패

1. API의 `/actuator/health/readiness`를 확인합니다.
2. Dashboard 서버의 `EDGEAI_API_BASE_URL` 또는 `EDGEAI_API_PORT`를 확인합니다.
3. API가 사용하는 PostgreSQL 주소와 DB 상태를 확인합니다.
4. 설정을 바꿨다면 해당 프로세스에 반영했는지 확인합니다.

readiness 실패와 브라우저 화면 로딩 성공은 동시에 발생할 수 있습니다. [빠른 시작](../guides/local-development.md)을 기준으로 확인합니다.

## 노드·센서 목록이 보이지 않음

| 증상 | 확인 |
|---|---|
| 노드 조회 DISABLED | `EDGEAI_INFRASTRUCTURE_SOURCE`와 개발 스크립트/IDE 실행 차이 |
| 노드 조회 UNAVAILABLE | 선택한 context 또는 API 주소, get/list 권한, 네트워크 |
| 서버·엣지 분류 불명 | 역할 라벨 누락 또는 서로 충돌하는 라벨 |
| 센서 목록 없음 | EdgeX 활성화, Core Metadata 주소와 장치 등록 |
| 한 보드가 여러 행 | 채널별 EdgeX 논리 장치인지 확인 |

[인프라 연결](../guides/infrastructure-inventory.md)의 원본을 기준으로 비교합니다.
앱 장치를 등록했다고 EdgeX 목록에 자동 등록되지는 않습니다.

## GPU·NPU 또는 메모리 값이 없음

1. 장착 정보와 사용량 지표를 구분합니다. 장착된 Hailo 등이 사용률 exporter를 갖는 것은 아닙니다.
2. Prometheus에서 해당 exporter의 `up`, 수집 시각, 원본 노드 라벨을 확인합니다.
3. 장비 신원을 유일하게 연결할 수 있는지 확인합니다.
4. 온도·전력·가속기 메모리는 장치·exporter의 지원 범위를 확인합니다.

미수집 값을 0으로 처리하지 않습니다. 하드웨어가 호스트에 인식되지 않으면 화면 수정으로 해결할 수 없습니다.
자세한 지표는 [메트릭 연결](../guides/node-metrics.md)을 참조합니다.

## 센서는 UP인데 측정값이 오래됨

UP은 EdgeX 운영 상태이며 Reading 수신 시각이 아닙니다. Core Data의 최신 Reading과
Device Service의 수집 상태를 확인합니다. **지금 읽기** 결과와 저장 이력도 서로 다릅니다.
쓰기 버튼이 없다면 Core Command의 `set` 지원과 프로필의 읽기 전용 설정을 확인합니다.

## 삭제가 409로 거절됨

프로필·장치·VD를 참조하는 연결과 실행 이력을 확인합니다. 장치나 VD를 해제해도 보존할
실행 이력까지 삭제되는 것은 아닙니다. [등록 정리](../guides/platform-usage.md#영구-삭제)를 따릅니다.

## 워크플로 화면·실행이 동작하지 않음

- 화면/API 404: API와 Dashboard의 `EDGEAI_WORKFLOW_ENABLED`를 확인합니다.
- DDS 서버 연결 실패: `EDGEAI_PLATFORM_SERVICE_URL`과 서버 간 토큰, FastAPI 상태를 확인합니다.
- Buildx 실패: Docker 소켓 권한, builder, registry 접근, 대상 아키텍처를 확인합니다.
- Git 저장 후 Argo 실패: 저장된 YAML과 Application 상태를 먼저 확인합니다.
- Spring 작업 대기: Runtime·VD·Remote·STREAM 기능과 실제 실행 자원·저장소를 확인합니다.

## CSRF 403 또는 결과 미확정

직접 API를 호출했다면 `/api/v1/csrf`에서 받은 토큰과 같은 세션 쿠키를 사용합니다.
복원 조회 전용 모드는 유효한 CSRF가 있어도 변경을 거절합니다.
감사 `OUTCOME_UNKNOWN`은 성공·실패를 확정할 수 없는 상태입니다. 원래 요청 키와 대상 리소스를 조회합니다.

해결되지 않으면 재현 순서, 경로, 시각, HTTP 상태, 감사 ID와 배포 revision을 기록합니다.
토큰·쿠키·비밀번호·센서 개인정보는 공유 로그에서 제외합니다.
