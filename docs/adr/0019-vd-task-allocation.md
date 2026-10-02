# ADR0019 — VD의 작업 배정과 결과 생산자

상태: 구현 중. 공개 실행 연결·수용 완료 여부는 별도 evidence를 따른다.

## 실행 대상과 잠금

Run의 VD 정책은 고정 `vdId`를 참조하며 활성 VD runtime에 Task를 배정한다.
해당 Run의 모든 Task는 VD와 같은 불변 SERVICE Profile을 사용한다. 다른 SERVICE나
여러 VD로 구성한 실행 계획은 이후 다중 장치 계획의 대상이다. 등록·설정과 실행 세대는 분리한다.
Run/Attempt의 VD 대상은 변경하지 않으며 retry는 이전 Attempt 대상을 계승한다.
명시적 NODE/REMOTE 전환의 새 Attempt는 그 대상만 참조한다.

VD 관련 변경은 VD 행 → Run 행 순서로 잠근다. 기존 `ExecutionRepository.run(id,true)`도
고정 Run 대상 VD를 먼저 잠가 취소·결과·배정·drain의 잠금 순서를 일치시킨다.
네트워크 관측과 artifact 전송/검증은 트랜잭션 밖이며 확정 시 생산자 권한을 재검사한다.

## 영속 배정과 용량

Task의 RuntimeInstance는 실행 공급자 `VD`를 명시하고 Job 이름/UID를 갖지 않는다.
지속 VD runtime과 작업별 RuntimeInstance는 서로 다른 수명이다. 배정 전 Task runtime은
PENDING이고, 활성 세대가 준비되고 slot이 있을 때 한 번만 배정한다.
`VDTaskAllocation`은 Task runtime·VD·실행 세대·session·Pod UID·slot·poll sequence를 고정한다.
실제 같은 컨테이너 자원을 공유하며 slot을 독립 GPU/CPU 예약으로 표시하지 않는다.

VD의 maxConcurrentTasks 이내에서 열린 slot은 하나뿐이다. 작업의 Result commit이나 취소
요청만으로 slot을 반환하지 않는다. supervisor의 실제 자식 프로세스 종료 보고 또는 VD Pod의
물리 종료 확인으로 작업 runtime을 TERMINATED로 만든 후 slot을 닫는다. 닫힌 이력은 보존한다.
프로세스 exitCode=0만으로 Result를 만들지 않는다. 검증된 artifact commit이 없으면 실패다.

## poll 재전송과 drain

기존 내부 wire의 assignments/cancelAttempts/acknowledgedAttempts를 사용한다.
배정 신원과 최초 sequence는 poll receipt와 같은 트랜잭션에 저장한다. 같은 sequence 재전송에서
새 작업을 추가하지 않고 기존 배정과 completion acknowledgement를 재구성한다. claim token은
기존 작업별 HMAC nonce로 재생성하며 원문 자격을 저장하지 않는다.
다음 poll의 active/completed는 소유·epoch·세대를 대조한다. 다른 Task의 보고는 거절한다.
완료를 확인하지 않은 배정은 응답 유실 후에도 보존한다. 취소·만료 작업은 배정 대신 취소로 응답한다.

DRAIN은 신규 배정을 막고 기존 작업/완료 보고를 기다린다. slot이 모두 닫힌 후 STOP을 저장한다.
기한 만료·Pod 장애·세대 교체는 이전 Task의 claim/commit을 차단하며 실제 종료 확인 후 retry할 수 있다.
한 작업 취소 때문에 공유 VD Pod나 다른 작업을 종료하지 않는다.

## 인증과 Result

VD 자식 Runner는 작업별 HMAC과 VD Pod-bound token으로 인증한다. 실제 VD gateway 신원과
저장된 allocation의 runtime/generation/session/Pod UID를 대조한다. 일반 Job gateway에 VD를
가짜 Job으로 전달하지 않는다. Result의 생산자 종류는 VD이며 실제 Pod UID와 vdRuntimeId를
allocation에 연결한다. 기존 Result의 실제 S3 크기/내용 hash/version 검증·불변 봉인을 재사용한다.
VD cgroup 사용량은 공유 컨테이너 측정이므로 작업별 독점 자원 기반 자동 전환으로 해석하지 않는다.

## 구현 및 수용 순서

1. 새 V16의 대상·배정·공급자/결과 FK, domain/repository와 실제 PostgreSQL 제약/경합 검증.
2. 배정·poll 재전송/ack·Runner 인증/claim/Result·취소/drain/장애 및 retry 연결.
3. 공개 VD Run 정책·Swagger·UI·실제 supervisor/DB/S3와 Kubernetes·kind 종단 검증.

1만 구현된 상태에서 공개 VD 실행 또는 전체 M6 완료를 주장하지 않는다.
