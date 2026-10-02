# ADR 0016 — VD Pod gateway와 영속 명령 worker

상태: 수락 (구성 요소, 전체 M6 수용 완료 아님). 날짜: 2026-10-03 KST.

## 결정

V14가 보존하는 runtime/generation과 명령 lease를 `VDWorker`가 실제 Kubernetes 경계에 연결한다.
외부 I/O는 DB 트랜잭션 밖에서 실행하고 반환값만 VD 행 잠금으로 반영한다. HTTP 요청 실패나
명령 lease 만료 후 다른 worker가 재시도해도 결정적인 Pod 이름과 기존 UID로 같은 실행을 찾는다.
이미 기록한 Pod가 사라지면 같은 실행을 재생성하지 않는다. 새 실행에는 새 runtime/generation이 필요하다.

기존 Task Job과 별도인 `VDGateway`는 namespace의 bootstrap 소유 label, VD/runtime/generation
labels, Pod spec 의도 해시, SERVICE 이미지와 ServiceAccount를 확인한다. 비밀 자격은 불변
Secret에 넣고 그 Secret의 실제 UID를 Pod의 controller ownerReference로 설정한다. Pod보다
Secret을 먼저 만들므로 생성 응답 유실·삭제와 늦은 생성 경합에도 소유 관계를 확인할 수 있다.

삭제는 STOPPED runtime만 허용하며 Pod와 Secret을 각각 실제 UID precondition으로 삭제한다.
같은 runtime의 늦은 Pod가 과거 DB UID와 달라도 정확한 Secret 소유 관계를 확인해야 정리한다.
과거 DB Pod UID나 닫힌 binding은 덮어쓰지 않는다. list에는 종료된 runtime의 늦은 Pod도 포함하므로
완료한 DELETE 명령을 다시 열 수 있다. 소유 관계 없는 Pod는 삭제하지 않고 충돌로 남긴다.
CREATE 명령이 확정되고 Pod·Secret의 부재를 모두 확인해야 lifecycle이 종료를 확정한다.

인증 경계는 audience `edgeai-vd`의 실제 TokenReview, 지정 ServiceAccount, 단일 Pod 이름/UID,
소유 labels/Secret 관계, Running 상태와 실제 scheduler Node UID/이름을 확인한다. Pending은
일시 재시도이고 종료·삭제 중 Pod와 Pod에 결합되지 않은 SA 토큰은 거절한다. Pod Ready는 별도
관측값이다. list 관측은 준비 상태를 제거할 수 있지만 실행 lease나 Ready를 부여할 수 없다.
실제 poll 서버는 이 경계와 VD HMAC 자격을 결합해야 한다.

`VDTokenService`는 기존 signing key 파일을 쓰되 `edgeai-vd-v1` 메시지 도메인과 `vd1.` 접두사를
사용한다. namespace/VD/runtime/generation/nonce를 포함하므로 Task 자격과 혼용되지 않는다.
자격은 로그·공개 API·DB 설정에 기록하지 않는다.

worker는 목록 조회 전에 deadline을 조정한다. 따라서 Kubernetes API 장애 중에도 DB의 startup/
lease/drain 만료를 처리한다. 목록은 페이지 간 resourceVersion 일치를 요구하고 watch 종료/410
후 새 목록을 조회한다. 네트워크 경계 오류를 성공 또는 리소스 부재로 간주하지 않는다.

## 배포와 권한

`EDGEAI_RUNTIME_ENABLED=true`와 `EDGEAI_VD_ENABLED=true`가 모두 있어야 VD gateway/worker를
만든다. VD 기본값은 false이고 현재 배포도 비활성이다. 공개 등록은 아직 자동 provision하지 않는다.
poll 서버·VD Task 연결을 구현한 뒤 전체 활성화와 수용시험을 진행한다.

bootstrap Role에 전용 `edgeai-runtimes`의 pods create/watch/delete를 추가한다. patch, exec,
다른 namespace 권한은 추가하지 않는다. Runner ServiceAccount에는 리소스 권한을 주지 않는다.
실제 시험의 kubectl은 대상 context/소유 namespace를 먼저 확인하고, gateway는 관리자 kubeconfig가
아닌 단기 control-plane SA 토큰과 CA를 사용한다. 관리자 exec/patch는 시험 fixture 조작에만 쓰인다.

## 검증 범위

HTTP 장애 fixture, 실제 PostgreSQL 명령/상태 시험, 실제 클러스터 Pod/scheduler/TokenReview/
삭제 시험을 구분한다. 마지막 시험도 workload는 대기 프로세스와 제어된 readiness를 사용하므로
감독 프로세스→poll 서버→VD Task→Result 종단 수용을 대체하지 않는다.
상세 결과는 [M6 gateway 증거](../evidence/m6-vd-gateway.md)를 따른다.
