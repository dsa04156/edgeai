# M7 — VD가 포함된 스트림 그룹의 노드 전환

2026-10-04 KST. ADR0056/V33의 로컬 구현·검증 기록이다. M7 전체 완료 판정이나 새 코드의
CI·배포 완료를 의미하지 않는다. 실제 외부 장치·모델 대신 합성 Device 입력을 사용한다.

## 구현 범위

- 기존 `POST /tasks/{taskId}/offload`로 VD STREAM 작업을 다른 READY NODE로 옮긴다.
  동료 작업은 직전 Attempt의 AUTO/NODE/VD 대상을 유지하며 Task 최초 배치는 변경하지 않는다.
- 공개 응답의 `members[].targetVdId`와 Swagger·PC/모바일 화면에 동료 VD 유지 계획을 표시한다.
- 전체 구성원의 producer·현재 VD 배정/lease·체크포인트를 Run 잠금 아래 검사한다.
  다른 VD의 mutex를 획득하지 않는다. drain 중인 VD가 있으면 producer 차단 전에 거절한다.
- 이전 runtime 종료·VD 자식 slot 회수·broker 권한 회수가 모두 확인된 뒤 OFFLOAD Attempt를 만든다.
  VD supervisor와 다른 Run의 자식은 유지한다. 마지막 VD claim도 전체 Operation 완료에 반영한다.
- V33의 새 열과 DB 제약이 VD 계획·체크포인트·후속 Attempt의 일관성을 보장한다.
  이미 적용한 V1–V32와 격리 DB에 적용한 V33의 bytes를 변경하지 않는다.

## 직접 확인한 증거

원시 실행 결과는 무시된 `docs/evidence/runs/<runId>/`에 보존한다.

| 검증 | runId | 결과와 범위 |
|---|---|---|
| STREAM 그룹·자동 전환 PG 회귀 | 20261003T225210Z-4bf0452d | 49개 PASS. 같은/다른 VD·NODE와 VD 혼합, peer mutex 없음, 고정 계획·멱등 요청·전체 종료/claim, DB 위조·drain 거절, 취소 중 다른 Run 보존 |
| PostgreSQL 전체 | 20261003T225704Z-8df31046 | 223개 PASS, 실패·오류·skip0 |
| 단위·JAR | 20261003T230022Z-b89b6b79 | 105개 PASS·bootJar |
| 계약·MVC·패키징 Swagger | 20261003T230251Z-a7e0304e | 계약5개·MVC26개 PASS, 패키징된 YAML과 생성 타입 일치 |
| 화면 lint/type/build·브라우저 | 20261003T224959Z-f9412930 | 46개 PASS. 동료 VD 표시·기존 요청 재전송 PC/모바일, 두 화면 직접 확인 |
| 실제 VD→NODE 상태 인계 | 20261003T225545Z-84ba8134 | 신규2개 PASS. 실제 supervisor/자식·독립 Node Runner·TLS MQTT·Device SDK·MinIO 상태9 복원, fanout28/chain37 결과 |
| 전체 실제 저장소 회귀 | 20261003T230420Z-d49fb375 | 47개 PASS, 실패·오류·skip0. Kubernetes 제출·Pod 신원은 fixture |
| 실제 Kubernetes 노드 전환 | 20261003T230420Z-53281045 | 3개 PASS. 같은/다른 VD→다른 Node·동료 VD 유지·전환 완료 뒤 취소, Node3Pods/VD7Pods/S3결과6개·소유 자원 정리 |
| 대기 중 API 교체·전환 취소 | 20261003T232628Z-8d401390 | 2개 PASS/Node1Pod·VD5Pods/S3결과3개. 실제 API Pod 교체 뒤 DRAINING·계획·체크포인트·동일 요청 재전송 보존, 다른 노드에서 상태 복원·결과37. 대기 취소는 새 Attempt/결과 없이 종료 |
| 실제 API/DB·Swagger·PC/모바일 | 20261003T231107Z-8434e8c0 | 10개 PASS. DB 중단 시503·기존 API/UI의 DB 재시작 후 복구도 확인 |
| 로컬 V30→V33 업그레이드 | 20261003T231429Z-4bbf79fe | 기존 Task9,371개의 신원·정의·최초 대상 보존, 성공 migration33개. V33 checksum1569742243 |

Kubernetes 시험은 실제 TokenReview·Pod/Node 신원·자식 종료 PROCESS_EXIT·broker 세대 종료와
고정 S3 파일의 version/bytes/SHA/계산값을 확인했다. 이전 두 자식이 종료된 뒤 상태 인계2개,
같은 장치 SDK 객체의 재연결2회·센서 cursor 보존과 peer VD의 새 자식을 확인했다.
선택 작업만 다른 노드의 독립 Runner로 옮기며 초기 Task VD 값은 그대로 유지한다.
시험에 사용한 JAR SHA는 `3f9bfc03329367fdc594d7fa765b79dcee7a66274849fe8bd3617635581fc8ac`다.
이 첫3개 시험의 취소는 전환 완료 뒤 수행했다. 대기 중 API 재시작·전환 취소 근거로 확대하지 않는다.
후속2개는 시험용 격리 DB의 VD 행을 `FOR NO KEY UPDATE`로 잠가 poll receipt 처리를 지연시킨다.
VD를 새로 참조하는 외래키 검사는 허용하고, API Pod를 교체해도 계획과 DRAINING이 유지되는지
확인한 뒤 잠금을 해제한다. 취소는 잠금 중 CANCELLING·아직 후속 Attempt 없음, 해제 뒤
CANCELLED·slot 회수·결과 없음까지 확인한다. 이 시험의 API pool은20개, VD lease는60초다.
공유 노드나 기존 배포에는 잠금·설정 변경을 적용하지 않았다.

## 시험 중 수정과 남은 확인

첫 PG fixture 컴파일은 존재하지 않는 heartbeat 메서드를 호출해 실패했다(224812Z-7556e0f1).
실제 제공하는 execution 조회로 producer fencing을 확인하도록 고쳤다. 다음 PG 실패는 fixture가
권한 worker의 route reconciliation 없이 revoke를 호출한 문제였다(224925Z-4140d76f).
제품 제약을 유지하고 실제 순서대로 fixture를 실행한 뒤49개·전체223개가 통과했다.

추가 Kubernetes 장애 시험의 첫 시작은 VD lease 설정180초가 제품의 상한60초에 거절됐다
(231429Z-c0ca06a5). 제품 상한을 유지하고 시험 값을 기존60초로 복원했다.
이어 PID1 신호를 사용한 대기 장치가 후조건을 충족하지 못했다(231612Z-3552dabd).
실제 제품의 전환 실패로 판정하지 않으며, 시험 전용 DB의 VD 행 잠금으로 poll receipt를
지연시키는 명시적 장벽으로 바꿨다.
해당 장벽의 다음 실행(232022Z-badb6388)에서 다른 VD 전환의 API 교체·계획/상태 인계는
통과했지만 같은 VD의 대기 중 취소 요청은 실패했다. private API 로그에서 의도적으로 잠근
poll/자식 요청이 기본 Hikari 연결5개를 모두 점유한 timeout을 확인했다. pool20만 추가한 실행도
동일 경계에서 실패했다(232335Z-bca84fdf). 같은 VD를 동료 대상으로 저장할 때 외래키의
KEY SHARE가 시험의 FOR UPDATE와 충돌했다. 실제 PG의 두 연결로 충돌/허용 조합4개를 확인하고
FOR NO KEY UPDATE로 고쳐 poll은 막고 외래키는 허용했다. 제품 코드 변경 없이 후속2개가 통과했다.

## 선행 VD STREAM 이미지·배포

소스415a1ce CI37159106124는5jobs 모두 성공했다. 원시JSON17개·PG220·실제 저장소45·
Runner111/MQTT90·STREAM20개/Node40Pods·VD24Pods/S348개·혼합 Remote3개를 감사했다
(232825Z-aa072254). GitOps2227a91의 실제 API/dashboard/MinIO imageID·Ready/PVCBound·
ArgoSynced를 확인했다(232742Z-d9dd8d87). 기존 고정 파일10개의 version/bytes/SHA와
두PVC UID도 보존됐다(232742Z-f9cc7914). 기존 공유 Ingress의 aggregate Progressing은 유지한다.
이 판정은 V31–V32의 선행 이미지이며 이번 V33 변경의 CI·배포 검증을 대신하지 않는다.

Remote STREAM, VD 공유 자원의 자동 전환, 실제 외부 장치·모델 수용, 새 이미지 CI·배포는
별도 남은 범위다. M5 잔여와 M7–M10 전체 목표는 미완료다.
