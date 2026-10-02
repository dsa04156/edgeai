# ADR 0018 — VD 공개 실행 요청과 관측 API

상태: 수락. 날짜: 2026-10-03 KST. 기존 V14/V15 상태 모델을 유지하며 DB migration은 추가하지 않는다.

## 요청과 멱등성

등록 POST는 논리 VD만 만든다. 별도 `POST /api/v1/virtual-devices/{vdId}/provision`, `/replace`,
`/drain`이 실제 lifecycle을 요청한다. 본문은 정확히 revision 하나이며 안전한 정수 범위를 요구한다.
Basic·CSRF와 UUID Idempotency-Key가 필요하다. 내부 registry/supervisor 키와 충돌하지 않도록
공개 키는 `public:<canonical UUID>`로 저장한다. 키 범위는 VD 하나이고 같은 키의 다른 명령·revision은409다.

VD 행 잠금 안에서 재전송 확인과 lifecycle 실행을 수행한다. 새 Operation은202, 재전송은200이며
Location은 `/api/v1/operations/{id}`다. 응답 시 이미 SUCCEEDED인 무실행 drain도 새 요청이면202다.
재전송은 최신 lifecycle 상태를 반환한다. 저장된 요청의 scope(namespace/account/control-plane)가
바뀌면 기존 digest와 충돌하여409이며 요청을 조용히 다른 실행 범위에 적용하지 않는다.

기존 Operation 조회를 `TASK_OFFLOAD | VD_PROVISION | VD_REPLACE | VD_DRAIN` 합집합으로 확장한다.
기존 TASK_OFFLOAD 응답은 유지한다. 새 요청은 기존 진행 요청을 SUPERSEDED로 보존할 수 있다.
PROVISION은 일치하는 실행을 재사용하고, REPLACE는 이전 물리 종료 확인 후 새 세대를 만든다.
DRAIN은 VD 등록과 source 연결을 유지한다. REGISTERED·Operation SUCCEEDED·현재 ready는 별개다.

## 조회와 노출 경계

`GET .../{vdId}/execution`은 REPEATABLE_READ 트랜잭션으로 현재 실행·pending Operation·최근100개
runtime/binding/Operation 이력을 반환한다. 각각 truncation을 명시하며 현재 실행과 진행 작업은
이력 제한과 별도로 조회한다. ready는 조회 asOf 시점에 의도 RUNNING·관측 READY·미만료 lease를
모두 만족해야 한다. API가 조회만으로 lifecycle을 바꾸지 않는다.

공개 DTO에 session·claim nonce·요청 키/digest·내부 configuration·자격을 넣지 않는다.
VD 실행 기능은 기본 비활성이고 runtime 설정과 VD 활성 설정이 모두 필요하다. 비활성 쓰기는503,
등록 및 과거 상태 조회는 계속 가능하다. 공개 실행을 추가했다고 VD Task 배정을 활성화하지 않는다.

## 화면

기존 VD 상세에 현재 준비·실행 세대·노드·요청·이력을 추가한다. 3초 주기 조회, 실패 시 상태 확인 필요,
lease 만료 시 준비 표시 해제, 새로고침, 비활성 기능, 재전송 상태를 제공한다. 서버 asOf/leaseUntil 차이에서
요청 시작 이후 monotonic 경과 시간을 빼어 시계 차이·응답 지연이 준비 표시를 연장하지 않게 한다. 요청 키와 revision은
실패 후에도 유지하고 명시적으로 닫거나 성공할 때 새 요청을 준비한다. 자격은 브라우저 영속 저장소에 넣지 않는다.

## 수용 범위

이번 검증은 Spring MVC/security→실제 PostgreSQL, UI 상태/요청 fixture 및 실제 API·DB의 등록/비활성
화면·Swagger·장애 복구를 포함한다. 준비/물리 종료 이벤트는 lifecycle 시험 fixture다. 실제 Kubernetes
Pod→감독→poll 전체 흐름·VD Task claim/Result·demo-vd와 API 프로세스 재시작은 후속 수용 게이트다.
