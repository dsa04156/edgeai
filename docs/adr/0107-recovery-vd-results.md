# ADR 0107: 원래 VD 배정·시작·확정 결과로 격리 DB를 복원한다

상태: 구현·실제 복원24개와 기존 Kube20/Remote15/시작 복구91개 검증 통과. 2026-10-05.
[검증 근거](../evidence/m9-recovery-vd-results.md).

ADR0105/0106의 독립 S3 시작·결과 기록을 ADR0104의 BATCH 결과 복원에 연결한다.
기존 명령의 `--vd-tasks` 옵션으로 VD 자식 runtime을 명시적으로 선택한다.
V36 스키마와 기존 DB 복원 marker, 보존 Kubernetes 종료 증거를 요구한다.
새 migration이나 실행 권한은 만들지 않는다.

VD는 하나의 supervisor Pod에서 여러 작업을 실행한다. Pod 종료만으로 자식 성공을
추론할 수 없다. 각 runtime의 원래 allocation ID, VD ID, supervisor ID, generation,
session, slot, assigned sequence/time을 DB와 시작·결과 기록에서 모두 대조한다.
원래 supervisor 설정 digest를 실제 설정으로 재계산하고 SERVICE 버전·namespace·슬롯
범위를 확인한다. 작업 digest는 불변 SERVICE/parameters와 전체 고정 BATCH 입력으로
재계산한다. nonce, token, parameters 원문은 복구 intent에 포함하지 않는다.

ADR0081의 물리 종료 조정 후 자식과 supervisor가 STOPPED/TERMINATED이고 원래
allocation이 POD_GONE 또는 PROCESS_EXIT로 닫힌 경우만 허용한다. NOT_STARTED는
성공을 증명하지 않는다. 실제 retained Pod/Node UID, VD labels, 전체 컨테이너 종료와
vd-supervisor 생존 구간을 확인한다. 시작 시각은 원래 assignment/readiness 이후,
원래 runtime 만료·기록된 최대60초 supervisor lease·drain·전환 기한 이전이어야 한다.
나중 heartbeat의 lease로 최초 허가를 바꾸지 않는다. 결과 시각은 시작 이후이고 원래
allocation 종료 이전이며 DB microsecond 정밀도를 보존해야 한다.

서로 다른 원본/백업 저장소 ID와 명시적 TLS 인증서 pin, 한 백업 manifest를 사용한다.
두 기록의 단일 version/head·Content-Type·길이·SHA256·엄격 JSON을 검사하고, 모든
출력의 SERVICE 계약·manifest digest·원래 task/attempt 경로와 고정 version 바이트를
검증한다. 결과와 시작 기록이 서로 일치하더라도 DB의 배정과 다르면 거절한다.

공유 BATCH transaction은 명시적인 VD producer 분기로 원래 Result ID/committedAt,
producer Pod/node와 vd_runtime_id, 고정 artifact를 반영한다. 원래 allocation/supervisor
이력과 runtime 종료/nonce는 보존한다. Task/Attempt 성공·자식 대기·Run 집계·검증된
발행 큐 완료를 같은 transaction으로 처리한다. 최신 Attempt, 취소·실패·retry·활성 전환
검사와 전체 관련 행 해시/잠금, 재검증, 무변경 재실행은 기존 경로와 공유한다.

외부 저장소/클러스터와 DB 사이에 분산 transaction은 없다. COMMIT 후 검증 실패나
응답 유실이면 격리를 유지하고 같은 증거로 다시 검사한다. 성공 보고서가 없다고 DB
원복을 주장하지 않는다. STREAM 그룹, 진행 중 VD 전환의 시작 허가 조정, 백업 이후
알려지지 않은 자식 실행의 전체 발견·회수, 전역 writer/API 권한 회수와 서비스 활성화는
후속 작업이다. 전체 M0–M10 수용 범위를 축소하지 않는다.
