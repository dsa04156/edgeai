# 문서 안내

**처음에는 아래 네 가지면 됩니다.** 상세 운영 절차와 과거 기록은 필요할 때 찾아보세요.

| 목적 | 시작 문서 |
|---|---|
| 로컬 실행·환경변수·DB | [로컬 개발](guides/local-development.md) |
| 현재 개발 단계와 남은 작업 | [현재 상태](../PROGRESS.md) · [단계](../PLAN.md) |
| Spring Boot 구조·API·DB | [아키텍처](architecture/architecture.md) · [ERD](architecture/erd.md) · [Swagger](guides/swagger-ui.md) |
| CI/CD·Kubernetes·ArgoCD | [배포 안내](operations/cicd.md) |

## 필요할 때 보는 상세 문서

| 폴더 | 내용 |
|---|---|
| [guides](guides/) | 로컬 개발, 화면/API 사용, Swagger |
| [architecture](architecture/) | 아키텍처, API 범위, ERD, 구현 계약, 설계 출처 |
| [requirements](requirements/) | 단계별 상세 수용 범위 |
| [operations](operations/) | CI/CD, Remote, 관리 감사; `backup/`은 백업, `recovery/`는 복구 절차 |
| [testing](testing/) | 검증 기준, 명령 목록, 부하 시험 |
| [adr](adr/) | 설계 결정을 바꾼 이유와 당시 제약 |
| [evidence](evidence/) | 개별 검증의 실제 결과·실패·한계 |
| [history](history/) | 정리 이전 README·계획·진행 이력 |

[환경 호환성](compatibility.yaml) · [전체 검증 명령](testing/commands.md) · [스크립트 안내](../scripts/README.md)

현재 상태는 `PROGRESS.md`가 기준입니다. ADR·evidence·history의 날짜별 상태를
현재 배포 상태로 해석하지 않습니다. 새 설명은 해당 분야의 기존 문서에 반영하고,
새 설계 결정이나 독립 검증 근거가 있을 때만 기록을 추가합니다.
