# ADR 0099: Remote 계산 시작 전에 원래 권한과 접수 시각을 기록한다

상태: 채택, Python24개·Java연동13개·실제 PG/S3 결합48개·단위122개·계약 검증 통과. 2026-10-05.

ADR0086의 복원된 STARTING 전환은 성공 파일만으로 원래 기한 내 시작을 증명할 수 없다.
이를 위해 API는 Run 잠금 아래 현재 Attempt/runtime·lease·전환 상태와 기한을 확인한 뒤
RemoteStart를 만든다. 전체 allocation/run/task/attempt/epoch, 예약 digest·lease와
선택적 offload ID/원래 start deadline을 전송한다. 새 기한이나 배치를 만들지 않는다.

참조 제공자는 실제 입력 검증 뒤 시계를 읽고 lease와 전환 기한을 다시 확인한다.
권한, UTC acceptedAt, RUNNING 전이와 executions 증가를 SQLite의 같은 FULL transaction에
저장한 뒤 계산 스레드를 시작한다. SQL 실패는 시작 기록과 실행 전이를 모두 원복한다.
그 뒤 프로세스가 죽으면 PROVIDER_RESTART이며 같은 할당을 다시 계산하지 않는다.
같은 권한의 재전송은 최초 기록을 보존하고 다른 권한은 거절한다.

`start_receipts`는 allocation별 하나이며 UPDATE/DELETE를 거절한다. 기존 DB는 별도
테이블을 추가해 업그레이드하고 과거 실행의 기록은 만들지 않는다. 원래 본문 없는 시작은
기존 참조 프로토콜 시험용으로 유지하지만 복구 권한 증거를 제공하지 않는다. 새 API worker는
항상 권한 본문을 보내며 지원하지 않는 제공자로 자동 하향하지 않는다. 제공자를 먼저
업그레이드해야 한다. 실제 2세부 제공자 계약은 별도다.

복구 전용 GET `.../recovery/allocations/{id}/start-receipt`는 별도 운영 자격, 고정 설치/복구 ID,
전체 차단·실제 스레드 종료 후에만 최초 권한과 접수 시각을 반환한다. 일반 상태 응답과
할당 inventory 형식은 유지한다. parameters·입출력·자격은 기록에 포함하지 않는다.

제공자의 시계와 영속 저장소를 신뢰하는 참조 프로토콜이다. 이 기록은 최초 시작 접수의
증거이며 계산 성공, 전환 완료 또는 전역 producer 종료 증명이 아니다. 복원 DB의 원래
전환/기한/최신 Attempt·취소·결과와 대조해 원자적으로 반영하는 복구 경로는 다음 단계다.
기존 복구의 미해결/격리 판정을 기록만으로 해제하지 않는다.
[검증 근거](../evidence/m9-remote-start-authority.md)를 따른다.
