# 원 설계 흐름 정렬 검증 — 2026-10-06

## 범위

사용자의 원 설계 정렬 요청에 따라 [설계 원문](../architecture/sources.md), 기존 VD 소비 계약·binding lifecycle,
Workflow·Run·Task·Result API를 대조했습니다. 대조 결과와 남은 수용 항목은 [설계 대조](../reference/design-alignment.md)를 따릅니다.
기존 작업 트리의 관리·센서·Prometheus·DDS/GitOps·문서 변경을 보존했습니다. 이 작업에서는 백엔드 도메인·DB migration·OpenAPI를 변경하지 않았습니다.

## 변경

- VD 기본 템플릿을 sensorMirror와 필수 LIVE 원본으로 구성하고 SERVICE/DEVICE 버전·원본 키·필수 여부·출처·STATELESS·실행 제한 양식을 제공했습니다.
- 템플릿의 필수 원본·정확한 규격 종류·허용 출처·정수 제한을 전송 전에 검사합니다. 서버의 실행 소비 검증은 그대로 유지합니다.
- 인스턴스 생성·수정은 템플릿의 sourceKey별 호환 ACTIVE 장치만 선택합니다. 비워 둔 선택 원본은 요청에서 제외합니다.
- 원본/실행 연결 이력, 영속 VD ID와 runtime 세대·기간·노드·Operation을 구분했습니다.
- `/workflows`는 Spring DAG 실행을 기본으로 하고 기존 DDS·Buildx·Gitea·Argo CD는 `?mode=dds`로 유지했습니다.
- 관측 장비의 요구사항 적용은 자원·아키텍처만 기본 반영합니다. hostname 제한은 명시적 체크로 적용합니다.
- 변경한 선택 필드·JSON 입력에 명확한 접근성 이름을 제공했습니다.

## 검증 조건

- VD 템플릿·호환 장치 단위2개와 기존 DAG 연결/파일·자동 정렬 단위4개 통과.
  잘못된 SERVICE 종류·필수 원본 누락·미지원/중복 출처·runtime 범위·상태형 모드·버전/출처/해제 상태 불일치를 검사했습니다.
- 문서 카탈로그 단위3개 포함 프론트 단위9개, TypeScript·전체 lint·production build 통과.
  문서 검사73개/주요33개·로컬 링크471개·API54개 통과, standalone 문서347개 패키징 확인.
- 백엔드 단위177개 통과(실패·오류·skip 0). 기존 VD lifecycle·Operation·Workflow·Result와 관리 API 회귀 범위입니다.
- 기존 Next 개발 서버 `127.0.0.1:13080`에서 독립 Playwright CLI로 아래 흐름을 확인했습니다.
  모든 해당 브라우저 관리 API 조회·변경은 route interception으로 모의했습니다. 운영 DB·클러스터 변경을 뜻하지 않습니다.

| 브라우저 흐름 | 확인 |
|---|---|
| VD 템플릿 발행 | 종류·규격 참조·원본 키·runtime 정책 반영, 필수 원본 누락 시 POST 없음 |
| VD 등록 | 잘못된 규격/출처·해제 장치 선택에서 제외, 선택 원본 생략 |
| 원본 교체 | PATCH revision, 동일 VD ID, 현재 연결과 닫힌 이력 표시 |
| 실행 교체 | 최신 revision·Idempotency-Key, Operation 응답, 같은 VD의 2세대 runtime 표시 |
| 장비 요구사항 | 기본 hostname 제한 없음, 명시적 선택 시 hostname 제한 포함 |
| 기본 DAG | SERVICE 2개·BATCH 포트 연결, Workflow 생성·DAG 불변 버전 발행 |
| 실행·조회 | 발행 버전 AUTO Run 요청·멱등 키, Task·Attempt·Result API 조회, 미확정 결과 표시 |
| 모바일 | 390px VD 템플릿·VD 상세·Run 상세 가로 넘침 없음 |

두 관리 브라우저 흐름과 새 설계 대조 문서(다이어그램 렌더링·390px 읽기)에서 pageerror 0개를 확인했습니다. 캡처는 `output/playwright/plan-vd-*`,
`plan-workflow-*`에 저장했고 데스크톱 양식과 모바일 상세/양식을 시각적으로 확인했습니다.

## 한계

모의 응답의 SourceBinding·RuntimeBinding 이력은 UI 요청·표시 검증입니다. 이번 작업에서 실 DB의 교체,
실제 Pod 재기동, Gitea push·Buildx build·Argo sync, 센서 명령, 실제 GPU/NPU 모델을 실행하지 않았습니다.
실제 외부 계약·상태형 복원·STREAM 전체 조합·성능·identity/RBAC·종합 복구 수용은 완료로 간주하지 않습니다.
앱 서버를 시작·재시작하거나 배포하지 않았습니다.
