# ADR0109 — 원래 STREAM 완료 장벽과 확정 결과의 복원

## 결정

`recovery-kubernetes-results.sh --stream-results`는 복원 DB에 이미 기록된 STREAM
완료 허가와 독립 S3 백업의 원래 시작·Result 기록을 함께 검증한다. VD 자식 결과는
`--vd-tasks`도 지정한다. 기본 BATCH 경로는 STREAM 결과를 계속 거절한다.

선택한 결과가 속한 연결 그룹 전체의 원래 완료 허가가 필요하다. Device fanout도 같은
그룹이다. 모든 작업의 최신 봉인 checkpoint, 같은 원래 grant 시각, Device END와
producer/consumer 최종 커서, 원래 경로 세대·actor를 대조한다. 고정 S3 version의 실제
bytes/SHA, checkpoint summary·SERVICE 버전·명령/인자/parameters·포트·제한도 확인한다.
백업 시점에 허가가 없으면 나중 Result 기록만으로 허가를 생성하지 않는다.

원래 checkpoint/완료 허가는 보존한 실제 producer 수명 안에 있어야 한다. 선택한 연결
그룹의 모든 기록된 producer·명령·배정이 종료됐고 경로가 닫혔는지 확인하며, 원래 MQTT 관리 권한과
principal 차단을 새로 관측한다. 최종 저장 재시도의 기록된 상속 경로는 원래 grant까지
이어져야 하며, 원래 실패 사유·횟수·backoff·최대 경과 시간을 넘을 수 없다.

ADR0038/0092와 같이 완료 장벽은 연결 그룹 단위다. 같은 Run의 별도 BATCH 후속 작업은
그 부모의 완료 장벽에 포함하지 않는다. 이미 확정된 부모 결과를 재검사할 때 후속 runtime의
종료를 요구하거나 이력을 덮어쓰지 않는다. 관측된 실행이 백업에 없는 경우의 기존 거절과
전체 DB guard는 유지한다.

기존 결과 복원 transaction에 원래 Result ID/시각·고정 출력과 발행 큐를 반영한다.
후속 STREAM 그룹은 모든 member가 WAITING이고 그룹 전체의 BATCH 부모 결과가
확정됐을 때 함께 READY/QUEUED로 전환한다. 원래 실행 배치를 유지하며 새 runtime이나
계산 허가, broker 세대는 만들지 않는다. 일부 member의 모순은 전체 transaction을 원복한다.

SQL 직전과 commit 후에 실제 종료·broker·S3·DB를 다시 확인한다. COMMIT 뒤 증거가
바뀌거나 응답이 유실되면 성공 보고를 만들지 않고 격리를 유지한다. 같은 입력과 새 출력
경로로 재실행해 원래 결과를 대조하며 이미 반영된 ID/시각·후속 이력을 변경하지 않는다.

## 검증과 남은 범위

Kubernetes30개/VD34개와 기존 BATCH20/24·Remote15개, 최종123개 감사가 PASS다.
검증 근거는 [STREAM 결과 복원 시험](../evidence/m9-recovery-stream-results.md)에 기록한다.
실제 API로 원래 시작·완료 허가·Result를 생성하는 시험과 명시적 SQL 그룹 준비 fixture의
범위를 구분한다. 후속 [상속 finalizer 검증](../evidence/m9-recovery-stream-finalizer-results.md)은
실제 두 번의 실패·세 번의 시작 기록과 원래 완료 허가를 연결해 Kubernetes35개/VD39개
전체 복원 CLI 시험을 통과했다. 재시도 배정과 VD readiness는 명시적 fixture다.
[혼합 그룹 검증](../evidence/m9-recovery-stream-group-results.md)은 Device→NODE→VD의
실제 checkpoint/완료/Result API와 원본 제거·독립 복원 CLI를 연결한다. 어느 순서로
Result를 복원해도 두 번째 결과 전에는 후속 Attempt를 만들지 않으며, peer 권한/객체
모순·경쟁·원복·응답 유실·기존 후속 실패 보존을 검사한다. 경로/Device END·배정/readiness는
fixture이며 자동 실행 수용과 구분한다.

백업 이후 누락된 완료 허가/실행의 독립 복원, 전역 writer/API 차단과 전체 서비스 재개,
실제 모델·외부 계약 및 M0–M10 전체 수용은 별도 잔여 작업이다.
