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
- 관련 Java 테스트 10개와 Dashboard 타입·lint·빌드 검사는 통과했습니다. 이번 변경의 CI와 배포는 확인 중입니다.
- 자동 CI 간소화는 완료했습니다. 백업·복구·부하·Kubernetes 전체 검증은 `full_verification=true` 수동 실행입니다.

## DB와 배포

- CI [37411420254](https://github.com/dsa04156/edgeai/actions/runs/37411420254)는 전체 7개 job이 성공했습니다.
- GitOps `a50e0e7` 배포에서 소스 `9c8ebba`의 API·Dashboard·MinIO 실행 이미지 일치,
  Pod Ready·PVC Bound·API와 Dashboard HTTP 200을 확인했습니다.
- ArgoCD는 Git 동기화 완료지만 기존 Ingress 주소 게시 문제로 전체 health는 Progressing입니다.
- Kubernetes DB는 V40 적용 성공을 확인했습니다. 이번 기능 제외에는 DB 변경이 없습니다.

## 다음 순서

1. 워크플로 제외 변경의 CI·GitOps 자동 배포 확인.
2. 배포된 메뉴·직접 URL·공개 API·Swagger에서 제외 상태와 기존 관리 기능 확인.

과거 진행 이력은 [보관 기록](docs/history/progress-through-2026-10-06.md)에 있습니다.
이 파일은 최신 상태로 교체하며 과거 실행 로그를 계속 덧붙이지 않습니다.
