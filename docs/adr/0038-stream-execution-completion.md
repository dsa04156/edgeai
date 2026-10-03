# ADR 0038: 스트림 실행 배정과 구성 요소별 영속 완료 허가

상태: 내부 서버 경로 구현·구성 요소 검증. 공개 STREAM 실행은 비활성.

## 배정과 실행 그룹

SERVICE.stream의 입출력으로 DataRoute를 검증한다. 모든 STREAM 의존성과 Device 입력,
SERVICE의 모든 live 포트가 연결되어야 실행 배정을 시작한다. 처음 배정할 때 Run의
전체 route ID 해시를 V24 `stream_run_binding`에 고정한다. 이후 새 route를 추가할 수 없다.
Run은 최대 128개 Task × 16개 입력을 지원하고, 작업별 SDK의 총 32경로·출력 16경로와
journal의 진행 여유를 검증한다. 세대·실행 주체가 아직 준비되지 않았다면 WAITING이다.

`streams/execution`은 현재 Attempt/Pod와 모든 작업 경로를 확인하고 포트별 generation ID,
NEW/RESTORE/HANDOVER를 반환한다. 실제 MQTT 자격은 기존 streams 배정 API로 받는다.
이 조회가 lease를 연장하지는 않는다.

STREAM 간선을 무방향 연결로 묶고 같은 Device의 fanout도 한 그룹으로 합친다.
독립된 그룹은 서로의 완료를 기다리지 않는다. BATCH 의존성은 이 그룹 사이의 선행 조건이다.
같은 그룹 안의 BATCH 또는 그룹을 축약했을 때 생기는 BATCH 순환은 교착되므로 거절한다.
원래 DAG가 비순환이어도 축약된 그래프에는 순환이 생길 수 있다.

## 완료 허가

1. Task는 최신 서버 확정 checkpoint의 모든 경로가 END이며 received=committed>0일 때
   `streams/complete`로 checkpoint ID를 보고한다. V24는 Attempt와 checkpoint의 복합 FK,
   최신 확정본·종료 커서를 확인하고 그 Attempt의 후속 checkpoint 기록을 막는다.
2. Device는 END 처리 확인을 받은 generation/sequence를 보고한다. 현재 Device 세션과
   소비 작업의 서버 확정 checkpoint에서 같은 종료 순번을 확인한다.
3. 같은 그룹의 모든 Task/Device 보고와 현재 세대·producer 기한을 확인한다. 각 route의
   생산자 종료 순번과 소비자 확정 순번이 같으면 전체 보고에 같은 granted_at을 원자적으로 쓴다.
4. WAITING 동안 참여자는 heartbeat를 유지한다. FINALIZE를 받은 Task만 최종 파일 결과를
   확정할 수 있다. 기존 Result 준비와 저장소 검증 후 commit 양쪽에서 이 허가를 검사한다.
5. 허가는 일부 peer가 성공하고 경로 권한이 회수된 뒤에도 해당 현재 producer가 조회할 수 있다.
   동일 Device 세션의 재조회는 Run 성공 뒤에도 가능하다. 취소·세션 교체·producer 기한 만료는
   허가를 대신하지 않는다. 보고한 checkpoint 또는 Device 순번을 바꿀 수 없다.

잠금 순서는 VD → Run에 연결된 Device ID 순 → Run이다. route 목록을 잠금 전후 대조하여
새 Device가 동시에 추가되면 재시도를 요구한다. 외부 broker/S3 호출은 완료 트랜잭션 안에서
수행하지 않는다. 완료 보고와 grant는 재전송·동시 요청에 대해 멱등이다.

## 계약과 남은 연결

내부 OpenAPI에 Runner 실행 배정·Runner 완료·Device 완료 3개 API를 추가한다.
공개 Run 생성과 runtime dispatch의 STREAM_NOT_IMPLEMENTED는 유지한다.
실제 검증의 범위는 [완료 처리 증거](../evidence/m7-stream-execution-completion.md)를 따른다.

아직 연결해야 할 항목은 DeviceSource의 공동 완료 대기, Run 요청의 Device 입력·route/세대
생성, 그룹별 Task 동시 시작과 BATCH 해제, peer journal 인계, 운영 TLS 구성, API/UI와 실제
Kubernetes 종단이다. 특히 완료 허가 후 프로세스가 재시작하면 이미 닫힌 경로에 재연결하지 않고
봉인된 상태로 최종 파일만 복구하는 경로가 필요하다. 현재 READY/RESTORE만으로 이 조건을
수용했다고 판단하지 않는다. 새 Attempt의 상태형 offload/재시도 수용도 M5 잔여다.
