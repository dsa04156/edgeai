# ADR 0051: 체크포인트를 보존하는 STREAM 그룹 노드 전환

2026-10-04. 로컬 실제 DB·서버·브로커·Runner·S3·화면과 실제 Kubernetes의 노드 전환·
대기 중 API 교체·취소를 검증했다. d3797d6의 CI·실제 이미지 배포와 완성 API 이미지의 전환도
확인했다. 전체7개 게이트의 새 CI는 후속이며 M5/M7 전체 완료를 뜻하지 않는다.

## 실행 계약

기존 `POST /tasks/{taskId}/offload`를 CHECKPOINT STREAM에도 연결한다. 선택한 작업은
현재와 다른 호환 READY 노드로 이동한다. 같은 STREAM 연결·Device fanout 구성 요소의
다른 작업도 새 Attempt에서 재개하되 기존 AUTO/NODE 및 제외 노드 정책을 유지한다.
서로 독립된 그룹과 BATCH 하위 작업은 이동시키지 않는다. Remote·VD 및 자동 STREAM
전환은 아직 지원하지 않으며 기존 공개 Run의 거절 조건을 유지한다.

전체 구성원이 실제 producer를 claim한 RUNNING이어야 하고, 현재 세대에 대응하는 외부
체크포인트가 확정되어 있어야 한다. 공동 완료 허가 또는 Result가 있으면 거절한다.
누락·이전 Attempt의 체크포인트, 비활성 경로, 만료된 producer는 아무 작업도 차단하기
전에 거절한다. 각 구성원의 전환 이력에 같은 Operation을 노출하고 모두에게 기존8회 한도를
적용한다. OFFLOAD Attempt는 장애 retry 예산을 사용하지 않는다.

## 영속 경계와 전환

V27 `task_offload_member`는 Operation/Run/Task, 이전·새 Attempt, 고정 체크포인트,
구성원별 NODE/AUTO 배치와 제외 노드를 기록한다. 같은 Run·Task의 FK, source/target의
고유성, 계획·완성 target의 불변성을 DB에서 검사한다. target을 연결할 때 OFFLOADED 원본,
정확히 증가한 epoch/number와 OFFLOAD 원인, 물리 종료·CREATE 완료·권한 회수를 검사한다.
V1–V26은 변경하지 않는다.

같은 Run 잠금 아래 그룹 계획을 저장하고 전체 경로를 REPLACED로 fence하며 각 Runtime을
STOPPED, 이전 Attempt를 OFFLOADED, Task를 OFFLOADING으로 바꾼다. 이후 이전 producer의
업로드/확정 권한은 없다. 이 전환은 마지막으로 서버가 확인한 외부 체크포인트를 사용한다.
외부 확정 뒤에만 처리 ACK/출력을 진행하는 기존 journal 계약과 Device 재전송을 재사용한다.
별도 완료 허가나 처음부터 재계산한 결과로 상태 복원을 대신하지 않는다.

모든 이전 Runtime이 TERMINATED이고 미완료 CREATE가 없으며 그룹의 모든 경로가 CLOSED인
경우에만 전체 새 Attempt와 Runtime 계획을 한 트랜잭션에서 만든다. API 재시작 뒤 기존
OffloadWorker가 같은 영속 Operation을 읽어 이어간다. 일부 claim만으로 성공시키지 않는다.
전체 target claim 뒤 SUCCEEDED가 되며, 기록된 source→target 관계가 있는 OFFLOAD만
다음 route generation을 생성할 수 있다. 기존 checkpoint handover API와 DeviceRunSource가
새 실행 폴더로 상태·미확인 전송과 센서 커서를 인계한다.

시작 중 한 구성원의 실패는 전환 실패로 처리하며 별도 그룹 retry로 바꾸지 않는다.
drain/start 기한 초과는 그룹과 BATCH 하위를 정리하고 독립 분기를 보존한다. 구성원 어느
하나의 취소도 Operation에 반영하며 전체 물리 종료·권한 회수 뒤 CANCELLED가 된다.
SUCCEEDED는 실행 위치 전환 완료이며 최종 계산 결과의 성공은 별도로 판단한다.

## 검증과 남은 범위

[실행 근거](../evidence/m7-stream-group-offload.md)에 공개 API/동시성/DB 제약, 실제 독립
Runner 두 개의 체크포인트 인계와14/23/BATCH37, PC·모바일 표시를 기록한다. 이 서버 시험의
Pod 생성·신원·노드 배치·종료 관측은 명시적 fixture다. 후속 실제 Kubernetes 시험에서는
다른 실제 Node로의 이동·peer 배치 유지, 전체 종료/경로 회수·고정 checkpoint 인계,
대기 중 API Pod 교체·멱등 요청과 새 Attempt 전 취소를 검증했다. 시험 Job만의 삭제 대기
finalizer로 전환 대기를 만들며 공유 노드는 변경하지 않는다. 기존5개와 새2개의 전체 회귀에서
실제 Pod24개·고정 S3파일15개도 검증했다. d3797d6 CI·GitOps 배포와 게시된 API 이미지의
새 전환/취소2개도 통과했다. 전체7개 기본 게이트의 새 CI는 별도다.
자동 STREAM 정책·VD/단계별 배치·실제 외부 장치/모델과 M8–M10도 남는다.
