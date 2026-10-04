# ADR0085: Kubernetes와 참조 Remote 종료 증거를 함께 조정한다

- 상태: 채택 — 실제 혼합64개·Remote 결과14개/실패15개 검증, 새 CI/배포는 후속
- 날짜: 2026-10-04

## 결정

ADR0083/0084 workflow 복구에 `--offloads --remote-connection <개인 JSON>`을 추가한다.
한 복원 DB의 참조 Remote binding 전체를 ADR0074의 실제 TLS 조회로 대조한다. 실제
provider/recovery ID, 인증서 pin·CA, fence/quiescent 상태와 전체 페이지/할당 수를 확인한다.
cached 보고서를 입력으로 받지 않는다. 기존 ADR0075 실행 정리가 먼저 완료되어야 하며
DB의 관측 응답·revision·state, 종료 runtime·기존 명령과 Attempt binding이 정확히 같아야 한다.

Kubernetes quota·보존 Pod 증거와 같은 recovery UUID를 사용한다. 선택 namespace의
Remote runtime만 종료 증거 집합에 합친다. 전체 관측은 읽기 전용이며 Remote 종료나
DB retirement를 이 명령에서 암묵적으로 실행하지 않는다. 참조 제공자는 SYNTHETIC이며
다른 제공자나 외부 계약의 종료를 증명하지 않는다. 복원 DB에 다른 Remote binding이
있으면 기존 inventory 계약대로 거절한다.

Remote가 source이면 Pod claim을 요구하지 않고 검증된 할당을 사용한다. Remote가
target이면 Operation/Attempt의 고정 provider key·configuration digest·source mode와
새로 관측한 binding까지 비교한다. 두 방향 모두 source OFFLOADED, 최신 epoch, 전체
runtime 종료, 원래 취소/실패/기한 우선순위를 유지한다. 아직 RUNNING/READY Task의
STARTING target에 실제 Remote SUCCEEDED/FAILED 관측이 있으면 시간 초과로 덮어쓰지
않고 별도 결과 조정에 남긴다. 명시적인 사용자 취소와 이미 기록된 실패는 보존한다.

기존21개에 remote_allocation을 포함한22테이블의 전체 행 guard·잠금 아래 같은 transaction을
사용한다. 개인 연결 파일 hash·provider inventory hash·binding·종료 상태·증명한 runtime ID를
intent에 남긴다. bearer나 작업 본문은 복사하지 않는다. 쓰기 직전과 커밋 후에도 양쪽의
실제 증거를 새로 대조한다. DB와 외부 관측의 분산 원자성을 주장하지 않는다.

## 범위

새 Attempt/Result/claim/배정/기한을 생성하거나 provider/API/worker를 활성화하지 않는다.
성공은 선택한 이력 조정을 뜻하며 전체 미해결 목록·결과·STREAM/group/journal와 전역
writer 격리 및 종합 서비스 재개가 남는다. 기본 옵션은 Remote 전환을 계속 미해결로 남긴다.

[실제 검증](../evidence/m9-recovery-mixed-remote-offloads.md)을 따른다.
