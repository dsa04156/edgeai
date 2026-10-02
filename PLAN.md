# 개발 계획

## 완료: M0 초기화

1. 설계 출처·범위·미확정 사항을 로컬 docs로 정리한다.
2. Spring/Next.js/PostgreSQL health path, Flyway, OpenAPI, 실행 스크립트를 구현한다.
3. 단위·계약·실DB·브라우저·health 시험을 수행하고 evidence를 기록한다.
4. 공개 GitHub 저장소를 생성하고 커밋·푸시한 뒤 CI 결과를 확인한다.

위 항목과 DB 장애·복구 및 실제 MinIO S3 검증을 완료했다. 코드 b469f62의 CI 36834353000은 두 job 모두 success다.
요구사항별 증거는 `docs/evidence/m0-completion-audit.md`에서 확인한다.

## M1 Profile

계약·Flyway V2·순수 도메인/저장 adapter·HTTP·Dashboard 수직 슬라이스를 구현했다.
구체적 결정은 ADR 0002에 기록한다. 검증 결과는 PROGRESS와 M1 evidence를 따른다.
다음 수직 슬라이스는 ProfileVersion을 참조하는 M2 Device/Node/Observation이다.

## 후속 순서

M1 Profile → M2 Device/Node → M3 Workflow/Run/Task/Attempt → M4 PodSpec/Kubernetes/Runner/Result →
M5 Retry/Offload/Remote → M6 VD → M7 다중 장치 DAG/Streaming → M8 부하 → M9 운영/복구/보안 → M10 실장비.

M1부터 각 기능은 설계 → OpenAPI → Flyway → 구현 → unit/integration/contract → 가능한 kind → 증거 순서다.
첫 기능 목표는 Profile 등록부터 검증된 Result까지 연결하는 한 경로다.

## 위험과 재개

- Docker 접근: `bash scripts/preflight.sh compose`. 현재 권한 차단; 권한 있는 개발 환경에서 `dev-up.sh` 재실행.
- 외부 상세 계약: M1 registry 계약은 ADR 0002로 정합화. M2+ 상태 전이·외부 계약은 해당 단계에서 확정.
- MinIO: source build/live health/실제 S3 검증 완료. `bash scripts/dev-storage.sh`와 `bash scripts/test-storage.sh`로 재현한다.
- kind/실장비: 전용 context·namespace·소유 label을 준비한 뒤 해당 단계에서 구현. 기존 context를 변경하지 않는다.
