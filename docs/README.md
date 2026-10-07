# EdgeAI 문서

EdgeAI는 엣지 AI 서버, 엣지 디바이스, 센서와 가상 장치를 관리하고 서비스 배포·실행 상태를 조회하는 플랫폼입니다.
이 문서는 저장소의 현재 구현을 기준으로 사용 방법과 운영 경계를 설명합니다.

## 처음 사용하는 경우

1. [EdgeAI 소개](getting-started/overview.md)에서 구성 요소와 적용 범위를 확인합니다.
2. [로컬 빠른 시작](guides/local-development.md)으로 API와 대시보드를 실행합니다.
3. [첫 프로필 등록](getting-started/first-profile.md)을 따라 등록·조회·정리 흐름을 익힙니다.
4. 실제 장비가 있는 환경은 [인프라 연결](guides/infrastructure-inventory.md)과 [센서 등록](guides/sensor-registration.md)·[센서 사용](guides/sensor-controls-and-vd-deletion.md)으로 이어갑니다.

## 목적별 문서

| 문서 영역 | 다루는 내용 | 시작 문서 |
|---|---|---|
| 시작하기 | 제품 범위, 준비, 첫 작업 | [소개](getting-started/overview.md) |
| 핵심 개념 | 프로필, 장치, 노드, 가상 장치, 워크플로의 관계 | [리소스 모델](concepts/resources.md) |
| 사용 가이드 | 화면과 API를 이용한 작업 | [관리 기능 사용](guides/platform-usage.md) |
| 운영 | 배포, 관측, 장애 진단, 백업과 복원 | [운영 안내](operations/overview.md) |
| 참고 자료 | API, 설정, 상태, 용어 | [API 참고](reference/api.md) |
| 기여하기 | 코드 구조, 변경·검증·문서 작성 | [개발 참여](contributing/development.md) |

## 문서의 기준

API 필드와 오류 코드는 OpenAPI, 기본 설정은 애플리케이션 설정과 `.env.example`,
DB 제약은 Flyway migration이 기준입니다. [원 설계와 현재 구현](reference/design-alignment.md)을 함께 대조할 수 있습니다. 웹에서 [문서를 검색](guides/web-documentation.md)할 수 있습니다.

구현 여부와 실제 배포 상태는 다릅니다. 기능 플래그·외부 서비스·장비 지원이 필요한 기능은
각 안내의 준비 사항을 확인하세요. 최신 개발 범위는 [현재 상태](../PROGRESS.md)와 [개발 단계](../PLAN.md)에 있습니다.

## 개발 기록

[설계 결정](adr/), [검증 기록](evidence/), [과거 문서](history/), [단계별 요구사항](requirements/)은
설계·시험 당시의 근거입니다. 사용자 안내와 별도로 찾아볼 수 있으며 당시의 성공·제약을 현재 배포 상태로 해석하지 않습니다.
세부 복구 명령과 구현 계약은 [운영 심화 자료](operations/backup-and-recovery.md)에서 필요한 범위만 선택합니다.
