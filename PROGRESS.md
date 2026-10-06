# 현재 상태

2026-10-06 확인. 전체 플랫폼 개발·수용 검증은 아직 완료되지 않았습니다.

## 개발 단계

- **완료된 단계:** M0–M4, M6의 해당 구현·검증 범위.
- **현재 진행:** M7 다중 장치 STREAM 실행·전환·복구.
- **남은 범위:** M5의 외부 시스템 계약·상태형 복원 수용, M8 성능 기준, M9 종합 운영/복구, M10 실장비·실모델 수용.
- 단계별 범위는 [PLAN.md](PLAN.md)를 확인하세요.

## 이번 작업

- 사용자 요청에 따라 자동 CI를 기본 단위·계약·타입/lint·이미지 smoke 위주로 줄였습니다.
- 백업·복구·부하·Kubernetes 전체 검증은 `full_verification=true` 수동 실행으로 분리했습니다.
- Runner native AMD64/ARM64의 111+112개 검증과 고정 이미지 provenance는 유지합니다.
- 기존 CI [37406753649](https://github.com/dsa04156/edgeai/actions/runs/37406753649)는
  scaffold/storage/native 2종/Runner 발행 성공 후, 사용자 요청으로 긴 Kubernetes 검증을 취소했습니다.
- 경로 정리 누락과 VD Python 진입점 문제는 수정·푸시했습니다. 실제 Kubernetes VD 완료/전환
  두 경로와 결과6개·실행 기록·자원 정리는 `20261006T025722Z-f68dcdc7`에서 통과했습니다.
- Session/Source/측정 시험 fixture 변경3파일은 로컬에 보존하며 이번 CI 변경에 포함하지 않습니다.

## DB와 배포

- 기존 Kubernetes 배포는 API·Dashboard HTTP200, Pod5개 Ready입니다.
- ArgoCD는 Git 동기화 완료지만 기존 Ingress 주소 게시 문제로 전체 health는 Progressing입니다.
- 기존 배포 DB는 V36, 로컬 DB는 V34입니다. 저장소 마이그레이션은 V40입니다.
- 간소화한 CI의 성공 및 신규 이미지 자동 배포·DB 반영은 확인 중입니다.

## 다음 순서

1. 기본 CI 통과와 GitOps 이미지 digest 자동 커밋 확인.
2. ArgoCD 동기화·정확한 실행 이미지·DB V40·HTTP 및 기존 데이터 보존 확인.

과거 진행 이력은 [보관 기록](docs/history/progress-through-2026-10-06.md)에 있습니다.
이 파일은 최신 상태로 교체하며 과거 실행 로그를 계속 덧붙이지 않습니다.
