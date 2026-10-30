# EdgeAI 문서

EdgeAI는 엣지 AI 서버, 엣지 디바이스, 센서와 가상 장치를 관리하고 서비스 배포·실행 상태를 조회하는 플랫폼입니다.
이 문서는 저장소의 현재 구현을 기준으로 사용 방법과 운영 경계를 설명합니다.

## 처음 사용하는 경우

1. [EdgeAI 소개](getting-started/overview.md)에서 구성 요소와 적용 범위를 확인합니다.
2. [로컬 빠른 시작](guides/local-development.md)으로 API와 대시보드를 실행합니다.
3. [첫 프로필 등록](getting-started/first-profile.md)을 따라 등록·조회·정리 흐름을 익힙니다.
4. 실제 장비가 있는 환경은 [인프라 연결](guides/infrastructure-inventory.md)과 [센서 등록](guides/sensor-registration.md)·[센서 사용](guides/sensor-controls-and-vd-deletion.md)으로 이어갑니다.

KubeEdge 장치 실행 계층은 [Mapper 구성](../deploy/kubeedge/README.md)을 확인하세요.
서비스를 Pod로 실행할 때의 CPU·메모리·GPU/NPU 요구량은 [서비스 자원 프로파일](guides/resource-profiling.md)에서 실측·KRR 추천으로 확인하고 2-1 전달 계약으로 제공합니다. 시간 기반 [측정 기반 DAG 배치](guides/operational-scheduling.md)와 별개입니다. 모델의 실행 이미지(기존 Registry digest)와 요구량은 [AI 모델 관리](guides/ai-model-catalog.md)에서 등록·조회하며 워크플로 연동은 후속입니다.
CPU·GPU 부하에 따른 실제 Pod 이동·복귀는 [오프로딩 테스트](guides/offload-lab.md), 일반 전환 조건은 [노드 전환 정책](guides/node-placement-offload.md)을 확인합니다. NPU 자동 전환은 아직 지원하지 않습니다.
가상 센서는 [연속 센서 계산](guides/vd-processing.md)에서 입력·계산·출력과 활성화 조건을 확인합니다. 원본 센서의 최신값, 가상 센서의 계산 결과, 일반 SERVICE 워크플로 실행은 서로 다른 기능입니다. [구형 방식 종료](guides/virtual-devices.md)는 현재 연속 VD와 과거 supervisor 기록을 구분합니다.

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
DB 제약은 Flyway migration이 기준입니다. [원 설계와 현재 구현](reference/design-alignment.md)을 함께 대조할 수 있습니다. [문서 읽기와 갱신](guides/web-documentation.md)에서 목록 관리 방식을 확인합니다.

구현 여부와 실제 배포 상태는 다릅니다. 기능 플래그·외부 서비스·장비 지원이 필요한 기능은
각 안내의 준비 사항을 확인하세요. 최신 개발 범위는 [현재 상태](../PROGRESS.md)와 [개발 단계](../PLAN.md)에 있습니다.

## 개발 기록

[설계 결정](adr/)과 [단계별 요구사항](requirements/)은 구현 경계와 수용 기준을 설명합니다.
과거 검증 보고서와 상태 문서 복사본은 Git 이력에서 확인합니다. 기존 인용은 해당 커밋으로 연결하며,
당시 성공·제약을 현재 배포 상태로 해석하지 않습니다.
세부 복구 명령과 구현 계약은 [운영 심화 자료](operations/backup-and-recovery.md)에서 필요한 범위만 선택합니다.
