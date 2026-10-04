# ADR 0105: VD 자식의 최초 실행 허가를 배정·세션·lease와 함께 보존한다

상태: 채택, 실제 Kubernetes7개·대상8개 포함 runtime70개·PG232개·단위122개·저장소11개 개별 수트 검증 통과. 2026-10-05.
전체 명령의 간헐 실패와 분리 재검증 범위는 아래 근거에 보존한다. 새 원격 CI/배포는 후속이다.

VD Pod 하나가 여러 Task와 슬롯을 처리하므로 Pod UID만으로 개별 작업의 시작 허가를
복원할 수 없다. 기존 claim의 배정·현재 supervisor 인증을 유지하면서 성공 응답 전에
`authority/vd-task-start/<runtime UUID>.json`을 버전 관리 S3에 보존한다.
새 DB 스키마·Kubernetes 권한·Runner 응답 필드는 추가하지 않는다.

Run/Task/Attempt/runtime/epoch, VD·allocation·supervisor runtime ID, generation,
session ID, Pod/node 신원, 슬롯·배정 sequence/시각, 고정 configuration digest,
supervisor의 최초 Ready 시각과 허가 당시 lease/drain 기한을 기록한다.
SERVICE spec·유효 parameters·고정 BATCH 입력의 work digest, runtime 만료,
선택적 offload ID/원래 start deadline과 API의 admittedAt도 포함한다.
parameters 원문·claim nonce·토큰·서명 URL은 저장하지 않는다.

현재 producer VD와 Run 잠금 아래 기존 최신 Attempt·배정·유효 supervisor 권한을
검사하고 claim 및 기존 전환 상태를 갱신한다. DB transaction 밖에서 versioning을
검사하고 `If-None-Match: *`로 최초 기록을 만든다. 조건부 경쟁은 기존 객체의
고정 version GET·길이/Content-Type·엄격 JSON 전체 필드 대조로 해결한다.
저장소 장애는503, 버전 관리 중단이나 기존 기록 충돌은400이며 실행 명령을 반환하지 않는다.

재요청은 최초 admittedAt/version/lease를 보존한다. heartbeat는 lease를 갱신하며
drain 진입 시 남은 기한으로 lease가 짧아질 수도 있다. 따라서 저장된 원래 lease와
현재 lease의 같음이나 증가를 요구하지 않는다. 최초 admittedAt은 runtime 생성·배정·
Ready 이후이고 원래 runtime 만료·당시 lease·당시 drain 및 offload 기한 이전이어야 한다.
원래 lease는 admittedAt 이후 최대60초다. 기록에 있는 drain deadline은 현재 고정 기한과
같아야 하며, 최초 허가 뒤 drain에 진입한 경우 원래 null 값을 유지한다.
기록 없이 이미 원래 offload 기한이 지난 최초 저장은 거절하고 과거 시각을 만들어내지 않는다.

S3 확인 후 현재 실행 권한을 다시 검사한다. 저장 중 취소·배정 종료·supervisor lease나
drain 만료가 발생하면409다. 역사적 lease는 현재 권한을 연장하지 않는다.
DB claim과 S3는 분산 transaction이 아니므로 저장 실패/응답 유실 뒤 DB에는
RUNNING 또는 전환 SUCCEEDED가 남을 수 있다. Runner는 claim 성공 응답을 받은 뒤
계산하며, 재시도는 같은 Task/Attempt와 최초 기록을 사용한다.

이 기록은 API의 시작 허가이고 실제 계산 시작·완료·Result 성공 증거는 아니다.
API 시계와 S3 관리 권한은 신뢰 경계다. 조건부 쓰기/versioning은 관리자 변조를 막는
Object Lock이 아니며 복구에는 원본 writer 차단·요청 소진·별도 백업·실제 종료 증거가 필요하다.
VD Result 독립 기록, 복원 DB의 기록 소비, STREAM 그룹의 권한 대조 및 전체 활성화는 후속이다.
[검증 근거](../evidence/m9-vd-task-start-journal.md)를 따른다.
