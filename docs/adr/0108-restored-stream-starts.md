# ADR0108 — STREAM 전환의 원래 그룹 시작 허가 복원

## 결정

`recovery-stream-workflows.sh --offloads`에서 고정된 독립 시작 기록을 읽어 그룹의 전환
성공을 복원한다. Kubernetes target은 ADR0102 검증기, VD peer는 ADR0107의 배정/세션
검증기를 사용한다. 같은 backup manifest/설치/namespace/복구 ID에서 모든 member를 확인한다.

각 기록은 정확한 target Attempt/runtime/작업 digest·원래 전환 ID와 start deadline에 묶인다.
원래 deadline 안의 admission을 현재 시각의 새 허가로 바꾸지 않는다. 전체 source/target의
실제 종료, 원본 broker 차단, 닫힌 경로와 고정 member/checkpoint 계획이 선행 조건이다.

완전한 그룹만 `COMPLETE_STREAM_OFFLOAD`로 분류한다. 전체 DB guard/잠금/transaction에서
Operation의 state와 updated_at만 갱신한다. 원래 producer claim, Attempt, Result, VD 배정,
checkpoint와 broker 세대는 바꾸지 않으며 새 실행·권한을 만들지 않는다. 새 Attempt,
기록된 취소/실패/결과, 다른 작업이나 다른 배정의 admission으로 과거 전환을 덮어쓰지 않는다.

한 target의 기록이 없거나 옵션이 없으면 그룹을 미해결로 남긴다. 종전 ADR0093의 STARTING
기한 만료 추론을 대체한다. 백업 후 허가·계산이 일어났을 가능성이 있으므로, 기록 부재는
실패 증거가 아니다. 기록된 취소/실패 및 기존 DRAINING 처리 범위는 유지한다.

SQL 직전과 commit 후에 manifest/최신 S3 version·물리 종료·broker 권한을 다시 관측한다.
응답 유실 또는 변경 감지 시 성공 보고를 만들지 않고 격리를 유지한다. 같은 복구 ID와 새
output으로 원래 증거를 다시 확인한다. 전환 성공은 업무 Result나 종합 서비스 활성화를 뜻하지 않는다.

## 검증 및 잔여

실제 TLS API와 Pod-bound TokenReview로 NODE/VD 두 원래 시작 기록을 만들고, 원본 DB/저장소
제거·복원 DB21개·실제 부모/자식 종료·MQTT 차단을 결합한55개가
`20261005T010404Z-5743138b`에서 PASS다. 원래 Operation만 변경하고 타43테이블을 보존했다.
기존 결과 복원20/24개와 시작 복구91개도 PASS다. [상세 근거](../evidence/m9-recovery-stream-starts.md).
시험 fixture의 저장소 클라이언트 수명 오류를 재현해 수정했으며 생산 관측 timeout은 유지했다.
최초 HTTP/Pod/TLS fixture 기동 실패의 미확정 원인은 별도로 보존한다.
STREAM 결과/finalization 권한, 백업 이후 새 실행 발견/회수, 전역 writer/API 차단·종합
활성화와 신규 원격 CI/배포는 남는다. BATCH 복원의 기존 옵션 생략 동작은 이 변경 범위가 아니다.
