# M6 구현 전 요구사항 확인

2026-10-02에 Notion의 전체 설계/API/ERD/실행 지시 본문을 다시 조회했다. 반환된 원문 수정 시각은
2026-10-01이며 기존 [출처](../architecture/sources.md)와 같다. 이 문서는 요구사항 확인이며 M6 구현·완료 기록이 아니다.

## 원문이 요구하는 동작

- 영속 VirtualDevice는 Device·Node·Pod/Container와 별개다. source binding이나 실제 실행 위치가
  바뀌어도 vdId를 유지하고 현재/과거 binding 이력을 보존한다.
- VDProfile은 sensorMirror/processing/emulation 종류, source 조건, serviceProfileRef, state와
  runtime policy를 표현한다. 기존 Profile 발행 버전은 수정하지 않는다.
- 생성/목록/상세/수정/해제의5개 공개 API를 제공한다. Provision/Drain은 추적 가능한 Operation으로
  상태를 확인한다. 생성 행 저장만으로 Ready를 표시하지 않는다.
- `POST /workflow-runs`의 VD 정책은 지정 VD의 활성 Runtime을 통해 실제 실행한다. 단순히 VD의
  Node UUID를 복사해 독립 Job을 생성하는 구현은 이 요구사항의 완료 증거가 아니다.
- demo-vd는 생성→Ready→Runtime 교체→Drain을 실제로 수행한다. 교체 후에도 vdId는 같아야 한다.

근거: [전체 설계 §5/§8](https://app.notion.com/p/3ecbafd382d681b295f4f878aad79160),
[API §3/§5/§7](https://app.notion.com/p/3ebbafd382d681feb4a5c3610d9d3b3b),
[ERD](https://app.notion.com/p/3ebbafd382d681cf8568e6d870fe97f3),
[실행 지시 §6](https://app.notion.com/p/3ebbafd382d681bd920ae91452b0463a).

## 구현 전에 구체화할 계약

원문은 source cardinality·상세 Request/Response·상태 전이·VDSlot을 확정하지 않았다.
다음 구현에서 source 수와 종류/호환성, source 변경 revision, 실제 runtime readiness·교체·drain,
동시 Task 수용과 producer fencing, 장애/재시작 복구를 OpenAPI와 새 DDL/ADR로 정해야 한다.
현재 RuntimeInstance는 TaskAttempt 실행 경로를 중심으로 구현되어 있으므로 지속 VD runtime과
Task 실행의 소유·수명을 명시해야 한다. 가짜 Workflow/Task나 Pod 별칭으로 이를 대체하지 않는다.

수용 증거는 DB FK/동시성/이력, API·UI·Swagger, 실제 runtime readiness/교체/drain과 VD를 통한
실제 Task/Result, API 재시작·실패·취소 및 demo-vd를 포함해야 한다. M7의 다중 장치 스트림과
M10의 실장비 수용을 M6 관리 화면 성공으로 대체하지 않는다.
