# ADR0015: VD 실행 세대·Operation·명령의 영속 수명

상태: V14와 DB lifecycle 구성 요소 구현. 실제 Kubernetes gateway/poll/Task 배정 연결은 후속이다.

## 저장과 직렬화

`vd_runtime`은 영속 VD와 분리한 실행 UUID/generation을 가진다. namespace·Pod 이름·claim nonce,
고정 SERVICE Profile·source binding ID 집합·배치와 runtime policy·제어 서버 origin을 불변 스냅샷으로
보존한다. namespace/Pod 이름은 결정적이며 토큰 자체는 저장하지 않는다. 실제 Pod UID는 한번만
결합하고 인증된 supervisor session·Node UID/이름도 한번 결합한 뒤 바꾸지 않는다.

모든 lifecycle 변경은 owning VD 행을 먼저 잠근다. 같은 요청 키/내용은 같은 Operation을 반환하고,
다른 내용의 재사용은409다. 새 요청은 진행 중인 이전 요청을SUPERSEDED로 보존한다. VD마다
종료 미확인 실행 하나·열린 runtime binding 하나·진행 중 Operation 하나만 허용한다.
generation은 이전 이력의 최대값+1이며 요청·생성 명령·binding을 같은 트랜잭션에 기록한다.

`vd_runtime_binding`은 source binding과 별도로 열린/닫힌 revision·시각을 보존한다. Runtime 생성은
현재 원본 binding 집합과 Profile 정책을 스냅샷으로 검증한다. DB 제약과 지연 trigger는 실행과
binding의 종료 상태 불일치, 다른 VD의 binding, 초기 스냅샷·신원·종료 이력 변조를 차단한다.

`vd_operation`의PROVISION/REPLACE/DRAIN과RUNNING/SUCCEEDED/FAILED/SUPERSEDED는 Task 결과와
별개다. PROVISION/REPLACE 성공은 유효한 supervisor lease와 실제 Pod Ready 관측이 결합된 상태다.
단순 Pod 제출이나 첫 poll만으로 성공하지 않는다. 첫 Ready 시각은 이력으로 보존하며 이후 장애가
과거 Operation 성공을 덮어쓰지 않는다. 현재 Runtime의 상태·lease·실패 사유를 별도로 확인해야 한다.

## 교체·해제·장애

이름만 바꾸면 기존 runtime/Operation을 유지한다. 원본 binding이나 배치가 바뀌면 변경된 VD 설정과
교체 요청을 같은 트랜잭션에 저장한다. 이전 세대를DRAINING으로 만들어 새 Task 수용을 차단하고,
감독 프로세스의 완료 확인 또는 drain 제한 시간 뒤STOPPED와DELETE 명령을 기록한다.
실제 종료 관측과 CREATE 명령의 확정이 모두 있어야 binding을 닫고 새 generation을 생성한다.
따라서 새 실행은 동일 vdId와 최신 설정을 가지며 이전 실행의 Task·Pod 신원을 재사용하지 않는다.

VD 해제는 진행 중인 교체를SUPERSEDED로 바꾸고 DRAIN을 저장한다. 해제된 VD에 교체 target을
생성하지 않는다. 이전 runtime이 캡처한 원본 Device는 source binding이 닫힌 뒤에도 실제 종료
확인 전까지 DEVICE_IN_USE다. API와 DB가 모두 이 조건을 검사한다.

startup timeout·lease 만료·Pod 실패는 실행을 fence하고 DELETE를 저장한다. 만료된 session은
heartbeat로 다시 살리지 않는다. 새 요청은 이전 실행의 정리 뒤 새 generation으로 시작한다.
drain timeout은 강제 정리를 요청하며 종료 관측 전에는 Operation 성공을 기록하지 않는다.

## 명령과 재시작

CREATE/DELETE 명령은 runtime별로 유일하다. 원자적인 `FOR UPDATE SKIP LOCKED` lease와 owner UUID,
마감, 시도 횟수를 저장한다. 만료 후 다른 worker가 회수할 수 있으며 과거 owner의 완료·지연 처리는
반영하지 않는다. 네트워크 I/O는 VD 트랜잭션 밖에서 하고, 응답 반영 때 VD를 다시 잠근다.

종료를 확인한 뒤 늦은 CREATE 응답이 도착하면 아직 미확정인 Pod UID를 보존한 runtime에 결합하고
DELETE를 다시 연다. 이미 다른 Pod UID가 결합돼 있으면 신원을 덮어쓰지 않고 거절한다.
그 runtime은STOPPED/TERMINATED 이력을 유지하고 현재 binding·작업 수용을 재개하지 않는다.
후속 gateway reconciliation은 현재 runtime뿐 아니라 관리 labels를 가진 과거 세대 Pod도 확인해야
한다. 과거 runtime의 다른 Pod UID도 실제 소유를 검증한 뒤 정리하는 경로가 필요하다.
과거의 종료 확인과 현재 cleanup 명령을 함께 보며, 과거 Operation 성공만으로 지금 Pod가
없다고 판단하지 않는다. 이력의 TERMINATED는 마지막으로 확인한 종료 상태다.

## 연결 경계와 남은 구현

현재 서비스의 `submitted`/`attest`/`confirmStopped`는 실제 gateway가 전달해야 할 관측 경계다.
DB 시험은 UUID·Ready·종료 관측을 fixture로 넣는다. 실제 TokenReview·Pod ownership·scheduler
Node UID 검증이나 실제 API 프로세스 장애 수용시험을 이 시험으로 대신하지 않는다.

공개 VD 생성은 여전히 등록만 한다. 내부 lifecycle로 관리하기 시작한 실행이 있을 때 기존 PATCH/
DELETE가 교체·종료 요청을 함께 저장한다. 공개 runtime/Operation 조회·provision 연결·poll sequence
재전송 저장·worker·VD Run/Task/Result 연결 및 UI와 demo-vd는 후속 구현이다.
기존 Job·Remote 경로와 적용된 V1–V13은 변경하지 않는다. V14도 적용 후에는 수정하지 않는다.
기존 API Deployment의 Recreate 전략을 유지한다. VD 실행을 실제 관리하기 시작한 뒤에는 이 수명을
모르는 이전 바이너리를 함께 쓰거나 단순 rollback하지 않는다. 복구·혼재 버전 수용은 별도 검증한다.
