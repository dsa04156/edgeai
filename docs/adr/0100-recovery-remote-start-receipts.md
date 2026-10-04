# ADR 0100: 원래 Remote 시작 기록으로 복원된 BATCH 전환을 조정한다

상태: 채택, 실제 결합79개·기존 결과15개/실패15개 검증 통과. 2026-10-05.

ADR0086은 실제 성공 파일만으로 STARTING 전환의 시작 기한을 증명하지 못해 결과 확정을
막는다. ADR0099의 영속 시작 기록을 복원 DB와 대조하는 명시적 옵션
`--offloads --remote-connection ... --remote-start-receipts`를 추가한다.

기존 Kubernetes/VD 종료·Remote 차단/종료·복원 신원·고정 제공자 binding 검사를 유지한다.
같은 TLS pin·설치/복구 UUID와 별도 복구 자격으로 모든 할당의 최초 기록을 새로 읽는다.
지원되는 명시적 `START_RECEIPT_NOT_RECORDED`만 기록 부재다. 미지원 endpoint나 다른
응답을 부재로 해석하지 않는다. 전체 inventory를 다시 조회해 관측 도중 변화를 거절한다.
완료된 전환의 기록도 포함하므로 DB 반영 전후 증거를 같은 범위로 대조할 수 있다.

기록의 스키마·신원·요청 digest·원래 lease·UTC 접수 시각을 대조한다. 대상이 실제 SUCCEEDED이고
최신 OFFLOAD Attempt이며 Task/Run이 RUNNING, 실패/취소/결과/재시도 기록이 없어야 한다.
source의 OFFLOADED 이력과 실제 종료, 모든 해당 runtime의 STOPPED/TERMINATED도 필요하다.
원래 전환 UUID와 start deadline이 정확히 같고 접수 시각이 전환/target 생성 및 STARTING
갱신 이후, lease와 시작 기한 이전이어야 한다. 현재 시각이 시작 기한을 지났다는 이유로
정당하게 접수된 성공을 실패로 바꾸지 않는다. 기한·배치·시도 이력을 새로 만들지 않는다.

기존 전체 행 guard·테이블 잠금·복원 DB 신원 검사를 같은 transaction에 적용하고 전환만
SUCCEEDED로 바꾼다. updated_at은 실제 복구 transaction 시각, 원래 시작 시각은 개인 intent의
불변 영수증으로 남긴다. `offloadsCompleted`는 실제 변경 수다. Task/Attempt/Run·Result는
이 단계에서 성공 처리하지 않으며, 별도 고정 S3 version/bytes/SHA/출력 계약 검증과
Result transaction이 그 다음이다. 새 실행/재시도·서비스 활성화는 하지 않는다.

검사 후 DB 변경은 SQL guard로 원복한다. 커밋 응답 유실은 intent를 유지하고 같은 복구
UUID로 새 출력 디렉터리에 재실행한다. 완료된 전환은 다시 쓰지 않으며 모든 영수증을
새로 대조한다. 기존 옵션 없는 경로와 기록 부재는 계속 미해결이다.

제공자 시계·SQLite와 관리 자격은 신뢰 경계다. SYNTHETIC 참조 제공자 한 설치 및 BATCH
전환 범위이며 실제 외부 제공자 계약·STREAM·Kubernetes 시작 권한·전역 writer 종료·
새 권한 발급과 종합 활성화의 증거는 아니다. 합성 부모/자식은 실제 컨테이너이고 업무
할당/전환은 명시적 DB fixture다. 시작 기록·계산·TLS·DB transaction·S3 파일은 실제다.
[검증 근거](../evidence/m9-recovery-remote-start-receipts.md)를 따른다. 새 원격 CI·배포는 별도다.
