# M7 계산 중 그룹 재시도 검증

2026-10-03. [ADR0044](../adr/0044-stream-group-retry.md)의 그룹 fence·종료 장벽·원자적 재배정을
구현했다. 실제 외부 checkpoint와 Device LOCAL journal을 새 세대로 인계하는 흐름을 검증했다.
공동 완료 허가 뒤 복구·장치 자동 재연결·공개 retry·실제 Kubernetes 장애 수용은 남는다.

## 동작

실패한 작업의 불변 스트림 구성 요소 전체를 같은 Run 잠금 아래 RETRY_WAIT로 바꾸고,
이전 세대를 즉시 fence하며 공통 backoff/가장 이른 deadline을 영속 예약한다. 브로커의 실제 회수와
전체 Runtime의 물리 종료·CREATE 완료가 확인될 때까지 새 Attempt는 생성되지 않는다.
두 retry worker가 경쟁해도 전체 새 Attempt/epoch/Runtime 계획이 한 번에 생성된다.
Device pin은 유지하며 Task endpoint의 이전 종료·RETRY·epoch 증가를 확인한 뒤 새 세대를 준비한다.
기존 execution/HANDOVER API가 실제 S3 checkpoint 내용을 검증해 새 현재 주체로 인계한다.

## 확인한 근거

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 PG 그룹 장벽·동시 요청·취소·예산/기한 및 기존 공개 Run/Device 조회 | `20261003T120334Z-b3b9eae6` | 19개 PASS |
| 실제 두 Runner 복원·Device journal 인계·계산 결과와 기존 Source/취소 | `20261003T120901Z-99f9b991` | 5개 PASS |
| 불변 인계 이력 관측을 적용한 전체 실제 Spring/PG/S3 회귀 | `20261003T121100Z-0ba9f8ea` | 35개 PASS |
| 전체 PostgreSQL | `20261003T121201Z-cb99da55` | 184개 PASS, skip0 |
| 전체 서버 단위 | `20261003T121238Z-4694f470` | 101개 PASS |

실제 전달 시험은 두 Device가4/5를 보내 root/sink 상태9를 확정한 뒤 sink의 실패 관측을 주입한다.
서버의 그룹 fence를 실제 HTTP/MQTT로 관측한 기존 두 Runner와 두 DeviceSource가 종료된다.
그 뒤 Task ID를 유지한 새 Attempt/epoch2·새 작업 폴더·새 generation2를 만들고, 두 Runner가
S3에서 상태9를 복원한 것을 새 Attempt의 불변 handover checkpoint 이력으로 확인한다.
Device는 자기 토큰의 경로 조회 후 명시적 handover로 같은 journal을 연다. 이어2/3을 보냈을 때
root14/sink23, BATCH37 및 고정 S3 bytes/SHA/version·세대 종료·한 번의 Result 확정을 확인한다.

이력 관측은 fence 트랜잭션 종료 뒤 이전 latest를 고정하고 새 Attempt의 serial+1을 읽는다.
ACK가 후속 checkpoint를 만들어 latest가 이미 전진한 경우도 인계 증거를 놓치지 않는다.
Runtime/장치 데이터 통신·계산·DB·TLS MQTT·S3는 실제다. **Run retry 정책 생성과 Pod 생성/신원/
물리 종료 관측은 명시적 fixture**이며, 실패 관측은 서버 service 호출로 주입했다.
실제 Kubernetes Pod 장애 수용으로 대신 해석하지 않는다.

## 경계

공개 API는 아직 STREAM 재시도·offload·REMOTE·VD 정책을 거절한다. 완료 허가가 이미 있거나
결과가 확정된 구성 요소를 계산 중 재시도로 바꾸지 않는다. 최종 파일 생성 단계의 새 Attempt
복구와 Device 자동 재연결을 연결하고 실제 공개 API·Kubernetes 시험을 통과한 뒤 정책을 열어야 한다.
이번 변경은 DDL/API schema 변경이 없으며 V1–V25와 기존 opt-in 범위를 유지한다.
M5 잔여/M7–M10 및 전체 목표는 미완료다.
