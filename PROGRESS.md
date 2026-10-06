# 현재 상태

2026-10-06 확인. 전체 플랫폼 개발·수용 검증은 아직 완료되지 않았습니다.

## 개발 단계

- **완료된 단계:** M0–M4, M6의 해당 구현·검증 범위.
- **현재 진행:** M7 다중 장치 STREAM 실행·전환·복구.
- **남은 범위:** M5의 외부 시스템 계약·상태형 복원 수용, M8 성능 기준, M9 종합 운영/복구, M10 실장비·실모델 수용.
- 단계별 범위는 [PLAN.md](PLAN.md)를 확인하세요.

## 이번 작업

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

워크플로 기관 통합 범위가 확정되면 후속 설계를 진행합니다. 공유 Ingress 상태 게시 문제와
서버 변경에도 재빌드되는 MinIO의 배포 대기 시간은 남은 운영 개선 사항입니다.

과거 진행 이력은 [보관 기록](docs/history/progress-through-2026-10-06.md)에 있습니다.
이 파일은 최신 상태로 교체하며 과거 실행 로그를 계속 덧붙이지 않습니다.
