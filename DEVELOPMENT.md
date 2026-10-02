# 개발 지침

## 적용 범위

새 구현용 `edgeai` 저장소다. 기존 `edge-ai-workspace`는 사용자 별도 요청 없이는 구현 기준이나 호환성 제약으로 사용하지 않는다.
기존 루트 `AGENTS.md`의 Semantica 규칙은 유지하며 모듈별 차이는 하위 `AGENTS.md`에서 설명한다.
에이전트 셸은 `rtk`, 미지원 명령·원시 검증은 `rtk proxy`를 사용한다.

## 작업 순서

1. 전체 설계도 → API 정의서 → ERD → implementation-contract → verification-matrix 순서로 읽는다.
2. 문서 충돌은 ADR에 기록하고 현재 단계에 필요한 최소 범위만 정합화한다.
3. OpenAPI → Flyway → 도메인/adapter/API → Dashboard → 시험 순서로 수직 슬라이스를 만든다.
4. 실제 시험의 명령·exit code·환경·증거를 남기고 `PROGRESS.md`를 갱신한다.
5. 외부 차단과 독립 구현을 분리한다. 미구현·미실행은 PASS로 보고하지 않는다.

## 경계

- 백엔드는 역할별 계층형 Java 패키지를 사용한다: `controller` → `service` → `repository`.
- HTTP DTO는 `dto`, Spring 설정은 `config`, 예외·HTTP 오류 변환은 `exception`, JSON 처리 보조 코드는 `support`에 둔다.
- `domain`의 모델·저장소 인터페이스와 `adapters`의 DB 구현은 기존 Gradle 모듈 경계를 따른다. 실제 경로는 [아키텍처의 패키지 구조](docs/architecture.md#백엔드-패키지-구조)를 참고한다.
- Physical Device / ExecutionNode / VirtualDevice / RuntimeInstance를 분리한다.
- ProfileVersion·WorkflowVersion은 발행 후 불변이다.
- retry/offload는 같은 Task의 새 Attempt다.
- domain에서 Kubernetes, MQTT, S3, Remote API를 직접 호출하지 않는다.
- AUTO는 PodSpec 요구조건을 전달하며 kube-scheduler가 Node를 선택한다.
- 일반 경로의 `nodeName` 직접 지정은 금지한다.
- Job 성공과 검증된 결과 COMMITTED를 구분한다.
- raw sensor stream·대형 artifact는 PostgreSQL 관리 메타데이터와 분리한다.
- 외부 2세부 계약·GPU/NPU capability를 추정해 고정하지 않는다.

## 개발 명령

루트에서 `bash scripts/bootstrap.sh`, `bash scripts/verify-all.sh scaffold`를 실행한다.
실 DB는 `test-integration.sh`, 전체 health 경로는 `test-health.sh`로 검증한다.
공유/운영 클러스터 mutation, 실제 구동기 제어, 권한 확대는 승인 없이 수행하지 않는다.
Git push는 해당 작업에서 사용자가 명시적으로 승인한 범위만 수행한다.
무한 retry, `git reset --hard`, `git clean`, 비테스트 DB/PV 삭제를 사용하지 않는다.
