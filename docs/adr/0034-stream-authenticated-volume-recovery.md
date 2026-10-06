# ADR0034 — 인증된 최신 체크포인트로 새 볼륨 복원

상태: 동일 Attempt·경로 세대의 SDK 복원 연결. 새 Attempt/generation 인계와 공개 STREAM
실행은 아직 미완료이며 [전체 요구사항](../requirements/m7-requirements.md)을 계속 적용한다.

## 복원 권한과 파일 검증

`CheckpointClient.recover`는 현재 Runner/Pod 자격으로 latest를 조회하고 요청한 전체 경로,
Run·Attempt·epoch·실행 digest·journal manifest와 일치하는 확정본만 사용한다. 저장소 GET은
별도 TLS opener를 사용하며 API 자격을 전달하지 않는다. URL의 versionId와 응답의 실제
version 헤더가 receipt와 같아야 한다. 중복/빈 versionId, redirect, 압축, 다른 MIME·크기를
거절하고 최대72MiB 내에서64KiB 단위로 읽어 실제 SHA-256을 계산한다.

정규 snapshot 검증 후에도 receipt의 manifest·상태 해시/크기·revision·커서 전체를 다시
대조한다. 저장소 I/O 뒤 인증 latest를 재조회해 확정 ID/내용이 바뀌지 않았는지 확인한다.
조회·읽기·파싱·복원 트랜잭션 전후에는 기존 취소/세션/경로 기한 guard를 검사한다.
모든 검사를 통과한 뒤에만 새 private journal을 만들고 원자적으로 상태·입력 커서·미확인
출력/END·외부 확정 frontier와 실행 digest를 복원한다. 기존 디렉터리는 덮어쓰지 않는다.

latest가 없거나 파일이 손상됐거나 권한이 바뀌면 실패한다. 더 오래된 파일 또는 빈 상태로
대체하지 않는다. I/O 일시 오류는 구분된 예외로 반환하며 호출자가 현재 권한 안에서 재시도한다.
복원 도중 파일시스템 오류가 난 새 디렉터리는 성공한 journal로 간주하지 않는다.

## Session 연결

`Session(..., create=True, durability='EXTERNAL', checkpoint_client=client,
restore_latest=True)`가 명시적 복원 진입점이다. 인증된 전체 배정으로 포트→route를 구성하고
기존 Processor와 같은 실행 digest를 계산한다. 모델/MQTT 전달 시작 전에 인증 복원을 끝낸다.
복원 중에는 Session guard, 이후 같은 소유 스레드에서 Link/Processor의 기존 guard가
journal을 소유한다. 복원한 snapshot의 실행 pin을 다시 만들거나 revision을0으로 초기화하지 않는다.
복원 후 자동 publisher가 기존 확정 이력을 이어간다. 완료된 END도 복원해 모델을 다시 기동하지 않는다.

`restore_latest=False`의 기존 생성/같은 볼륨 재개 동작은 유지한다. 새 Attempt나 새 경로 세대의
manifest로 과거 상태를 임의 변경하는 기능은 없다. 서버도 해당 요청을409로 거절한다.

## 소유 자원 종료

반복 복원 시험에서 Paho2.1.0의 `loop()`가 만드는 내부 알림용 TCP 소켓 쌍이 `disconnect()`
후에도 GC까지 남는 것을 allocation trace로 확인했다. Link 종료에서 고정 버전의
`_reset_sockets()`를 호출해 broker 연결과 내부 두 소켓을 즉시 정리한다. 참조를 유지한 채
실제 `/proc/self/fd` 소켓 수가 정상/강제 종료 모두 기준값으로 돌아오는 회귀시험을 추가했다.

[검증 범위와 남은 연결](../evidence/m7-stream-checkpoint-recovery.md)을 따른다.
