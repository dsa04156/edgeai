# 현재 상태

2026-10-06 확인. 전체 플랫폼 개발·수용 검증은 아직 완료되지 않았습니다.

## 개발 단계

- **완료된 단계:** M0–M4, M6의 해당 구현·검증 범위.
- **현재 우선순위:** 백엔드·Dashboard 관리 흐름 완성. Runner/STREAM 고도화와 종합 검증은 보류합니다.
- **남은 범위:** M5의 외부 시스템 계약·상태형 복원 수용, M8 성능 기준, M9 종합 운영/복구, M10 실장비·실모델 수용.
- 단계별 범위는 [PLAN.md](PLAN.md)를 확인하세요.

## 이번 작업

- Runner/STREAM 고도화를 중단하고 Dashboard의 관리·배치·결과 조회 흐름을 우선합니다.
- 로그인 폼을 제거했습니다. Dashboard 서버가 기존 API 계정을 사용하며 비밀번호는 브라우저로 전달하지 않습니다.
- Profile 기본 양식과 다음 단계 링크, Device/VD의 프로필·장치·노드 선택을 연결했습니다.
- Workflow Builder는 편집 즉시 DAG에 반영하며 작업 매개변수, 포트 선택 연결, 순환·중복 입력 연결 방지를 지원합니다.
- 실행 요청 후 `/runs`로 이동합니다. 선택한 Task를 유지하며 상태·배치·결과를 3초마다 갱신합니다.
- `GET /api/v1/workflow-runs/{runId}/placements`가 최신 Attempt의 실제 노드·Pod·Job·실패 원인을 제공합니다. 내부 claim nonce는 제외합니다.
- 앞선 V41 자원 연결로 GPU·NPU를 포함한 allocatable 자원이 API·화면에 표시됩니다.
  기관 도구는 ReactFlow 편집 방식을 기존 Profile/DAG/Run에 맞게 적용했으며 DDS/Gitea/FastAPI 전체 이식은 아닙니다.
- Java 컴파일·관리 API 계약 시험, Dashboard 타입·lint·빌드가 통과했습니다. 로컬 새 화면에서 운영 API의 장치·GPU/NPU·VD 목록과 Builder 편집을 확인했습니다.
- Dashboard 흐름 소스 `6254c1b`의 CI [37417379765](https://github.com/dsa04156/edgeai/actions/runs/37417379765)는 성공했고 GitOps `30d5dc1`이 생성됐습니다.
- 후속 `1a031d0`은 Swagger 문서 조회의 Basic 인증을 제거했습니다. 직접 관리 API 실행 인증은 유지합니다. 관련 Java 테스트는 통과했고 CI [37418501519](https://github.com/dsa04156/edgeai/actions/runs/37418501519)는 진행 중입니다.
- 최신 이미지의 전체 배포 완료는 아직 확인하지 않았습니다. 기존 수동 브라우저 시험에는 이전 로그인·입력 UI를 참조하는 부분이 남아 있습니다.
- 실모델 추론·성능·복구·전체 브라우저 수용 검증은 사용자 요청에 따라 후속으로 미룹니다.

## 직전 배포 기록

- 기관 간 통합 전까지 워크플로 메뉴·직접 URL·공개 API·Swagger 작업 목록을 제외합니다.
  기본값은 `EDGEAI_WORKFLOW_ENABLED=false`이며 API와 Dashboard에 함께 적용합니다.
- 공유 내부 실행 코드와 기존 DB 데이터는 보존합니다. 다른 Platform-Service 저장소는 검토만 했고 통합하지 않았습니다.
- Java 단위 테스트 124개·API 계약과 Dashboard 타입·lint·빌드 검사는 통과했습니다.
- 워크플로 제외 CI [37413088339](https://github.com/dsa04156/edgeai/actions/runs/37413088339)는 성공했고 실제 메뉴·직접 URL·API·프록시·Swagger 제외를 확인했습니다.
- API는 AMD64 이미지로 56번 서버 `etri-ser0001-cg0msb`에 고정했으며 해당 노드의 Pod Ready를 확인했습니다.
- 서버 변경 시 Runner 재빌드를 생략하고 검증된 기존 digest를 재사용하도록 CI를 분리했습니다.
  Runner 관련 변경·최초 발행·수동 전체 검증에만 AMD64/ARM64 빌드와 검사를 실행합니다.
  소스 버전 분리 및 이미지 재사용 검사 10개와 후속 CI [37414136528](https://github.com/dsa04156/edgeai/actions/runs/37414136528)가 통과했습니다.
  실제 CI에서 Runner native job은 생략됐고 기존 digest 재사용·서버 이미지 발행·GitOps 갱신이 성공했습니다.
- 자동 CI 간소화는 완료했습니다. 백업·복구·부하·Kubernetes 전체 검증은 `full_verification=true` 수동 실행입니다.

## DB와 배포

- GitOps `08224cd`의 소스 `42f7247` 배포에서 API·Dashboard·MinIO 실행 이미지 일치,
  Pod Ready·PVC Bound를 확인했고 후속 API 배치 변경 후에도 기존 Profile·Device 기능과 두 접속 주소의 HTTP 200 검증이 통과했습니다.
- 최신 서버 소스 `a250722`, Runner 소스 `42f7247`로 분리됐습니다. GitOps `b6982d4`의
  API·Dashboard·MinIO 실행 이미지 일치, Pod Ready·PVC Bound·Argo Synced를 확인했습니다.
  마지막 커밋 감지가 지연돼 ArgoCD에 일반 Git 새로고침을 요청했으며, 이후 자동 sync와 MinIO 교체가 완료됐습니다.
- ArgoCD는 Git 동기화 완료지만 기존 Ingress 주소 게시 문제로 전체 health는 Progressing입니다.
- Kubernetes DB는 V40 적용 성공을 확인했습니다. 이번 기능 제외에는 DB 변경이 없습니다.

## 다음 순서

Dashboard 흐름과 공개 Swagger 문서의 자동 배포가 진행 중입니다. 실모델·성능·복구 검증은 이후 진행합니다. 공유 Ingress 상태 게시 문제와
서버 변경에도 재빌드되는 MinIO의 배포 대기 시간은 남은 운영 개선 사항입니다.

과거 진행 이력은 [보관 기록](docs/history/progress-through-2026-10-06.md)에 있습니다.
이 파일은 최신 상태로 교체하며 과거 실행 로그를 계속 덧붙이지 않습니다.
