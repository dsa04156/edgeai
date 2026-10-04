# ADR 0101: Kubernetes 시작 응답 전에 최초 허가를 버전 관리 S3에 기록한다

상태: 채택, 단위122/PG232/runtime53/storage11개·계약·실제 Kubernetes3개 검증 통과. 2026-10-05.

DB 백업 이후의 Kubernetes claim은 복원 DB에 없을 수 있다. 성공 파일만으로 원래
producer와 시작 기한을 추정하지 않도록 PostgreSQL과 별도로 최초 시작 허가를 보존한다.
기존 artifact 버킷의 `authority/runtime-start/<runtime UUID>.json`을 사용한다.
Runner의 출력 업로드 키 공간과 분리하며 새 Kubernetes 권한이나 DB migration은 추가하지 않는다.

기존 Run 잠금/최신 Attempt·실제 Pod/Job/node·원래 lease/전환 기한 검사를 통과한 claim이
DB에 반영되면 신원, epoch, SERVICE spec·유효 parameters·고정 BATCH 입력의 digest,
원래 lease, 선택적 offload ID/start deadline과 admittedAt을 만든다. admittedAt은 해당
DB claim을 처리한 API 시각이다. 실제 Runner가 응답을 받거나 계산을 시작한 시각은 아니다.
parameters 원문, 토큰/claim nonce, 서명 URL은 기록하지 않는다.

API는 DB transaction 밖에서 S3 versioning을 확인하고 조건부 `If-None-Match: *`로 기록한다.
조건부 충돌은 기존 객체를 읽고, 고정 version GET으로 metadata/길이/엄격 JSON/schema·
전체 신원/작업/기한을 대조한다. 최초 admittedAt은 runtime 생성 이후이며 lease와 원래
start deadline 이전이어야 한다. 같은 작업의 재요청은 최초 시각과 버전을 유지한다.
이미 기한 내 기록한 허가는 이후 재요청 시각으로 연장하거나 다시 만들지 않는다.
누락된 기록을 처음 만들 때는 해당 요청의 admittedAt이 원래 기한 안이어야 한다.

저장 확인 후 다시 Run 잠금 아래 현재 producer 권한을 확인하고 성공 응답을 반환한다.
그동안 취소되거나 lease가 만료되면409, 저장소 장애는503, 기존 기록 충돌·버전 관리
미설정은400이다. 새로운 작업/시도나 원래 기한 연장은 없다. VD 자식은 기존 supervisor
배정 경계를 유지하며 Remote는 ADR0099의 독립 제공자 기록을 사용한다.

DB claim과 S3 저장은 분산 transaction이 아니다. 저장 실패/응답 유실 시 DB에는
RUNNING 또는 전환 SUCCEEDED가 남을 수 있다. 정상 Runner는 claim 성공 응답을 받아야
계산을 시작한다. 기록이 먼저 저장된 경우 재요청이 그 기록을 재사용하며,
기록 부재를 성공이나 실행 완료로 간주하지 않는다. 이 변경은 기존 uploads/commit의
producer 검사를 시작 기록 조회로 교체하지 않는다. 복구에서는 실제 종료와 결과 증거도 필요하다.

이 버전의 claim 재요청은 최초 저장 요청을 의미한다. 업그레이드 이전 실행에 과거의
시작 시각을 소급 생성하지 않는다. 특히 기록 없이 이미 시작 기한이 지난 전환은 거절한다.
초기/일반 작업은 현재 유효한 허가 시각을 기록할 수 있으므로 복구 도구는 백업 시각과
실제 종료/결과의 인과관계를 별도로 대조해야 한다.

API 시계와 S3 관리 권한은 신뢰 경계다. 조건부 쓰기와 버전 관리는 API의 덮어쓰기를
막지만 S3 Object Lock이나 관리자에 대한 변조 방지는 아니다. 복구 시 원본 writer 차단,
진행 요청 소진, 모든 관련 버전의 백업 및 새 관측 검증을 결합해야 한다.
이 기록을 복원 DB 전환/Result·STREAM 그룹에 반영하는 경로와 종합 재가동은 후속이다.
[검증 근거](../evidence/m9-kubernetes-start-journal.md)를 따른다.
