# 현재 상태

2026-10-06 확인. 전체 플랫폼 개발·수용 검증은 아직 완료되지 않았습니다.

## 개발 단계

- **완료된 단계:** M0–M4, M6의 해당 구현·검증 범위.
- **현재 우선순위:** 실제 GPU·NPU 노드와 서비스 실행 연결, 기관 워크플로 편집기 연결. 종합 검증은 후속으로 미룹니다.
- **남은 범위:** M5의 외부 시스템 계약·상태형 복원 수용, M8 성능 기준, M9 종합 운영/복구, M10 실장비·실모델 수용.
- 단계별 범위는 [PLAN.md](PLAN.md)를 확인하세요.

## 이번 작업

- 사용자 요청으로 워크플로 제외 결정을 변경하고 시각 편집·실장비 연결을 우선합니다.
- Kubernetes에서 10개 노드 모두 Ready, GPU 자원 등록 노드 5개와 NPU 자원 등록 노드 3개를 관측했습니다.
  NVIDIA GPU/공유 GPU 슬롯, Hailo H8, Mobilint 자원입니다. 실제 추론 성공을 뜻하지 않습니다.
- 노드의 전체 allocatable 자원을 DB V41·API·화면에 연결했습니다. 서비스 등록에서 실제 노드·GPU/NPU 요청량을 선택해 실행 규격에 반영합니다.
- 기관 도구의 ReactFlow 캔버스·노드 편집 방식을 우리 SERVICE Profile·DAG 버전·Run API에 맞게 이식했습니다.
  연결선은 실제 DAG 의존 관계가 됩니다. 원본의 DDS YAML/Gitea/FastAPI API를 그대로 연결한 것은 아닙니다.
- Java 컴파일과 Dashboard 타입·lint 검사는 통과했습니다. 이 변경의 빌드·배포는 진행 중이며 실모델·성능·복구 종합 검증은 실행하지 않습니다.

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

실장비 자원과 워크플로 편집·실행 연결을 배포합니다. 실모델·성능·복구 검증은 이후 진행합니다. 공유 Ingress 상태 게시 문제와
서버 변경에도 재빌드되는 MinIO의 배포 대기 시간은 남은 운영 개선 사항입니다.

과거 진행 이력은 [보관 기록](docs/history/progress-through-2026-10-06.md)에 있습니다.
이 파일은 최신 상태로 교체하며 과거 실행 로그를 계속 덧붙이지 않습니다.
