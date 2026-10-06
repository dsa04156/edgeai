# 개발 단계

전체 목표는 M0–M10 구현과 검증입니다. 최신 작업·DB·CI 상태는 [PROGRESS.md](PROGRESS.md)를 확인하세요.

| 단계 | 남은 구현·검증 게이트 |
|---|---|
| M0 | 완료 — 초기 개발 환경·저장소·Swagger·GitHub Actions/GHCR·ArgoCD 연결 |
| M1 | 완료 — DEVICE/SERVICE/VD Profile 등록·불변 버전 관리·API/UI·실DB 검증 |
| M2 | 완료 — Device/Node/Observation, UI·실DB·CI·실 Kubernetes 읽기·배포 검증 |
| M3 | 완료 — DAG/Run/Task/Attempt·로컬·CI·이미지·ArgoCD·실제 Ingress 검증 |
| M4 | 완료 — 실제 kind·기존 클러스터 Runner/MinIO/Result·실패/취소·CI/배포 검증 |
| M5 | 재시도·전환·참조 Remote 검증 완료. 상태형 checkpoint 복원과 실제 외부 시스템 계약 수용은 남음 |
| M6 | 완료 — 영속 VD·원본/실행 이력·Operation·실제 자식 Task/Result·교체/취소/재시도·CI·배포·UI 검증 |
| M7 | 진행 중 — 다중 장치 BATCH/STREAM DAG·전환·복구 구현/시험, 외부 계약·전체 수용 잔여 |
| M8 | 부분 검증 — 100→300→1,000 장치 관리 부하·인증 병목 개선, 합의 성능 수용 기준 잔여 |
| M9 | 일부 구현 — TLS·재시작 복구·DB/S3/키 백업·관리 HTTP 감사, identity/RBAC·외부 감사/보관·종합 복구 잔여 |
| M10 | 미완료 — 실제 KubeEdge·ARM/x86·GPU/NPU, 실제 모델/2세부 연동, 합의한 성능 수용 기준 충족 |

## 진행 순서

1. 현재 M7 VD/STREAM 수정과 CI·배포 검증을 정리합니다.
2. M5 잔여 계약과 M7 전체 수용 범위를 확인합니다.
3. M8 성능 기준, M9 종합 운영·복구, M10 실장비·실모델을 순서대로 검증합니다.

검증된 구성 요소와 전체 플랫폼 수용 완료는 구분합니다.
실제 외부 시스템 계약·GPU/NPU·성능 수치는 확인 없이 가정하지 않습니다.

## 상세 범위

- [구현 계약](docs/architecture/implementation-contract.md)
- [검증 기준](docs/testing/verification-matrix.md)
- [M6 가상 장치](docs/requirements/m6-requirements.md)
- [M7 다중 장치·스트리밍](docs/requirements/m7-requirements.md)
- [M9 운영·복구](docs/requirements/m9-requirements.md)

이전 세부 계획과 검증 이력은 [보관 기록](docs/history/plan-through-2026-10-06.md)에 있습니다.
