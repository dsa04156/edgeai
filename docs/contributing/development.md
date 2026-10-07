# 개발에 참여하기

변경은 기존 도메인 경계를 유지하면서 API·저장소·화면을 함께 검증합니다.
처음에는 [빠른 시작](../guides/local-development.md)과 [아키텍처](../architecture/architecture.md)를 읽습니다.

## 변경 순서

1. 해결할 문제와 영향을 받는 리소스를 정합니다. 기존 ADR과 최신 지원 범위를 확인합니다.
2. 공개 계약이 바뀌면 `contracts/openapi/`를 먼저 갱신합니다.
3. DB 변경은 기존 Flyway 파일을 수정하지 않고 새 migration으로 추가합니다.
4. domain 인터페이스, adapter, app service·controller와 Dashboard 호출을 연결합니다.
5. 변경한 동작을 검증하고 사용 가이드·설정 설명을 함께 갱신합니다.

프로필·워크플로 버전의 불변성, 실제 자원과 등록의 분리, Result 검증, 이력 보존 조건을 유지합니다.
외부 서비스의 동작이나 장비 성능을 확인 없이 가정하지 않습니다.

## 검증 선택

```bash
make test
corepack pnpm --filter @edgeai/dashboard lint
corepack pnpm --filter @edgeai/dashboard build
```

계약 변경 후에는 `corepack pnpm contract:generate`와 계약 검사를 실행합니다.
DB·실장비·복구 검사는 [테스트 안내](../testing/commands.md)에서 필요한 환경을 확인한 뒤 선택합니다.
단위 테스트 성공을 전체 배포·성능·복구 성공으로 확대하지 않습니다.

## 문서와 변경 설명

사용자에게 바뀐 동작, 실행 조건, 검증 범위와 한계를 설명합니다.
일상 사용법은 가이드, 설계 변경 근거는 ADR, 실제 시험 결과는 evidence에 기록합니다.
[문서 작성 기준](documentation.md)을 따르며 대화 기록과 개인 환경 값을 사용자 문서에 누적하지 않습니다.

에이전트별 저장소 작업 지침은 루트와 모듈의 `AGENTS.md`, 개발 규칙은 `DEVELOPMENT.md`를 확인합니다.
