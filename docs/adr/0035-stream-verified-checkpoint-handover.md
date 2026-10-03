# ADR0035 — 서버가 검증하는 Attempt·경로 세대 체크포인트 인계

상태: 인증 API·SDK 복원·실제 DB/S3 구성 요소 구현. 전체 실행 전환의 완료 판정은 아니다.
[M7 원문 요구사항](../m7-requirements.md)과 ADR0031–0034의 외부 확정 경계를 유지한다.

## 현재 실행으로만 인계

`POST /internal/v1/attempts/{attemptId}/streams/checkpoints/handover`는 현재 Runner/Pod와
전체 활성 generationIds, 동일 실행 digest를 요구한다. Run·Task·불변 SERVICE와 논리 경로를
유지하며 현재 Task의 최신 확정본만 선택한다. 오래된 체크포인트를 요청하는 인자는 없다.
다른 Attempt면 이전 Runtime의 STOPPED/TERMINATED와 더 큰 epoch를 확인한다.
변경된 경로는 이전 generation이 broker 권한 회수 확인까지 끝난 CLOSED여야 한다.
같은 Attempt의 peer 경로 세대 변경도 같은 검사를 사용한다. 바뀌지 않은 경로는 유지한다.

Device Session 교체는 입력 순번의 연속성을 별도로 증명해야 하므로 이 API에서 임의로
인계하지 않는다. 현재 경로의 Device Session이 달라지면409 DEVICE_STREAM_HANDOVER_REQUIRED다.
Task/경로 준비·전환 자체를 수행하는 API도 아니다. 제어 서버가 이미 배정한 현재 실행을 인증한다.

## 원본 검증·재기록·재검사

짧은 권한 트랜잭션 → 고정 원본 S3 다운로드/검증 → 새 S3 파일 작성/검증 → 현재 권한
트랜잭션 순서다. 저장소 I/O 중 Run 잠금을 유지하지 않는다. 같은 검증 슬롯2개를 사용한다.
원본 내용과 receipt/summary가 일치하는지 frame 단위로 확인하며 전체72MiB를 heap에 올리지 않는다.

서버는 manifest와 미확인 frame의 generation/producer를 현재 인증 배정으로 변경한다.
계산 state·revision·입출력 커서·END·payload·순번·실행 digest·용량 제한은 그대로 보존한다.
snapshot serial만1 증가시키며 target Task/Attempt의 별도 S3 key/version으로 저장한다.
새 파일도 정규 parser와 실제 S3 bytes/SHA 검증을 통과해야 한다. 클라이언트가 변경한
manifest나 인계용 파일을 제출하는 경로는 없다.

최종 트랜잭션은 현재 producer·전체 경로·이전 종료/권한 회수와 최신 원본 ID를 다시 검사한다.
취소·만료·세대 변경·다른 최신 확정이 있으면 늦은 쓰기를 거절한다. 동시 동일 인계는 한 행만
확정하고 이후 요청은 현재 scope의 최신 receipt를 반환한다. 검증 후 확정하지 못한 S3 version은
Result/확정본이 아니며 운영 저장소의 수명/정리 정책은 M9에서 다룬다.

## V23 불변 이력

V22는 수정하지 않는다. V23의 `handover_from_id`는 같은 Task의 직전 checkpoint를 참조한다.
marker가 없으면 기존 Attempt/generation/manifest 변경 금지를 그대로 적용한다. marker가 있으면
serial+1·동일 revision·state 해시/크기·전체 커서·END·논리 경로/방향·limits, 이전 실행 종료,
옛 경로 CLOSED와 현재 generation/producer 일치를 DB에서도 검사한다. 일반 업로드/commit은
이 marker를 지정할 수 없다. UPDATE/DELETE/TRUNCATE 금지도 유지한다.

## SDK와 남은 실행 연결

`CheckpointClient.handover(execution_sha256)`는 현재 actor/scope의 receipt를 검증한다.
`recover(..., handover=True)`는 서버 인계 뒤 ADR0034의 고정 version 다운로드·latest 재검사를
수행한다. `Session(..., create=True, durability='EXTERNAL', checkpoint_client=client,
restore_latest=True, handover_latest=True)`로 명시적으로 연결한다. SDK는 과거 frame의
식별자를 직접 변경하지 않으며 모델 실행 전에 새 확정본을 복원한다.

서버 인계와 peer journal 전환은 별도다. Device/인접 Task가 새 세대의 같은 순번을 이어가도록
하는 제어·배정과 SDK 인계, SERVICE/Runner 시작/종료·Result·공개 STREAM 및 실제 Kubernetes
다중 장치 수용을 아직 연결해야 한다. 현재 공개 STREAM501과 M5 잔여/M7–M10 미완료를 유지한다.
[검증과 fixture 경계](../evidence/m7-stream-checkpoint-handover.md)를 따른다.
