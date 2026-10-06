# ADR0063 — 복원 DB와 Kubernetes 실행 목록의 조회 전용 대조

상태: 채택, 2026-10-04. ADR0059/0061/0062에 이어 외부 실행을 회수하기 전 관측 대상을 확정한다.

## 결정

복원 DB의 실행 행만 순회하면 백업 이후 만들어진 Job/VD Pod를 놓친다. 명시한 context와
소유 namespace의 **전체 Job/Pod 목록**을 페이지 단위로 읽고, 복원 DB의 전체 Kubernetes
runtime/VD 이력과 대조한다. DB는 정상 복원 보고서의 이름/OID/무작위 marker를 확인한 뒤
V1–V33 전체 이력을 요구하며, 하나의 `REPEATABLE READ READ ONLY` 트랜잭션으로 읽는다.

Kubernetes 목록은 페이지 간 resourceVersion과 중복 UID/continuation을 확인한다. namespace는
`edgeai`/`edgeai-bootstrap` 소유 표시와 비종료 상태를 요구하며 조회 전후 UID가 같아야 한다.
서로 다른 리소스 목록과 DB의 관측 시점이 같다고 주장하지 않는다.

결과는 DB UID 일치/미기록/불일치, DB에 없는 실행, 소유 충돌, 고아 또는 충돌하는 Runner Pod를
구분한다. 소유 label이 빠져도 예약 이름·DB의 예상 이름·관측한 Job의 자식이면 조용히 제외하지
않는다. DB에는 있지만 목록에서 보이지 않는 객체는 `NOT_OBSERVED`이며 종료 증명이 아니다.
조회하지 않은 DB namespace도 기록한다. Secret이나 컨테이너 환경변수·명령·annotation은
보고서에 쓰지 않는다. 보고서는 새 소유자 전용 디렉터리/파일에만 저장한다.

## 권한과 후속

이 명령은 Kubernetes GET과 DB SELECT만 수행한다. 관측 성공 상태는
`OBSERVED_KUBERNETES_PRODUCERS`이고 `quiesced=false`, `activated=false`다.
이 파일은 삭제/재가동 허가서가 아니다. 실제 회수 시에는 원래 제어기의 생성 권한을 막고,
현재 UID·소유권을 다시 확인해야 한다. Remote·broker·장치·claim Secret 관측 및 회수,
원래 producer의 실제 종료, 키/journal 복원, 서비스 활성화와 종합 RPO/RTO 수용은 남는다.

[실행법](../operations/recovery/recovery-kubernetes.md), [검증](../evidence/m9-recovery-kubernetes.md).
