# ADR0080 — 실제 Kubernetes 종료 증거를 복원 DB 실행 이력에 반영

상태: 채택, 2026-10-04. ADR0063/0064의 관측·종료와 복원 실행 조정을 연결한다.

## 결정

명시 context·namespace/UID·복구 UUID와 개인 종료 보고서를 요구한다. 매번 실제 namespace,
quota UID/소유권/정책/status, 서버 dry-run의 Pod/Job 생성 거절, 전체 Job/Pod 목록을 다시
조회한다. 모든 Job의 suspend, Pod의 동일 복구 finalizer, 실제 컨테이너 종료 또는 never-bound
증거가 종료 보고서의 전체 대상과 같아야 한다. 404·삭제된 객체는 종료 증거를 대신하지 않는다.
보고서는 운영자가 제공하는 개인 입력이며 외부 서명이나 인증의 대체물이 아니다.

복원 보고서의 DB 이름/OID/marker/스키마를 확인한다. 실제 label의 Run/Task/Attempt/epoch,
Job 소유 관계, VD/generation 및 기록된 producer UID·node를 대조한다. 신원 충돌이면 전체
쓰기를 거절한다. 일치하는 실행만 정리하며 UID 미기록·미관측·다른 namespace의 DB 행은
`unresolved`에 남긴다. 백업 이후 생성된 객체도 종료 증거를 검증하지만 DB 이력을 새로 만들지 않는다.

실행·명령·작업/결과·VD/binding/operation·migration의13테이블 전체 행 해시를 읽기 전용
snapshot에서 얻는다. SQL 직전 실제 종료 상태와 DB를 다시 조회하고, SHARE ROW EXCLUSIVE
잠금 안에서 OID/marker와 전체 해시를 재확인한다. 잠금5초·문장30초 제한을 적용한다.

한 transaction에서 기존 CREATE/DELETE 명령을 완료하고 lease를 지운 뒤 선택한 runtime을
STOPPED/TERMINATED로 바꾼다. VD는 기존 CREATE 완료 제약을 만족시키고 동일 transaction에서
runtime binding을 현재 VD revision으로 닫는다. 기존 deferred 제약도 커밋 전에 검사한다.
명령 ID/횟수, producer 신원, Task/Attempt/Run·Result/artifact·VD operation 결과는 보존한다.

쓰기 전에 개인 intent를 fsync한다. 커밋 뒤 별도 DB 연결과 실제 Kubernetes 조회로 다시
대조한다. 커밋 응답 유실·사후 관측 변경은 원복됐다고 주장하지 않는다. 동일 복구 입력과
새 출력 경로로 재실행하면 이미 정리한 행/timestamp는 변경하지 않는다.

## 한계와 검증

DB transaction과 Kubernetes 관측은 분산 원자성이 없다. 원래 API/controller의 권한,
admission 이전에 접수된 요청과 관리자 변경은 종합 복구가 별도로 통제해야 한다.
quota/finalizer/DB 격리를 해제하지 않는다. VD Task allocation 결과 조정, 진행 중 offload,
STREAM/journal, 미관측 producer와 전체 서비스 활성화는 남는다.

실제 PostgreSQL 복원3개와 Kubernetes 부모/자식2쌍의16개 결합 시험이 통과했다.
업무는 합성이며 runtime claim/VD binding은 명시적 DB fixture다. 실제 모델이나 SDK claim
수용을 주장하지 않는다. [검증 근거](../evidence/m9-recovery-kubernetes-retirement.md),
[운영 명령](../operations/recovery/recovery-kubernetes-retirement.md).
