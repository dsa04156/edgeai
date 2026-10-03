# ADR0054 — BATCH 작업별 VD·Remote 대상과 잠금 경계

2026-10-04. 구현과 로컬·실제 Kubernetes VD 혼합 실행 검증을 통과했다.
새 이미지·CI·배포와 M7 전체 수용 완료 기록은 아니다. [검증 범위](../evidence/m7-mixed-task-targets.md).

`taskExecutions`를 AUTO/NODE/VD/REMOTE 정책으로 확장한다. Run의 execution은 기본값이며
실제 각 Task의 최초 대상은 별도로 고정한다. VD는 해당 Task의 SERVICE 버전과 비교하고
Remote는 생성 시 선택한 provider key/configuration digest/sourceMode를 Task에 저장한다.
대기 중인 하위 Task도 같은 대상 스냅샷을 갖는다. 재전송은 현재 VD readiness나 Remote 설정을
다시 선택하기 전에 기존 Run을 반환한다. retry는 직전 Attempt, offload는 명시한 새 대상을 따른다.

V30은 Task의 최초 VD/Remote 필드와 INITIAL Attempt의 일치를 강제한다. V1–V29는 변경하지 않는다.
Run 요청의 providerKey와 Task의 내부 제공자 binding을 구분한다. endpoint·자격은 공개 요청이나
응답에 넣지 않는다. 서로 다른 VD의 SERVICE와 slot/실행 세대는 독립적으로 검증한다.

기존 Run 잠금이 기본 VD를 자동으로 잠그는 규칙은 여러 VD를 가진 Run에서 맞지 않는다.
Run 상태/취소/하위 작업 준비는 Run만 잠근다. 실제 VD 생산자 인증·배정·결과 확정·slot 반환은
해당 Attempt의 VD → Run 순서로 잠근다. 한 VD를 잠근 채 다른 VD에 새 배정을 만들지 않는다.
하위 작업 준비는 미배정 Runtime까지만 만들고, 실제 배정은 그 VD의 poll이 처리한다.
VD 행의 동시 변경은 `FOR NO KEY UPDATE`로 직렬화한다. VD ID는 불변이므로 외래 키의
KEY SHARE를 막을 필요가 없다. 기존 FOR UPDATE는 하위 Attempt/Runtime의 VD 외래 키 확인도
막아 다른 VD의 poll이 같은 Run을 기다릴 때 순환 대기를 만들 수 있다. 저장소와 VD 관련
trigger의 잠금을 함께 바꾸며 실제 생산자 권한/slot 상호 배제는 유지한다.
공유 Run에 속한 두 VD의 poll·취소·commit 경합과 하위 작업 해제를 실제 PostgreSQL에서 검증한다.

여러 VD를 선택하는 최초 요청은 UUID 문자열 순서로 VD들을 잠근 뒤 새 Run을 생성한다.
이미 존재하는 Run을 그 트랜잭션에서 추가로 잠그지 않는다. 네트워크/파일 검증은 기존대로
DB 트랜잭션 밖에서 수행하고 결과 확정 시 해당 생산자와 Run을 다시 검증한다.

이번 연결의 실제 실행 대상은 BATCH다. STREAM의 VD/REMOTE transport/권한·그룹 checkpoint는
아직 별도 연결이 필요하며 명시적으로 거절한다. VD/Remote를 포함한 실행의 자동 전환도 기존
측정 계약으로 지원되지 않는다. 이 제한을 전체 M7 완료로 축소하지 않는다.
