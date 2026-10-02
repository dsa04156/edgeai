# ADR 0007: 실행 중 Task의 노드 전환

상태: 채택, 로컬·실제 kind·CI·배포 검증 완료. M5 running offload의 명시적 Kubernetes 경로이며
자동 정책·Remote·상태형 복원은 후속 범위다.

Notion은 동일 Task의 새 Attempt, 실행 중 전환과 Pending 재시도의 구분, fence/drain/route,
필요 시 checkpoint를 요구한다. 최종 판정 알고리즘과 2세부 Remote API는 미정이다.

## 현재 전환 계약

SERVICE 실행 규격의 선택 필드 `recovery.mode=RESTART`는 동일한 고정 입력으로 처음부터
다시 실행해도 되는 작업임을 발행자가 선언한다. 생략은 NONE이다. 상태를 보존해야 하는 작업은
checkpoint 계약을 추가하기 전까지 이 재시작 전환을 거절한다. 기존 Profile을 자동 변경하지 않는다.

`POST /tasks/{taskId}/offload`는 Idempotency-Key, sourceAttemptId, targetNodeId,
drainTimeoutSeconds(1–600), startTimeoutSeconds(1–600)를 받는다. 공개 Attempt 생성 API는 아니다.
실제 producer가 claim한 RUNNING 작업만 허용한다. target은 현재 source와 다른, 최근 관측된 READY
Linux 노드여야 하고 Profile arch/nodeSelector와 일치해야 한다. 실제 bind는 kube-scheduler가 한다.
작업당 명시적 전환 요청은 최대8회이며 동시에 하나만 진행한다. 같은 키·정규화 입력은 같은 Operation,
다른 입력은409다. 기존 Run 정책은 보존하고 실제 Attempt별 실행 정책/원인을 기록한다.

202와 Operation을 반환하며 `GET /operations/{operationId}` 및 Task 상세의 offloads로 상태를 읽는다.
상태는 DRAINING→STARTING→SUCCEEDED, 실패는FAILED, 취소는CANCELLING→CANCELLED다.
SUCCEEDED는 새 target producer의 claim 확인이며 전체 Task 결과 성공과는 다르다.

## 트랜잭션과 종료

Run 잠금 아래 이전 Attempt를 OFFLOADED, Task를 OFFLOADING으로 바꾸고 Runtime을 STOPPED로
fence한 뒤 DELETE 명령과 Operation을 저장한다. 늦은 upload/commit은 즉시 차단된다.
실제 이전 Runtime TERMINATED와 모든 CREATE 완료 확인 후 새 Attempt/epoch/claim과 target NODE를
생성한다. 새 target claim과 Operation 성공은 같은 Run 잠금/트랜잭션으로 확정한다.
고정 BATCH 입력은 새 Attempt에 전달하고, 결과는 새 producer의 검증된 Result만 하위에 전달한다.
STREAM route/generation과 상태형 checkpoint는 후속 구현이며 BATCH 전환이 이를 대신하지 않는다.

drain 또는 target 시작 deadline을 넘으면 Operation/Task를 실패 처리하고 하위를 SKIPPED로 만든다.
cleanup 명령은 유지한다. 취소는 진행 Operation에도 전달하며 물리 종료 확인 후 완료한다.
재시작한 worker는 영속 Operation에서 이어간다. 완료된 Result는 전환/취소로 덮어쓰지 않는다.

Attempt의 cause는 INITIAL/RETRY/OFFLOAD다. retry maxAttempts는 최초 실행+RETRY 횟수만 센다.
OFFLOAD는 별도8회 한도이며 retry 예산을 소비하지 않는다. 재시도는 마지막 Attempt의 실제 target을
유지한다. 기존 V6 행의 number1은 INITIAL, 이후는 RETRY로 확장한다. 적용된 V1–V6는 수정하지 않는다.

현재 단일 API Deployment의 upgrade는 Recreate를 사용한다. V7 필수 mode/cause와 새 OFFLOADING
상태를 모르는 이전 API writer/worker가 새 버전과 겹치지 않도록 한다. 구버전 종료 후 새 API가
준비될 때까지 요청 중단과 세션 재연결이 발생한다. 무중단/다중 replica 운영은 M9의 호환 migration·
공유 세션·leader/복구 검증을 요구한다. DB를 이전 바이너리에 맞추려고 적용된 V7을 되돌리지 않는다.
근거: [Kubernetes Recreate upgrade](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/#recreate-deployment).

## 남은 범위

측정 기반 자동 trigger 정책과 resource-pressure/latency/failure 입력, Remote adapter·외부 계약,
상태형 checkpoint/drain/route 복원은 계속 M5/M7 계획에 남긴다. 명시적 전환만으로 M5 완료를 선언하지 않는다.
